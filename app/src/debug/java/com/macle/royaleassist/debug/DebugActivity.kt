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

        val name = intent.getStringExtra("name")
        val clan = intent.getStringExtra("clan")
        app.appScope.launch {
            app.ocr.ensureModels(applicationContext)
            if (name != null) {
                val r = app.lookup.lookup(name, clan)
                emit("RESULT lookup name='$name' clan='${clan ?: ""}' ${describe(r)}")
                return@launch
            }
            val inputs = loadInputs()
            if (inputs.isEmpty()) emit("RESULT error=no-input (use --es asset, --es image or --ez all true)")
            for ((label, bmp) in inputs) {
                when (mode) {
                    "variants" -> runVariants(label, bmp)
                    "ocr" -> emitOcr(label, app.analyzer.ocrAndPick(bmp), null)
                    else -> {
                        val o = app.analyzer.ocrAndPick(bmp)
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
            assets.list("")?.filter { it.endsWith(".png") }?.sorted().orEmpty()
        } else listOfNotNull(intent.getStringExtra("asset"))
        for (a in assetNames) {
            runCatching { assets.open(a).use { BitmapFactory.decodeStream(it) } }.getOrNull()?.let { list += a to it }
        }
        return list
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
        val lines = o.lines.joinToString(" | ") { "'${it.text}'(${it.source},h=${it.box.height},c=${"%.2f".format(it.confidence)})" }
        emit(
            "RESULT sample=$label name='${o.pick.name?.text ?: ""}' clan='${o.pick.clan?.text ?: ""}' " +
                "nameSrc=${o.pick.name?.source} tess=${o.tesseractUsed} ocrMs=${o.millis}" +
                (r?.let { " " + describe(it) } ?: "")
        )
        emit("LINES sample=$label $lines")
    }

    private fun describe(r: LookupResult): String {
        val h = r.hit
        val s = r.stats
        return "lookup=${r.strategy ?: "none"} found='${h?.name ?: ""}' tag=${h?.tag?.let { "#$it" } ?: ""} " +
            "foundClan='${h?.clanName ?: ""}' conf=${"%.2f".format(r.confidence)} cfBlocked=${r.needsVerification} " +
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
