package com.macle.royaleassist.lookup

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** [HttpClients] on Android: cookies and User-Agent of the system WebView. */
fun HttpClients(context: Context): HttpClients = HttpClients(AndroidHttpPlatform(context))

/**
 * Ktor's OkHttp engine over the same OkHttp client as before: the system WebView [CookieManager]
 * as cookie jar (shared with `VerifyActivity`, where the user passes the Cloudflare challenge),
 * the WebView default User-Agent, 10/15/25 s timeouts and OkHttp's own redirect handling.
 */
class AndroidHttpPlatform(private val context: Context) : HttpPlatform {

    override suspend fun userAgent(): String = withContext(Dispatchers.Main) {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
    } ?: HttpClients.FALLBACK_UA

    private val webViewCookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()
                ?: return emptyList()
            return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
            cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
        }
    }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(webViewCookieJar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            // Ktor adds an Accept-Charset header the plain OkHttp client never sent; keep the
            // requests exactly as Cloudflare has seen them so far.
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().removeHeader("Accept-Charset").build()) }
            .build()
    }

    override fun createClient(): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
    }

    override fun hasClearanceCookie(): Boolean = runCatching {
        CookieManager.getInstance().getCookie(RoyaleApiWeb.BASE)?.contains("cf_clearance=") == true
    }.getOrDefault(false)

    /**
     * OkHttp reports connect, read and call timeouts as [InterruptedIOException]s; Ktor passes
     * them on as such or wraps them in its own socket-timeout types, which extend it on the JVM.
     */
    override fun isTimeout(e: Throwable): Boolean = e is InterruptedIOException || e.cause is InterruptedIOException

    override fun evictConnections() {
        okHttp.connectionPool.evictAll()
    }
}
