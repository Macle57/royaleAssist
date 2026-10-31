package com.macle.royaleassist.debug

import android.content.Context
import android.graphics.BitmapFactory
import com.macle.royaleassist.L
import com.macle.royaleassist.lookup.StringSim
import com.macle.royaleassist.ocr.ClanText
import com.macle.royaleassist.ocr.ScanRegion
import com.macle.royaleassist.pipeline.Analyzer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * Batch evaluation of OCR + name/clan picking (no network) over the labelled corpus in
 * assets/corpus (repo samples/corpus). Writes `corpus-results.json` to the external files dir;
 * `tools/corpus_report.py` pulls it and prints per-script accuracy.
 *
 * A name "matches" when [StringSim.similarity] >= [MATCH] (case-insensitive, spaces/decorations
 * stripped, Hangul by jamo, CJK jamo look-alikes folded); "exact" is plain string equality.
 * Expected values ending in "?" are uncertain labels; the "?" is dropped before comparing.
 * An expected clan of null (the UI showed "No Clan" or a translation) matches when no clan was read.
 */
class CorpusRunner(private val context: Context, private val analyzer: Analyzer, private val emit: suspend (String) -> Unit) {

    suspend fun run(region: ScanRegion, only: String?): File {
        val index = Json.parseToJsonElement(context.assets.open("corpus/index.json").bufferedReader().readText()).jsonArray
        val results = mutableListOf<JsonObject>()
        val t0 = System.currentTimeMillis()
        for (el in index) {
            val item = el.jsonObject
            val file = item["file"]!!.jsonPrimitive.content
            if (only != null && only !in file) continue
            val expName = item["expected_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val expClan = item["expected_clan"]?.jsonPrimitive?.contentOrNull
            val script = item["script"]?.jsonPrimitive?.contentOrNull ?: "?"
            val bmp = context.assets.open("corpus/$file").use { BitmapFactory.decodeStream(it) }
            if (bmp == null) {
                emit("CORPUS $file error=decode")
                continue
            }
            val start = System.currentTimeMillis()
            val o = try {
                analyzer.scan(bmp, region, exclude = null, fullScreenFallback = false)
            } catch (e: Exception) {
                L.e("corpus: $file failed", e)
                emit("CORPUS $file error=${e.message}")
                continue
            }
            val ms = System.currentTimeMillis() - start
            val readName = o.pick.name?.text
            val readClan = o.pick.clan?.text
            val nameSim = if (readName == null) 0.0 else StringSim.similarity(readName, clean(expName))
            val nameMatch = nameSim >= MATCH
            val nameExact = readName?.trim() == clean(expName)
            val clanMatch = if (expClan == null) readClan == null || ClanText.isNoClanPlaceholder(readClan)
            else readClan != null && StringSim.similarity(readClan, clean(expClan)) >= MATCH
            val r = buildJsonObject {
                put("file", file)
                put("script", script)
                put("width", bmp.width)
                put("height", bmp.height)
                put("expectedName", expName)
                put("expectedClan", expClan)
                put("uncertain", expName.endsWith("?") || expClan?.endsWith("?") == true)
                put("readName", readName)
                put("readClan", readClan)
                put("nameSim", nameSim)
                put("nameMatch", nameMatch)
                put("nameExact", nameExact)
                put("clanMatch", clanMatch)
                put("reason", o.pick.reason)
                put("grown", o.expanded)
                put("tesseract", o.tesseractUsed)
                put("nameSource", o.pick.name?.source)
                put("ms", ms)
                putJsonArray("lines") {
                    o.lines.forEach { l ->
                        add(buildJsonObject {
                            put("text", l.text); put("src", l.source); put("conf", l.confidence); put("warmth", l.warmth)
                            put("box", buildJsonArray { add(l.box.left); add(l.box.top); add(l.box.right); add(l.box.bottom) })
                        })
                    }
                }
                putJsonArray("refine") { o.refinements.forEach { add(it) } }
            }
            results += r
            emit(
                "CORPUS $file [$script] name ${if (nameMatch) "OK" else "XX"}${if (nameExact) "=" else ""} " +
                    "'${readName ?: ""}' vs '$expName' clan ${if (clanMatch) "OK" else "XX"} '${readClan ?: ""}' vs '${expClan ?: "-"}' " +
                    "grown=${o.expanded} ${ms}ms"
            )
            bmp.recycle()
        }
        val out = File(context.getExternalFilesDir(null) ?: context.filesDir, "corpus-results.json")
        out.writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(results)))
        val n = results.size
        val names = results.count { it["nameMatch"]!!.jsonPrimitive.content == "true" }
        val clans = results.count { it["clanMatch"]!!.jsonPrimitive.content == "true" }
        emit("CORPUS SUMMARY items=$n name=$names clan=$clans in ${(System.currentTimeMillis() - t0) / 1000}s -> ${out.absolutePath}")
        return out
    }

    companion object {
        const val MATCH = 0.85
        fun clean(s: String) = s.trim().removeSuffix("?").trim()
    }
}
