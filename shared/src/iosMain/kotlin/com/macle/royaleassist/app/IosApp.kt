package com.macle.royaleassist.app

import com.macle.royaleassist.cache.CacheDb
import com.macle.royaleassist.cache.LookupCache
import com.macle.royaleassist.cache.create
import com.macle.royaleassist.lookup.HttpClients
import com.macle.royaleassist.lookup.IosHttpPlatform
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.settings.Settings
import com.macle.royaleassist.ui.CardImages
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * The app-wide singletons on iOS (the counterpart of `RoyaleAssistApp` on Android): settings,
 * the lookup cache, the HTTP client and the lookup chain. Created on first use.
 */
object IosApp {
    /** The HTTP platform; the WKWebView verifier hands its cookies and User-Agent to it. */
    val httpPlatform: IosHttpPlatform by lazy { IosHttpPlatform() }
    val http: HttpClients by lazy { HttpClients(httpPlatform) }
    val settings: Settings by lazy { Settings(saveScansDefault = false) }
    val db: CacheDb by lazy { CacheDb.create() }
    val cache: LookupCache by lazy { LookupCache(db.dao()) }
    val lookup: LookupChain by lazy { LookupChain(http, settings, cache) }

    private var imagesInstalled = false

    /** Sets up card image loading (Coil) once; every entry point calls it. */
    fun ensureImages() {
        if (imagesInstalled) return
        imagesInstalled = true
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        CardImages.install(http, diskCacheDir = "$caches/coil3_disk_cache".toPath())
    }
}
