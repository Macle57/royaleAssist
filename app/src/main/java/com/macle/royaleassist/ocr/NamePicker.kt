package com.macle.royaleassist.ocr

import kotlin.math.abs
import kotlin.math.max

/** The chosen name and clan lines, plus a short human-readable reason (logged and saved with scans). */
data class NamePick(
    val name: OcrLine?,
    val clan: OcrLine?,
    val reason: String = "",
    /** The clan slot showed "No Clan" (or a translation): the player has no clan. */
    val noClan: Boolean = false,
    /** "League 5" under the clan. */
    val league: Int? = null,
    /** Trophy count under the clan (some screens show it instead of the league). */
    val trophies: Int? = null,
    /** Emoji or symbols around the name that OCR skipped (see [NamePicker.isDecorated]). */
    val decorated: Boolean = false,
) {
    fun hint() = com.macle.royaleassist.lookup.ScreenHint(clan?.text, noClan && clan == null, league, trophies, decorated)
}

/**
 * Picks the opponent's name and clan among OCR lines.
 *
 * 1. Drop lines that cannot be a name: game labels ("League 5", "Time left", "Next", "Max: 10"),
 *    timers and numbers (mostly digits), fewer than two letters, the bubble area, and short
 *    low-confidence non-Latin fragments (e.g. a Devanagari misread of a flag badge) when Latin text
 *    is present.
 * 2. The game draws the opponent name in saturated pink/orange, the clan and labels in white or
 *    pale yellow. If some lines are "warm" ([OcrLine.warmth] >= [WARM]), the name is the tallest warm
 *    line; otherwise the tallest line (non-Latin lines count a little smaller, so they only win when
 *    clearly taller).
 * 3. The clan is the nearest line below the name whose left edge lines up with it.
 */
object NamePicker {
    /** Fraction of saturated warm pixels inside a line box that marks it as name-coloured. */
    const val WARM = 0.08

    private val LABELS = listOf(
        // "League 5", also with a stray leading character from the badge ("MLeague 5", "|League5").
        Regex("^[^a-z0-9]*[a-z]?[^a-z0-9]*lea[gq]ue\\s*\\d+\\W*$", RegexOption.IGNORE_CASE),
        Regex("^time\\s*left\\W*$", RegexOption.IGNORE_CASE),
        Regex("^next\\W*$", RegexOption.IGNORE_CASE),
        Regex("^max\\W*\\d*$", RegexOption.IGNORE_CASE),
        Regex("^(king|princess)\\s*tower$", RegexOption.IGNORE_CASE),
        Regex("^level\\s*\\d+$", RegexOption.IGNORE_CASE),
    )

    fun isGameLabel(text: String): Boolean {
        val t = text.trim()
        return LABELS.any { it.matches(t) }
    }

    /**
     * Timers, tower HP, level badges: digits with at most a couple of stray characters
     * ("4858", "E4858", "的4858-", "1:44", "16") or simply at least as many digits as letters.
     */
    fun isMostlyDigits(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        if (digits == 0) return false
        val letters = Scripts.letterCount(text)
        return digits >= letters || (digits >= 3 && letters <= 2)
    }

    fun isLatin(l: OcrLine) = Scripts.fitFraction(l.text, "latin") >= 0.5

    /** Why a line is not a name/clan candidate, or null if it is one. */
    fun rejectReason(l: OcrLine, all: List<OcrLine>, exclude: Box?): String? {
        val letters = Scripts.letterCount(l.text)
        return when {
            l.box.height <= 0 -> "empty box"
            exclude != null && l.box.overlapRatio(exclude) >= 0.3 -> "under the bubble"
            isGameLabel(l.text) -> "game label"
            ClanText.isNoClanPlaceholder(l.text) -> "no-clan placeholder"
            isMostlyDigits(l.text) -> "number/timer"
            letters < 2 -> "fewer than 2 letters"
            // Misreads of badges/icons by the Devanagari, Bengali or CJK models: very low confidence.
            letters <= 3 && l.confidence < 0.3f && all.any { it !== l && it.confidence >= 0.4f && Scripts.letterCount(it.text) >= 2 &&
                !isGameLabel(it.text) && !isMostlyDigits(it.text) && !ClanText.isNoClanPlaceholder(it.text) } -> "low-confidence fragment"
            !isLatin(l) && letters <= 2 && l.confidence < 0.7f &&
                all.any { it !== l && isLatin(it) && Scripts.letterCount(it.text) >= 2 && !isGameLabel(it.text) && !isMostlyDigits(it.text) } ->
                "short low-confidence non-Latin fragment"
            else -> null
        }
    }

    fun candidates(lines: List<OcrLine>, exclude: Box? = null): List<OcrLine> =
        lines.filter { rejectReason(it, lines, exclude) == null }

