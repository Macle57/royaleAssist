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
import com.macle.royaleassist.ocr.Preprocess
import com.macle.royaleassist.ocr.Scripts
import com.macle.royaleassist.ocr.TesseractFallback
import java.lang.Character.UnicodeScript

data class OcrOutcome(
    val lines: List<OcrLine>,
    val pick: NamePick,
    val tesseractUsed: Boolean,
    val millis: Long,
)

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
    suspend fun ocrAndPick(src: Bitmap, exclude: Box? = null, prep: Prep = Prep.DEFAULT, useTesseract: Boolean = true): OcrOutcome {
        val t0 = System.currentTimeMillis()
        val (img, scale) = Preprocess.apply(src, prep)
        var lines = ocr.recognize(img, scale)
        var pick = NamePicker.pick(lines, exclude)
        var tessUsed = false
        val name = pick.name
        if (useTesseract && name != null && needsTesseract(name)) {
            val (crop, _) = Preprocess.crop(src, name.box, 0.2f)
            val best = tesseract.readLine(crop).firstOrNull()
            if (best != null && acceptTesseract(best)) {
                val tLine = OcrLine(best.text, name.box, best.confidence / 100f, "tesseract-${best.lang}")
                lines = lines + tLine
                pick = NamePick(tLine, pick.clan)
                tessUsed = true
                L.i("analyze: tesseract replaced name '${name.text}' -> '${best.text}'")
            }
        }
        val ms = System.currentTimeMillis() - t0
        L.i("analyze: name='${pick.name?.text}' (${pick.name?.source}) clan='${pick.clan?.text}' lines=${lines.size} in $ms ms")
        return OcrOutcome(lines, pick, tessUsed, ms)
    }

    suspend fun analyze(src: Bitmap, exclude: Box? = null): Analysis {
        val o = ocrAndPick(src, exclude)
        val name = o.pick.name ?: return Analysis(o, null)
        return Analysis(o, lookup.lookup(name.text, o.pick.clan?.text))
    }

    suspend fun lookupPick(pick: NamePick): LookupResult? =
        pick.name?.let { lookup.lookup(it.text, pick.clan?.text) }

    companion object {
        /** ML Kit has no Arabic/Cyrillic model; a weak or garbled reading of the name triggers Tesseract. */
        fun needsTesseract(name: OcrLine): Boolean {
            val letters = Scripts.letterCount(name.text)
            val fit = Scripts.fitFraction(name.text, name.source)
            val dominant = Scripts.dominant(name.text)
            return name.confidence < 0.6f || letters < 2 || fit < 0.6 ||
                dominant == UnicodeScript.CYRILLIC || dominant == UnicodeScript.ARABIC
        }

        fun acceptTesseract(r: TesseractFallback.Result): Boolean {
            val dominant = Scripts.dominant(r.text)
            return r.confidence >= 55 && Scripts.letterCount(r.text) >= 2 &&
                (dominant == UnicodeScript.ARABIC || dominant == UnicodeScript.CYRILLIC)
        }
    }
}
