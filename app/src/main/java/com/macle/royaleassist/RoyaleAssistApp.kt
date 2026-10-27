package com.macle.royaleassist

import android.app.Application
import com.macle.royaleassist.cache.CacheDb
import com.macle.royaleassist.cache.LookupCache
import com.macle.royaleassist.lookup.HttpClients
import com.macle.royaleassist.lookup.LookupChain
import com.macle.royaleassist.ocr.OcrEngine
import com.macle.royaleassist.ocr.TesseractFallback
import com.macle.royaleassist.pipeline.Analyzer
import com.macle.royaleassist.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Holds the app-wide singletons. Kept deliberately simple (no DI framework). */
class RoyaleAssistApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { Settings(this) }
    val http by lazy { HttpClients(this) }
    val db by lazy { CacheDb.create(this) }
    val cache by lazy { LookupCache(db.dao()) }
    val lookup by lazy { LookupChain(http, settings, cache) }
    val ocr by lazy { OcrEngine() }
    val tesseract by lazy { TesseractFallback(this) }
    val analyzer by lazy { Analyzer(ocr, tesseract, lookup) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: RoyaleAssistApp
            private set
    }
}
