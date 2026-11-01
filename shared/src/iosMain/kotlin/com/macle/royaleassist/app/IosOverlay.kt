package com.macle.royaleassist.app

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.macle.royaleassist.L
import com.macle.royaleassist.lookup.LookupOrigin
import com.macle.royaleassist.lookup.LookupResult
import com.macle.royaleassist.ocr.NamePick
import com.macle.royaleassist.ocr.NamePicker
import com.macle.royaleassist.ocr.OcrLine
import com.macle.royaleassist.ocr.VisionText
import com.macle.royaleassist.overlay.DeckPager
import com.macle.royaleassist.overlay.PanelState
import com.macle.royaleassist.overlay.decksFor
import com.macle.royaleassist.settings.OverlayPrefs
import com.macle.royaleassist.text.fmt2
import com.macle.royaleassist.ui.ScreenCaptureControls
import com.macle.royaleassist.ui.ScreenCaptureStatus
import com.macle.royaleassist.util.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIImage

/**
 * The Swift side of the iOS bubble (PipBubble.swift): the picture-in-picture window around an
 * `AVSampleBufferDisplayLayer` and the system broadcast picker. Called on the main thread.
 */
interface IosScreenHost {
    /** Opens the system sheet that starts (or stops) the royaleAssist screen broadcast. */
    fun openBroadcastPicker()

    /** Starts picture in picture; the result arrives through [IosOverlay.bubbleShown] / [IosOverlay.bubbleFailed]. */
    fun startBubble()

    fun stopBubble()

    fun isBubbleShown(): Boolean

    fun isBubbleSupported(): Boolean

    /** Shows [image] in the picture-in-picture window. */
    fun showFrame(image: UIImage)

    /** The play/pause button follows [IosOverlay.isBusy]; called when that changes. */
    fun playbackStateChanged()
}

/**
 * The iOS counterpart of Android's overlay service. The screen broadcast extension keeps the newest
 * frame ([CaptureFrames]); the picture-in-picture window is the bubble. Its play button (a "tap on
 * the bubble") reads that frame like the Android overlay reads a capture (scan region, region
 * growing, second pass, full-screen fallback), looks the player up and shows the minimal deck
 * card in the window. The window is a video player, so the card stays (no hide timer): the idle
 * bubble only shows before the first scan, and every later play press shows the spinner, then the
 * new card. The skip buttons step through the recent decks ([DeckPager]). Also implements the
 * setup screen's [ScreenCaptureControls] ("Scan now" shows the result in the app).
 */
object IosOverlay : ScreenCaptureControls {
    private val scope = MainScope()
    private var host: IosScreenHost? = null
    private val renderer by lazy { PipRenderer() }

    private val _status = MutableStateFlow(ScreenCaptureStatus())
    override val status: StateFlow<ScreenCaptureStatus> = _status.asStateFlow()

    /** The in-app result of the last scan (stays until closed). */
    private val _lastScan = MutableStateFlow<PanelState>(PanelState.Hidden)
    override val lastScan: StateFlow<PanelState> = _lastScan.asStateFlow()
    private val appPager = DeckPager()
    override val deckIndex: StateFlow<Int> = appPager.index

    /** What the window shows: Hidden = the idle bubble (before the first scan), else the last card, kept until the next scan. */
    private val panel = MutableStateFlow<PanelState>(PanelState.Hidden)
    private val pipPager = DeckPager()
    private var prefs = OverlayPrefs()
    private var job: Job? = null
    private var renderJob: Job? = null

