package com.macle.royaleassist.image

import android.graphics.Bitmap
import com.macle.royaleassist.ocr.Preprocess

/** The pixels of this bitmap as an [ArgbImage] (hardware and other configs are copied to ARGB_8888 first). */
fun Bitmap.toArgbImage(): ArgbImage {
    val src = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val px = IntArray(src.width * src.height)
    src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
    return ArgbImage(src.width, src.height, px)
}

/** An immutable ARGB_8888 bitmap with these pixels. */
fun ArgbImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.ARGB_8888)

/** Resize with Skia's bilinear filter, as `Bitmap.createScaledBitmap(filter = true)` did before. */
fun skiaResize(img: ArgbImage, w: Int, h: Int): ArgbImage =
    Bitmap.createScaledBitmap(img.toBitmap(), w, h, true).toArgbImage()

/** Makes [Preprocess] scale with Skia (call once at app start). */
fun useSkiaResize() {
    Preprocess.resizer = ::skiaResize
}
