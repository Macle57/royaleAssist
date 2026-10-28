package com.macle.royaleassist.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.macle.royaleassist.L
import com.macle.royaleassist.RoyaleAssistApp
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.ocr.Prep
import com.macle.royaleassist.ocr.Preprocess
import com.macle.royaleassist.ocr.ScanRegion
import kotlinx.coroutines.flow.first
import com.macle.royaleassist.pipeline.OcrOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-only harness (not in release builds). Every result is logged with tag RoyaleAssist and a
 * "RESULT" prefix. Examples:
 *
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --ez all true
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --es asset 01-royale-king.png
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --es image /sdcard/Android/data/com.macle.royaleassist/files/x.png
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --es mode ocr --ez all true
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --es mode variants --ez all true
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --ez corpus true [--es only <file>] [--es region x0,y0,x1,y1]
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --es mode bench --es image <1080x2400 png>
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --es mode fullscreen --es image <png>
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --es mode show --es asset 01-royale-king.png
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --es name Macle --es clan "Kings & Queens"
 *
 * Assets are the repo's samples/ folder (wired in app/build.gradle.kts for debug builds).
 */
class DebugActivity : Activity() {
    private lateinit var out: TextView
    private val app get() = application as RoyaleAssistApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "full"
        if (mode == "show") { showImage(); return }

