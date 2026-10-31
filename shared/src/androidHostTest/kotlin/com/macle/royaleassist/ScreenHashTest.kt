package com.macle.royaleassist

import com.macle.royaleassist.cache.ScreenHash
import com.macle.royaleassist.ocr.Box
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The screenshot cache must match repeated captures of one screen and never a different opponent.
 *
 * Phone: 21 scans of five opponents from the phone (2026-10-28, all in the same arena, so the
 * background around the names is identical), gray top-left crops in resources/screenhash/phone.
 * Corpus: the name/clan boxes the app's OCR found on samples/corpus (resources/screenhash/
 * corpus_boxes.json); every other corpus frame, scaled to the same size, is cut at those boxes.
 *
 * Unit tests compile against android.jar (no java.awt / javax.imageio), so images are decoded
 * with the JVM's ImageIO through reflection into a plain ARGB [Img].
 */
class ScreenHashTest {
    /** A decoded frame kept as 8-bit gray (the hash only uses luma), to keep 66 corpus frames small. */
    private class Img(val width: Int, val height: Int, val gray: ByteArray) {
        fun getRGB(x: Int, y: Int, w: Int, h: Int): IntArray = IntArray(w * h) { i ->
            val v = gray[(y + i / w) * width + x + i % w].toInt() and 0xff
            (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    private fun read(stream: java.io.InputStream): Img {
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", java.io.InputStream::class.java).invoke(null, stream)!!
        val w = img.javaClass.getMethod("getWidth").invoke(img) as Int
        val h = img.javaClass.getMethod("getHeight").invoke(img) as Int
        val px = IntArray(w * h)
        img.javaClass.getMethod(
            "getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(img, 0, 0, w, h, px, 0, w)
        return Img(w, h, ByteArray(w * h) { ScreenHash.luma(px[it]).toByte() })
    }

    private fun res(path: String) = javaClass.classLoader!!.getResource(path)!!

    private class Shot(val label: String, val group: String, val img: Img, val nameBox: Box, val clanBox: Box?)

    private fun box(a: JsonArray?) = a?.let { Box(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int, it[2].jsonPrimitive.int, it[3].jsonPrimitive.int) }

    private fun hashAt(img: Img, b: Box): LongArray? {
        val p = ScreenHash.pad(b, img.width, img.height)
        val l = p.left.coerceIn(0, img.width)
        val t = p.top.coerceIn(0, img.height)
        val w = p.right.coerceIn(l, img.width) - l
        val h = p.bottom.coerceIn(t, img.height) - t
        if (w < 8 || h < 4) return null
        return ScreenHash.dHash(img.getRGB(l, t, w, h), w, h)
    }

    /** Distance of [b] cut at [a]'s boxes: the larger of name and clan (as the cache requires both). */
    private fun dist(a: Shot, b: Img): Int {
        // Another frame size: the same screen fractions (as if [b] were scaled to [a]'s size).
        fun m(x: Box) = if (b.width == a.img.width && b.height == a.img.height) x else Box(
            x.left * b.width / a.img.width, x.top * b.height / a.img.height,
            x.right * b.width / a.img.width, x.bottom * b.height / a.img.height,
        )
        val n = ScreenHash.distance(hashAt(a.img, a.nameBox)!!, hashAt(b, m(a.nameBox)) ?: return ScreenHash.BITS)
        val c = a.clanBox?.let { cb ->
            val ha = hashAt(a.img, cb) ?: return@let 0
            ScreenHash.distance(ha, hashAt(b, m(cb)) ?: return@let ScreenHash.BITS)
        } ?: 0
        return maxOf(n, c)
    }

    private val phone: List<Shot> by lazy {
        Json.parseToJsonElement(res("screenhash/phone/index.json").readText()).jsonArray.map { e ->
            val o = e.jsonObject
            val file = o["file"]!!.jsonPrimitive.content
            Shot(
                file, o["person"]!!.jsonPrimitive.content, res("screenhash/phone/$file").openStream().use(::read),
                box(o["nameBox"]!!.jsonArray)!!, (o["clanBox"] as? JsonArray)?.let(::box),
            )
        }
    }

    private fun corpusDir(): File? = listOf("../samples/corpus", "samples/corpus").map(::File).firstOrNull { File(it, "index.json").exists() }

    private val corpus: List<Shot> by lazy {
        val dir = corpusDir() ?: return@lazy emptyList()
        val boxes = Json.parseToJsonElement(res("screenhash/corpus_boxes.json").readText()).jsonObject
        boxes.map { (file, v) ->
            val o = v as JsonObject
            Shot(file, file, File(dir, file).inputStream().use(::read), box(o["name"]!!.jsonArray)!!, (o["clan"] as? JsonArray)?.let(::box))
        }
    }

    /** The same frame captured again: slightly brighter, with +-[noise] per pixel (sensor/encoder noise). */
    private fun recaptured(src: Img, noise: Int, brighten: Int): Img {
        val rnd = java.util.Random(7)
        val out = ByteArray(src.gray.size) { i ->
            ((src.gray[i].toInt() and 0xff) + brighten + rnd.nextInt(2 * noise + 1) - noise).coerceIn(0, 255).toByte()
        }
        return Img(src.width, src.height, out)
    }

    @Test
    fun repeatedPhoneCapturesOfOneScreenMatch() {
        val same = mutableListOf<Int>()
        for (a in phone) for (b in phone) if (a !== b && a.group == b.group) same += dist(a, b.img)
        println("phone same-screen distances: ${same.sorted()}")
        assertTrue(same.size >= 40)
        assertTrue("max ${same.max()}", same.all { it <= ScreenHash.MAX_DISTANCE })
    }

    @Test
    fun differentPhoneOpponentsInTheSameArenaDoNotMatch() {
        val diff = mutableListOf<Int>()
        for (a in phone) for (b in phone) if (a.group != b.group) diff += dist(a, b.img)
        println("phone different-opponent distances: min ${diff.min()}, ${diff.sorted().take(10)}")
        // Clearly apart: at least twice the limit.
        assertTrue("min ${diff.min()}", diff.all { it > 2 * ScreenHash.MAX_DISTANCE })
    }

    @Test
    fun corpusNamesAreAllApart() {
        val shots = corpus
        if (corpusDir() == null) return
        assertTrue("corpus boxes", shots.size >= 40)
        val diff = mutableListOf<Pair<Int, String>>()
        for (a in shots) for (b in shots) {
            if (a === b) continue
            diff += dist(a, b.img) to "${a.label} vs ${b.label}"
        }
        val sorted = diff.sortedBy { it.first }
        println("corpus different-opponent distances: ${diff.size} pairs, closest ${sorted.take(5)}")
        assertTrue("closest ${sorted.first()}", sorted.first().first > 2 * ScreenHash.MAX_DISTANCE)
    }

    @Test
    fun corpusRecapturesMatch() {
        val shots = corpus
        if (corpusDir() == null) return
        val same = shots.map { a -> dist(a, recaptured(a.img, 6, 4)) }
        println("corpus re-captured distances: max ${same.max()}, ${same.sorted().takeLast(8)}")
        assertTrue("max ${same.max()}", same.all { it <= ScreenHash.MAX_DISTANCE / 2 })
    }

    @Test
    fun bestMatchPicksTheSameScreenAmongStoredOnes() {
        val stored = phone.distinctBy { it.group }
        val keys = stored.map { s ->
            ScreenHash.Key(
                1080, 2340, ScreenHash.pad(s.nameBox, s.img.width, s.img.height), hashAt(s.img, s.nameBox)!!,
                s.clanBox?.let { ScreenHash.pad(it, s.img.width, s.img.height) }, s.clanBox?.let { hashAt(s.img, it) },
            )
        }
        for (probe in phone) {
            val img = probe.img
            val m = ScreenHash.bestMatch(stored.indices.toList(), { keys[it] }, 1080, 2340) { b ->
                val w = b.right.coerceAtMost(img.width) - b.left
                val h = b.bottom.coerceAtMost(img.height) - b.top
                ScreenHash.dHash(img.getRGB(b.left, b.top, w, h), w, h)
            }
            assertNotNull(probe.label, m)
            assertEquals(probe.label, probe.group, stored[m!!.item].group)
        }
        // Another frame size (rotated, other phone) never matches.
        assertNull(ScreenHash.bestMatch(stored.indices.toList(), { keys[it] }, 2340, 1080) { LongArray(4) })
    }

    @Test
    fun hashBasics() {
        val flat = IntArray(64 * 16) { 0xff808080.toInt() }
        assertNull("featureless", ScreenHash.dHash(flat, 64, 16))
        val ramp = IntArray(66 * 16) { i -> val v = 255 - (i % 66) * 3; (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        val h = ScreenHash.dHash(ramp, 66, 16)!!
        assertEquals(ScreenHash.BITS, h.take(4).sumOf { java.lang.Long.bitCount(it) })
        assertEquals(ScreenHash.BITS, ScreenHash.sureBits(h))
        // Unsure bits of the stored hash never count.
        val probe = h.copyOf().also { it[0] = 0L }
        assertEquals(64, ScreenHash.distance(h, probe))
        assertEquals(0, ScreenHash.distance(h.copyOf().also { it[4] = 0L }, probe))
        assertEquals(0, ScreenHash.distance(h, ScreenHash.fromHex(ScreenHash.toHex(h))!!))
        assertNull(ScreenHash.fromHex("xyz"))
        assertEquals(Box(0, 0, 110, 60), ScreenHash.pad(Box(5, 10, 105, 50), 110, 2000))
    }

    @Test
    fun storedEntryRoundTrips() {
        val r = com.macle.royaleassist.lookup.LookupResult(
            "mbg", null, com.macle.royaleassist.lookup.PlayerHit("mbg", "ABC"), "L2 RoyaleAPI fuzzy search", 1.0,
            com.macle.royaleassist.lookup.PlayerStats(name = "mbg", tag = "ABC", extra = listOf("King Level" to "15"), source = "royaleapi.com profile"),
            recentDecks = listOf(com.macle.royaleassist.lookup.RecentDeck(List(8) { com.macle.royaleassist.lookup.DeckCard("c$it", 14, "u$it", "k$it") }, "Ladder", 1L, 2, 5)),
            hint = com.macle.royaleassist.lookup.ScreenHint(noClan = true, league = 5), strongMatch = true, note = "n", log = listOf("x"),
        )
        val json = Json.encodeToString(com.macle.royaleassist.lookup.LookupResult.serializer(), r)
        assertEquals(r, Json.decodeFromString(com.macle.royaleassist.lookup.LookupResult.serializer(), json))
        val h = LongArray(8) { it * 31L - 5 }
        val e = com.macle.royaleassist.cache.ScreenKeyEntity(
            frameWidth = 1080, frameHeight = 2340, nameLeft = 1, nameTop = 2, nameRight = 30, nameBottom = 12,
            nameHash = ScreenHash.toHex(h), nameText = "mbg", tag = "ABC", resultJson = json, createdAt = 0, expiresAt = 1,
        )
        val k = com.macle.royaleassist.cache.ScreenCache.keyOf(e)!!
        assertEquals(Box(1, 2, 30, 12), k.nameBox)
        assertTrue(h.contentEquals(k.nameHash))
        assertNull(k.clanBox)
        assertNull(com.macle.royaleassist.cache.ScreenCache.keyOf(e.copy(nameHash = "00")))
    }
}
