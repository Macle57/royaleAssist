package com.macle.royaleassist.lookup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.macle.royaleassist.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shows royaleapi.com in a WebView so the user can pass the Cloudflare check once ("Verify you are
 * human"). The `cf_clearance` cookie lands in the shared WebView [CookieManager] and [HttpClients]
 * reuses it (with the same WebView User-Agent) for plain OkHttp requests. Closes itself as soon as
 * a real RoyaleAPI page has loaded.
 */
class VerifyActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1A2B"))
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "Checking RoyaleAPI... tap the checkbox if one appears."
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = Button(this).apply { text = "Close"; setOnClickListener { finish() } }
        bar.addView(status)
        bar.addView(close)
        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(bar)
        root.addView(web)
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                L.i("verify: loaded '${view.title}' $url")
                checkPassed()
            }
        }
        // The challenge often swaps the page in place without a new onPageFinished, so poll too.
        web.postDelayed(poll, 1000)
        val q = intent.getStringExtra(EXTRA_QUERY) ?: "Macle"
        web.loadUrl(RoyaleApiWeb.searchUrl(q, exact = true))
    }

    private var done = false

    private val poll = object : Runnable {
        override fun run() {
            if (done || isFinishing) return
            checkPassed()
            web.postDelayed(this, 1000)
        }
    }

    private fun checkPassed() {
        if (done) return
        val title = web.title.orEmpty()
        val url = web.url.orEmpty()
        if (title.isEmpty() || title.contains("Just a moment", ignoreCase = true) || !url.startsWith(RoyaleApiWeb.BASE)) return
        done = true
        CookieManager.getInstance().flush()
        val ok = CookieManager.getInstance().getCookie(RoyaleApiWeb.BASE)?.contains("cf_clearance=") == true
        L.i("verify: passed ('$title'), cf_clearance=$ok")
        _verified.value = _verified.value + 1
        status.text = "Verified. Returning..."
        web.postDelayed({ if (!isFinishing) finish() }, 800)
    }

    override fun onDestroy() {
        web.removeCallbacks(poll)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_QUERY = "q"
        private val _verified = MutableStateFlow(0)

        /** Increments every time a verification completes; the overlay listens to retry the lookup. */
        val verified: StateFlow<Int> = _verified.asStateFlow()

        fun intent(context: Context, query: String? = null) =
            Intent(context, VerifyActivity::class.java)
                .putExtra(EXTRA_QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
