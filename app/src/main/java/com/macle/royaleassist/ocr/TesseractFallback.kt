package com.macle.royaleassist.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.macle.royaleassist.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tesseract (Tesseract4Android) for the scripts ML Kit cannot read: Arabic and Cyrillic.
 * The tessdata_fast models (ara 1.4 MB, rus 3.9 MB) ship in assets/tessdata and are copied to
 * app files on first use. Only runs on the name region when ML Kit's reading looks unreliable.
 */
class TesseractFallback(private val context: Context) {
    data class Result(val text: String, val confidence: Int, val lang: String, val variant: String)

    private val mutex = Mutex()
    private val apis = HashMap<String, TessBaseAPI>()

    private fun dataPath(): File {
        val root = File(context.filesDir, "tesseract")
        val dir = File(root, "tessdata").apply { mkdirs() }
        for (lang in LANGS) {
            val f = File(dir, "$lang.traineddata")
            if (!f.exists() || f.length() == 0L) {
                context.assets.open("tessdata/$lang.traineddata").use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
                L.i("tesseract: copied $lang.traineddata (${f.length()} bytes)")
            }
        }
        return root
    }

    private fun api(lang: String): TessBaseAPI? = apis[lang] ?: run {
        val api = TessBaseAPI()
        if (!api.init(dataPath().absolutePath, lang, TessBaseAPI.OEM_LSTM_ONLY)) {
            L.w("tesseract: init $lang failed")
            api.recycle()
            null
        } else {
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            apis[lang] = api
            api
        }
    }

    /**
     * Reads a single text line crop with every language and a few binarisations; returns all
     * readings sorted best first (by mean confidence, then script fit).
     */
    suspend fun readLine(crop: Bitmap): List<Result> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val t0 = System.currentTimeMillis()
            val targetH = 96f
            val scale = (targetH / crop.height).coerceIn(1f, 6f)
            val (up, _) = Preprocess.apply(crop, Prep.UPSCALE_GRAY, forcedScale = scale)
            val variants = listOf(
                "gray" to up,
                "bin150" to Preprocess.binarize(up, 150, invert = false),
                "bin190" to Preprocess.binarize(up, 190, invert = false),
            )
            val out = mutableListOf<Result>()
            for (lang in LANGS) {
                val api = api(lang) ?: continue
                for ((vName, bmp) in variants) {
                    api.setImage(bmp)
                    val text = cleanEdges(api.utF8Text.orEmpty())
                    val conf = api.meanConfidence()
                    api.clear()
                    if (text.isNotEmpty()) out += Result(text, conf, lang, vName)
                }
            }
            val sorted = out.sortedWith(
                compareByDescending<Result> { it.confidence + (Scripts.fitFraction(it.text, "tesseract-${it.lang}") * 15).toInt() }
            )
            L.i("tesseract: ${sorted.size} readings in ${System.currentTimeMillis() - t0} ms: " +
                sorted.take(4).joinToString { "'${it.text}'(${it.lang}/${it.variant} ${it.confidence})" })
            sorted
        }
    }

    companion object {
        val LANGS = listOf("ara", "rus")

        /** Drop edge tokens without letters (stray digits, bars, quotes Tesseract adds at the borders). */
        fun cleanEdges(raw: String): String {
            val tokens = raw.replace('\n', ' ').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return tokens.dropWhile { Scripts.letterCount(it) == 0 }
                .dropLastWhile { Scripts.letterCount(it) == 0 }
                .joinToString(" ")
                .trim('\'', '"', '|', '.', ',', '-', '_')
        }
    }
}