    /** Called by the Swift shell at launch with its picture-in-picture host. */
    fun attach(host: IosScreenHost) {
        this.host = host
        IosApp.start()
        L.i("bubble: attached (picture in picture ${if (host.isBubbleSupported()) "supported" else "not supported"})")
        scope.launch { IosApp.settings.overlayPrefs.collect { prefs = it; redraw() } }
        watchBackground()
        refresh()
        val defaults = NSUserDefaults.standardUserDefaults
        // Automation (launch arguments): `-autoBubble YES` shows the bubble, `-autoScanNow YES` scans
        // once, `-autoScanEvery <s>` presses the bubble's play button every s seconds.
        if (defaults.boolForKey("autoBubble")) scope.launch { delay(1500); showBubble() }
        if (defaults.boolForKey("autoScanNow")) scope.launch { delay(2500); scanNow() }
        val every = defaults.integerForKey("autoScanEvery")
        if (every > 0) scope.launch {
            while (true) {
                delay(every * 1000L)
                onPlayTapped()
            }
        }
    }

    /** True while a scan runs (the play button then shows pause). */
    fun isBusy(): Boolean = job?.isActive == true

    /** The bubble's play/pause button: the tap on the Android bubble. */
    fun onPlayTapped() {
        L.i("bubble: play tapped${if (isBusy()) " (scan already running)" else ""}")
        if (isBusy()) return
        job = scope.launch { runScan() }
    }

    /**
     * The bubble's skip buttons: forward = an older deck, back = a newer one (clamped at both ends).
     * Works whenever a deck card is up; ignored while a scan runs (the spinner is showing).
     */
    fun onSkip(forward: Boolean) {
        val dir = if (forward) "forward" else "back"
        if (isBusy()) {
            L.i("bubble: skip $dir ignored (scan running)")
            return
        }
        val count = decksFor((panel.value as? PanelState.Result)?.result, prefs.decksToShow).size
        if (count == 0) {
            L.i("bubble: skip $dir: no decks on the card")
            return
        }
        val changed = pipPager.step(if (forward) 1 else -1, count)
        L.i("bubble: skip $dir -> deck ${pipPager.index.value + 1}/$count (index ${pipPager.index.value})${if (changed) "" else " (end)"}")
        if (changed) redraw()
    }

    /** From Swift: picture in picture started or stopped. */
    fun bubbleShown(shown: Boolean) {
        L.i("bubble: ${if (shown) "shown" else "hidden"}")
        _status.update { it.copy(bubbleShown = shown, message = if (shown) null else it.message) }
        if (shown) redraw()
    }

    /** From Swift: picture in picture could not start. */
    fun bubbleFailed(reason: String) {
        L.w("bubble: cannot show: $reason")
        _status.update { it.copy(bubbleShown = false, message = "The bubble could not start: $reason") }
    }

    /** The frame the window shows now (Swift asks before starting picture in picture). */
    fun currentImage(): UIImage? = renderer.render(viewNow()).also { savePipDebug() }

    override fun refresh() {
        val h = host
        val age = CaptureFrames.frameAgeSeconds()
        val running = CaptureFrames.isRunning()
        val was = _status.value
        _status.value = was.copy(
            captureRunning = running,
            frameAgeSeconds = age?.toLong(),
            bubbleSupported = h?.isBubbleSupported() ?: false,
            bubbleShown = h?.isBubbleShown() ?: false,
            scanning = isBusy(),
        )
        // The idle bubble says whether the capture is on.
        if (was.captureRunning != running && panel.value == PanelState.Hidden && !isBusy()) redraw()
    }

    override fun startCapture() {
        val h = host ?: return message("Screen capture is not available in this build.")
        h.openBroadcastPicker()
    }

    override fun showBubble() {
        val h = host ?: return message("The bubble is not available in this build.")
        if (!h.isBubbleSupported()) return message("Picture in picture is not available on this device.")
        _status.update { it.copy(message = null) }
        h.startBubble()
    }

    override fun hideBubble() {
        host?.stopBubble()
    }

    override fun scanNow() {
        L.i("bubble: scan now (in the app)")
        if (isBusy()) return
        job = scope.launch { runScan() }
    }

