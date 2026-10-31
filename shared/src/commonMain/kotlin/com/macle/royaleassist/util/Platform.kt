package com.macle.royaleassist.util

import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Clock

/** Wall-clock time in epoch milliseconds (was `System.currentTimeMillis()`). */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * `application/x-www-form-urlencoded` UTF-8 encoding exactly like `java.net.URLEncoder.encode(s,
 * "UTF-8")`: ASCII letters, digits and `.-*_` stay, a space becomes `+`, every other byte `%XX`
 * (upper-case hex). RoyaleAPI's search URLs rely on the `+` for spaces.
 */
fun formUrlEncode(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> sb.append(c.toChar())
            c == ' '.code -> sb.append('+')
            else -> sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
        }
    }
    return sb.toString()
}

private const val HEX = "0123456789ABCDEF"

/**
 * A small thread-safe map (was `ConcurrentHashMap`): copy-on-write behind an atomic reference,
 * fine for the handful of entries a lookup keeps.
 */
class ConcurrentMap<K, V> {
    private val ref = AtomicReference<Map<K, V>>(emptyMap())

    operator fun get(key: K): V? = ref.load()[key]

    operator fun set(key: K, value: V) {
        while (true) {
            val cur = ref.load()
            if (ref.compareAndSet(cur, cur + (key to value))) return
        }
    }

    fun snapshot(): Map<K, V> = ref.load()
}
