package com.macle.royaleassist.debug

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.FileProvider
import com.macle.royaleassist.L
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.OcrLine
import com.macle.royaleassist.ocr.ScanRegion
import com.macle.royaleassist.ocr.Scripts
import com.macle.royaleassist.pipeline.OcrOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Saves every bubble scan for debugging under `<base>/scans/<timestamp>/`: frame.jpg (full capture),
 * crop.png (what was OCR'd), scan.json (region, lines, pick, lookup trace, timings).
 * `<base>` is the app's external files dir (`adb pull /sdcard/Android/data/<pkg>/files/scans`)
 * or internal files when that is unavailable. Keeps the newest [KEEP] scans.
 */
class ScanRecorder(private val context: Context) {
    val baseDir: File get() = context.getExternalFilesDir(null) ?: context.filesDir
    val scansDir: File get() = File(baseDir, "scans")
    val logsDir: File get() = File(baseDir, "logs")

    private val json = Json { prettyPrint = true }

    data class Stats(val count: Int, val bytes: Long)

    fun stats(): Stats {
        val dirs = scansDir.listFiles { f -> f.isDirectory }.orEmpty()
        return Stats(dirs.size, dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } })
    }

    suspend fun record(
        frame: Bitmap,
        region: ScanRegion,
        ocr: OcrOutcome?,
        result: LookupResult?,
        timings: Map<String, Long>,
        note: String? = null,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(scansDir, SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())).apply { mkdirs() }
            File(dir, "frame.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ocr?.crop?.let { c -> File(dir, "crop.png").outputStream().use { c.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            File(dir, "scan.json").writeText(json.encodeToString(JsonObject.serializer(), describe(frame, region, ocr, result, timings, note)))
            prune()
            L.i("debug: scan saved to ${dir.absolutePath}")
            dir
        } catch (e: Exception) {
            L.w("debug: could not save scan: ${e.message}")
            null
        }
    }

    private fun boxJson(b: Box) = buildJsonObject {
        put("left", b.left); put("top", b.top); put("right", b.right); put("bottom", b.bottom)
        put("width", b.width); put("height", b.height)
    }

    private fun lineJson(l: OcrLine) = buildJsonObject {
        put("text", l.text)
        put("recognizer", l.source)
        put("script", Scripts.dominant(l.text)?.name ?: "COMMON")
        put("confidence", l.confidence)
        put("warmth", l.warmth)
        put("language", l.language)
        put("box", boxJson(l.box))
    }

    private fun describe(
        frame: Bitmap, region: ScanRegion, ocr: OcrOutcome?, r: LookupResult?, timings: Map<String, Long>, note: String?,
    ) = buildJsonObject {
        put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
        put("device", "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        putJsonObject("screen") { put("width", frame.width); put("height", frame.height) }
        putJsonObject("region") {
            putJsonObject("fractions") {
                put("left", region.left); put("top", region.top); put("right", region.right); put("bottom", region.bottom)
            }
            put("pixels", boxJson(region.toBox(frame.width, frame.height)))
        }
        note?.let { put("note", it) }
        if (ocr != null) {
            put("mode", ocr.mode)
            ocr.scanned?.let { put("scannedBox", boxJson(it)) }
            put("expanded", ocr.expanded)
            ocr.firstScanned?.let { put("firstScannedBox", boxJson(it)) }
            put("tesseractUsed", ocr.tesseractUsed)
            putJsonArray("lines") { ocr.lines.forEach { add(lineJson(it)) } }
            putJsonObject("rawByRecognizer") {
                ocr.raw.forEach { (src, ls) -> put(src, buildJsonArray { ls.forEach { add(lineJson(it)) } }) }
            }
            put("secondPass", buildJsonArray { ocr.refinements.forEach { add(it) } })
            putJsonObject("pick") {
                put("name", ocr.pick.name?.text)
                put("clan", ocr.pick.clan?.text)
                put("reason", ocr.pick.reason)
            }
        }
        if (r != null) {
            putJsonObject("lookup") {
                put("query", r.query)
                put("clanQuery", r.clanQuery)
                put("strategy", r.strategy)
                put("confidence", r.confidence)
                put("strongMatch", r.strongMatch)
                put("cached", r.cached)
                put("error", r.error)
                put("found", r.hit?.let { "${it.name} #${it.tag} (${it.clanName ?: "no clan"})" })
                put("trophies", r.stats?.trophies)
                put("statsSource", r.stats?.source)
                put("deck", r.lastBattle?.cards?.joinToString { it.name })
                put("log", buildJsonArray { r.log.forEach { add(it) } })
            }
        }
        putJsonObject("timingsMs") { timings.forEach { (k, v) -> put(k, v) } }
    }

    private fun prune() {
        val dirs = scansDir.listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.name }
        dirs.drop(KEEP).forEach { it.deleteRecursively() }
    }

    fun clear() {
        scansDir.deleteRecursively()
        L.i("debug: saved scans cleared")
    }

    /** Zips scans and logs into cacheDir/export and returns a share intent (FileProvider). */
    suspend fun exportBundle(): Intent = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "export").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val zip = File(outDir, "royaleassist-debug-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            for ((root, prefix) in listOf(scansDir to "scans", logsDir to "logs")) {
                if (!root.exists()) continue
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    zos.putNextEntry(ZipEntry("$prefix/" + f.relativeTo(root).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        L.i("debug: bundle ${zip.absolutePath} (${zip.length()} bytes)")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "royaleAssist debug bundle")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        const val KEEP = 30
    }
}
