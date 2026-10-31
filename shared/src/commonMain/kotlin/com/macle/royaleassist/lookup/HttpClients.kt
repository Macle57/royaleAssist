package com.macle.royaleassist.lookup

import com.macle.royaleassist.L
import com.macle.royaleassist.util.nowMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlin.concurrent.Volatile

class CloudflareBlockedException(url: String) : IOException("Cloudflare challenge on $url")

class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(200)}")

/**
 * What a platform supplies to [HttpClients]: the Ktor client (engine, cookie store, timeouts of
 * 10 s connect / 15 s read / 25 s per call), the browser User-Agent and the Cloudflare cookie.
 */
interface HttpPlatform {
    /** The User-Agent of the browser that solved the Cloudflare challenge. */
    suspend fun userAgent(): String

    /** True when the cookie store holds RoyaleAPI's `cf_clearance` cookie. */
    fun hasClearanceCookie(): Boolean

    fun createClient(): HttpClient

    /** True for a timeout worth one retry on a fresh connection. */
    fun isTimeout(e: Throwable): Boolean

    /** Drops pooled connections (after a network change they can be dead). */
    fun evictConnections()
}

/**
 * HTTP setup shared by every lookup strategy.
 *
 * RoyaleAPI's website sits behind a Cloudflare managed challenge. A plain HTTP client always gets
 * the "Just a moment..." page, but once a real browser has passed the challenge, the resulting
 * `cf_clearance` cookie works from any client that sends the *same User-Agent* from the same IP.
 * So the platform shares its cookies with the browser view that solved the challenge (Android: the
 * system WebView `CookieManager`, shared with `VerifyActivity`) and sends that browser's User-Agent.
 */
class HttpClients(private val platform: HttpPlatform) {

    @Volatile private var cachedUa: String? = null

    /** The browser User-Agent; must match the one that solved the Cloudflare challenge. */
    suspend fun userAgent(): String {
        cachedUa?.let { return it }
        val ua = platform.userAgent()
        cachedUa = ua
        return ua
    }

    val client: HttpClient by lazy { platform.createClient() }

    fun hasClearanceCookie(): Boolean = platform.hasClearanceCookie()

    /** GET a RoyaleAPI web page as HTML, throwing [CloudflareBlockedException] on a challenge page. */
    suspend fun getHtml(url: String): String {
        val ua = userAgent()
        suspend fun get(): HttpResponse = client.get(url) {
            header("User-Agent", ua)
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.9")
        }
        val t0 = nowMillis()
        // After a network change pooled connections can be dead and time out; drop them and retry once.
        val resp = try {
            get()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!platform.isTimeout(e)) throw e
            L.w("HTTP GET $url timed out (${e.message}); retrying on a fresh connection")
            platform.evictConnections()
            get()
        }
        val code = resp.status.value
        val body = resp.bodyAsText()
        L.i("HTTP GET $url -> $code (${body.length} chars, ${nowMillis() - t0} ms)")
        if (isCloudflareChallenge(code, resp.headers["cf-mitigated"], body)) throw CloudflareBlockedException(url)
        if (code !in 200..299) throw HttpStatusException(code, body)
        return body
    }

    /** GET JSON from the official API (via the RoyaleAPI proxy) with a bearer token. */
    suspend fun getApiJson(url: String, token: String): String {
        val resp = client.get(url) {
            header("Authorization", "Bearer $token")
            header("Accept", "application/json")
        }
        val code = resp.status.value
        val body = resp.bodyAsText()
        L.i("API GET $url -> $code (${body.length} chars)")
        if (code !in 200..299) throw HttpStatusException(code, body)
        return body
    }

    companion object {
        const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        fun isCloudflareChallenge(code: Int, cfMitigated: String?, body: String): Boolean =
            cfMitigated == "challenge" ||
                ((code == 403 || code == 503) && isChallengeHtml(body))

        fun isChallengeHtml(body: String): Boolean =
            body.contains("<title>Just a moment", ignoreCase = true) ||
                body.contains("challenges.cloudflare.com") ||
                body.contains("cf-chl-", ignoreCase = true)
    }
}
