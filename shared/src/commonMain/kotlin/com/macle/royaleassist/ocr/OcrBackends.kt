package com.macle.royaleassist.ocr

import com.macle.royaleassist.image.ArgbImage

/** Merged lines plus what each recognizer read (kept for the debug scan JSON). */
data class RecognizedText(val merged: List<OcrLine>, val raw: Map<String, List<OcrLine>>)

/** One reading of a line crop by the fallback reader: text, mean confidence, model and binarisation. */
data class LineReading(val text: String, val confidence: Int, val lang: String, val variant: String)

/**
 * The on-device text recognizer the Analyzer reads screens with (ML Kit's `OcrEngine` in the
 * Android app, which converts the image to a Bitmap). Boxes are divided by `scale` so they are in
 * the coordinates of the image before preprocessing.
 */
interface TextRecognizerBackend {
    suspend fun recognizeDetailed(image: ArgbImage, scale: Float = 1f): RecognizedText

    /** Per-recognizer output (all recognizers, or only [sources]). Failures give an empty list. */
    suspend fun recognizeRaw(image: ArgbImage, scale: Float = 1f, sources: Set<String>? = null): Map<String, List<OcrLine>>
}

/** Second-opinion reader for a single name line (Tesseract in the Android app, for scripts ML Kit lacks). */
interface LineReader {
    /** Every reading of [crop], best first. */
    suspend fun readLine(crop: ArgbImage): List<LineReading>
}
