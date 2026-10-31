package com.macle.royaleassist.ocr

import com.macle.royaleassist.L
import com.macle.royaleassist.image.ArgbImage
import com.macle.royaleassist.image.toCGImage
import com.macle.royaleassist.util.nowMillis
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.NSError
import platform.Vision.VNImageRequestHandler
import platform.Vision.VNRecognizeTextRequest
import platform.Vision.VNRecognizedText
import platform.Vision.VNRecognizedTextObservation
import platform.Vision.VNRequestTextRecognitionLevelAccurate

/** One Vision pass: the recognizer name the shared OCR logic knows and Vision's language list. */
data class VisionPass(val source: String, val languages: List<String>)

/** A line as Vision reports it: text, confidence 0..1 and the normalised box (origin bottom-left). */
data class VisionLine(val text: String, val confidence: Float, val x: Double, val y: Double, val w: Double, val h: Double)

/**
 * Apple Vision text recognition (`VNRecognizeTextRequest`, `.accurate`, no language correction).
 *
 * Mirrors Android's ML Kit multi-recognizer: one pass per script family, named like the ML Kit
 * recognizers so [LineMerger], [NamePicker] and the Analyzer's second pass treat them the same
 * ("latin", "chinese", "japanese", "korean"). Each pass lists its own language first, then English
 * (Vision reads mixed "まるNoxi" style names that way). Boxes are pixel boxes in the source image
 * (divided by the preprocessing scale), lines are Vision's text observations with the top
 * candidate's confidence.
 */
class VisionTextRecognizer(private val passes: List<VisionPass> = DEFAULT_PASSES) : TextRecognizerBackend {

    override suspend fun recognizeDetailed(image: ArgbImage, scale: Float): RecognizedText {
        val t0 = nowMillis()
        val raw = recognizeRaw(image, scale)
        val all = raw.values.flatten()
        val merged = LineMerger.merge(all)
        L.i("ocr: ${all.size} raw lines -> ${merged.size} merged in ${nowMillis() - t0} ms (${image.width}x${image.height}, Vision)")
        return RecognizedText(merged, raw)
    }

    override suspend fun recognizeRaw(image: ArgbImage, scale: Float, sources: Set<String>?): Map<String, List<OcrLine>> =
        withContext(Dispatchers.Default) {
            val cg = image.toCGImage()
            try {
                coroutineScope {
                    passes.filter { sources == null || it.source in sources }.map { pass ->
                        async {
                            pass.source to try {
                                VisionText.recognize(cg, pass.languages).map { l ->
                                    OcrLine(
                                        l.text,
                                        NormalizedBoxes.toBox(l.x, l.y, l.w, l.h, image.width, image.height, scale),
                                        l.confidence,
                                        pass.source,
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                L.w("ocr: Vision ${pass.source} pass failed: ${e.message}")
                                emptyList()
                            }
                        }
                    }.awaitAll().toMap()
                }
            } finally {
                CGImageRelease(cg)
            }
        }

    companion object {
        val DEFAULT_PASSES = listOf(
            VisionPass("latin", listOf("en-US", "fr-FR", "de-DE", "es-ES", "it-IT", "pt-BR")),
            VisionPass("chinese", listOf("zh-Hans", "zh-Hant", "en-US")),
            VisionPass("japanese", listOf("ja-JP", "en-US")),
            VisionPass("korean", listOf("ko-KR", "en-US")),
        )
    }
}

/**
 * Second-opinion reader for one name line, the counterpart of Android's Tesseract fallback for the
 * scripts ML Kit lacks: Vision's Cyrillic (ru/uk) and Arabic passes. Readings carry Tesseract's
 * conventions (confidence 0..100, lang "rus"/"ara"), so the Analyzer's acceptance rules apply as is.
 */
class VisionLineReader(private val passes: List<VisionPass> = DEFAULT_PASSES) : LineReader {
    override suspend fun readLine(crop: ArgbImage): List<LineReading> = withContext(Dispatchers.Default) {
        val cg = crop.toCGImage()
        try {
            passes.mapNotNull { pass ->
                val lines = try {
                    VisionText.recognize(cg, pass.languages)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w("ocr: Vision ${pass.source} line pass failed: ${e.message}")
                    emptyList()
                }
                if (lines.isEmpty()) null
                else LineReading(
                    text = lines.sortedBy { it.x }.joinToString(" ") { it.text }.trim(),
                    confidence = (lines.map { it.confidence }.average() * 100).toInt(),
                    lang = pass.source,
                    variant = "vision",
                )
            }.filter { it.text.isNotEmpty() }.sortedByDescending { it.confidence }
        } finally {
            CGImageRelease(cg)
        }
    }

    companion object {
        val DEFAULT_PASSES = listOf(
            VisionPass("rus", listOf("ru-RU", "uk-UA")),
            VisionPass("ara", listOf("ar-SA", "ars-SA")),
        )
    }
}

/** The Vision call itself (synchronous; run it off the main thread). */
object VisionText {
    /** Languages `.accurate` supports on this OS, read once. */
    val supportedLanguages: Set<String> by lazy {
        val langs = memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val req = VNRecognizeTextRequest()
            req.recognitionLevel = VNRequestTextRecognitionLevelAccurate
            req.supportedRecognitionLanguagesAndReturnError(err.ptr)?.mapNotNull { it as? String }.orEmpty()
        }
        L.i("ocr: Vision accurate languages: ${langs.joinToString()}")
        langs.toSet()
    }

    /**
     * Run Vision on the CPU only. Set while the app is in the background (picture-in-picture
     * bubble scans): iOS does not let background apps submit GPU work.
     */
    @kotlin.concurrent.Volatile
    var cpuOnly: Boolean = false

    /**
     * Recognises [cg] with [languages] (unsupported ones dropped; nothing left = no lines). A failed
     * GPU/Neural Engine run is retried on the CPU.
     */
    fun recognize(cg: CGImageRef, languages: List<String>): List<VisionLine> {
        val langs = languages.filter { it in supportedLanguages }
        if (langs.isEmpty()) return emptyList()
        if (cpuOnly) return recognize(cg, langs, cpu = true)
        return try {
            recognize(cg, langs, cpu = false)
        } catch (e: IllegalStateException) {
            L.w("ocr: Vision failed (${e.message}), retrying on the CPU")
            recognize(cg, langs, cpu = true)
        }
    }

    @OptIn(BetaInteropApi::class)
    @Suppress("DEPRECATION")
    private fun recognize(cg: CGImageRef, langs: List<String>, cpu: Boolean): List<VisionLine> {
        return autoreleasepool {
            val req = VNRecognizeTextRequest()
            req.recognitionLevel = VNRequestTextRecognitionLevelAccurate
            req.usesLanguageCorrection = false
            req.recognitionLanguages = langs
            // Deprecated in iOS 17 in favour of per-stage compute devices, but still honoured.
            if (cpu) req.usesCPUOnly = true
            val handler = VNImageRequestHandler(cGImage = cg, options = emptyMap<Any?, Any?>())
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                if (!handler.performRequests(listOf(req), err.ptr)) {
                    error(err.value?.localizedDescription ?: "performRequests failed")
                }
            }
            req.results.orEmpty().mapNotNull { obs ->
                val o = obs as? VNRecognizedTextObservation ?: return@mapNotNull null
                val top = o.topCandidates(1u).firstOrNull() as? VNRecognizedText ?: return@mapNotNull null
                val text = top.string.trim()
                if (text.isEmpty()) return@mapNotNull null
                o.boundingBox.useContents { VisionLine(text, top.confidence, origin.x, origin.y, size.width, size.height) }
            }
        }
    }
}
