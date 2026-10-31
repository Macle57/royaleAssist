package com.macle.royaleassist.lookup

/**
 * When the in-app browser has passed RoyaleAPI's Cloudflare check, and which of its cookies the
 * HTTP client needs. Used by Android's VerifyActivity and the iOS WKWebView verifier.
 */
object VerifyCheck {
    /** RoyaleAPI's host, without "www." ("royaleapi.com"). */
    val HOST: String = RoyaleApiWeb.BASE.substringAfter("://").substringBefore('/').removePrefix("www.")

    /** True once a real RoyaleAPI page has loaded (not Cloudflare's "Just a moment..." interstitial). */
    fun passed(title: String?, url: String?): Boolean {
        val t = title.orEmpty()
        val u = url.orEmpty()
        return t.isNotBlank() && !t.contains("Just a moment", ignoreCase = true) && u.startsWith(RoyaleApiWeb.BASE)
    }

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
}
