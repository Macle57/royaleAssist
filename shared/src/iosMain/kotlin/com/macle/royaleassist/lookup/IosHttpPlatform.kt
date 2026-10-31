package com.macle.royaleassist.lookup

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorTimedOut
import kotlin.concurrent.Volatile

/**
 * [HttpClients] on iOS: Ktor's Darwin engine (NSURLSession) with the shared
 * [NSHTTPCookieStorage] as cookie store.
 *
 * Cloudflare hook (the WKWebView verifier is a later phase): after the user passes the challenge
 * in a WKWebView, copy its cookies from `WKHTTPCookieStore.getAllCookies` into [importCookies]
 * and set [userAgentOverride] to that web view's User-Agent, so `cf_clearance` is sent with the
 * same User-Agent that earned it.
 */
class IosHttpPlatform : HttpPlatform {

    /** The WKWebView's User-Agent once known; until then a Safari-on-iPad one. */
    @Volatile var userAgentOverride: String? = null

    override suspend fun userAgent(): String = userAgentOverride ?: FALLBACK_UA

    /** Adds cookies (e.g. `cf_clearance` from WKHTTPCookieStore) to the store NSURLSession uses. */
    fun importCookies(cookies: List<NSHTTPCookie>) {
        val store = NSHTTPCookieStorage.sharedHTTPCookieStorage
        cookies.forEach { store.setCookie(it) }
    }

    override fun hasClearanceCookie(): Boolean {
        val url = NSURL.URLWithString(RoyaleApiWeb.BASE) ?: return false
        return NSHTTPCookieStorage.sharedHTTPCookieStorage.cookiesForURL(url).orEmpty()
            .any { (it as? NSHTTPCookie)?.name == "cf_clearance" }
    }

    override fun createClient(): HttpClient = HttpClient(Darwin) {
        engine {
            configureSession {
                HTTPCookieStorage = NSHTTPCookieStorage.sharedHTTPCookieStorage
                HTTPShouldSetCookies = true
                // NSURLSession has no separate connect timeout: 15 s without data, 25 s per request.
                timeoutIntervalForRequest = 15.0
                timeoutIntervalForResource = 25.0
            }
        }
    }

    override fun isTimeout(e: Throwable): Boolean =
        e is HttpRequestTimeoutException || e is SocketTimeoutException || e is ConnectTimeoutException ||
            (e is DarwinHttpRequestException && e.origin.code == NSURLErrorTimedOut)

    /** NSURLSession manages its own connections; the retry simply goes out again. */
    override fun evictConnections() {}

    companion object {
        const val FALLBACK_UA =
            "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    }
}
