package com.macle.royaleassist.ui

/**
 * `royaleassist://` links the iOS app opens (Shortcuts, `xcrun simctl openurl` in tests):
 *
 * - `royaleassist://lookup?name=Macle&clan=...`: look a player up
 * - `royaleassist://scan`: pick a screenshot; `scan?photo=latest` reads the newest photo,
 *   `scan?path=/abs/file.jpg` a file (simulator: the Mac's file system)
 * - `royaleassist://history`, `royaleassist://setup`, `royaleassist://verify?q=Macle`
 */
sealed interface DeepLink {
    data class Lookup(val name: String, val clan: String?) : DeepLink
    data class Scan(val path: String? = null, val latestPhoto: Boolean = false) : DeepLink
    data object History : DeepLink
    data object Setup : DeepLink
    data class Verify(val query: String?) : DeepLink

    companion object {
        const val SCHEME = "royaleassist"

        /** Parses [url]; null for other schemes or unknown commands. */
        fun parse(url: String): DeepLink? {
            val rest = url.trim()
            if (!rest.startsWith("$SCHEME:", ignoreCase = true)) return null
            val body = rest.substring(SCHEME.length + 1).removePrefix("//")
            val command = body.substringBefore('?').trim('/').lowercase()
            val query = if ('?' in body) parseQuery(body.substringAfter('?')) else emptyMap()
            return when (command) {
                "lookup" -> query["name"]?.takeIf { it.isNotBlank() }?.let { Lookup(it.trim(), query["clan"]?.trim()?.ifBlank { null }) }
                "scan" -> Scan(path = query["path"]?.ifBlank { null }, latestPhoto = query["photo"].equals("latest", ignoreCase = true))
                "history" -> History
                "setup", "" -> Setup
                "verify" -> Verify(query["q"]?.ifBlank { null })
                else -> null
            }
        }

        /** `a=1&b=x%20y` -> {a=1, b="x y"}; '+' is a space. */
        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate { part ->
            val k = part.substringBefore('=')
            val v = if ('=' in part) part.substringAfter('=') else ""
            percentDecode(k) to percentDecode(v)
        }

        /** Decodes %XX escapes as UTF-8. */
        fun percentDecode(s: String): String {
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out.add(hex.toByte()); i += 3; continue
                    }
                }
                if (c == '+') out.add(' '.code.toByte()) else c.toString().encodeToByteArray().forEach { out.add(it) }
                i++
            }
            return out.toByteArray().decodeToString()
        }
    }
}
