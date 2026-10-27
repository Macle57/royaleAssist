package com.macle.royaleassist.pipeline

import android.graphics.Bitmap
import com.macle.royaleassist.L
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.ocr.NamePicker
import com.macle.royaleassist.ocr.OcrEngine
import com.macle.royaleassist.ocr.OcrLine
import com.macle.royaleassist.ocr.Prep
import com.macle.royaleassist.ocr.ScanRegion
import com.macle.royaleassist.ocr.Preprocess
import com.macle.royaleassist.ocr.Scripts
import com.macle.royaleassist.ocr.TesseractFallback
import java.lang.Character.UnicodeScript

data class OcrOutcome(
    val lines: List<OcrLine>,
    val pick: NamePick,
    val tesseractUsed: Boolean,
    val millis: Long,
    /** "region" (cropped scan region), "whole-image" (small image treated as the region) or "full-screen". */
    val mode: String = "whole-image",
    /** The screen area that was OCR'd, in source image coordinates. */
    val scanned: Box? = null,
)

/** Moves every box by (dx, dy): crop coordinates -> screen coordinates. */
private fun OcrOutcome.offset(dx: Int, dy: Int): OcrOutcome {
    val map = HashMap<OcrLine, OcrLine>()
    fun m(l: OcrLine) = map.getOrPut(l) { l.copy(box = l.box.offset(dx, dy)) }
    return copy(lines = lines.map(::m), pick = NamePick(pick.name?.let(::m), pick.clan?.let(::m)))
}

data class Analysis(
    val ocr: OcrOutcome,
    val lookup: LookupResult?,
) {
    val name get() = ocr.pick.name?.text
    val clan get() = ocr.pick.clan?.text
}

