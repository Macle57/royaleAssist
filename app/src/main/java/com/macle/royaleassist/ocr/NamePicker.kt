package com.macle.royaleassist.ocr

import kotlin.math.abs
import kotlin.math.max

data class NamePick(val name: OcrLine?, val clan: OcrLine?)

/**
 * Heuristic: the opponent name is the tallest text line on screen (excluding the bubble area and
 * pure numbers such as timers or trophy counts); the clan is the nearest line below it whose left
 * edge roughly lines up with the name.
 */
object NamePicker {

    fun candidates(lines: List<OcrLine>, exclude: Box? = null): List<OcrLine> =
        lines.filter { l ->
            Scripts.letterCount(l.text) >= 1 &&
                l.box.height > 0 &&
                (exclude == null || l.box.overlapRatio(exclude) < 0.3)
        }

    fun pick(lines: List<OcrLine>, exclude: Box? = null): NamePick {
        val c = candidates(lines, exclude)
        val name = c.maxWithOrNull(compareBy<OcrLine>({ it.box.height }, { it.confidence })) ?: return NamePick(null, null)
        return NamePick(name, clanFor(name, c))
    }

    /** Re-pick with a user-chosen name line (tap in the panel). */
    fun pickWithName(name: OcrLine, lines: List<OcrLine>): NamePick = NamePick(name, clanFor(name, candidates(lines)))

    fun clanFor(name: OcrLine, lines: List<OcrLine>): OcrLine? {
        val nb = name.box
        val h = nb.height.coerceAtLeast(1)
        return lines
            .filter { it !== name && it != name }
            .filter { l ->
                val b = l.box
                b.top >= nb.centerY &&                          // below the name
                    b.top - nb.bottom <= 2 * h &&               // but close
                    b.height <= nb.height * 1.1 &&              // and not taller than it
                    abs(b.left - nb.left) <= max(1.5 * h, 0.25 * nb.width) // left edges aligned
            }
            .minByOrNull { (it.box.top - nb.bottom) + abs(it.box.left - nb.left) / 4 }
    }
}
