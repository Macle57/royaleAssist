package com.macle.royaleassist.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.macle.royaleassist.L
import com.macle.royaleassist.lookup.RoyaleApiHealth
import com.macle.royaleassist.lookup.VerifyCheck
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume

/**
 * The RoyaleAPI check on iOS (the counterpart of Android's VerifyActivity): a WKWebView on [url].
 * Once a real RoyaleAPI page has loaded ([VerifyCheck.passed], polled every 500 ms because the
 * challenge swaps the page in place), the royaleapi.com cookies (`cf_clearance` included) are
 * copied from the web view's cookie store into the HTTP client's NSHTTPCookieStorage and the web
 * view's `navigator.userAgent` becomes the client's User-Agent. Then [onVerified] runs.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VerifyWebView(url: String, modifier: Modifier = Modifier, onVerified: (Boolean) -> Unit = {}) {
    val web = remember(url) {
        val config = WKWebViewConfiguration().apply { websiteDataStore = WKWebsiteDataStore.defaultDataStore() }
        WKWebView(frame = CGRectZero.readValue(), configuration = config).apply {
            allowsBackForwardNavigationGestures = true
            NSURL.URLWithString(url)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
        }
    }
    UIKitView(
        factory = { web },
        modifier = modifier,
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
        ),
    )
    LaunchedEffect(web) {
        L.i("verify: loading $url")
        var lastTitle: String? = null
        while (true) {
            delay(500)
            val title = web.title
            val current = web.URL?.absoluteString
            if (title != lastTitle) {
                L.i("verify: page '$title' $current")
                lastTitle = title
            }
            if (VerifyCheck.passed(title, current)) {
                val clearance = handOver(web)
                RoyaleApiHealth.shared.reportOk("verifier")
                onVerified(clearance)
                break
            }
        }
    }
    DisposableEffect(web) {
        onDispose { web.stopLoading() }
    }
}

/** Copies the web view's royaleapi.com cookies and User-Agent to the HTTP client; true when `cf_clearance` was among them. */
private suspend fun handOver(web: WKWebView): Boolean {
    val ua = suspendCancellableCoroutine { c ->
        web.evaluateJavaScript("navigator.userAgent") { result, _ -> c.resume(result as? String) }
    }
    val all = suspendCancellableCoroutine { c ->
        web.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
            c.resume(cookies.orEmpty().filterIsInstance<NSHTTPCookie>())
        }
    }
    val cookies = VerifyCheck.royaleApiCookies(all) { it.domain }
    IosApp.httpPlatform.importCookies(cookies)
    if (!ua.isNullOrBlank()) {
        IosApp.httpPlatform.setUserAgent(ua)
        IosApp.http.resetUserAgent()
    }
    val clearance = cookies.any { it.name == VerifyCheck.CLEARANCE_COOKIE }
    L.i(
        "verify: passed ('${web.title}'); copied ${cookies.size} of ${all.size} cookies " +
            "(${cookies.joinToString { it.name }}), cf_clearance=$clearance, now in store=${IosApp.httpPlatform.hasClearanceCookie()}, UA=$ua",
    )
    return clearance
}
