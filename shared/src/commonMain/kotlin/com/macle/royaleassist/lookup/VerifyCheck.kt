package com.macle.royaleassist.lookup

/**
 * When the in-app browser has passed RoyaleAPI's Cloudflare check, and which of its cookies the
 * HTTP client needs. Used by Android's VerifyActivity and the iOS WKWebView verifier.
 */
object VerifyCheck {
    /** RoyaleAPI's host, without "www." ("royaleapi.com"). */
    val HOST: String = RoyaleApiWeb.BASE.substringAfter("://").substringBefore('/').removePrefix("www.")

    /**
     * True once a real RoyaleAPI page has loaded: not Cloudflare's "Just a moment..." interstitial,
     * and not the browser's own error page (offline, DNS), which is titled with the bare URL.
     */
    fun passed(title: String?, url: String?): Boolean {
        val t = title.orEmpty().trim()
        val u = url.orEmpty()
        return t.isNotBlank() && !t.contains("Just a moment", ignoreCase = true) &&
            !t.contains(HOST, ignoreCase = true) && !t.startsWith("http", ignoreCase = true) &&
            u.startsWith(RoyaleApiWeb.BASE)
    }

    /**
     * True when the challenge page's text asks for a tap (Turnstile's "Verify you are human"
     * checkbox); "Verifying you are human..." is the part that passes by itself.
     */
    fun wantsTap(pageText: String): Boolean = WANTS_TAP.containsMatchIn(pageText)

    private val WANTS_TAP = Regex("Verify you are human|Confirm you are human|press and hold", RegexOption.IGNORE_CASE)

    /** Script for a web view: the start of the page's visible text (logged when an attempt gives up). */
    const val PAGE_TEXT_JS = "(document.body ? document.body.innerText : '').slice(0, 400)"

    /**
     * True for a cookie of royaleapi.com or one of its subdomains: `cf_clearance`, `__cf_bm` and the
     * site's own cookies. [domain] is the cookie's domain attribute, with or without a leading dot.
     */
    fun isRoyaleApiCookie(domain: String): Boolean {
        val d = domain.trim().removePrefix(".").lowercase()
        return d == HOST || d.endsWith(".$HOST")
    }

    /** The cookies (by domain) to copy into the HTTP client's store. */
    fun <T> royaleApiCookies(cookies: List<T>, domain: (T) -> String): List<T> = cookies.filter { isRoyaleApiCookie(domain(it)) }

    const val CLEARANCE_COOKIE = "cf_clearance"

    /**
     * NSHTTPCookie property keys that describe a cookie by itself. WebKit's cookies also carry
     * `StoragePartition` (Cloudflare sets `cf_clearance` as a CHIPS `Partitioned` cookie): a copy
     * with that key lands in NSHTTPCookieStorage under the partition, where plain NSURLSession
     * requests (no partition) never find it. The iOS verifier copies only these keys.
     */
    val PORTABLE_COOKIE_KEYS: Set<String> = setOf(
        "Name", "Value", "Domain", "Path", "Secure", "HttpOnly", "Expires", "Max-Age", "SameSite",
        "Version", "Discard", "Port", "Comment", "CommentURL", "OriginURL", "Created",
    )

    /** [props] without partition and other WebKit-internal keys (see [PORTABLE_COOKIE_KEYS]). */
    fun <V> portableCookieProperties(props: Map<String, V>): Map<String, V> = props.filterKeys { it in PORTABLE_COOKIE_KEYS }
}
