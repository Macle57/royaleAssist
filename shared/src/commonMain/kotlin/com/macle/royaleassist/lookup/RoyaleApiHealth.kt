package com.macle.royaleassist.lookup

import com.macle.royaleassist.L
import com.macle.royaleassist.util.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Whether RoyaleAPI's Cloudflare check currently lets the app's HTTP client through. */
enum class RoyaleApiStatus { Unknown, Verified, NeedsVerification }

/**
 * Tracks whether RoyaleAPI lookups work or need the browser check again ("Verify RoyaleAPI"): the
 * clearance cookie expires after a while, and the overlay warns before a match instead of failing
 * in one. Every RoyaleAPI page fetch reports here ([HttpClients.getHtml]): a Cloudflare challenge
 * makes it [RoyaleApiStatus.NeedsVerification], a page that got through makes it
 * [RoyaleApiStatus.Verified], as does the verifier passing. [probe] fetches one small page to find
 * out (app foreground, overlay start, every [PROBE_INTERVAL_MS] while the bubble is up).
 *
 * A report only counts if its request started after the one behind the current state, so a slow
 * request that began before the user verified cannot turn the warning back on.
 */
class RoyaleApiHealth(private val clock: () -> Long = ::nowMillis) {
    data class State(
        val status: RoyaleApiStatus = RoyaleApiStatus.Unknown,
        /** When the request behind [status] started (ms); null before the first report. */
        val since: Long? = null,
        /** What reported [status] (log and debugging). */
        val source: String? = null,
    ) {
        val needsVerification: Boolean get() = status == RoyaleApiStatus.NeedsVerification
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val probing = MutableStateFlow(false)

    /** A RoyaleAPI page started at [startedAt] came through (or the verifier passed). */
    fun reportOk(source: String, startedAt: Long = clock()) = report(RoyaleApiStatus.Verified, source, startedAt)

    /** A RoyaleAPI request started at [startedAt] hit the Cloudflare challenge. */
    fun reportBlocked(source: String, startedAt: Long = clock()) = report(RoyaleApiStatus.NeedsVerification, source, startedAt)

    private fun report(status: RoyaleApiStatus, source: String, startedAt: Long) {
        var changed = false
        _state.update { s ->
            if (s.since != null && startedAt < s.since) return@update s
            changed = s.status != status
            State(status, startedAt, source)
        }
        if (changed) L.i("royaleapi: $status ($source)")
    }

    /**
     * One small GET of RoyaleAPI through [fetch] (normally [HttpClients.getHtml]): a challenge means
     * the check is needed again, any page means it is fine. Other failures (offline, timeouts, HTTP
     * errors) say nothing about the check and change nothing. Skipped while another probe runs, and
     * unless [force], when a report arrived in the last [MIN_PROBE_GAP_MS].
     */
    suspend fun probe(fetch: suspend (String) -> Any?, force: Boolean = false): RoyaleApiStatus {
        val since = _state.value.since
        if (!force && since != null && clock() - since < MIN_PROBE_GAP_MS) return _state.value.status
        if (!probing.compareAndSet(expect = false, update = true)) return _state.value.status
        val t0 = clock()
        try {
            fetch(PROBE_URL)
            reportOk("probe", t0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudflareBlockedException) {
            reportBlocked("probe", t0)
        } catch (e: Exception) {
            L.w("royaleapi: probe failed, status unchanged: ${e.message}")
        } finally {
            probing.value = false
        }
        return _state.value.status
    }

    suspend fun probe(http: HttpClients, force: Boolean = false): RoyaleApiStatus = probe({ http.getHtml(it) }, force)

    /** Probes now and then every [everyMs] until cancelled (while the overlay or the bubble is up). */
    suspend fun watch(http: HttpClients, everyMs: Long = PROBE_INTERVAL_MS) {
        while (true) {
            probe(http)
            delay(everyMs)
        }
    }

    companion object {
        /** The app-wide tracker ([HttpClients] reports to it by default). */
        val shared = RoyaleApiHealth()

        const val PROBE_INTERVAL_MS = 5 * 60_000L

        /** A probe is not needed when a RoyaleAPI page was fetched this recently. */
        const val MIN_PROBE_GAP_MS = 60_000L

        /** A small page behind the same Cloudflare check as the lookups (an exact player search). */
        val PROBE_URL: String = RoyaleApiWeb.searchUrl("royaleAssist", exact = true)
    }
}
