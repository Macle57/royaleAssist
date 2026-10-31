package com.macle.royaleassist

import com.macle.royaleassist.image.loadArgbImage
import com.macle.royaleassist.lookup.HttpClients
import com.macle.royaleassist.lookup.IosHttpPlatform
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.lookup.StringSim
import com.macle.royaleassist.ocr.ScanRegion
import com.macle.royaleassist.ocr.VisionLineReader
import com.macle.royaleassist.ocr.VisionTextRecognizer
import com.macle.royaleassist.pipeline.Analyzer
import com.macle.royaleassist.pipeline.OcrOutcome
import com.macle.royaleassist.settings.Settings
import com.macle.royaleassist.text.fmt2
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Apple Vision through the shared Analyzer on the repo's full-screen samples, with the default scan
 * region like a capture. Expected labels: sample 08 is Daniel / GLI ARGONAUTI
 * (NamePickerSample08Test), sample 09 the Hangul name of HangulMatchTest.
 */
class VisionSampleTest {
    private val analyzer by lazy {
        val settings = Settings(saveScansDefault = false, path = NSTemporaryDirectory() + "vision-test.preferences_pb")
        Analyzer(VisionTextRecognizer(), VisionLineReader(), LookupChain(HttpClients(IosHttpPlatform()), settings, null))
    }

    private fun scan(file: String): OcrOutcome {
        val img = assertNotNull(loadArgbImage("$SAMPLES_DIR/$file"), "cannot read $file")
        val o = runBlocking { analyzer.scan(img, ScanRegion.DEFAULT, exclude = null, fullScreenFallback = true) }
        println("VISION $file ${img.width}x${img.height}: name='${o.pick.name?.text}' (${o.pick.name?.source} ${o.pick.name?.confidence}) clan='${o.pick.clan?.text}' mode=${o.mode} expanded=${o.expanded} ${o.millis} ms: ${o.pick.reason}")
        o.lines.forEach { println("VISION   LINE '${it.text}' ${it.source} c=${fmt2(it.confidence)} warm=${fmt2(it.warmth)} ${it.box}") }
        o.raw.forEach { (src, ls) -> println("VISION   RAW $src: " + ls.joinToString(" | ") { "'${it.text}' c=${fmt2(it.confidence)}" }) }
        o.refinements.forEach { println("VISION   REFINE $it") }
        return o
    }

    @Test
    fun sample08Daniel() {
        val o = scan("08-daniel-fullscreen.jpg")
        assertEquals("Daniel", o.pick.name?.text)
        assertEquals("GLI ARGONAUTI", o.pick.clan?.text)
    }

    @Test
    fun sample09Hangul() {
        val o = scan("09-korean-fullscreen.jpg")
        val name = assertNotNull(o.pick.name?.text)
        val sim = StringSim.similarity(name, "동덕여대여자화장실문지기황나☠")
        println("VISION 09 similarity to the real name: ${fmt2(sim)}")
        assertTrue(sim >= 0.85, "'$name' similarity $sim")
    }
}
