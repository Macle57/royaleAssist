package com.macle.royaleassist.lookup

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.macle.royaleassist.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class CloudflareBlockedException(url: String) : IOException("Cloudflare challenge on $url")

class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(200)}")

/**
 * OkHttp setup shared by every lookup strategy.
 *
 * RoyaleAPI's website sits behind a Cloudflare managed challenge. A plain HTTP client always gets
 * the "Just a moment..." page, but once a real browser has passed the challenge, the resulting
 * `cf_clearance` cookie works from any client that sends the *same User-Agent* from the same IP.
 * So cookies live in the system WebView [CookieManager] (shared with [VerifyActivity]) and OkHttp
 * sends the WebView default User-Agent.
 */
class HttpClients(private val context: Context) {

    @Volatile private var cachedUa: String? = null

    /** The WebView User-Agent; must match the one that solved the Cloudflare challenge. */
    suspend fun userAgent(): String {
        cachedUa?.let { return it }
        val ua = withContext(Dispatchers.Main) {
            runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        } ?: FALLBACK_UA
        cachedUa = ua
        return ua
    }

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

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(webViewCookieJar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    fun hasClearanceCookie(): Boolean = runCatching {
        CookieManager.getInstance().getCookie(RoyaleApiWeb.BASE)?.contains("cf_clearance=") == true
    }.getOrDefault(false)

    /** GET a RoyaleAPI web page as HTML, throwing [CloudflareBlockedException] on a challenge page. */
    suspend fun getHtml(url: String): String {
        val ua = userAgent()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val t0 = System.currentTimeMillis()
            // After a network change pooled connections can be dead and time out; drop them and retry once.
            val call = try {
                client.newCall(req).execute()
            } catch (e: java.io.InterruptedIOException) {
                L.w("HTTP GET $url timed out (${e.message}); retrying on a fresh connection")
                client.connectionPool.evictAll()
                client.newCall(req).execute()
            }
            call.use { resp ->
                val body = resp.body?.string().orEmpty()
                L.i("HTTP GET $url -> ${resp.code} (${body.length} chars, ${System.currentTimeMillis() - t0} ms)")
                if (isCloudflareChallenge(resp, body)) throw CloudflareBlockedException(url)
                if (!resp.isSuccessful) throw HttpStatusException(resp.code, body)
                body
            }
        }
    }

    /** GET JSON from the official API (via the RoyaleAPI proxy) with a bearer token. */
    suspend fun getApiJson(url: String, token: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            L.i("API GET $url -> ${resp.code} (${body.length} chars)")
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, body)
            body
        }
    }

    companion object {
        const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        fun isCloudflareChallenge(resp: Response, body: String): Boolean =
            resp.header("cf-mitigated") == "challenge" ||
                ((resp.code == 403 || resp.code == 503) && isChallengeHtml(body))

        fun isChallengeHtml(body: String): Boolean =
            body.contains("<title>Just a moment", ignoreCase = true) ||
                body.contains("challenges.cloudflare.com") ||
                body.contains("cf-chl-", ignoreCase = true)
    }
}
