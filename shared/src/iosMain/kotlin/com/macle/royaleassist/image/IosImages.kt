package com.macle.royaleassist.image

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBitmapContextGetBytesPerRow
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.Foundation.NSData
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageOrientation
import platform.posix.memcpy

/*
 * ArgbImage <-> CoreGraphics. An ArgbImage pixel is an Int 0xAARRGGBB; stored little-endian that
 * is the byte order B, G, R, A, which CoreGraphics calls "32-bit little-endian, alpha first".
 * Screenshots are opaque, so the alpha byte is skipped on the way in and set to 0xFF on the way out.
 */

private val OPAQUE_BGRX: UInt = CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value or kCGBitmapByteOrder32Little

/** A CGImage with this image's pixels (the caller releases it with `CGImageRelease`). */
fun ArgbImage.toCGImage(): CGImageRef {
    val cs = CGColorSpaceCreateDeviceRGB()
    // The context owns its buffer, so the image it makes stays valid after the context is released.
    val ctx = CGBitmapContextCreate(null, width.convert(), height.convert(), 8u, 0u, cs, OPAQUE_BGRX)
        ?: error("CGBitmapContextCreate failed for ${width}x$height")
    CGColorSpaceRelease(cs)
    try {
        val base = CGBitmapContextGetData(ctx) ?: error("bitmap context without data")
        val stride = CGBitmapContextGetBytesPerRow(ctx).toLong()
        pixels.usePinned { pinned ->
            for (y in 0 until height) {
                val dst: CPointer<ByteVar> = interpretCPointer(base.rawValue + y * stride)!!
                memcpy(dst, pinned.addressOf(y * width), (width * 4).convert())
            }
        }
        return CGBitmapContextCreateImage(ctx) ?: error("CGBitmapContextCreateImage failed")
    } finally {
        CGContextRelease(ctx)
    }
}

/** The pixels of a CGImage (any format; CoreGraphics converts while drawing). */
fun CGImageRef.toArgbImage(): ArgbImage {
    val w = CGImageGetWidth(this).toInt()
    val h = CGImageGetHeight(this).toInt()
    val out = IntArray(w * h)
    val cs = CGColorSpaceCreateDeviceRGB()
    out.usePinned { pinned ->
        val ctx = CGBitmapContextCreate(pinned.addressOf(0), w.convert(), h.convert(), 8u, (w * 4).convert(), cs, OPAQUE_BGRX)
            ?: error("CGBitmapContextCreate failed for ${w}x$h")
        CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), this)
        CGContextRelease(ctx)
    }
    CGColorSpaceRelease(cs)
    for (i in out.indices) out[i] = out[i] or OPAQUE_ALPHA
    return ArgbImage(w, h, out)
}

private const val OPAQUE_ALPHA = -0x1000000 // 0xFF000000

/**
 * The pixels of a UIImage, upright: photos with an EXIF orientation are redrawn first. Uses the
 * image's pixel size (not points).
 */
fun UIImage.toArgbImage(): ArgbImage {
    val cg = CGImage
    if (cg != null && imageOrientation == UIImageOrientation.UIImageOrientationUp) return cg.toArgbImage()
    val format = UIGraphicsImageRendererFormat.preferredFormat().apply {
        scale = 1.0
        opaque = true
    }
    val pxW = size.useContents { width } * scale
    val pxH = size.useContents { height } * scale
    val renderer = UIGraphicsImageRenderer(size = CGSizeMake(pxW, pxH), format = format)
    val upright = renderer.imageWithActions { drawInRect(CGRectMake(0.0, 0.0, pxW, pxH)) }
    return upright.CGImage?.toArgbImage() ?: error("cannot read the image")
}

/** Decodes JPEG/PNG/HEIC data into upright pixels; null when it is not an image. */
fun NSData.toArgbImage(): ArgbImage? = UIImage.imageWithData(this)?.toArgbImage()

/** Reads an image file (JPEG/PNG/HEIC); null when it cannot be read. */
fun loadArgbImage(path: String): ArgbImage? = UIImage.imageWithContentsOfFile(path)?.toArgbImage()
