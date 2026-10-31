package com.macle.royaleassist.app

import com.macle.royaleassist.L
import com.macle.royaleassist.cache.CacheDb
import com.macle.royaleassist.cache.LookupCache
import com.macle.royaleassist.cache.create
import com.macle.royaleassist.lookup.HttpClients
import com.macle.royaleassist.lookup.IosHttpPlatform
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.ocr.VisionLineReader
import com.macle.royaleassist.ocr.VisionTextRecognizer
import com.macle.royaleassist.pipeline.Analyzer
import com.macle.royaleassist.settings.Settings
import com.macle.royaleassist.ui.CardImages
import com.macle.royaleassist.ui.DeepLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * The app-wide singletons on iOS (the counterpart of `RoyaleAssistApp` on Android): settings,
 * the lookup cache, the HTTP client, the lookup chain and the screenshot analyzer (Apple Vision
 * OCR). Created on first use. Files: settings and the cache database in Library/Application
 * Support, the log in Documents/logs, card images in Library/Caches.
 */
object IosApp {
    /** The HTTP platform; the WKWebView verifier hands its cookies and User-Agent to it. */
    val httpPlatform: IosHttpPlatform by lazy { IosHttpPlatform() }
    val http: HttpClients by lazy { HttpClients(httpPlatform) }
    val settings: Settings by lazy { Settings(saveScansDefault = false) }
    val db: CacheDb by lazy { CacheDb.create() }
    val cache: LookupCache by lazy { LookupCache(db.dao()) }
    val lookup: LookupChain by lazy { LookupChain(http, settings, cache) }
    val analyzer: Analyzer by lazy { Analyzer(VisionTextRecognizer(), VisionLineReader(), lookup) }

    private val _links = MutableStateFlow<Pair<Long, DeepLink>?>(null)
    private var linkCount = 0L

    /** The latest `royaleassist://` link with a running number (the shell reacts to each). */
    val links: StateFlow<Pair<Long, DeepLink>?> = _links.asStateFlow()

    /** Queues [url] for the shell; false when it is not a `royaleassist://` command. */
    fun openUrl(url: String): Boolean {
        val link = DeepLink.parse(url)
        L.i("app: open url $url -> $link")
        if (link == null) return false
        _links.value = ++linkCount to link
        return true
    }

    private var started = false

    /** Starts the log file and card image loading (Coil) once; every entry point calls it. */
    fun start() {
        if (started) return
        started = true
        L.init()
        L.i("app: start (iOS)")
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        CardImages.install(http, diskCacheDir = "$caches/coil3_disk_cache".toPath())
    }
}
