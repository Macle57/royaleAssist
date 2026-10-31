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
import com.macle.royaleassist.image.ArgbImage
import com.macle.royaleassist.image.toArgbImage
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
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --es mode seed --es name "Royale KiNg" --es clan "Eternal Night" --es tag PVJ28CYGG
 *     [--es battles <battles.html> --es decks <decks.html>]
 *     (maps that screen reading to a player already in the cache, so a bubble scan resolves offline)
 *   adb shell am start -n com.macle.royaleassist/.debug.DebugActivity --es mode deckcard [--es battles <battles.html>] [--ei count 3]
 *     (turns the deck view on and shows the overlay deck card with [count] recent decks, no scan or lookup;
 *     decks come from a saved royaleapi.com battles page, else placeholder cards without icons)
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
        if (mode == "seed") { seed(); return }
        if (mode == "deckcard") { deckCard(); return }

        out = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f; setPadding(24, 80, 24, 24) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(out) })

        // A recreated activity (e.g. configuration change) must not start the run a second time.
        if (savedInstanceState != null) { out.text = "Run already started; see logcat."; return }
        val name = intent.getStringExtra("name")
        val clan = intent.getStringExtra("clan")
        app.appScope.launch {
            app.ocr.ensureModels(applicationContext)
            if (name != null) {
                // --ez noclan true: the screen showed "No Clan"; --ei league N / --ei trophies N: rank line.
                val hint = com.macle.royaleassist.lookup.ScreenHint(
                    clan, intent.getBooleanExtra("noclan", false),
                    intent.getIntExtra("league", -1).takeIf { it >= 0 }, intent.getIntExtra("trophies", -1).takeIf { it >= 0 },
                )
                val r = app.lookup.lookup(name, hint, bypassCache = intent.getBooleanExtra("fresh", false))
                emit("RESULT lookup name='$name' ${hint.describe()} ${describe(r)}")
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
            for ((label, bitmap) in inputs) {
                val bmp = bitmap.toArgbImage()
                when (mode) {
                    "variants" -> runVariants(label, bmp)
                    "tessbox" -> {
                        // --es box l,t,r,b (pixels) --es lang kor
                        val b = intent.getStringExtra("box")!!.split(',').map { it.trim().toInt() }
                        val crop = bmp.crop(b[0], b[1], b[2] - b[0], b[3] - b[1])
                        val lang = intent.getStringExtra("lang") ?: "kor"
                        app.tesseract.readWith(crop, lang).forEach { emit("TESSBOX $label $lang: '${it.text}' conf=${it.confidence} ${it.variant}") }
                    }
                    "bench" -> runBench(label, bmp, region)
                    "ocr" -> emitOcr(label, app.analyzer.scan(bmp, region, fullScreenFallback = fallback), null)
                    "fullscreen" -> {
                        val o = app.analyzer.ocrAndPick(bmp)
                        emitOcr(label, o, o.pick.name?.let { app.lookup.lookup(it.text, o.pick.hint()) })
                    }
                    else -> {
                        // Same path as the bubble: region crop (small images count as the region).
                        val o = app.analyzer.scan(bmp, region, fullScreenFallback = fallback)
                        val r = o.pick.name?.let { app.lookup.lookup(it.text, o.pick.hint()) }
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
    private suspend fun runBench(label: String, bmp: ArgbImage, region: ScanRegion) {
        val runs = intent.getIntExtra("runs", 5)
        app.analyzer.ocrAndPick(bmp)
        app.analyzer.scan(bmp, region)
        val full = (1..runs).map { app.analyzer.ocrAndPick(bmp) }
        val reg = (1..runs).map { app.analyzer.scan(bmp, region) }
        fun stats(l: List<OcrOutcome>) = l.map { it.millis }.let { "avg=${it.average().toInt()}ms min=${it.min()} max=${it.max()}" }
        emit("BENCH $label ${bmp.width}x${bmp.height} runs=$runs fullscreen[${stats(full)} name='${full.last().pick.name?.text}'] " +
            "region[${stats(reg)} name='${reg.last().pick.name?.text}' clan='${reg.last().pick.clan?.text}' box=${reg.last().scanned}]")
    }

    private suspend fun runVariants(label: String, bmp: ArgbImage) {
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
                "hint=[${o.pick.hint().describe()}] nameSrc=${o.pick.name?.source} tess=${o.tesseractUsed} mode=${o.mode} grown=${o.expanded} box=${o.scanned} ocrMs=${o.millis} why='${o.pick.reason}'" +
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
        return "note='${r.note ?: ""}' alts=[${r.alternatives.joinToString { it.name + " #" + it.tag + " (" + (it.clanName ?: "no clan") + ")" }}] lookup=${r.strategy ?: "none"} found='${h?.name ?: ""}' tag=${h?.tag?.let { "#$it" } ?: ""} " +
            "foundClan='${h?.clanName ?: ""}' conf=${"%.2f".format(r.confidence)} strong=${r.strongMatch} cfBlocked=${r.needsVerification} " +
            "trophies=${s?.trophies} level=${s?.expLevel} statsSrc='${s?.source ?: ""}' err='${r.error ?: ""}' " +
            "log=[${r.log.joinToString(" ; ")}]"
    }

    private suspend fun emit(line: String) {
        L.i(line)
        withContext(Dispatchers.Main) { if (::out.isInitialized) out.append(line + "\n\n") }
    }

    /** Maps an OCR reading (name, clan or --ez noclan) to a cached tag, for offline overlay tests. */
    private fun seed() {
        val name = intent.getStringExtra("name") ?: return finish()
        val tag = intent.getStringExtra("tag")?.removePrefix("#")?.uppercase() ?: return finish()
        val clan = intent.getStringExtra("clan")
        val key = com.macle.royaleassist.cache.CachePolicy.queryKey(name, clan, intent.getBooleanExtra("noclan", false))
        app.appScope.launch {
            val cached = app.cache.byTag(tag)
            if (cached == null) L.i("SEED no cached player #$tag") else {
                // --es battles <saved royaleapi battles page> [--es decks <saved Decks tab>]: offline decks.
                intent.getStringExtra("battles")?.let { path ->
                    val battles = com.macle.royaleassist.lookup.BattleLog.parseRoyaleApiBattles(File(path).readText(), tag)
                    val tab = intent.getStringExtra("decks")?.let { com.macle.royaleassist.lookup.BattleLog.parseRoyaleApiDecksTab(File(it).readText()) }.orEmpty()
                    if (battles.isNotEmpty()) {
                        app.cache.updateDecks(tag, battles.first(), com.macle.royaleassist.lookup.BattleLog.uniqueDecks(battles, 3, tab))
                    }
                }
                app.cache.putQuery(key, tag, System.currentTimeMillis())
                L.i("SEED '$key' -> #$tag (${cached.entity.name}, ${app.cache.byTag(tag)?.decks?.size ?: 0} decks cached)")
            }
            withContext(Dispatchers.Main) { finish() }
        }
    }

    /** Shows the overlay deck card for a made-up result, so the card UI can be checked without a scan. */
    private fun deckCard() {
        val count = intent.getIntExtra("count", 3).coerceIn(1, 3)
        app.appScope.launch {
            val battles = intent.getStringExtra("battles")?.let {
                com.macle.royaleassist.lookup.BattleLog.parseRoyaleApiBattles(File(it).readText(), "DEMO")
            }.orEmpty()
            val decks = if (battles.isNotEmpty()) {
                com.macle.royaleassist.lookup.BattleLog.uniqueDecks(battles, count)
            } else {
                (1..count).map { d ->
                    com.macle.royaleassist.lookup.RecentDeck((1..8).map { com.macle.royaleassist.lookup.DeckCard("card $d.$it", 11, null) }, "Ladder", null, 1, count)
                }
            }
            val r = LookupResult(
                "Royale KiNg", "Eternal Night", com.macle.royaleassist.lookup.PlayerHit("Royale KiNg", "PVJ28CYGG", "Eternal Night"), "L1", 1.0,
                com.macle.royaleassist.lookup.PlayerStats(name = "Royale KiNg", tag = "PVJ28CYGG", clanName = "Eternal Night", trophies = 9000, source = "debug"),
                strongMatch = true,
                lastBattle = battles.firstOrNull() ?: com.macle.royaleassist.lookup.LastBattle("Ladder", null, decks.first().cards, "debug"),
                recentDecks = decks,
            )
            val line = com.macle.royaleassist.ocr.OcrLine("Royale KiNg", com.macle.royaleassist.ocr.Box(0, 0, 100, 20), 1f, "debug")
            app.settings.updateOverlayPrefs { it.copy(deckOnly = true, decksToShow = count) }
            com.macle.royaleassist.overlay.OverlayService.debugPanel =
                com.macle.royaleassist.overlay.PanelState.Result(listOf(line), com.macle.royaleassist.ocr.NamePick(line, null, reason = "debug"), r)
            L.i("DECKCARD showing ${decks.size} decks (${if (battles.isEmpty()) "placeholders" else "from battles page"})")
            withContext(Dispatchers.Main) {
                com.macle.royaleassist.overlay.OverlayService.send(this@DebugActivity, com.macle.royaleassist.overlay.OverlayService.ACTION_DEBUG_SHOW)
                finish()
            }
        }
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
