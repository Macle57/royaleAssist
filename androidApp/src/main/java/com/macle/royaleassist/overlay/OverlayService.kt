package com.macle.royaleassist.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.macle.royaleassist.L
import com.macle.royaleassist.MainActivity
import com.macle.royaleassist.R
import com.macle.royaleassist.RoyaleAssistApp
import com.macle.royaleassist.capture.ConsentActivity
import com.macle.royaleassist.capture.ScreenCapturer
import com.macle.royaleassist.lookup.VerifyActivity
import com.macle.royaleassist.lookup.RoyaleApiWeb
import android.net.Uri
import com.macle.royaleassist.ocr.Box
import com.macle.royaleassist.ocr.NamePicker
import com.macle.royaleassist.ocr.OcrLine
import com.macle.royaleassist.settings.OverlayPrefs
import com.macle.royaleassist.ui.RoyalTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Foreground service that owns the floating bubble, the result panel, the scan-region editor and
 * the screen capture.
 *
 * Foreground type: `specialUse` while only the bubble is shown (Android 14+ forbids the
 * mediaProjection type before consent), upgraded to `mediaProjection|specialUse` once the user
 * grants capture consent.
 *
 * Panel lifecycle: results appear compact next to the bubble and hide after the auto-hide setting
 * ("not found" after [NOT_FOUND_HIDE_MS]; a deck card after at least [DECK_MIN_SHOW_MS]). Tapping
 * the panel pins it (timer cancelled, line list shown); the close button hides it; tapping the
 * bubble always starts a new scan. The deck arrows page through the recent decks ([deckPager]) and
 * restart the hide timer.
 */
class OverlayService : Service() {

