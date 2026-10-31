package com.macle.royaleassist.lookup

import com.macle.royaleassist.L
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
import platform.Foundation.NSUserDefaults
import kotlin.concurrent.Volatile

/**
 * [HttpClients] on iOS: Ktor's Darwin engine (NSURLSession) with the shared
 * [NSHTTPCookieStorage] as cookie store.
 *
 * Cloudflare hook: after the user passes the challenge in the WKWebView verifier, its cookies
 * (from `WKHTTPCookieStore.getAllCookies`) go into [importCookies] and its `navigator.userAgent`
 * into [setUserAgent], so `cf_clearance` is sent with the same User-Agent that earned it. The
 * User-Agent is kept in NSUserDefaults because the cookie store outlives the process.
 */
class IosHttpPlatform : HttpPlatform {

    /** The WKWebView's User-Agent once known (also after a restart); until then a Safari-on-iPad one. */
    @Volatile var userAgentOverride: String? = NSUserDefaults.standardUserDefaults.stringForKey(UA_KEY)
        private set

    override suspend fun userAgent(): String = userAgentOverride ?: FALLBACK_UA

    /** Remembers the User-Agent of the web view that passed the challenge. */
    fun setUserAgent(ua: String) {
        userAgentOverride = ua
        NSUserDefaults.standardUserDefaults.setObject(ua, forKey = UA_KEY)
    }

    /**
     * Adds cookies (e.g. `cf_clearance` from WKHTTPCookieStore) to the store NSURLSession uses.
     * Each one is rebuilt from [VerifyCheck.portableCookieProperties]: WebKit's copy carries a
     * `StoragePartition`, and a partitioned cookie in NSHTTPCookieStorage is never sent with (or
     * found by `cookiesForURL` for) a plain NSURLSession request. Older copies of the same cookie,
     * partitioned ones included, are deleted first.
     */
    fun importCookies(cookies: List<NSHTTPCookie>) {
        val store = NSHTTPCookieStorage.sharedHTTPCookieStorage
        cookies.forEach { c ->
            val props = c.properties.orEmpty().entries.associate { (k, v) -> k.toString() to v }
            val portable = VerifyCheck.portableCookieProperties(props)
            val copy = NSHTTPCookie.cookieWithProperties(portable.mapKeys { it.key as Any? })
            store.cookies.orEmpty().filterIsInstance<NSHTTPCookie>()
                .filter { it.name == c.name && it.domain == c.domain && it.path == c.path }
                .forEach { store.deleteCookie(it) }
            store.setCookie(copy ?: c)
            L.i(
                "http: imported cookie ${c.name} domain=${c.domain} path=${c.path} secure=${c.secure} " +
                    "httpOnly=${c.HTTPOnly} sameSite=${c.sameSitePolicy} expires=${c.expiresDate} " +
                    "dropped=${props.keys - portable.keys}" + if (copy == null) " (rebuild failed, copied as is)" else "",
            )
        }
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
            configureRequest {
                // Ktor adds an Accept-Charset header that browsers never send (Android strips it too).
                setValue(null, forHTTPHeaderField = "Accept-Charset")
                val url = URL
                if (url?.host == VerifyCheck.HOST) {
                    val cookies = NSHTTPCookieStorage.sharedHTTPCookieStorage.cookiesForURL(url).orEmpty()
                        .mapNotNull { (it as? NSHTTPCookie)?.name }
                    L.i("http: -> ${url.path} UA=${valueForHTTPHeaderField("User-Agent")} cookies=$cookies")
                }
            }
        }
    }

    override fun isTimeout(e: Throwable): Boolean =
        e is HttpRequestTimeoutException || e is SocketTimeoutException || e is ConnectTimeoutException ||
            (e is DarwinHttpRequestException && e.origin.code == NSURLErrorTimedOut)

    /** NSURLSession manages its own connections; the retry simply goes out again. */
    override fun evictConnections() {}

    companion object {
        private const val UA_KEY = "verify.userAgent"

        const val FALLBACK_UA =
            "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    }
}
