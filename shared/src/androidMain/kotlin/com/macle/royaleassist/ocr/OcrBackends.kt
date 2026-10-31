package com.macle.royaleassist.ocr

import android.graphics.Bitmap

/** Merged lines plus what each recognizer read (kept for the debug scan JSON). */
data class RecognizedText(val merged: List<OcrLine>, val raw: Map<String, List<OcrLine>>)

/** One reading of a line crop by the fallback reader: text, mean confidence, model and binarisation. */
data class LineReading(val text: String, val confidence: Int, val lang: String, val variant: String)

/**
 * The on-device text recognizer the Analyzer reads screens with (ML Kit's `OcrEngine` in the app).
 * Boxes are divided by `scale` so they are in the coordinates of the image before preprocessing.
 */
interface TextRecognizerBackend {
    suspend fun recognizeDetailed(bitmap: Bitmap, scale: Float = 1f): RecognizedText

    /** Per-recognizer output (all recognizers, or only [sources]). Failures give an empty list. */
    suspend fun recognizeRaw(bitmap: Bitmap, scale: Float = 1f, sources: Set<String>? = null): Map<String, List<OcrLine>>
}

/** Second-opinion reader for a single name line (Tesseract in the app, for scripts ML Kit lacks). */
interface LineReader {
    /** Every reading of [crop], best first. */
    suspend fun readLine(crop: Bitmap): List<LineReading>
}