    data class Status(val running: Boolean = false, val capturing: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private lateinit var capturer: ScreenCapturer
    private val app get() = application as RoyaleAssistApp

    private val owners = HashMap<View, OverlayViewOwner>()
    private var bubbleRoot: DragFrame? = null
    private var panelRoot: View? = null
    private var regionRoot: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panelParams: WindowManager.LayoutParams? = null

    private val panel = MutableStateFlow<PanelState>(PanelState.Hidden)
    private val pinned = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    /** Which recent deck the card shows; back to the newest for every new result. */
    private val deckPager = DeckPager()
    /** Live overlay settings (opacity, size, auto-hide, deck view); DataStore changes apply at once. */
    private val prefs = MutableStateFlow(OverlayPrefs())
    private var job: Job? = null
    private var hideJob: Job? = null
    private var previewJob: Job? = null
    private var pendingCapture = false
    /** Fingerprints of the last scanned screen, so a manual correction can update its cache entry. */
    private var lastScreenKeys: com.macle.royaleassist.cache.ScreenCache.Keys? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        capturer = ScreenCapturer(this)
        capturer.onStopped = {
            scope.launch {
                _status.update { it.copy(capturing = false) }
                goForeground(withProjection = false)
            }
        }
        createChannel()
        goForeground(withProjection = false)
        scope.launch {
            app.settings.overlayPrefs.collect { p ->
                val old = prefs.value
                prefs.value = p
                if (old.scale != p.scale) { clampBubble(); positionPanel() }
                if (p.alwaysPin && panelRoot != null) pinPanel()
            }
        }
        addBubble()
        _status.value = Status(running = true, capturing = false)
        scope.launch {
            // Re-run the lookup automatically after the user passed the RoyaleAPI check.
            VerifyActivity.verified.drop(1).collect {
                val s = panel.value
                if (s is PanelState.Result) s.pick.name?.let { pickLine(it, s.lines) }
            }
        }
        L.i("service: created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PROJECTION_GRANTED -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (data != null) onProjectionGranted(code, data)
            }
            ACTION_PROJECTION_DENIED -> {
                pendingCapture = false
                showPanel(PanelState.NotFound("Screen capture was not allowed. Tap the bubble to ask again."))
            }
            ACTION_CAPTURE -> onBubbleTap()
            ACTION_EDIT_REGION -> scope.launch { showRegionEditor() }
            ACTION_SHOW_REGION -> scope.launch { flashRegion() }
            ACTION_DEBUG_SHOW -> if (com.macle.royaleassist.BuildConfig.DEBUG) debugPanel?.let { debugPanel = null; showPanel(it) }
        }
        return START_STICKY
    }

    private fun onProjectionGranted(code: Int, data: Intent) {
        goForeground(withProjection = true)
        val ok = capturer.start(code, data)
        _status.update { it.copy(capturing = ok) }
        if (!ok) {
            goForeground(withProjection = false)
            showPanel(PanelState.NotFound("Could not start screen capture."))
            return
        }
        if (pendingCapture) {
            pendingCapture = false
            // Let the consent dialog disappear before grabbing the screen.
            scope.launch { delay(600); onBubbleTap() }
        }
    }

    // ---- foreground / notification ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle("royaleAssist is running")
            .setContentText("Tap the crown bubble in a battle. Long-press it to set the scan region.")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun goForeground(withProjection: Boolean) {
        val type = when {
            Build.VERSION.SDK_INT >= 34 ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        } catch (e: Exception) {
            L.e("service: startForeground(type=$type) failed", e)
        }
    }

    // ---- windows ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun overlayParams(width: Int, height: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or extraFlags,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Wraps Compose content in a view with lifecycle owners attached (see [OverlayViewOwner]). */
    private fun composeView(content: @Composable () -> Unit): ComposeView {
        val owner = OverlayViewOwner()
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { RoyalTheme(content) }
            owner.attach(this)
            owner.resume()
            owners[this] = owner
        }
    }

    private fun removeWindow(view: View?) {
        if (view == null) return
        runCatching { wm.removeView(view) }
        owners.remove(view)?.destroy()
    }

    private fun addBubble() {
        bubbleParams = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            x = dp(8)
            y = resources.displayMetrics.heightPixels / 3
        }
        val root = DragFrame(
            this,
            onDrag = { dx, dy ->
                bubbleParams.x += dx
                bubbleParams.y += dy
                bubbleRoot?.let { wm.updateViewLayout(it, bubbleParams) }
            },
            onDragEnd = { clampBubble(); positionPanel() },
            onTap = { onBubbleTap() },
            onLongPress = { scope.launch { showRegionEditor() } },
        )
        val owner = OverlayViewOwner().also { owners[root] = it }
        root.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val isBusy by busy.collectAsState()
                val p by prefs.collectAsState()
                RoyalTheme { Scaled(p.scale) { Bubble(isBusy, painterResource(R.drawable.ic_bubble), p.bubbleAlpha) } }
            }
        })
        owner.attach(root)
        owner.resume()
        wm.addView(root, bubbleParams)
        bubbleRoot = root
    }

    private fun scaledDp(v: Int) = (v * resources.displayMetrics.density * prefs.value.scale).roundToInt()

    private fun clampBubble() {
        if (bubbleRoot == null) return
        val dm = resources.displayMetrics
        val size = scaledDp(56)
        bubbleParams.x = bubbleParams.x.coerceIn(0, dm.widthPixels - size)
        bubbleParams.y = bubbleParams.y.coerceIn(0, dm.heightPixels - size)
        bubbleRoot?.let { wm.updateViewLayout(it, bubbleParams) }
    }

    private fun showPanel(state: PanelState, pinRequested: Boolean = false) {
        hideJob?.cancel()
        val pin = pinRequested || (prefs.value.alwaysPin && state !is PanelState.Hidden)
        // A new result always starts at the newest deck.
        deckPager.reset()
        panel.value = state
        if (state is PanelState.Hidden) { removePanel(); return }
        pinned.value = pin
        if (panelRoot == null) {
            val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            panelParams = params
            val view = composeView {
                val s by panel.collectAsState()
                val isPinned by pinned.collectAsState()
                val p by prefs.collectAsState()
                val deckIndex by deckPager.index.collectAsState()
                Scaled(p.scale) {
                Panel(
                    state = s,
                    pinned = isPinned,
                    onPin = { pinPanel() },
                    onClose = { cancelJob(); showPanel(PanelState.Hidden) },
                    onRetry = { onBubbleTap() },
                    onPickLine = { line -> pickLine(line, panel.value.linesOrEmpty) },
                    onVerify = { openVerifier() },
                    alpha = p.panelAlpha,
                    deckOnly = p.deckOnly,
                    decksToShow = p.decksToShow,
                    scale = p.scale,
                    maxWidthDp = overlayMaxWidthDp(resources.displayMetrics.widthPixels / resources.displayMetrics.density, p.scale),
                    onRefresh = { tag -> refreshPlayer(tag) },
                    onOpenWeb = { tag -> openOnRoyaleApi(tag) },
                    deckIndex = deckIndex,
                    onDeckStep = { delta -> stepDeck(delta) },
                )
                }
            }
            positionPanel(params)
            wm.addView(view, params)
            panelRoot = view
        } else {
            positionPanel()
        }
        if (!pin) scheduleHide()
    }

    /** (Re)starts the auto-hide timer for the shown, unpinned panel. */
    private fun scheduleHide() {
        hideJob?.cancel()
        val state = panel.value
        if (state is PanelState.Hidden || pinned.value) return
        val p = prefs.value
        val ms = panelHideDelayMs(state, p.autoHideSeconds, showsDeck(state, p.deckOnly, p.decksToShow))
        L.i("service: panel hides in $ms ms")
        hideJob = scope.launch {
            delay(ms)
            if (!pinned.value) {
                L.i("service: panel auto-hidden")
                showPanel(PanelState.Hidden)
            }
        }
    }

    /** Deck arrow: show an older (+1) or newer (-1) deck and give the user the full time again. */
    private fun stepDeck(delta: Int) {
        val count = decksFor((panel.value as? PanelState.Result)?.result, prefs.value.decksToShow).size
        val changed = deckPager.step(delta, count)
        L.i("service: deck ${deckPager.index.value + 1}/$count${if (changed) "" else " (end)"}")
        scheduleHide()
    }

    private fun pinPanel() {
        if (pinned.value) return
        hideJob?.cancel()
        pinned.value = true
        positionPanel()
        L.i("service: panel pinned")
    }

    private fun positionPanel(params: WindowManager.LayoutParams? = panelParams) {
        val p = params ?: return
        val dm = resources.displayMetrics
        val bubble = scaledDp(56)
        // One deck at a time; with several decks the card is wider by the two arrow columns.
        val paged = decksFor((panel.value as? PanelState.Result)?.result, prefs.value.decksToShow).size > 1
        val miniW = miniDeckWidthDp(miniDeckIconDp(maxPanelWidthDp(), paged), paged)
        val panelW = if (isMiniDeck()) scaledDp(miniW.roundToInt()) else scaledDp(264)
        // An explicit window width: WRAP_CONTENT windows are first measured at the platform's
        // preferred dialog width (~320 dp), which squeezes the content.
        p.width = panelW.coerceAtMost(dm.widthPixels)
        val gap = dp(6)
        val onLeft = bubbleParams.x + bubble / 2 < dm.widthPixels / 2
        val besideX = if (onLeft) bubbleParams.x + bubble + gap else bubbleParams.x - panelW - gap
        val fitsBeside = besideX >= 0 && besideX + panelW <= dm.widthPixels
        if (fitsBeside) {
            p.x = besideX
            p.y = bubbleParams.y.coerceIn(0, (dm.heightPixels - scaledDp(480)).coerceAtLeast(0))
        } else {
            // Large sizes: no room next to the bubble, so drop the panel below it (or above near the bottom).
            p.x = (bubbleParams.x + bubble / 2 - panelW / 2).coerceIn(0, (dm.widthPixels - panelW).coerceAtLeast(0))
            val below = bubbleParams.y + bubble + gap
            p.y = if (below + scaledDp(300) <= dm.heightPixels) below
            else (bubbleParams.y - scaledDp(300) - gap).coerceAtLeast(0)
        }
        panelRoot?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, p) }
    }

    /** Screen width in overlay dp (after the size setting), minus a small margin. */
    private fun maxPanelWidthDp(): Float {
        val dm = resources.displayMetrics
        return overlayMaxWidthDp(dm.widthPixels / dm.density, prefs.value.scale)
    }

    /** True while the panel shows only the 8 deck icons (narrower window). */
    private fun isMiniDeck() = prefs.value.deckOnly && !pinned.value &&
        decksFor((panel.value as? PanelState.Result)?.result, prefs.value.decksToShow).isNotEmpty()

    private fun removePanel() {
        hideJob?.cancel()
        removeWindow(panelRoot)
        panelRoot = null
        panelParams = null
        pinned.value = false
    }

    /** Full-display window: same pixel grid as the capture, including status bar and cutout. */
    private fun fullScreenParams(extraFlags: Int): WindowManager.LayoutParams {
        val b = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else null
        val w = b?.width() ?: resources.displayMetrics.widthPixels
        val h = b?.height() ?: resources.displayMetrics.heightPixels
        return overlayParams(w, h, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags).apply {
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun removeRegionWindow() {
        previewJob?.cancel()
        removeWindow(regionRoot)
        regionRoot = null
    }

    private suspend fun showRegionEditor() {
        removeRegionWindow()
        showPanel(PanelState.Hidden)
        val current = app.settings.currentScanRegion()
        val view = composeView {
            RegionEditor(
                initial = current,
                onSave = { r ->
                    scope.launch {
                        app.settings.setScanRegion(r)
                        L.i("service: scan region saved $r")
                        removeRegionWindow()
                        flashRegion()
                    }
                },
                onReset = {
                    scope.launch {
                        app.settings.setScanRegion(null)
                        L.i("service: scan region reset")
                        showRegionEditor()
                    }
                },
                onCancel = { removeRegionWindow() },
            )
        }
        wm.addView(view, fullScreenParams(0))
        regionRoot = view
        view.post {
            val loc = IntArray(2).also { view.getLocationOnScreen(it) }
            L.i("service: region editor at (${loc[0]},${loc[1]}) size ${view.width}x${view.height}")
        }
    }

    /** Flashes the saved scan region for a moment (touches pass through). */
    private suspend fun flashRegion() {
        removeRegionWindow()
        val region = app.settings.currentScanRegion()
        val view = composeView { RegionPreview(region) }
        wm.addView(view, fullScreenParams(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))
        regionRoot = view
        L.i("service: showing scan region $region")
        previewJob = scope.launch {
            delay(REGION_FLASH_MS)
            if (regionRoot === view) removeRegionWindow()
        }
    }

    private fun setOverlaysVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        bubbleRoot?.visibility = v
        panelRoot?.visibility = v
        regionRoot?.visibility = v
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine { cont ->
        Choreographer.getInstance().postFrameCallback { cont.resume(Unit) }
    }

    private fun bubbleBox(): Box {
        val s = scaledDp(56)
        return Box(bubbleParams.x, bubbleParams.y, bubbleParams.x + s, bubbleParams.y + s)
    }

    // ---- actions ----

    private fun cancelJob() {
        job?.cancel()
        busy.value = false
    }

    private fun openVerifier() {
        val q = (panel.value as? PanelState.Result)?.pick?.name?.text
        startActivity(VerifyActivity.intent(this, q))
    }

    private fun onBubbleTap() {
        if (job?.isActive == true) return
        if (app.ownScreensResumed > 0) {
            // Our own setup/history screen is in front: reading it would "find" players named
            // "Overlay" or "Permissions".
            L.i("service: own screen in front, not scanning")
            showPanel(PanelState.NotFound("Open the game first: royaleAssist is on screen."))
            return
        }
        if (regionRoot != null) removeRegionWindow()
        if (!capturer.isActive) {
            L.i("service: no projection, asking for consent")
            pendingCapture = true
            startActivity(ConsentActivity.intent(this))
            return
        }
        job = scope.launch { runScan() }
    }

    private suspend fun runScan() {
        busy.value = true
        val keepPinned = pinned.value
        try {
            val exclude = bubbleBox()
            val t0 = System.currentTimeMillis()
            val bmp = capturer.capture {
                setOverlaysVisible(false)
                awaitFrame(); awaitFrame()
                delay(40)
            }
            setOverlaysVisible(true)
            if (bmp == null) {
                showPanel(PanelState.NotFound("Could not grab the screen. Tap the bubble to try again."))
                return
            }
            val tCapture = System.currentTimeMillis() - t0
            val region = app.settings.currentScanRegion()
            val save = app.settings.saveScans.first()
            lastScreenKeys = null
            // Same screen as an earlier tap (same name/clan pixels)? Show that result at once.
            val tHash0 = System.currentTimeMillis()
            val hit = try {
                withContext(Dispatchers.Default) { app.screenCache.find(bmp) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w("service: screen cache lookup failed: ${e.message}")
                null
            }
            val tHash = System.currentTimeMillis() - tHash0
            if (hit != null) {
                showScreenHit(bmp, region, hit, tCapture, tHash, System.currentTimeMillis() - t0, save, keepPinned)
                return
            }
            val fallback = app.settings.fullScreenFallback.first()
            val ocr = withContext(Dispatchers.Default) { app.analyzer.scan(bmp, region, exclude, fallback) }
            val name = ocr.pick.name
            if (name == null) {
                if (save) app.recorder.record(bmp, region, ocr, null, mapOf("capture" to tCapture, "ocr" to ocr.millis), "no name found")
                showPanel(PanelState.NotFound("No name in the scan region. Long-press the bubble to adjust it.", ocr.lines), pinRequested = keepPinned)
                return
            }
            val tLookup0 = System.currentTimeMillis()
            val result = app.analyzer.lookupPick(ocr.pick)
            val total = System.currentTimeMillis() - t0
            L.i("service: scan done in $total ms (ocr ${ocr.millis} ms, ${ocr.mode}${if (ocr.expanded) ", region grown" else ""})")
            // Remember this screen for an instant answer on the next tap.
            val keys = if (result?.stats != null) withContext(Dispatchers.Default) { app.screenCache.keysFor(bmp, ocr.pick) } else null
            lastScreenKeys = keys
            if (keys != null && result != null) app.appScope.launch { runCatching { app.screenCache.put(keys, result) }.onFailure { L.w("service: screen cache put failed: ${it.message}") } }
            if (save) {
                val timings = mapOf("capture" to tCapture, "screenHash" to tHash, "ocr" to ocr.millis, "lookup" to System.currentTimeMillis() - tLookup0, "total" to total)
                val screen = mapOf(
                    "hit" to "false",
                    "stored" to (keys != null && result?.stats != null).toString(),
                )
                app.appScope.launch { app.recorder.record(bmp, region, ocr, result, timings, screenCache = screen) }
            }
            val found = result?.stats != null
            showPanel(
                if (found) PanelState.Result(ocr.lines, ocr.pick, result)
                else PanelState.NotFound(result?.error ?: "Player not found", ocr.lines, ocr.pick),
                pinRequested = keepPinned,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e("service: scan failed", e)
            showPanel(PanelState.NotFound("Scan failed: ${e.message}"))
        } finally {
            setOverlaysVisible(true)
            busy.value = false
        }
    }

    /** Shows the result stored for this screen: no OCR, no network. */
    private suspend fun showScreenHit(
        bmp: android.graphics.Bitmap, region: com.macle.royaleassist.ocr.ScanRegion, hit: com.macle.royaleassist.cache.ScreenCache.Hit,
        tCapture: Long, tHash: Long, total: Long, save: Boolean, keepPinned: Boolean,
    ) {
        val e = hit.entity
        val nameLine = OcrLine(e.nameText, Box(e.nameLeft, e.nameTop, e.nameRight, e.nameBottom), 1f, "screenshot cache")
        // Locals: the entity lives in :shared, so its properties cannot be smart-cast here.
        val clanText = e.clanText; val cl = e.clanLeft; val ct = e.clanTop; val cr = e.clanRight; val cb = e.clanBottom
        val clanLine = if (clanText != null && cl != null && ct != null && cr != null && cb != null) {
            OcrLine(clanText, Box(cl, ct, cr, cb), 1f, "screenshot cache")
        } else null
        val pick = com.macle.royaleassist.ocr.NamePick(nameLine, clanLine, reason = "cache hit by screenshot")
        val why = "cache hit by screenshot: ${hit.describe()}; no OCR, no network"
        L.i("service: $why, shown in $total ms")
        val result = hit.result.copy(
            cached = true, screenHit = true,
            log = listOf(why) + hit.result.log,
        )
        lastScreenKeys = com.macle.royaleassist.cache.ScreenCache.keyOf(e)?.let { com.macle.royaleassist.cache.ScreenCache.Keys(it, e.nameText, e.clanText) }
        showPanel(PanelState.Result(listOfNotNull(nameLine, clanLine), pick, result), pinRequested = keepPinned)
        app.appScope.launch { runCatching { app.cache.touch(e.tag, System.currentTimeMillis(), com.macle.royaleassist.cache.PlayerEntity.VIA_SCREENSHOT) } }
        if (save) {
            val timings = mapOf("capture" to tCapture, "screenHash" to tHash, "total" to total)
            val screen = mapOf(
                "hit" to "true",
                "entryId" to e.id.toString(),
                "tag" to e.tag,
                "nameText" to e.nameText,
                "nameDistance" to hit.nameDistance.toString(),
                "clanDistance" to (hit.clanDistance?.toString() ?: "-"),
                "maxDistance" to com.macle.royaleassist.cache.ScreenHash.MAX_DISTANCE.toString(),
                "ageSeconds" to (hit.ageMs / 1000).toString(),
                "storedScreens" to hit.checked.toString(),
            )
            app.appScope.launch { app.recorder.record(bmp, region, null, result, timings, "cache hit by screenshot", screenCache = screen) }
        }
    }

    /** A manual correction (picked line, refresh) becomes the answer for the last scanned screen. */
    private fun rememberForLastScreen(r: com.macle.royaleassist.lookup.LookupResult) {
        val keys = lastScreenKeys ?: return
        if (r.stats == null) return
        app.appScope.launch { runCatching { app.screenCache.put(keys, r) } }
    }

    /** Opens the player page in the browser; works from the service because we hold the overlay permission. */
    private fun openOnRoyaleApi(tag: String) {
        val url = RoyaleApiWeb.playerUrl(tag)
        L.i("service: opening $url")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            showPanel(PanelState.Hidden)
        } catch (e: Exception) {
            L.e("service: cannot open browser", e)
        }
    }

    /** Re-fetches the pinned player, bypassing the cache. */
    private fun refreshPlayer(tag: String) {
        val cur = panel.value as? PanelState.Result ?: return
        cancelJob()
        job = scope.launch {
            busy.value = true
            try {
                val r = cur.result?.let { app.lookup.refresh(it) } ?: app.lookup.refresh(tag)
                rememberForLastScreen(r)
                showPanel(
                    if (r.stats != null) PanelState.Result(cur.lines, cur.pick, r)
                    else PanelState.NotFound(r.error ?: "Refresh failed", cur.lines, cur.pick),
                    pinRequested = true,
                )
            } finally {
                busy.value = false
            }
        }
    }

    private fun pickLine(line: OcrLine, lines: List<OcrLine>) {
        cancelJob()
        val pick = NamePicker.pickWithName(line, lines)
        L.i("service: user picked '${line.text}' (clan '${pick.clan?.text}')")
        job = scope.launch {
            busy.value = true
            try {
                val result = app.analyzer.lookupPick(pick)
                // The user chose this line by hand: that is a confirmed answer for the screen.
                result?.let { rememberForLastScreen(it.copy(strongMatch = true)) }
                showPanel(
                    if (result?.stats != null) PanelState.Result(lines, pick, result)
                    else PanelState.NotFound(result?.error ?: "Player not found", lines, pick),
                    pinRequested = true,
                )
            } finally {
                busy.value = false
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        capturer.shutdown()
        removePanel()
        removeRegionWindow()
        removeWindow(bubbleRoot)
        bubbleRoot = null
        owners.values.forEach { it.destroy() }
        _status.value = Status(running = false, capturing = false)
        L.i("service: destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.macle.royaleassist.START"
        const val ACTION_STOP = "com.macle.royaleassist.STOP"
        const val ACTION_CAPTURE = "com.macle.royaleassist.CAPTURE"
        const val ACTION_EDIT_REGION = "com.macle.royaleassist.EDIT_REGION"
        const val ACTION_SHOW_REGION = "com.macle.royaleassist.SHOW_REGION"
        const val ACTION_PROJECTION_GRANTED = "com.macle.royaleassist.PROJECTION_GRANTED"
        const val ACTION_PROJECTION_DENIED = "com.macle.royaleassist.PROJECTION_DENIED"
        /** Debug builds only: shows [debugPanel] as if a scan had produced it (overlay UI tests). */
        const val ACTION_DEBUG_SHOW = "com.macle.royaleassist.DEBUG_SHOW"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val AUTO_HIDE_MS = 10_000L
        const val REGION_FLASH_MS = 2_500L
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 7

        /** Panel state for [ACTION_DEBUG_SHOW]; set by the debug harness. */
        @Volatile
        var debugPanel: PanelState? = null

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        /** Starts the service if needed and sends it [action]. */
        fun send(context: Context, action: String) {
            context.startForegroundService(Intent(context, OverlayService::class.java).setAction(action))
        }
    }
}