        out = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f; setPadding(24, 80, 24, 24) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(out) })

        // A recreated activity (e.g. configuration change) must not start the run a second time.
        if (savedInstanceState != null) { out.text = "Run already started; see logcat."; return }
        val name = intent.getStringExtra("name")
        val clan = intent.getStringExtra("clan")
        app.appScope.launch {
            app.ocr.ensureModels(applicationContext)
            if (name != null) {
                val r = app.lookup.lookup(name, clan)
                emit("RESULT lookup name='$name' clan='${clan ?: ""}' ${describe(r)}")
                return@launch
            }
            // --es region x0,y0,x1,y1 (fractions) overrides the saved scan region.
            val region = intent.getStringExtra("region")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
                ?.takeIf { it.size == 4 }?.let { ScanRegion(it[0], it[1], it[2], it[3]).normalized() }
                ?: app.settings.currentScanRegion()
            emit("REGION $region")
            if (intent.getBooleanExtra("corpus", false)) {
                // --ez corpus true [--es only <file substring>]: batch OCR + picking over assets/corpus.
                CorpusRunner(applicationContext, app.analyzer, ::emit).run(region, intent.getStringExtra("only"))
                emit("DONE")
                return@launch
            }
            val inputs = loadInputs()
            if (inputs.isEmpty()) emit("RESULT error=no-input (use --es asset, --es image or --ez all true)")
            val fallback = app.settings.fullScreenFallback.first()
            for ((label, bmp) in inputs) {
                when (mode) {
                    "variants" -> runVariants(label, bmp)
                    "tessbox" -> {
                        // --es box l,t,r,b (pixels) --es lang kor
                        val b = intent.getStringExtra("box")!!.split(',').map { it.trim().toInt() }
                        val crop = Bitmap.createBitmap(bmp, b[0], b[1], b[2] - b[0], b[3] - b[1])
                        val lang = intent.getStringExtra("lang") ?: "kor"
                        app.tesseract.readWith(crop, lang).forEach { emit("TESSBOX $label $lang: '${it.text}' conf=${it.confidence} ${it.variant}") }
                    }
                    "bench" -> runBench(label, bmp, region)
                    "ocr" -> emitOcr(label, app.analyzer.scan(bmp, region, fullScreenFallback = fallback), null)
                    "fullscreen" -> {
                        val o = app.analyzer.ocrAndPick(bmp)
                        emitOcr(label, o, o.pick.name?.let { app.lookup.lookup(it.text, o.pick.clan?.text) })
                    }
                    else -> {
                        // Same path as the bubble: region crop (small images count as the region).
                        val o = app.analyzer.scan(bmp, region, fullScreenFallback = fallback)
                        val r = o.pick.name?.let { app.lookup.lookup(it.text, o.pick.clan?.text) }
                        emitOcr(label, o, r)
                    }
                }
            }
            emit("DONE")
        }
    }

    private fun loadInputs(): List<Pair<String, Bitmap>> {
        val list = mutableListOf<Pair<String, Bitmap>>()
        intent.getStringExtra("image")?.let { path ->
            BitmapFactory.decodeFile(path)?.let { list += File(path).name to it } ?: L.w("debug: cannot decode $path")
        }
        val assetNames = if (intent.getBooleanExtra("all", false)) {
            assets.list("")?.filter { it.endsWith(".png") || it.endsWith(".jpg") }?.sorted().orEmpty()
        } else listOfNotNull(intent.getStringExtra("asset"))
        for (a in assetNames) {
            runCatching { assets.open(a).use { BitmapFactory.decodeStream(it) } }.getOrNull()?.let { list += a to it }
        }
        return list
    }

    /** OCR timing: whole screen vs. scan region, after one warm-up run of each. */
    private suspend fun runBench(label: String, bmp: Bitmap, region: ScanRegion) {
        val runs = intent.getIntExtra("runs", 5)
        app.analyzer.ocrAndPick(bmp)
        app.analyzer.scan(bmp, region)
        val full = (1..runs).map { app.analyzer.ocrAndPick(bmp) }
        val reg = (1..runs).map { app.analyzer.scan(bmp, region) }
        fun stats(l: List<OcrOutcome>) = l.map { it.millis }.let { "avg=${it.average().toInt()}ms min=${it.min()} max=${it.max()}" }
        emit("BENCH $label ${bmp.width}x${bmp.height} runs=$runs fullscreen[${stats(full)} name='${full.last().pick.name?.text}'] " +
            "region[${stats(reg)} name='${reg.last().pick.name?.text}' clan='${reg.last().pick.clan?.text}' box=${reg.last().scanned}]")
    }

    private suspend fun runVariants(label: String, bmp: Bitmap) {
        for (prep in Prep.entries) {
            val o = app.analyzer.ocrAndPick(bmp, prep = prep, useTesseract = false)
            emitOcr("$label[$prep]", o, null)
        }
        // Raw per-recognizer output with the default preprocessing, to see which model read what.
        val (img, scale) = Preprocess.apply(bmp, Prep.DEFAULT)
        app.ocr.recognizeRaw(img, scale).forEach { (src, lines) ->
            emit("RAW $label $src: " + lines.joinToString(" | ") { "'${it.text}' h=${it.box.height} c=${"%.2f".format(it.confidence)}" })
        }
        // Tesseract on the tallest line region.
        val o = app.analyzer.ocrAndPick(bmp, useTesseract = false)
        o.pick.name?.let { n ->
            val (crop, _) = Preprocess.crop(bmp, n.box, 0.2f)
            app.tesseract.readLine(crop).take(4).forEach {
                emit("TESS $label: '${it.text}' lang=${it.lang} variant=${it.variant} conf=${it.confidence}")
            }
        }
    }

    private suspend fun emitOcr(label: String, o: OcrOutcome, r: LookupResult?) {
        val lines = o.lines.joinToString(" | ") {
            "'${it.text}'(${it.source},h=${it.box.height},c=${"%.2f".format(it.confidence)},w=${"%.2f".format(it.warmth)},box=${it.box.left},${it.box.top},${it.box.right},${it.box.bottom})"
        }
        emit(
            "RESULT sample=$label name='${o.pick.name?.text ?: ""}' clan='${o.pick.clan?.text ?: ""}' " +
                "nameSrc=${o.pick.name?.source} tess=${o.tesseractUsed} mode=${o.mode} grown=${o.expanded} box=${o.scanned} ocrMs=${o.millis} why='${o.pick.reason}'" +
                (r?.let { " " + describe(it) } ?: "")
        )
        emit("LINES sample=$label $lines")
        o.refinements.forEach { emit("REFINE sample=$label $it") }
        o.raw.forEach { (src, ls) ->
            emit("RAWREC sample=$label $src: " + ls.joinToString(" | ") { "'${it.text}' c=${"%.2f".format(it.confidence)} y=${it.box.top}" })
        }
    }

    private fun describe(r: LookupResult): String {
        val h = r.hit
        val s = r.stats
        return "lookup=${r.strategy ?: "none"} found='${h?.name ?: ""}' tag=${h?.tag?.let { "#$it" } ?: ""} " +
            "foundClan='${h?.clanName ?: ""}' conf=${"%.2f".format(r.confidence)} strong=${r.strongMatch} cfBlocked=${r.needsVerification} " +
            "trophies=${s?.trophies} level=${s?.expLevel} statsSrc='${s?.source ?: ""}' err='${r.error ?: ""}' " +
            "log=[${r.log.joinToString(" ; ")}]"
    }

    private suspend fun emit(line: String) {
        L.i(line)
        withContext(Dispatchers.Main) { if (::out.isInitialized) out.append(line + "\n\n") }
    }

    /** Shows one image fullscreen (scaled to fit) so the real bubble -> capture path can be tested. */
    private fun showImage() {
        val bmp = loadInputs().firstOrNull()?.second
        val iv = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            setImageBitmap(bmp)
        }
        setContentView(iv)
        window.decorView.post {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        iv.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        L.i("debug: showing ${bmp?.width}x${bmp?.height}")
    }
}
