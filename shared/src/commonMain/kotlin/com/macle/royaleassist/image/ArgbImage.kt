package com.macle.royaleassist.image

/**
 * A platform-free image: [width] x [height] ARGB_8888 pixels, row-major (the layout of Android's
 * `Bitmap.getPixels`). The OCR preprocessing, colour statistics and screen hashes work on this;
 * the platforms convert at the edges (Android: `Bitmap.toArgbImage()` / `ArgbImage.toBitmap()`).
 */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "empty image ${width}x$height" }
        require(pixels.size >= width * height) { "${pixels.size} pixels for ${width}x$height" }
    }

    fun getPixel(x: Int, y: Int): Int = pixels[y * width + x]

    /** Copies a [w] x [h] block at ([x], [y]) into [out] (like `Bitmap.getPixels`). */
    fun getPixels(out: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        require(x >= 0 && y >= 0 && x + w <= width && y + h <= height) { "block ${w}x$h at ($x,$y) outside ${width}x$height" }
        for (row in 0 until h) {
            pixels.copyInto(out, offset + row * stride, (y + row) * width + x, (y + row) * width + x + w)
        }
    }

    /** A copy of the [w] x [h] block at ([x], [y]) (like `Bitmap.createBitmap(src, x, y, w, h)`). */
    fun crop(x: Int, y: Int, w: Int, h: Int): ArgbImage {
        if (x == 0 && y == 0 && w == width && h == height) return this
        val out = IntArray(w * h)
        getPixels(out, 0, w, x, y, w, h)
        return ArgbImage(w, h, out)
    }
}
