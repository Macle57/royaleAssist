package com.macle.royaleassist.app

import com.macle.royaleassist.L
import com.macle.royaleassist.image.ArgbImage
import com.macle.royaleassist.image.toArgbImage
import platform.Foundation.NSSortDescriptor
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHPhotoLibrary
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Screenshot sources for the "Scan a screenshot" card: the system photo picker (no permission
 * needed) and, for automation, the newest photo in the library (needs photo access).
 * Callbacks run on the main thread.
 */
object Photos {
    // PHPickerViewController holds its delegate weakly.
    private var delegate: PickerDelegate? = null

    /** Shows the photo picker over [from]; [onPicked] gets the upright pixels, or null. */
    fun pick(from: UIViewController, onPicked: (ArgbImage?) -> Unit) {
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration = config)
        val d = PickerDelegate { img ->
            delegate = null
            onPicked(img)
        }
        delegate = d
        picker.delegate = d
        topmost(from).presentViewController(picker, animated = true, completion = null)
    }

    /** Reads the most recently added photo (asks for photo access first). */
    fun latest(onLoaded: (ArgbImage?) -> Unit) {
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { status ->
            if (status != PHAuthorizationStatusAuthorized && status != PHAuthorizationStatusLimited) {
                L.w("photos: no photo access (status $status)")
                main { onLoaded(null) }
                return@requestAuthorizationForAccessLevel
            }
            val opts = PHFetchOptions().apply {
                // Imported media keep their EXIF creation date; the modification date is the import.
                sortDescriptors = listOf(NSSortDescriptor(key = "modificationDate", ascending = false))
                fetchLimit = 1u
            }
            val asset = PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeImage, opts).firstObject as? PHAsset
            if (asset == null) {
                L.w("photos: library is empty")
                main { onLoaded(null) }
                return@requestAuthorizationForAccessLevel
            }
            val req = PHImageRequestOptions().apply {
                networkAccessAllowed = true
                synchronous = false
                deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
            }
            PHImageManager.defaultManager().requestImageDataAndOrientationForAsset(asset, req) { data, uti, _, _ ->
                val img = runCatching { data?.toArgbImage() }.onFailure { L.e("photos: cannot decode", it) }.getOrNull()
                L.i("photos: latest asset ${asset.localIdentifier} ($uti) -> ${img?.width}x${img?.height}")
                main { onLoaded(img) }
            }
        }
    }

    private class PickerDelegate(private val done: (ArgbImage?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
        override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
            picker.dismissViewControllerAnimated(true, completion = null)
            val result = didFinishPicking.firstOrNull() as? PHPickerResult
            if (result == null) {
                done(null)
                return
            }
            result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, error ->
                val img = runCatching { data?.toArgbImage() }.onFailure { L.e("photos: cannot decode", it) }.getOrNull()
                L.i("photos: picked ${img?.width}x${img?.height}${error?.let { " (${it.localizedDescription})" } ?: ""}")
                main { done(img) }
            }
        }
    }

    private fun topmost(vc: UIViewController): UIViewController {
        var top = vc
        while (true) top = top.presentedViewController ?: return top
    }

    private fun main(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)
}