/** Screenshot -> OCR lines -> (name, clan) -> lookup. Shared by the overlay and the debug harness. */
class Analyzer(
    private val ocr: OcrEngine,
    private val tesseract: TesseractFallback,
    private val lookup: LookupChain,
) {
    /**
     * Normal entry point for a capture: OCR only the scan [region] (cropped and upscaled 2-3x), with
     * an optional full-screen retry. Small images (the sample crops) already are the region.
     */
    suspend fun scan(src: Bitmap, region: ScanRegion?, exclude: Box? = null, fullScreenFallback: Boolean = false): OcrOutcome {
        if (region == null || isRegionSized(src)) return ocrAndPick(src, exclude).copy(mode = "whole-image")
        val t0 = System.currentTimeMillis()
        val box = region.toBox(src.width, src.height)
        val crop = Bitmap.createBitmap(src, box.left, box.top, box.width, box.height)
        val scale = regionScale(box)
        val inCrop = exclude?.offset(-box.left, -box.top)
        val o = ocrAndPick(crop, inCrop, forcedScale = scale).offset(box.left, box.top)
            .copy(mode = "region", scanned = box)
        L.i("analyze: region ${box.width}x${box.height} at (${box.left},${box.top}) x$scale -> '${o.pick.name?.text}' in ${System.currentTimeMillis() - t0} ms")
        if (o.pick.name != null || !fullScreenFallback) return o
        L.i("analyze: nothing in the region, falling back to a full-screen scan")
        return ocrAndPick(src, exclude).let { it.copy(mode = "full-screen", millis = it.millis + o.millis) }
    }

    suspend fun ocrAndPick(
        src: Bitmap,
        exclude: Box? = null,
        prep: Prep = Prep.DEFAULT,
        useTesseract: Boolean = true,
        forcedScale: Float? = null,
    ): OcrOutcome {
        val t0 = System.currentTimeMillis()
        val (img, scale) = Preprocess.apply(src, prep, forcedScale)
        var lines = ocr.recognize(img, scale)
        var pick = NamePicker.pick(lines, exclude)
        var tessUsed = false
        val name = pick.name
        if (useTesseract && name != null) {
            if (pick.clan == null && lines.none { it !== name && it.box.bottom <= name.box.top && it.box.bottom > name.box.top - 3 * name.box.height }) {
                // Lone line with nothing above it: maybe it is really the clan, and the name above is
                // in a script ML Kit cannot read (Arabic, Cyrillic). Read the strip above with Tesseract.
                val above = regionAbove(name.box, src.width)
                if (above != null) {
                    val best = tesseract.readLine(Bitmap.createBitmap(src, above.left, above.top, above.width, above.height))
                        .firstOrNull()
                    if (best != null && acceptTesseract(best, mlKitConfidence = 0f)) {
                        val tLine = OcrLine(best.text, above, best.confidence / 100f, "tesseract-${best.lang}")
                        lines = (lines + tLine).sortedBy { it.box.top }
                        pick = NamePick(tLine, name)
                        tessUsed = true
                        L.i("analyze: tesseract found name '${best.text}' above '${name.text}'")
                    }
                }
            } else if (needsTesseract(name)) {
                val (crop, _) = Preprocess.crop(src, name.box, 0.2f)
                val best = tesseract.readLine(crop).firstOrNull()
                if (best != null && acceptTesseract(best, name.confidence)) {
                    val tLine = OcrLine(best.text, name.box, best.confidence / 100f, "tesseract-${best.lang}")
                    lines = lines + tLine
                    pick = NamePick(tLine, pick.clan)
                    tessUsed = true
                    L.i("analyze: tesseract replaced name '${name.text}' -> '${best.text}'")
                }
            }
        }
        val ms = System.currentTimeMillis() - t0
        L.i("analyze: name='${pick.name?.text}' (${pick.name?.source}) clan='${pick.clan?.text}' lines=${lines.size} in $ms ms")
        return OcrOutcome(lines, pick, tessUsed, ms, mode = "full-screen")
    }

    suspend fun analyze(src: Bitmap, exclude: Box? = null): Analysis {
        val o = ocrAndPick(src, exclude)
        val name = o.pick.name ?: return Analysis(o, null)
        return Analysis(o, lookup.lookup(name.text, o.pick.clan?.text))
    }

    suspend fun lookupPick(pick: NamePick): LookupResult? =
        pick.name?.let { lookup.lookup(it.text, pick.clan?.text) }

    companion object {
        /** Images smaller than this on both sides are treated as already being the scan region. */
        const val REGION_SIZED_MAX = 1000

        fun isRegionSized(b: Bitmap) = b.width < REGION_SIZED_MAX && b.height < REGION_SIZED_MAX

        /** 3x for small crops, 2x for larger ones, never past ~2400 px on the long side. */
        fun regionScale(box: Box): Float {
            val longSide = maxOf(box.width, box.height).coerceAtLeast(1)
            val s = if (longSide <= 500) 3f else 2f
            return minOf(s, 2400f / longSide).coerceAtLeast(1f)
        }

        /** ML Kit has no Arabic/Cyrillic model; a weak or garbled reading of the name triggers Tesseract. */
        fun needsTesseract(name: OcrLine): Boolean {
            val letters = Scripts.letterCount(name.text)
            val fit = Scripts.fitFraction(name.text, name.source)
            return name.confidence < 0.5f || letters < 2 || fit < 0.5
        }

        /**
         * Tesseract's rus model happily reads Latin game text as Cyrillic look-alikes with high
         * confidence ("TOT" -> "тет" at 89), so a Tesseract reading only wins when it is in a
         * script ML Kit lacks AND ML Kit's own reading was weak.
         */
        fun acceptTesseract(r: TesseractFallback.Result, mlKitConfidence: Float): Boolean {
            val dominant = Scripts.dominant(r.text)
            val fit = Scripts.fitFraction(r.text, "tesseract-${r.lang}")
            if (Scripts.letterCount(r.text) < 2 || fit < 0.7) return false
            return when (dominant) {
                UnicodeScript.ARABIC -> r.confidence >= 55
                UnicodeScript.CYRILLIC -> r.confidence >= 60 && mlKitConfidence < 0.5f
                else -> false
            }
        }

        /** The strip directly above a text line, where the name sits when [line] is the clan. */
        fun regionAbove(line: Box, imageWidth: Int): Box? {
            val h = line.height
            val top = (line.top - (h * 2.4f).toInt()).coerceAtLeast(0)
            val bottom = line.top - (h * 0.05f).toInt()
            if (bottom - top < maxOf(8, h)) return null
            val left = (line.left - h).coerceAtLeast(0)
            val right = (line.right + 2 * h).coerceAtMost(imageWidth)
            if (right - left < 8) return null
            return Box(left, top, right, bottom)
        }
    }
}
