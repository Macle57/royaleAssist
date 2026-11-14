package com.macle.royaleassist.lookup

import com.macle.royaleassist.L
import com.macle.royaleassist.util.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One invisible attempt at RoyaleAPI's Cloudflare check in a real browser view, the way the user's
 * "Verify RoyaleAPI" screen does it but without showing it (Android: a WebView in an invisible,
 * untouchable overlay window; iOS: a WKWebView behind the app's UI). Cloudflare's managed
 * challenge usually lets a real browser through without a tap; the clearance cookie lands where
 * the HTTP client finds it. Implementations time out by themselves (about 30 s; a slow device can need 20 s before Cloudflare decides).
 */
fun interface ChallengeSolver {
    suspend fun attempt(query: String?): ChallengeAttempt
}

data class ChallengeAttempt(
    /** A real RoyaleAPI page loaded (the HTTP client is checked separately before it counts). */
    val passed: Boolean,
    /** What happened, for the log ("'Search results - RoyaleAPI' in 4.1 s", "timed out on 'Just a moment...'"). */
    val detail: String,
    /** Cloudflare showed a widget that wants a tap (Turnstile checkbox): only the user can pass it. */
    val interactive: Boolean = false,
    /** It ran while the app was in the background, where iOS may suspend web views. */
    val background: Boolean = false,
)

/**
 * Passes RoyaleAPI's browser check without the user where possible, so lookups keep working when
 * the `cf_clearance` cookie runs out (about every 30 min).
 *
 * - **On a block**: whenever [RoyaleApiHealth] hears of a Cloudflare challenge (a lookup, the
 *   probe), one invisible attempt runs through the platform's [ChallengeSolver]. It only counts
 *   when a forced probe through the HTTP client then gets through ([install]'s `confirm`), so a
 *   page that loads without a usable cookie cannot cause a loop. A pass bumps [passes]; the
 *   overlays re-run the scan the block spoiled.
 * - **Keeping it fresh**: [watch] probes every 5 minutes, and once a clearance nears the end of
 *   its expected life ([lifetimeMs], learned from the last one that ran out) every [FAST_PROBE_MS],
 *   so an expired clearance is noticed and replaced within about a minute instead of in the
 *   middle of a lookup.
 * - **Modest**: one attempt at a time, never two within [MIN_ATTEMPT_GAP_MS], and after a failure
 *   a pause of 3, 6, 12, 24, then 30 minutes. An attempt that failed while the app was in the
 *   background pauses [BACKGROUND_RETRY_MS] and does not grow the pause; [onForeground] lifts it.
 * - **Fallback**: [needsUser] (the red bubble, the notification, the card's text) is true only
 *   when RoyaleAPI is blocked and the invisible attempt failed or cannot run; the user then
 *   verifies by hand as before ([markPassed]).
 */
class AutoVerifier(
    private val health: RoyaleApiHealth = RoyaleApiHealth.shared,
    private val clock: () -> Long = ::nowMillis,
) {
    enum class Phase { Idle, Solving, Failed }

    data class State(
        val phase: Phase = Phase.Idle,
        /** When the check was last passed (in the background or by hand), ms. */
        val lastPassAt: Long? = null,
        /** Failed attempts in a row (background ones not counted). */
        val failures: Int = 0,
        /** No automatic attempt before this time (ms). */
        val retryAt: Long? = null,
        /** The last attempt's outcome, for the log and the setup screen. */
        val detail: String? = null,
        /** The last failure ran in the background (iOS), so coming to the foreground may retry at once. */
        val failedInBackground: Boolean = false,
        /** The last failure met a widget that wants a tap. */
        val interactive: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _passes = MutableStateFlow(0)
    /** Counts passed checks (background or by hand); the overlays re-run a blocked scan on each. */
    val passes: StateFlow<Int> = _passes.asStateFlow()

    private var solver: ChallengeSolver? = null
    private var confirm: (suspend () -> RoyaleApiStatus)? = null
    private var scope: CoroutineScope? = null
    private val busy = MutableStateFlow(false)
    private var lastAttemptAt: Long? = null
    private var lastSkipLog: String? = null

    /** Expected life of a clearance; starts at [DEFAULT_LIFETIME_MS], then what the last one lasted. */
    var lifetimeMs: Long = DEFAULT_LIFETIME_MS
        private set

    val installed: Boolean get() = solver != null

    /**
     * Turns automatic passing on: [solver] makes the invisible attempt, [confirm] forces a probe
     * through the HTTP client (normally `health.probe(http, force = true)`). Attempts run in [scope].
     */
    fun install(solver: ChallengeSolver, confirm: suspend () -> RoyaleApiStatus, scope: CoroutineScope) {
        this.solver = solver
        this.confirm = confirm
        this.scope = scope
        scope.launch { followHealth() }
        L.i("autoverify: on")
    }

    /** RoyaleAPI is blocked and no invisible attempt is running or about to: the user must verify. */
    fun needsUser(h: RoyaleApiHealth.State = health.state.value, s: State = _state.value): Boolean =
        h.needsVerification && (solver == null || s.phase == Phase.Failed)

    /** [needsUser] as a flow (red bubble, notification). */
    val needsUserFlow: Flow<Boolean> = combine(health.state, _state) { h, s -> needsUser(h, s) }.distinctUntilChanged()

    /** True while an invisible attempt runs (the setup screen and the card say so). */
    val solving: Boolean get() = _state.value.phase == Phase.Solving

    /** The user passed the check in the verifier screen. */
    fun markPassed(source: String = "verifier") {
        health.reportOk(source)
        pass(source)
    }

    /** The app came to the foreground: a pause left by a background failure no longer applies. */
    fun onForeground() {
        val s = _state.value
        if (s.phase == Phase.Failed && s.failedInBackground) {
            L.i("autoverify: in the foreground, the background failure no longer pauses attempts")
            _state.update { it.copy(retryAt = null, failedInBackground = false) }
            if (health.state.value.needsVerification) request("foreground")
        }
    }

    /**
     * Starts an attempt in the background when one is due (see [solve]). Held back only by the
     * minimum gap, it is tried again once that gap is over.
     */
    fun request(reason: String, query: String? = null) {
        val sc = scope ?: return
        when (val w = waitFor(reason)) {
            null -> sc.launch { solve(reason, query) }
            is Wait.Gap -> if (!retryQueued) {
                retryQueued = true
                sc.launch {
                    delay(w.remainingMs)
                    retryQueued = false
                    if (health.state.value.needsVerification) request(reason, query)
                }
            }
            else -> {}
        }
    }

    private var retryQueued = false

    /**
     * One invisible attempt now, unless one is running, the last one was under
     * [MIN_ATTEMPT_GAP_MS] ago or a failure's pause is still on. True when the HTTP client gets
     * through afterwards.
     */
    suspend fun solve(reason: String, query: String? = null): Boolean {
        val s = solver ?: return false
        if (waitFor(reason) != null) return false
        if (!busy.compareAndSet(expect = false, update = true)) return false
        val t0 = clock()
        lastAttemptAt = t0
        lastSkipLog = null
        _state.update { it.copy(phase = Phase.Solving) }
        L.i("autoverify: trying RoyaleAPI's check in the background ($reason)")
        try {
            val a = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) { s.attempt(query) }
                ?: ChallengeAttempt(false, "no answer in ${ATTEMPT_TIMEOUT_MS / 1000} s")
            if (!a.passed) {
                fail(a)
                return false
            }
            val status = confirmed()
            if (status != RoyaleApiStatus.Verified) {
                fail(a.copy(detail = "${a.detail}, but the HTTP client is still blocked ($status)"))
                return false
            }
            L.i("autoverify: passed without a tap in ${clock() - t0} ms (${a.detail}); lookups work again")
            pass("background")
            return true
        } catch (e: CancellationException) {
            _state.update { if (it.phase == Phase.Solving) it.copy(phase = Phase.Idle) else it }
            throw e
        } catch (e: Exception) {
            fail(ChallengeAttempt(false, "error: ${e.message}"))
            return false
        } finally {
            busy.value = false
        }
    }

    /** The forced probe, once more after a moment when another probe was in flight. */
    private suspend fun confirmed(): RoyaleApiStatus {
        val c = confirm ?: return RoyaleApiStatus.Verified
        val first = c()
        if (first == RoyaleApiStatus.Verified) return first
        delay(CONFIRM_RETRY_MS)
        return c()
    }

    private sealed class Wait {
        object Busy : Wait()
        class Gap(val remainingMs: Long) : Wait()
        class Paused(val remainingMs: Long) : Wait()
    }

    /** Why no attempt may start now; null when one may. Says so in the log once per pause. */
    private fun waitFor(reason: String): Wait? {
        if (solver == null || busy.value) return Wait.Busy
        val now = clock()
        val s = _state.value
        val last = lastAttemptAt
        val w = when {
            last != null && now - last < MIN_ATTEMPT_GAP_MS -> Wait.Gap(MIN_ATTEMPT_GAP_MS - (now - last))
            s.retryAt != null && now < s.retryAt -> Wait.Paused(s.retryAt - now)
            else -> return null
        }
        // Lookups report several blocks in a row; say it once per pause.
        val key = "${w::class.simpleName} ${s.retryAt} $last"
        if (key != lastSkipLog) {
            lastSkipLog = key
            val text = when (w) {
                is Wait.Gap -> "the last attempt was ${(now - last!!) / 1000} s ago"
                is Wait.Paused -> "paused for another ${w.remainingMs / 1000} s after: ${s.detail}"
                else -> "busy"
            }
            L.i("autoverify: not trying now ($reason): $text")
        }
        return w
    }

    private fun pass(source: String) {
        val now = clock()
        _state.update { State(phase = Phase.Idle, lastPassAt = now, detail = "passed ($source)") }
        _passes.value = _passes.value + 1
    }

    private fun fail(a: ChallengeAttempt) {
        val now = clock()
        _state.update { s ->
            val failures = if (a.background) s.failures else s.failures + 1
            val pause = if (a.background) BACKGROUND_RETRY_MS else backoffMs(failures)
            s.copy(
                phase = Phase.Failed, failures = failures, retryAt = now + pause, detail = a.detail,
                failedInBackground = a.background, interactive = a.interactive,
            )
        }
        val s = _state.value
        L.i(
            "autoverify: background attempt failed (${a.detail})${if (a.interactive) ", Cloudflare wants a tap" else ""}" +
                "${if (a.background) ", app in the background" else ""}; next try in ${(s.retryAt!! - now) / 1000} s at the earliest, " +
                "the user can verify by hand",
        )
    }

    /** Reacts to every report: a block asks for an attempt; a clearance that ran out teaches [lifetimeMs]. */
    private suspend fun followHealth() {
        var prev: RoyaleApiHealth.State? = null
        health.state.collect { h ->
            val p = prev
            prev = h
            if (h.needsVerification) {
                if (p?.status == RoyaleApiStatus.Verified) learnLifetime(okSince = p.since, blockedSince = h.since)
                request("blocked: ${h.source}")
            } else if (h.status == RoyaleApiStatus.Verified && _state.value.phase == Phase.Failed) {
                // Got through anyway (a manual pass elsewhere, a cookie that came back): no longer failed.
                _state.update { it.copy(phase = Phase.Idle, retryAt = null, failures = 0, failedInBackground = false, interactive = false) }
            }
        }
    }

    private fun learnLifetime(okSince: Long?, blockedSince: Long?) {
        val passAt = _state.value.lastPassAt ?: return
        if (okSince == null || blockedSince == null || okSince < passAt) return
        // Only when the last good page was shortly before the block: then the clearance ran out in between.
        if (blockedSince - okSince > LEARN_WINDOW_MS) return
        val lasted = okSince - passAt
        if (lasted < MIN_LIFETIME_MS) return
        lifetimeMs = lasted.coerceAtMost(MAX_LIFETIME_MS)
        L.i("autoverify: the clearance lasted about ${lasted / 60_000} min (good at ${lasted / 1000} s, blocked at ${(blockedSince - passAt) / 1000} s)")
    }

    /** How long [watch] waits before the next probe: sooner once the clearance is near its expected end. */
    fun nextProbeDelayMs(now: Long = clock()): Long {
        val passAt = _state.value.lastPassAt ?: return RoyaleApiHealth.PROBE_INTERVAL_MS
        if (health.state.value.status != RoyaleApiStatus.Verified) return RoyaleApiHealth.PROBE_INTERVAL_MS
        val soon = passAt + lifetimeMs - SOON_MARGIN_MS
        if (now >= soon) return FAST_PROBE_MS
        return (soon - now).coerceIn(FAST_PROBE_MS, RoyaleApiHealth.PROBE_INTERVAL_MS)
    }

    /** Probes RoyaleAPI until cancelled (while a bubble is up), at [nextProbeDelayMs]. A block found starts an attempt. */
    suspend fun watch(probe: suspend () -> Unit) {
        while (true) {
            probe()
            delay(nextProbeDelayMs())
        }
    }

    suspend fun watch(http: HttpClients) = watch { health.probe(http) }

    companion object {
        /** The app-wide instance; the platform calls [install] at start. */
        val shared = AutoVerifier()

        const val ATTEMPT_TIMEOUT_MS = 40_000L
        const val MIN_ATTEMPT_GAP_MS = 60_000L
        const val BACKGROUND_RETRY_MS = 3 * 60_000L
        const val CONFIRM_RETRY_MS = 1_500L
        const val DEFAULT_LIFETIME_MS = 30 * 60_000L
        const val MIN_LIFETIME_MS = 5 * 60_000L
        const val MAX_LIFETIME_MS = 6 * 60 * 60_000L
        const val SOON_MARGIN_MS = 4 * 60_000L
        const val FAST_PROBE_MS = 70_000L
        const val LEARN_WINDOW_MS = 3 * 60_000L

        /** Pause after [failures] failed attempts in a row: 3, 6, 12, 24, then 30 minutes. */
        fun backoffMs(failures: Int): Long {
            val n = (failures - 1).coerceIn(0, 4)
            return (3 * 60_000L shl n).coerceAtMost(30 * 60_000L)
        }
    }
}