    override fun pickLine(line: OcrLine) {
        if (isBusy()) return
        val lines = _lastScan.value.linesOrEmpty
        val pick = NamePicker.pickWithName(line, lines)
        L.i("bubble: user picked '${line.text}' (clan '${pick.clan?.text}')")
        job = scope.launch {
            setBusy(true)
            try {
                val r = IosApp.analyzer.lookupPick(pick, LookupOrigin.App)
                val state = panelFor(r, lines, pick)
                prefetchCards(state)
                show(state)
            } finally {
                setBusy(false)
            }
        }
    }

    override fun stepDeck(delta: Int) {
        appPager.step(delta, decksFor((_lastScan.value as? PanelState.Result)?.result, prefs.decksToShow).size)
    }

    override fun clearScan() {
        _lastScan.value = PanelState.Hidden
    }

    private fun message(text: String) {
        L.w("bubble: $text")
        _status.update { it.copy(message = text) }
    }

    private suspend fun runScan() {
        setBusy(true)
        try {
            val t0 = nowMillis()
            val age = CaptureFrames.frameAgeSeconds()
            if (age == null) {
                show(PanelState.NotFound("No screen capture yet. Start screen capture in royaleAssist, then tap again."))
                return
            }
            if (!CaptureFrames.isRunning() && age > STALE_FRAME_SECONDS) {
                show(PanelState.NotFound("Screen capture is off. Start it in royaleAssist, then tap again."))
                return
            }
            val frame = withContext(Dispatchers.Default) { CaptureFrames.latest() }
            if (frame == null) {
                show(PanelState.NotFound("Could not read the screen capture. Tap again."))
                return
            }
            saveFrameDebug()
            val region = IosApp.settings.currentScanRegion()
            L.i("bubble: frame ${frame.width}x${frame.height}, ${fmt2(age)} s old")
            val ocr = withContext(Dispatchers.Default) { IosApp.analyzer.scan(frame, region, exclude = null, fullScreenFallback = true) }
            val name = ocr.pick.name
            if (name == null) {
                saveTextDebug(ocr.lines, ocr.pick, null, "no name")
                show(PanelState.NotFound("No name on screen. Open a battle and tap again.", ocr.lines))
                return
            }
            // The card always shows decks, so they are always fetched.
            val result = IosApp.analyzer.lookupPick(ocr.pick, LookupOrigin.App)
            L.i(
                "bubble: scan done in ${nowMillis() - t0} ms (ocr ${ocr.millis} ms, ${ocr.mode}): '${name.text}' -> " +
                    "'${result?.hit?.name}' #${result?.hit?.tag} decks=${result?.recentDecks?.size} error=${result?.error}",
            )
            saveTextDebug(ocr.lines, ocr.pick, result, "${nowMillis() - t0} ms, ${ocr.mode}")
            val state = panelFor(result, ocr.lines, ocr.pick)
            prefetchCards(state)
            show(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e("bubble: scan failed", e)
            show(PanelState.NotFound("Scan failed: ${e.message}"))
        } finally {
            setBusy(false)
        }
    }

    /** A result (or a RoyaleAPI block, which offers "Verify RoyaleAPI"), else "not found". */
    private fun panelFor(r: LookupResult?, lines: List<OcrLine>, pick: NamePick): PanelState =
        if (r != null && (r.stats != null || r.needsVerification)) PanelState.Result(lines, pick, r)
        else PanelState.NotFound(r?.error ?: "Player not found", lines, pick)

    private fun setBusy(on: Boolean) {
        _status.update { it.copy(scanning = on) }
        host?.playbackStateChanged()
        // The job is still active while its finally block runs, so draw the end state explicitly.
        redraw(busy = on)
    }

    /** Shows [state] in the window (until the next scan replaces it) and in the app. */
    private fun show(state: PanelState) {
        appPager.reset()
        _lastScan.value = state
        pipPager.reset()
        panel.value = state
        redraw(busy = false)
    }

    private fun viewNow(busy: Boolean = isBusy()): PipView = when {
        busy -> PipView.Busy
        panel.value != PanelState.Hidden -> PipView.Card(panel.value, pipPager.index.value, prefs.decksToShow)
        else -> PipView.Idle(capturing = CaptureFrames.isRunning())
    }

    /**
     * Redraws the window while it is shown: at once, then every 250 ms while scanning (spinner)
     * and a few more times after a change, so card images that load late appear.
     */
    private fun redraw(busy: Boolean? = null) {
        val h = host ?: return
        renderJob?.cancel()
        renderJob = scope.launch {
            var n = 0
            while (true) {
                val scanning = busy ?: isBusy()
                if (h.isBubbleShown()) {
                    renderer.render(viewNow(scanning))?.let { h.showFrame(it) }
                    if (n == 0 || !scanning) savePipDebug()
                }
                n++
                if (scanning) delay(250) else if (n < 8) delay(400) else break
            }
        }
    }

    /** Loads the card images of the decks into Coil's caches first, so the first frame has them. */
    private suspend fun prefetchCards(state: PanelState) {
        val r: LookupResult = (state as? PanelState.Result)?.result ?: return
        val urls = decksFor(r, prefs.decksToShow).flatten().mapNotNull { it.iconUrl }.distinct()
        if (urls.isEmpty()) return
        val ctx = PlatformContext.INSTANCE
        val loader = SingletonImageLoader.get(ctx)
        val t0 = nowMillis()
        withTimeoutOrNull(PREFETCH_TIMEOUT_MS) {
            coroutineScope { urls.forEach { u -> launch { loader.execute(ImageRequest.Builder(ctx).data(u).build()) } } }
        }
        L.i("bubble: ${urls.size} card images ready in ${nowMillis() - t0} ms")
    }

    /** Vision runs on the CPU while the app is in the background (no GPU work allowed there). */
    private fun watchBackground() {
        val app = UIApplication.sharedApplication
        VisionText.cpuOnly = app.applicationState == UIApplicationState.UIApplicationStateBackground
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            VisionText.cpuOnly = true
        }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            VisionText.cpuOnly = false
        }
    }

    // Debug artefacts (`-debugArtifacts YES` launch argument or the user default): the last frame
    // read, what was recognised and the last picture-in-picture image, in Documents/debug.

    private val debugOn get() = NSUserDefaults.standardUserDefaults.boolForKey("debugArtifacts")

    private val debugDir: String by lazy {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        "$docs/debug".also { NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null) }
    }

    private fun saveFrameDebug() {
        if (!debugOn) return
        val fm = NSFileManager.defaultManager
        val dst = "$debugDir/frame.jpg"
        fm.removeItemAtPath(dst, null)
        fm.copyItemAtPath(CaptureFrames.framePath, dst, null)
    }

    private fun saveTextDebug(lines: List<OcrLine>, pick: NamePick, r: LookupResult?, note: String) {
        if (!debugOn) return
        val text = buildString {
            appendLine("scan: $note")
            appendLine("name='${pick.name?.text}' clan='${pick.clan?.text}' (${pick.reason})")
            lines.forEach { appendLine("LINE '${it.text}' ${it.source} c=${fmt2(it.confidence)} box=${it.box}") }
            if (r != null) {
                appendLine("lookup: '${r.hit?.name}' #${r.hit?.tag} stats=${r.stats != null} decks=${r.recentDecks.size} verify=${r.needsVerification} error=${r.error}")
                r.log.forEach { appendLine("  $it") }
            }
        }
        writeDebug("ocr.txt", text.encodeToByteArray())
    }

    private fun savePipDebug() {
        if (!debugOn) return
        renderer.lastPng?.let { writeDebug("pip.png", it) }
    }

    private fun writeDebug(name: String, bytes: ByteArray) {
        bytes.toNSData().writeToFile("$debugDir/$name", atomically = true)
    }

    /** An old frame with the broadcast stopped is not the current screen. */
    private const val STALE_FRAME_SECONDS = 10.0
    private const val PREFETCH_TIMEOUT_MS = 4000L
}
