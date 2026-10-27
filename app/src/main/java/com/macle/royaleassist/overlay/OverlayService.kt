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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Foreground service that owns the floating bubble, the result panel and the screen capture.
 *
 * Foreground type: `specialUse` while only the bubble is shown (Android 14+ forbids the
 * mediaProjection type before consent), upgraded to `mediaProjection|specialUse` once the user
 * grants capture consent.
 */
class OverlayService : Service() {

    data class Status(val running: Boolean = false, val capturing: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private lateinit var capturer: ScreenCapturer
    private val app get() = application as RoyaleAssistApp

    private val bubbleOwner = OverlayViewOwner()
    private val panelOwner = OverlayViewOwner()
    private var bubbleRoot: DragFrame? = null
    private var panelRoot: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panelParams: WindowManager.LayoutParams? = null

    private val panel = MutableStateFlow<PanelState>(PanelState.Hidden)
    private val busy = MutableStateFlow(false)
    private var job: Job? = null
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
        addBubble()
        _status.value = Status(running = true, capturing = false)
        scope.launch {
            // Re-run the lookup automatically after the user passed the RoyaleAPI check.
            VerifyActivity.verified.drop(1).collect {
                val s = panel.value
                if (s is PanelState.Done) s.pick.name?.let { pickLine(it, s.lines) }
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
                showPanel(PanelState.Failed("Screen capture was not allowed. Tap the bubble to ask again."))
            }
            ACTION_CAPTURE -> onBubbleTap()
        }
        return START_STICKY
    }

    private fun onProjectionGranted(code: Int, data: Intent) {
        goForeground(withProjection = true)
        val ok = capturer.start(code, data)
        _status.update { it.copy(capturing = ok) }
        if (!ok) {
            goForeground(withProjection = false)
            showPanel(PanelState.Failed("Could not start screen capture."))
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
            .setContentText("Tap the crown bubble during a battle to scout the opponent.")
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

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun addBubble() {
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
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
        )
        root.addView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val isBusy by busy.collectAsState()
                RoyalTheme { Bubble(isBusy) }
            }
        })
        bubbleOwner.attach(root)
        bubbleOwner.resume()
        wm.addView(root, bubbleParams)
        bubbleRoot = root
    }

    private fun clampBubble() {
        val dm = resources.displayMetrics
        val size = dp(56)
        bubbleParams.x = bubbleParams.x.coerceIn(0, dm.widthPixels - size)
        bubbleParams.y = bubbleParams.y.coerceIn(0, dm.heightPixels - size)
        bubbleRoot?.let { wm.updateViewLayout(it, bubbleParams) }
    }

    private fun showPanel(state: PanelState) {
        panel.value = state
        if (state is PanelState.Hidden) { removePanel(); return }
        if (panelRoot != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // Not focusable: touches outside the panel still reach the game; scrolling works anyway.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        panelParams = params
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val s by panel.collectAsState()
                RoyalTheme {
                    Panel(
                        state = s,
                        onClose = { cancelJob(); showPanel(PanelState.Hidden) },
                        onRetry = { onBubbleTap() },
                        onPickLine = { line ->
                            val lines = when (val cur = panel.value) {
                                is PanelState.Done -> cur.lines
                                is PanelState.Failed -> cur.lines
                                is PanelState.Working -> cur.lines
                                else -> emptyList()
                            }
                            pickLine(line, lines)
                        },
                        onVerify = { openVerifier() },
                    )
                }
            }
        }
        panelOwner.attach(view)
        panelOwner.resume()
        positionPanel(params)
        wm.addView(view, params)
        panelRoot = view
    }

    private fun positionPanel(params: WindowManager.LayoutParams? = panelParams) {
        val p = params ?: return
        val dm = resources.displayMetrics
        val bubble = dp(56)
        val panelW = dp(300)
        val onLeft = bubbleParams.x + bubble / 2 < dm.widthPixels / 2
        p.x = if (onLeft) bubbleParams.x + bubble + dp(6) else bubbleParams.x - panelW - dp(6)
        p.x = p.x.coerceIn(0, (dm.widthPixels - panelW).coerceAtLeast(0))
        p.y = bubbleParams.y.coerceIn(0, (dm.heightPixels - dp(480)).coerceAtLeast(0))
        panelRoot?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, p) }
    }

    private fun removePanel() {
        panelRoot?.let { runCatching { wm.removeView(it) } }
        panelRoot = null
        panelParams = null
    }

    private fun setOverlaysVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        bubbleRoot?.visibility = v
        panelRoot?.visibility = v
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine { cont ->
        Choreographer.getInstance().postFrameCallback { cont.resume(Unit) }
    }

    private fun bubbleBox(): Box {
        val s = dp(56)
        return Box(bubbleParams.x, bubbleParams.y, bubbleParams.x + s, bubbleParams.y + s)
    }

    // ---- actions ----

    private fun cancelJob() {
        job?.cancel()
        busy.value = false
    }

    private fun openVerifier() {
        val q = (panel.value as? PanelState.Done)?.pick?.name?.text
        startActivity(VerifyActivity.intent(this, q))
    }

    private fun onBubbleTap() {
        if (job?.isActive == true) return
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
        try {
            val exclude = bubbleBox()
            val bmp = capturer.capture {
                setOverlaysVisible(false)
                awaitFrame(); awaitFrame()
                delay(40)
            }
            setOverlaysVisible(true)
            if (bmp == null) {
                showPanel(PanelState.Failed("Could not grab the screen. Tap Scan again."))
                return
            }
            L.i("service: captured ${bmp.width}x${bmp.height}")
            showPanel(PanelState.Working("Reading the screen..."))
            val ocr = withContext(Dispatchers.Default) { app.analyzer.ocrAndPick(bmp, exclude) }
            val name = ocr.pick.name
            if (name == null) {
                showPanel(PanelState.Failed("No player name found on screen.", ocr.lines))
                return
            }
            showPanel(PanelState.Working("Looking up ${name.text}...", ocr.lines, ocr.pick))
            val result = app.analyzer.lookupPick(ocr.pick)
            showPanel(PanelState.Done(ocr.lines, ocr.pick, result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e("service: scan failed", e)
            showPanel(PanelState.Failed("Scan failed: ${e.message}"))
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
                showPanel(PanelState.Working("Looking up ${line.text}...", lines, pick))
                showPanel(PanelState.Done(lines, pick, app.analyzer.lookupPick(pick)))
            } finally {
                busy.value = false
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        capturer.shutdown()
        removePanel()
        bubbleRoot?.let { runCatching { wm.removeView(it) } }
        bubbleRoot = null
        bubbleOwner.destroy()
        panelOwner.destroy()
        _status.value = Status(running = false, capturing = false)
        L.i("service: destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.macle.royaleassist.START"
        const val ACTION_STOP = "com.macle.royaleassist.STOP"
        const val ACTION_CAPTURE = "com.macle.royaleassist.CAPTURE"
        const val ACTION_PROJECTION_GRANTED = "com.macle.royaleassist.PROJECTION_GRANTED"
        const val ACTION_PROJECTION_DENIED = "com.macle.royaleassist.PROJECTION_DENIED"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
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
    }
}
