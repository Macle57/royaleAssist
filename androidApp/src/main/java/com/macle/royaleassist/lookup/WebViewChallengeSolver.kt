package com.macle.royaleassist.lookup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.macle.royaleassist.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Android's [ChallengeSolver]: RoyaleAPI's search page in a WebView the user never sees, the way
 * [VerifyActivity] shows it. With the overlay permission (the bubble has it) the WebView sits in
 * an invisible (alpha 0), untouchable, unfocusable overlay window, so it renders and runs its
 * scripts like a page on screen whatever app is in front; without it the WebView is laid out but
 * not attached to a window. The WebView shares the app's [CookieManager] and User-Agent with
 * [HttpClients], so a `cf_clearance` it earns is what the next lookup sends.
 *
 * Passed when a real RoyaleAPI page shows ([VerifyCheck.passed]); failed after [TIMEOUT_MS] (the
 * last page text says whether Cloudflare wanted a tap) or on a load error (offline).
 */
class WebViewChallengeSolver(private val context: Context) : ChallengeSolver {

    override suspend fun attempt(query: String?): ChallengeAttempt = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val run = Run(context.applicationContext) { if (cont.isActive) cont.resume(it) }
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { run.finish(ChallengeAttempt(false, "cancelled")) } }
            run.start(query)
        }
    }

    private class Run(private val context: Context, private val done: (ChallengeAttempt) -> Unit) {
        private val handler = Handler(Looper.getMainLooper())
        private var web: WebView? = null
        private var wm: WindowManager? = null
        private var finished = false
        private val t0 = SystemClock.elapsedRealtime()
        private var mode = "?"

        private fun ms() = SystemClock.elapsedRealtime() - t0

        @SuppressLint("SetJavaScriptEnabled")
        fun start(query: String?) {
            val w = try {
                WebView(context)
            } catch (e: Exception) {
                finish(ChallengeAttempt(false, "cannot create a WebView: ${e.message}"))
                return
            }
            web = w
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        L.i("autoverify: [web] loaded '${view.title}' $url after ${ms()} ms")
                        check()
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        // No network: give up now instead of waiting for the timeout.
                        if (request.isForMainFrame) finish(ChallengeAttempt(false, "load error ${error.errorCode} ${error.description}"))
                    }
                }
                val dm = context.resources.displayMetrics
                if (Settings.canDrawOverlays(context)) {
                    val params = WindowManager.LayoutParams(
                        dm.widthPixels, dm.heightPixels,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        alpha = 0f
                    }
                    val manager = context.getSystemService(WindowManager::class.java)
                    manager.addView(w, params)
                    wm = manager
                    mode = "overlay window"
                } else {
                    w.measure(
                        View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
                    )
                    w.layout(0, 0, dm.widthPixels, dm.heightPixels)
                    mode = "detached web view"
                }
                val q = query?.takeIf { it.isNotBlank() } ?: DEFAULT_QUERY
                w.loadUrl(RoyaleApiWeb.searchUrl(q, exact = true))
                L.i("autoverify: [web] started in an invisible $mode, cf_clearance before=${hasClearance()}")
            } catch (e: Exception) {
                finish(ChallengeAttempt(false, "could not start: ${e.message}"))
                return
            }
            // The challenge swaps the page in place without a new onPageFinished, so poll too.
            handler.postDelayed(poll, POLL_MS)
            handler.postDelayed({ timedOut() }, TIMEOUT_MS)
        }

        private val poll = object : Runnable {
            override fun run() {
                if (finished) return
                check()
                if (!finished) handler.postDelayed(this, POLL_MS)
            }
        }

        private fun check() {
            val w = web ?: return
            if (finished) return
            if (VerifyCheck.passed(w.title, w.url)) {
                CookieManager.getInstance().flush()
                finish(ChallengeAttempt(true, "'${w.title}' in ${ms()} ms ($mode), cf_clearance=${hasClearance()}"))
            }
        }

        /** Looks at what the page asks for before giving up: a "Verify you are human" box needs a tap. */
        private fun timedOut() {
            val w = web ?: return
            if (finished) return
            val title = w.title
            runCatching {
                w.evaluateJavascript(VerifyCheck.PAGE_TEXT_JS) { raw ->
                    val text = raw.orEmpty().trim('"').replace("\\n", " ").take(160)
                    val interactive = VerifyCheck.wantsTap(text)
                    finish(ChallengeAttempt(false, "timed out after ${ms()} ms ($mode) on '$title': \"$text\"", interactive = interactive))
                }
            }.onFailure { finish(ChallengeAttempt(false, "timed out after ${ms()} ms ($mode) on '$title'")) }
            // A stuck page may never answer the script.
            handler.postDelayed({ finish(ChallengeAttempt(false, "timed out after ${ms()} ms ($mode) on '$title'")) }, 2_000)
        }

        fun finish(result: ChallengeAttempt) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            web?.let { w ->
                wm?.let { m -> runCatching { m.removeView(w) } }
                runCatching { w.stopLoading(); w.destroy() }
            }
            web = null
            done(result)
        }

        private fun hasClearance() = runCatching {
            CookieManager.getInstance().getCookie(RoyaleApiWeb.BASE)?.contains("${VerifyCheck.CLEARANCE_COOKIE}=") == true
        }.getOrDefault(false)
    }

    companion object {
        const val TIMEOUT_MS = 30_000L
        private const val POLL_MS = 500L
        private const val DEFAULT_QUERY = "Macle"
    }
}
