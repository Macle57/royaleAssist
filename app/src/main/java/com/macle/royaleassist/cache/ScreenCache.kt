package com.macle.royaleassist.cache

import android.graphics.Bitmap
import com.macle.royaleassist.L
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.NamePick
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Screenshot-keyed instant cache: in memory, backed by the `screen_keys` table. After a scan that
 * found a player, the name/clan boxes and their [ScreenHash] fingerprints are stored with the
 * result; a later capture whose same boxes hash alike is the same screen and gets that result
 * without OCR or network. Strong matches are kept [STRONG_TTL_MS], best guesses only
 * [GUESS_TTL_MS] (long enough for one battle) so a doubtful pick is not repeated for hours.
 */
class ScreenCache(private val dao: CacheDao, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    @Volatile private var mem: MutableList<Loaded>? = null

    private class Loaded(val entity: ScreenKeyEntity, val key: ScreenHash.Key)

    /** A hit: the stored result plus how close the boxes were. */
    data class Hit(
        val entity: ScreenKeyEntity,
        val result: LookupResult,
        val nameDistance: Int,
        val clanDistance: Int?,
        val ageMs: Long,
        val checked: Int,
    ) {
        fun describe() = "same screen as ${ageMs / 1000} s ago -> #${entity.tag} '${entity.nameText}' " +
            "(name ${nameDistance}/${ScreenHash.BITS} bits" + (clanDistance?.let { ", clan $it/${ScreenHash.BITS}" } ?: "") +
            " differ, limit ${ScreenHash.MAX_DISTANCE}; $checked stored screens checked)"
    }

    /** Hashes for a new entry, computed on the frame they belong to. */
    data class Keys(val key: ScreenHash.Key, val nameText: String, val clanText: String?)

    fun invalidate() {
        mem = null
    }

    private suspend fun entries(now: Long): MutableList<Loaded> {
        mem?.let { list ->
            list.removeAll { it.entity.expiresAt <= now }
            return list
        }
        dao.deleteExpiredScreenKeys(now)
        val loaded = dao.screenKeys(now).mapNotNull { e -> keyOf(e)?.let { Loaded(e, it) } }.toMutableList()
        mem = loaded
        return loaded
    }

    /** Looks [frame] up; null when no stored screen matches. */
    suspend fun find(frame: Bitmap): Hit? = mutex.withLock {
        val now = clock()
        val list = entries(now)
        if (list.isEmpty()) return null
        val m = ScreenHash.bestMatch(list, { it.key }, frame.width, frame.height) { b -> hashBox(frame, b) } ?: return null
        val result = runCatching { json.decodeFromString<LookupResult>(m.item.entity.resultJson) }
            .onFailure { L.w("screen cache: cannot decode entry ${m.item.entity.id}: ${it.message}") }
            .getOrNull() ?: return null
        Hit(m.item.entity, result, m.nameDistance, m.clanDistance, now - m.item.entity.createdAt, list.size)
    }

    /** Fingerprints of the picked name/clan on [frame]; null when the name box is unusable. */
    fun keysFor(frame: Bitmap, pick: NamePick): Keys? {
        val name = pick.name ?: return null
        val nb = ScreenHash.pad(name.box, frame.width, frame.height)
        val nh = hashBox(frame, nb) ?: return null
        val cb = pick.clan?.box?.let { ScreenHash.pad(it, frame.width, frame.height) }
        val ch = cb?.let { hashBox(frame, it) }
        return Keys(
            ScreenHash.Key(frame.width, frame.height, nb, nh, cb.takeIf { ch != null }, ch),
            name.text, pick.clan?.text.takeIf { ch != null },
        )
    }

    /** Remembers [result] for the screen [keys] (replacing an older entry for the same screen). */
    suspend fun put(keys: Keys, result: LookupResult) = mutex.withLock {
        val tag = result.stats?.tag ?: result.hit?.tag ?: return@withLock
        val now = clock()
        val list = entries(now)
        // An entry for the same screen (same boxes, hashes alike) is replaced.
        val same = list.filter { l ->
            l.key.frameWidth == keys.key.frameWidth && l.key.frameHeight == keys.key.frameHeight &&
                l.key.nameBox == keys.key.nameBox && ScreenHash.distance(l.key.nameHash, keys.key.nameHash) <= ScreenHash.MAX_DISTANCE
        }
        if (same.isNotEmpty()) {
            dao.deleteScreenKeys(same.map { it.entity.id })
            list.removeAll(same.toSet())
        }
        val k = keys.key
        val ttl = if (result.strongMatch) STRONG_TTL_MS else GUESS_TTL_MS
        val e = ScreenKeyEntity(
            frameWidth = k.frameWidth, frameHeight = k.frameHeight,
            nameLeft = k.nameBox.left, nameTop = k.nameBox.top, nameRight = k.nameBox.right, nameBottom = k.nameBox.bottom,
            nameHash = ScreenHash.toHex(k.nameHash), nameText = keys.nameText,
            clanLeft = k.clanBox?.left, clanTop = k.clanBox?.top, clanRight = k.clanBox?.right, clanBottom = k.clanBox?.bottom,
            clanHash = k.clanHash?.let(ScreenHash::toHex), clanText = keys.clanText,
            tag = tag,
            resultJson = json.encodeToString(LookupResult.serializer(), result.copy(log = emptyList(), screenHit = false)),
            createdAt = now, expiresAt = now + ttl,
        )
        val id = dao.insertScreenKey(e)
        list.add(0, Loaded(e.copy(id = id), k))
        // Keep the table small: the newest MAX_ENTRIES.
        if (list.size > MAX_ENTRIES) {
            val drop = list.drop(MAX_ENTRIES)
            dao.deleteScreenKeys(drop.map { it.entity.id })
            list.removeAll(drop.toSet())
        }
        L.i("screen cache: stored #$tag for '${keys.nameText}' (${if (result.strongMatch) "strong, 2 h" else "best guess, ${GUESS_TTL_MS / 60000} min"}), ${list.size} entries")
    }

    suspend fun clear() = mutex.withLock {
        dao.clearScreenKeys()
        mem = null
    }

    companion object {
        const val STRONG_TTL_MS = 2 * 3_600_000L
        const val GUESS_TTL_MS = 20 * 60_000L
        const val MAX_ENTRIES = 200

        fun keyOf(e: ScreenKeyEntity): ScreenHash.Key? {
            val nh = ScreenHash.fromHex(e.nameHash) ?: return null
            val cb = if (e.clanLeft != null && e.clanTop != null && e.clanRight != null && e.clanBottom != null) {
                Box(e.clanLeft, e.clanTop, e.clanRight, e.clanBottom)
            } else null
            val ch = e.clanHash?.let(ScreenHash::fromHex)
            return ScreenHash.Key(
                e.frameWidth, e.frameHeight, Box(e.nameLeft, e.nameTop, e.nameRight, e.nameBottom), nh,
                cb.takeIf { ch != null }, ch.takeIf { cb != null },
            )
        }

        /** [ScreenHash.dHash] of a box of [frame] (clamped); null when it is outside or featureless. */
        fun hashBox(frame: Bitmap, b: Box): LongArray? {
            val l = b.left.coerceIn(0, frame.width)
            val t = b.top.coerceIn(0, frame.height)
            val r = b.right.coerceIn(l, frame.width)
            val bt = b.bottom.coerceIn(t, frame.height)
            val w = r - l
            val h = bt - t
            if (w < 8 || h < 4) return null
            val px = IntArray(w * h)
            frame.getPixels(px, 0, w, l, t, w, h)
            return ScreenHash.dHash(px, w, h)
        }
    }
}
