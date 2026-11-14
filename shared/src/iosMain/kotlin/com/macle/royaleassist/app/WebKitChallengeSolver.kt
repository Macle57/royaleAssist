package com.macle.royaleassist.app

import com.macle.royaleassist.L
import com.macle.royaleassist.lookup.ChallengeAttempt
import com.macle.royaleassist.lookup.ChallengeSolver
import com.macle.royaleassist.lookup.RoyaleApiWeb
import com.macle.royaleassist.lookup.VerifyCheck
import com.macle.royaleassist.util.nowMillis
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIWindow
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume

/**
 * iOS's [ChallengeSolver]: RoyaleAPI's search page in a WKWebView the user never sees, placed in
 * the app's key window *behind* the app's own UI (almost transparent, not touchable), so WebKit
 * treats it as a page on screen and runs Cloudflare's script. It uses the default website data
 * store like the "Verify RoyaleAPI" screen, and a pass hands the cookies (rebuilt without WebKit's
 * `StoragePartition`) and the User-Agent to NSURLSession the same way ([handOver]).
 *
 * Limits: with another app in front (the bubble in picture in picture over the game) the app is
 * in the background, where iOS may suspend WebKit's processes; such attempts are marked
 * [ChallengeAttempt.background] and are tried again when the app comes to the front.
 */
class WebKitChallengeSolver : ChallengeSolver {

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun attempt(query: String?): ChallengeAttempt = withContext(Dispatchers.Main) {
        val app = UIApplication.sharedApplication
        val background = app.applicationState != UIApplicationState.UIApplicationStateActive
        val window = keyWindow()
        val (w, h) = window?.bounds?.useContents { size.width to size.height } ?: (390.0 to 844.0)
        val config = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.defaultDataStore() }
        val web = WKWebView(frame = CGRectMake(0.0, 0.0, w, h), configuration = config)
        web.userInteractionEnabled = false
        web.alpha = 0.01
        window?.insertSubview(web, atIndex = 0)
        val where = if (window != null) "behind the app's window" else "outside any window"
        val state = if (background) "app in the background" else "app in front"
        val t0 = nowMillis()
        try {
            val q = query?.takeIf { it.isNotBlank() } ?: "Macle"
            val url = RoyaleApiWeb.searchUrl(q, exact = true)
            NSURL.URLWithString(url)?.let { web.loadRequest(NSURLRequest.requestWithURL(it)) }
            L.i("autoverify: [web] started $where ($state)")
            var lastTitle: String? = null
            val passed = withTimeoutOrNull(TIMEOUT_MS) {
                while (true) {
                    delay(POLL_MS)
                    val title = web.title
                    if (title != lastTitle) {
                        L.i("autoverify: [web] page '$title' ${web.URL?.absoluteString} after ${nowMillis() - t0} ms")
                        lastTitle = title
                    }
                    if (VerifyCheck.passed(title, web.URL?.absoluteString)) break
                }
                true
            } ?: false
            if (passed) {
                val clearance = handOver(web)
                return@withContext ChallengeAttempt(
                    true, "'${web.title}' in ${nowMillis() - t0} ms ($where, $state), cf_clearance=$clearance", background = background,
                )
            }
            val text = pageText(web)
            ChallengeAttempt(
                false, "timed out after ${nowMillis() - t0} ms ($where, $state) on '${web.title}': \"$text\"",
                interactive = VerifyCheck.onChallenge(web.title), background = background,
            )
        } finally {
            web.stopLoading()
            web.removeFromSuperview()
        }
    }

    @Suppress("DEPRECATION")
    private fun keyWindow(): UIWindow? = UIApplication.sharedApplication.keyWindow

    private suspend fun pageText(web: WKWebView): String = withTimeoutOrNull(2_000) {
        suspendCancellableCoroutine { c ->
            web.evaluateJavaScript(VerifyCheck.PAGE_TEXT_JS) { result, _ ->
                c.resume((result as? String).orEmpty().replace('\n', ' ').take(160))
            }
        }
    } ?: ""

    companion object {
        const val TIMEOUT_MS = 30_000L
        private const val POLL_MS = 500L
    }
}
