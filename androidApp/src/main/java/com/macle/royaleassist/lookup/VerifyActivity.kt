package com.macle.royaleassist.lookup

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.viewinterop.AndroidView
import com.macle.royaleassist.L
import com.macle.royaleassist.ui.RoyalTheme
import com.macle.royaleassist.ui.VERIFY_CHECKING
import com.macle.royaleassist.ui.VERIFY_DONE
import com.macle.royaleassist.ui.VerifyScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shows royaleapi.com in a WebView so the user can pass the Cloudflare check once ("Verify you are
 * human"). The `cf_clearance` cookie lands in the shared WebView [CookieManager] and [HttpClients]
 * reuses it (with the same WebView User-Agent) for plain HTTP requests. Closes itself as soon as
 * a real RoyaleAPI page has loaded. The chrome around the WebView is the shared [VerifyScreen].
 */
class VerifyActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val status = mutableStateOf(VERIFY_CHECKING)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContent {
            RoyalTheme {
                VerifyScreen(status.value, onClose = { finish() }) { modifier ->
                    AndroidView(factory = { web }, modifier = modifier)
                }
            }
        }

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
        if (!VerifyCheck.passed(title, web.url)) return
        done = true
        CookieManager.getInstance().flush()
        val ok = CookieManager.getInstance().getCookie(RoyaleApiWeb.BASE)?.contains("cf_clearance=") == true
        L.i("verify: passed ('$title'), cf_clearance=$ok")
        RoyaleApiHealth.shared.reportOk("verifier")
        _verified.value = _verified.value + 1
        status.value = VERIFY_DONE
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
