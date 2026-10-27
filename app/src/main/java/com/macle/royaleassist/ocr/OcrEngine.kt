package com.macle.royaleassist.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.macle.royaleassist.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * On-device OCR with ML Kit text recognition v2 (unbundled; models come from Play services).
 * All five script recognizers run concurrently on the same image and their lines are merged
 * by [LineMerger].
 */
class OcrEngine {
    private val recognizers: List<Pair<String, TextRecognizer>> by lazy {
        listOf(
            "latin" to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            "chinese" to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            "japanese" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            "korean" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            "devanagari" to TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
        )
    }

    /** Asks Play services to download any missing OCR model. Safe to call repeatedly. */
    suspend fun ensureModels(context: Context): Boolean = try {
        val req = ModuleInstallRequest.newBuilder().apply { recognizers.forEach { addApi(it.second) } }.build()
        val resp = ModuleInstall.getClient(context).installModules(req).await()
        L.i("ocr: models ${if (resp.areModulesAlreadyInstalled()) "already installed" else "install requested"}")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.w("ocr: model install request failed: ${e.message}")
        false
    }

    /**
     * Runs every recognizer on [bitmap] and returns merged lines. Boxes are divided by [scale] so
     * they are in the coordinates of the image before preprocessing.
     */
    suspend fun recognize(bitmap: Bitmap, scale: Float = 1f): List<OcrLine> = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)
        val t0 = System.currentTimeMillis()
        val perRecognizer = recognizers.map { (source, client) ->
            async {
                try {
                    val text = client.process(image).await()
                    text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val r = line.boundingBox ?: return@mapNotNull null
                        OcrLine(
                            text = line.text,
                            box = Box(r.left, r.top, r.right, r.bottom).scaled(1f / scale),
                            confidence = line.confidence,
                            source = source,
                            language = line.recognizedLanguage,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w("ocr: $source recognizer failed: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll()
        val all = perRecognizer.flatten()
        val merged = LineMerger.merge(all)
        L.i("ocr: ${all.size} raw lines -> ${merged.size} merged in ${System.currentTimeMillis() - t0} ms (${bitmap.width}x${bitmap.height})")
        merged
    }

    /** Raw per-recognizer output; used by the debug harness to compare recognizers. */
    suspend fun recognizeRaw(bitmap: Bitmap, scale: Float = 1f): Map<String, List<OcrLine>> = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizers.map { (source, client) ->
            async {
                source to runCatching {
                    client.process(image).await().textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val r = line.boundingBox ?: return@mapNotNull null
                        OcrLine(line.text, Box(r.left, r.top, r.right, r.bottom).scaled(1f / scale), line.confidence, source, line.recognizedLanguage)
                    }
                }.getOrElse { listOf(OcrLine("<error: ${it.message}>", Box(0, 0, 0, 0), 0f, source)) }
            }
        }.awaitAll().toMap()
    }
}
