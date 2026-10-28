package com.macle.royaleassist.pipeline

import android.graphics.Bitmap
import com.macle.royaleassist.L
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.ColorStats
import com.macle.royaleassist.lookup.StringSim
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
    /** The bitmap that was OCR'd (before upscaling); kept for the debug scan recorder. */
    val crop: Bitmap? = null,
    /** True when the region was automatically grown because the first pass found no good name. */
    val expanded: Boolean = false,
    /** The region box of the first pass when [expanded]. */
    val firstScanned: Box? = null,
    /** What each recognizer read before merging (screen coordinates). */
    val raw: Map<String, List<OcrLine>> = emptyMap(),
    /** Second-pass re-reads of the name/clan boxes (human-readable, for logs and scan JSON). */
    val refinements: List<String> = emptyList(),
)

/** Moves every box by (dx, dy): crop coordinates -> screen coordinates. */
private fun OcrOutcome.offset(dx: Int, dy: Int): OcrOutcome {
    val map = HashMap<OcrLine, OcrLine>()
    fun m(l: OcrLine) = map.getOrPut(l) { l.copy(box = l.box.offset(dx, dy)) }
    return copy(
        lines = lines.map(::m),
        pick = pick.copy(name = pick.name?.let(::m), clan = pick.clan?.let(::m)),
        raw = raw.mapValues { (_, v) -> v.map { it.copy(box = it.box.offset(dx, dy)) } },
    )
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
        if (region == null || isRegionSized(src)) return ocrAndPick(src, exclude).copy(mode = "whole-image", crop = src)
        val t0 = System.currentTimeMillis()
        val box = region.toBox(src.width, src.height)
        var o = scanBox(src, box, exclude)
        L.i("analyze: region ${box.width}x${box.height} at (${box.left},${box.top}) -> '${o.pick.name?.text}' (${o.pick.reason.substringBefore(';')}) in ${System.currentTimeMillis() - t0} ms")
        val why = expandReason(o, box)
        if (why != null) {
            val grown = expandBox(box, src.width, src.height)
            if (grown != box) {
                L.i("analyze: $why; retrying with the region grown to ${grown.width}x${grown.height} at (${grown.left},${grown.top})")
                val o2 = scanBox(src, grown, exclude)
                val better = o2.pick.name != null && (o.pick.name == null || o2.pick.name.warmth >= NamePicker.WARM ||
                    o.pick.name.warmth < NamePicker.WARM && o2.pick.name.box.height > o.pick.name.box.height ||
                    o.pick.clan == null && o2.pick.clan != null && o2.pick.name.text == o.pick.name.text)
                L.i("analyze: grown region -> '${o2.pick.name?.text}' (${if (better) "used" else "kept first result"})")
                if (better) o = o2.copy(expanded = true, firstScanned = box, millis = o.millis + o2.millis)
            }
        }
        o = refine(src, o)
        o = withTesseract(src, o)
        o = tidy(o)
        L.i("analyze: final name='${o.pick.name?.text}' clan='${o.pick.clan?.text}' in ${System.currentTimeMillis() - t0} ms")
        if (o.pick.name != null || !fullScreenFallback) return o
        L.i("analyze: nothing in the region, falling back to a full-screen scan")
        return ocrAndPick(src, exclude).let { it.copy(mode = "full-screen", millis = it.millis + o.millis) }
    }

    /** OCR one screen box (cropped, upscaled), without Tesseract; boxes in screen coordinates. */
    private suspend fun scanBox(src: Bitmap, box: Box, exclude: Box?): OcrOutcome {
        val crop = Bitmap.createBitmap(src, box.left, box.top, box.width, box.height)
        val inCrop = exclude?.offset(-box.left, -box.top)
        return ocrAndPick(crop, inCrop, useTesseract = false, forcedScale = regionScale(box), secondPass = false)
            .offset(box.left, box.top)
            .copy(mode = "region", scanned = box, crop = crop)
    }

    private fun tidy(o: OcrOutcome): OcrOutcome {
        val n = o.pick.name ?: return o
        val t = tidyName(n.text)
        if (t == n.text || t.isEmpty()) return o
        val nn = n.copy(text = t)
        return o.copy(lines = o.lines.map { if (it === n) nn else it }, pick = o.pick.copy(name = nn))
    }

    /** Tesseract fallback on a region outcome (boxes in [src] coordinates). */
    private suspend fun withTesseract(src: Bitmap, o: OcrOutcome): OcrOutcome {
        val (lines, pick, used) = applyTesseract(src, o.lines, o.pick)
        return if (used) o.copy(lines = lines, pick = pick, tesseractUsed = true) else o
    }

    suspend fun ocrAndPick(
        src: Bitmap,
        exclude: Box? = null,
        prep: Prep = Prep.DEFAULT,
        useTesseract: Boolean = true,
        forcedScale: Float? = null,
        secondPass: Boolean = true,
    ): OcrOutcome {
        val t0 = System.currentTimeMillis()
        val (img, scale) = Preprocess.apply(src, prep, forcedScale)
        val rec = ocr.recognizeDetailed(img, scale)
        val lines0 = rec.merged.map { it.copy(warmth = ColorStats.warmFraction(src, it.box)) }
        val pick0 = NamePicker.pick(lines0, exclude)
        var o = OcrOutcome(lines0, pick0, false, 0, mode = "full-screen", crop = src, raw = rec.raw)
        if (secondPass) o = refine(src, o)
        if (useTesseract) o = withTesseract(src, o)
        val ms = System.currentTimeMillis() - t0
        L.i("analyze: name='${o.pick.name?.text}' (${o.pick.name?.source}) clan='${o.pick.clan?.text}' lines=${o.lines.size} in $ms ms: ${o.pick.reason}")
        return o.copy(millis = ms)
    }

    /**
     * Second pass: re-read the picked name and clan boxes on their own, padded and upscaled 3-6x
     * (game text is small; a 29 px line was misread). A re-read replaces the first reading when it
     * is more confident; next to a Hangul name the Korean model's reading of the clan is preferred.
     */
    private suspend fun refine(src: Bitmap, o: OcrOutcome): OcrOutcome {
        val name = o.pick.name ?: return o
        val notes = mutableListOf<String>()
        // Name and clan text are left-aligned after the badge: if ML Kit dropped the name's first
        // letters, the clan's left edge shows where the name really starts.
        val below = o.lines.filter { it !== name && it.box.top >= name.box.centerY && it.box.top - name.box.bottom < name.box.height * 2 }
        val alignLeft = below.minOfOrNull { it.box.left }?.takeIf { it < name.box.left && name.box.left - it < name.box.height * 2 }
        val newName = reread(src, name, preferKorean = false, notes, alignLeft)
        val hangul = StringSim.hasHangul(newName.text)
        val newClan = o.pick.clan?.let { reread(src, it, preferKorean = hangul, notes) }
        if (newName === name && newClan === o.pick.clan) return o.copy(refinements = o.refinements + notes)
        val lines = o.lines.map { l -> if (l === name) newName else if (l === o.pick.clan) newClan!! else l }
        return o.copy(lines = lines, pick = o.pick.copy(name = newName, clan = newClan), refinements = o.refinements + notes)
    }

    private suspend fun reread(src: Bitmap, line: OcrLine, preferKorean: Boolean, notes: MutableList<String>, alignLeft: Int? = null): OcrLine {
        val b = line.box
        val h = b.height.coerceAtLeast(1)
        val padded = Box(
            (minOf(b.left, alignLeft ?: b.left) - (h * 0.3).toInt()).coerceAtLeast(0), (b.top - (h * 0.45).toInt()).coerceAtLeast(0),
            (b.right + (h * 0.3).toInt()).coerceAtMost(src.width), (b.bottom + (h * 0.45).toInt()).coerceAtMost(src.height),
        )
        if (padded.width < 4 || padded.height < 4) return line
        val crop = Bitmap.createBitmap(src, padded.left, padded.top, padded.width, padded.height)
        val scale = (110f / h).coerceIn(3f, 6f)
        val (img, s) = Preprocess.apply(crop, Prep.UPSCALE, forcedScale = scale)
        // Latin text only needs the Latin model (the others read Latin too, but worse); other
        // scripts, and a clan next to a Hangul name, get every model.
        val latinOnly = !preferKorean && Scripts.dominant(line.text) == java.lang.Character.UnicodeScript.LATIN &&
            line.text.none { isCjk(it) }
        val raw = ocr.recognizeRaw(img, s, if (latinOnly) setOf("latin") else null)
        val readings = raw.mapNotNull { (source, ls) ->
            // Only text on the original line: its vertical centre must fall inside the first box
            // (the padding can catch the clan line below or the badge above).
            val top = b.top - padded.top
            val bottom = b.bottom - padded.top
            val row = ls.filter { it.box.centerY in top..bottom }.sortedBy { it.box.left }
            if (row.isEmpty()) null
            else OcrLine(
                trimStrayTokens(row.joinToString(" ") { it.text }), b, row.map { it.confidence }.average().toFloat(),
                "$source+2nd", warmth = line.warmth,
            )
        }
        val desc = readings.joinToString { "${it.source.removeSuffix("+2nd")}='${it.text}' ${"%.2f".format(it.confidence)}" }
        // ML Kit's Korean model cannot emit lone jamo: a jamo clan like "ㅅㅁㅂ" comes back as weak
        // syllables ("스므", 0.17). Prefer it only when it is reasonably confident; otherwise the
        // CJK reading stays and the 人口日 -> ㅅㅁㅂ homoglyph fold in StringSim matches it.
        val korean = readings.firstOrNull { it.source == "korean+2nd" && StringSim.hasHangul(it.text) && it.confidence >= 0.4f }
        val chosen = if (preferKorean && korean != null && !StringSim.hasHangul(line.text)) {
            korean
        } else {
            val script = Scripts.dominant(line.text)
            readings
                .filter { r -> Scripts.fitFraction(r.text, r.source.removeSuffix("+2nd")) >= 0.5 }
                .filter { r -> Scripts.dominant(r.text) == script || preferKorean && StringSim.hasHangul(r.text) }
                .filter { r -> Scripts.letterCount(r.text) >= Scripts.letterCount(line.text) * 0.6 }
                .maxByOrNull { it.confidence }
                // Fewer letters than the first reading (a dropped "M" in "MajiNBuu") needs a clear
                // confidence gain to win.
                ?.takeIf {
                    it.confidence > line.confidence &&
                        (Scripts.letterCount(line.text) - Scripts.letterCount(it.text)).let { lost ->
                            lost <= 0 || lost <= 2 && it.confidence >= line.confidence + 0.095f || it.confidence >= line.confidence + 0.15f
                        }
                }
        }
        val note = "'${line.text}' (${line.source} ${"%.2f".format(line.confidence)}) -> " +
            (chosen?.let { "'${it.text}' (${it.source} ${"%.2f".format(it.confidence)})" } ?: "kept") + "; 2nd pass x$scale: $desc"
        L.i("refine: $note")
        notes += note
        return chosen ?: line
    }

    private suspend fun applyTesseract(src: Bitmap, lines0: List<OcrLine>, pick0: NamePick): Triple<List<OcrLine>, NamePick, Boolean> {
        var lines = lines0
        var pick = pick0
        var tessUsed = false
        val name = pick.name
        if (name != null) {
            val hasPlaceholder = lines.any { com.macle.royaleassist.ocr.ClanText.isNoClanPlaceholder(it.text) }
            if (pick.clan == null && !hasPlaceholder && lines.none { it !== name && it.box.bottom <= name.box.top && it.box.bottom > name.box.top - 3 * name.box.height }) {
                // Lone line with nothing above it: maybe it is really the clan, and the name above is
                // in a script ML Kit cannot read (Arabic, Cyrillic). Read the strip above with Tesseract.
                val above = regionAbove(name.box, src.width)
                if (above != null) {
                    val best = tesseract.readLine(Bitmap.createBitmap(src, above.left, above.top, above.width, above.height))
                        .firstOrNull()
                    if (best != null && acceptTesseract(best, mlKitConfidence = 0f)) {
                        val tLine = OcrLine(best.text, above, best.confidence / 100f, "tesseract-${best.lang}")
                        lines = (lines + tLine).sortedBy { it.box.top }
                        pick = pick.copy(name = tLine, clan = name, reason = "Tesseract read a name above the only line")
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
                    pick = pick.copy(name = tLine, reason = "Tesseract replaced a weak reading")
                    tessUsed = true
                    L.i("analyze: tesseract replaced name '${name.text}' -> '${best.text}'")
                }
            }
        }
        return Triple(lines, pick, tessUsed)
    }

    suspend fun analyze(src: Bitmap, exclude: Box? = null): Analysis {
        val o = ocrAndPick(src, exclude)
        val name = o.pick.name ?: return Analysis(o, null)
        return Analysis(o, lookup.lookup(name.text, o.pick.hint()))
    }

    suspend fun lookupPick(pick: NamePick): LookupResult? =
        pick.name?.let { lookup.lookup(it.text, pick.hint()) }

    companion object {
        /** "7あぎったん。" -> "あぎったん": a lone leading digit/symbol stuck to CJK/kana text and trailing 。. */
        fun tidyName(text: String): String {
            var t = trimStrayTokens(text.trim().trimEnd('。', '．'))
            if (t.length >= 3 && !t[0].isLetter() && isCjk(t[1]) && !t[1].isDigit()) t = t.substring(1)
            return t.trim()
        }

        private fun isCjk(c: Char) = when (java.lang.Character.UnicodeScript.of(c.code)) {
            java.lang.Character.UnicodeScript.HAN, java.lang.Character.UnicodeScript.HIRAGANA,
            java.lang.Character.UnicodeScript.KATAKANA, java.lang.Character.UnicodeScript.HANGUL -> true
            else -> false
        }

        /**
         * Drops 1-2 character tokens that contain none of the line's main script: at the start
         * (a badge edge read as "1" or "|" next to a Hangul name) and symbol-only ones at the end.
         */
        fun trimStrayTokens(text: String): String {
            val tokens = text.trim().split(Regex("\\s+")).toMutableList()
            val script = Scripts.dominant(text) ?: return text.trim()
            fun stray(t: String) = t.length <= 2 && t.none { Character.UnicodeScript.of(it.code) == script }
            while (tokens.size > 1 && stray(tokens.first())) tokens.removeAt(0)
            // Trailing digits are often part of the name ("Macle 2"), so only symbols are dropped there.
            while (tokens.size > 1 && stray(tokens.last()) && tokens.last().none { it.isDigit() }) tokens.removeAt(tokens.size - 1)
            return tokens.joinToString(" ")
        }

        /**
         * Why the region should be grown and OCR'd again: no name, a name that is not
         * name-coloured, or a name touching the top edge (probably cut off).
         */
        fun expandReason(o: OcrOutcome, box: Box): String? {
            val n = o.pick.name ?: return "no name in the region"
            if (n.box.top - box.top <= 3) return "name '${n.text}' touches the top of the region"
            // A pale line right under the top edge may be the clan, with the real name cut off above.
            if (n.warmth < 0.15 && n.box.top - box.top < n.box.height * 1.2) return "pale line '${n.text}' near the top of the region"
            // Clan probably below the region (pillarboxed or low frames): name near the bottom edge,
            // no clan read and no "No Clan" placeholder either.
            if (o.pick.clan == null && box.bottom - n.box.bottom < n.box.height * 1.5 &&
                o.lines.none { com.macle.royaleassist.ocr.ClanText.isNoClanPlaceholder(it.text) }
            ) return "name '${n.text}' sits at the bottom of the region with no clan below"
            if (n.warmth < NamePicker.WARM) return "no name-coloured line (picked '${n.text}', warmth ${"%.2f".format(n.warmth)})"
            return null
        }

        /** Region grown by 8 % of the screen height upward and 5 % downward. */
        fun expandBox(b: Box, w: Int, h: Int): Box =
            Box(b.left, (b.top - (h * 0.08).toInt()).coerceAtLeast(0), b.right, (b.bottom + (h * 0.05).toInt()).coerceAtMost(h))

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
            // Mixed Latin+CJK readings and names with real Latin text are not Arabic/Cyrillic.
            if (Scripts.mixedFit(name.text, name.source)) return false
            if (Scripts.letterCount(name.text) - Scripts.letterCount(name.text.filter { !(it in 'a'..'z' || it in 'A'..'Z') }) >= 3 &&
                name.confidence >= 0.4f
            ) return false
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
