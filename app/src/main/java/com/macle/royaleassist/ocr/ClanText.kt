package com.macle.royaleassist.ocr

import com.macle.royaleassist.lookup.StringSim

/** Text the game shows in the clan slot when the player has no clan, in the UI languages seen so far. */
object ClanText {
    private val PLACEHOLDERS = listOf(
        "No Clan", "클랜 없음", "無部落", "无部落", "Klan yok", "クランなし", "Sin clan", "Sans clan", "Kein Clan",
        "Nessun clan", "Sem clã", "Нет клана", "Không có Hội", "لا عشيرة",
    ).map { StringSim.compact(it) }.toSet()

    /** True for "No Clan", "클랜 없음", "無部落", "Klan yok", "クランなし" and close OCR readings of them. */
    fun isNoClanPlaceholder(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val c = StringSim.compact(text)
        if (c.isEmpty()) return false
        return c in PLACEHOLDERS || PLACEHOLDERS.any { p -> p.length >= 4 && StringSim.similarity(text, p) >= 0.9 }
    }
}
