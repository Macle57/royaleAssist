package com.macle.royaleassist

import android.app.Application
import com.macle.royaleassist.cache.CacheDb
import com.macle.royaleassist.debug.ScanRecorder
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
    val recorder by lazy { ScanRecorder(this) }

    /** Number of our own (non-debug) activities currently resumed. */
    @Volatile var ownScreensResumed = 0
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private fun counts(a: android.app.Activity) = !a.javaClass.name.contains(".debug.")
            override fun onActivityResumed(a: android.app.Activity) { if (counts(a)) ownScreensResumed++ }
            override fun onActivityPaused(a: android.app.Activity) { if (counts(a)) ownScreensResumed = (ownScreensResumed - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        L.init(recorder.logsDir)
    }

    companion object {
        lateinit var instance: RoyaleAssistApp
            private set
    }
}