    /**
     * Lines that may be the clan: name candidates plus clan names with digits ("CRL2026",
     * "10/G POMPAKE") that the name filter treats as numbers. Pure numbers stay out.
     */
    fun clanCandidates(lines: List<OcrLine>, exclude: Box?): List<OcrLine> =
        lines.filter { l ->
            rejectReason(l, lines, exclude) == null ||
                (rejectReason(l, lines, exclude) == "number/timer" && Scripts.letterCount(l.text) >= 3)
        }

    fun pick(lines: List<OcrLine>, exclude: Box? = null): NamePick {
        val c = candidates(lines, exclude)
        val placeholders = lines.filter { ClanText.isNoClanPlaceholder(it.text) }
        val rejected = lines.filter { it !in c }.joinToString { "'${it.text}' (${rejectReason(it, lines, exclude)})" }
        val warm = c.filter { it.warmth >= WARM }
        val name: OcrLine
        val why: String
        if (warm.isNotEmpty()) {
            name = warm.maxWith(compareBy<OcrLine>({ it.box.height }, { it.warmth }))
            why = "tallest name-coloured line (warmth ${"%.2f".format(name.warmth)})"
        } else {
            name = c.maxWithOrNull(compareBy<OcrLine>({ effectiveHeight(it) }, { it.confidence }))
                ?: return NamePick(null, null, "no candidates" + if (rejected.isNotEmpty()) "; rejected $rejected" else "")
            why = "tallest line (no name-coloured line)"
        }
        // The clan slot may hold "No Clan" (and translations): then the player has no clan.
        val slot = clanFor(name, clanCandidates(lines, exclude) + placeholders)
        val noClan = slot != null && ClanText.isNoClanPlaceholder(slot.text)
        val clan = slot?.takeUnless { noClan }
        val (league, trophies) = rankInfo(name, lines)
        val decorated = isDecorated(name, slot)
        return NamePick(
            name, clan, why + (if (decorated) "; text starts right of the clan (emoji?)" else "") +
                if (rejected.isNotEmpty()) "; rejected $rejected" else "",
            noClan, league, trophies, decorated,
        )
    }

    private fun effectiveHeight(l: OcrLine) = if (isLatin(l)) l.box.height.toDouble() else l.box.height * 0.8

    /** Re-pick with a user-chosen name line (tap in the panel). */
    fun pickWithName(name: OcrLine, lines: List<OcrLine>): NamePick {
        val slot = clanFor(name, clanCandidates(lines, null) + lines.filter { ClanText.isNoClanPlaceholder(it.text) })
        val noClan = slot != null && ClanText.isNoClanPlaceholder(slot.text)
        val (league, trophies) = rankInfo(name, lines)
        return NamePick(name, slot?.takeUnless { noClan }, "chosen by the user", noClan, league, trophies)
    }

    /**
     * "League N" and the trophy count shown under the clan (left-aligned with the name, within a
     * few line heights). Tower HP and timers elsewhere on screen are not aligned and are ignored.
     */
    fun rankInfo(name: OcrLine, lines: List<OcrLine>): Pair<Int?, Int?> {
        val nb = name.box
        val h = nb.height.coerceAtLeast(1)
        val below = lines.filter { it !== name && it.box.top >= nb.centerY && it.box.top - nb.bottom < 4 * h }
        val league = below.firstOrNull { isLeagueLabel(it.text) }
            ?.let { Regex("\\d+").findAll(it.text).lastOrNull()?.value?.toIntOrNull() }
        val trophies = below
            .filter { abs(it.box.left - nb.left) < 2.5 * h && isMostlyDigits(it.text) }
            .mapNotNull { l -> l.text.filter { it.isDigit() }.takeIf { it.length in 1..5 && l.text.count { c -> c.isLetter() } <= 1 }?.toIntOrNull() }
            .firstOrNull()
        return league to trophies
    }

    /**
     * Name and clan text start at the same x after the badge. A name that starts much further
     * right had something OCR skipped before it, usually an emoji ("⚽JP⚽" read as "JP").
     */
    fun isDecorated(name: OcrLine, clanSlot: OcrLine?): Boolean {
        val c = clanSlot ?: return false
        return name.box.left - c.box.left > name.box.height * 0.8
    }

    fun isLeagueLabel(text: String) = LABELS[0].matches(text.trim())

    fun clanFor(name: OcrLine, lines: List<OcrLine>): OcrLine? {
        val nb = name.box
        val h = nb.height.coerceAtLeast(1)
        return lines
            .filter { it !== name && it != name }
            .filter { l ->
                val b = l.box
                b.top >= nb.centerY &&                          // below the name
                    b.top - nb.bottom <= 2 * h &&               // but close
                    b.height <= nb.height * 1.25 &&             // and not much taller than it
                    abs(b.left - nb.left) <= max(1.5 * h, 0.25 * nb.width) // left edges aligned
            }
            .minByOrNull { (it.box.top - nb.bottom) + abs(it.box.left - nb.left) / 4 }
    }
}
