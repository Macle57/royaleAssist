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
 * Panel lifecycle: results appear compact next to the bubble and hide after [AUTO_HIDE_MS]
 * ("not found" after [NOT_FOUND_HIDE_MS]). Tapping the panel pins it (timer cancelled, line list
 * shown); the close button hides it; tapping the bubble always starts a new scan.
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
    /** Live overlay settings (opacity, size, auto-hide, deck view); DataStore changes apply at once. */
    private val prefs = MutableStateFlow(OverlayPrefs())
    private var job: Job? = null
    private var hideJob: Job? = null
    private var previewJob: Job? = null
    private var pendingCapture = false

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
                RoyalTheme { Scaled(p.scale) { Bubble(isBusy, p.bubbleAlpha) } }
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
                )
                }
            }
            positionPanel(params)
            wm.addView(view, params)
            panelRoot = view
        }
        if (!pin) {
            val full = prefs.value.autoHideSeconds * 1000L
            val ms = if (state is PanelState.NotFound) minOf(NOT_FOUND_HIDE_MS, full) else full
            hideJob = scope.launch {
                delay(ms)
                if (!pinned.value) showPanel(PanelState.Hidden)
            }
        }
    }

    private fun pinPanel() {
        if (pinned.value) return
        hideJob?.cancel()
        pinned.value = true
        L.i("service: panel pinned")
    }

    private fun positionPanel(params: WindowManager.LayoutParams? = panelParams) {
        val p = params ?: return
        val dm = resources.displayMetrics
        val bubble = scaledDp(56)
        val panelW = scaledDp(264)
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
            val region = app.settings.currentScanRegion()
            val fallback = app.settings.fullScreenFallback.first()
            val ocr = withContext(Dispatchers.Default) { app.analyzer.scan(bmp, region, exclude, fallback) }
            val name = ocr.pick.name
            if (name == null) {
                showPanel(PanelState.NotFound("No name in the scan region. Long-press the bubble to adjust it.", ocr.lines), pinRequested = keepPinned)
                return
            }
            val result = app.analyzer.lookupPick(ocr.pick)
            L.i("service: scan done in ${System.currentTimeMillis() - t0} ms (ocr ${ocr.millis} ms, ${ocr.mode})")
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

    private fun pickLine(line: OcrLine, lines: List<OcrLine>) {
        cancelJob()
        val pick = NamePicker.pickWithName(line, lines)
        L.i("service: user picked '${line.text}' (clan '${pick.clan?.text}')")
        job = scope.launch {
            busy.value = true
            try {
                val result = app.analyzer.lookupPick(pick)
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
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val AUTO_HIDE_MS = 6_000L
        const val NOT_FOUND_HIDE_MS = 3_500L
        const val REGION_FLASH_MS = 2_500L
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 7

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
