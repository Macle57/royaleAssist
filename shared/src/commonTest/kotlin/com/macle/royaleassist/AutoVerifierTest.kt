package com.macle.royaleassist

import com.macle.royaleassist.lookup.AutoVerifier
import com.macle.royaleassist.lookup.ChallengeAttempt
import com.macle.royaleassist.lookup.RoyaleApiHealth
import com.macle.royaleassist.lookup.RoyaleApiStatus
import com.macle.royaleassist.lookup.VerifyCheck
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Passing RoyaleAPI's check without the user: when an attempt runs, what counts, how it backs off. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoVerifierTest {
    private class Rig(scope: TestScope) {
        val clock = { 1_000_000L + scope.testScheduler.currentTime }
        val health = RoyaleApiHealth(clock)
        val auto = AutoVerifier(health, clock)
        var attempts = 0
        var nextAttempt = ChallengeAttempt(true, "page loaded")
        /** What the HTTP client sees after an attempt. */
        var httpGetsThrough = true

        init {
            auto.install(
                solver = { attempts++; nextAttempt },
                confirm = {
                    if (httpGetsThrough) health.reportOk("probe") else health.reportBlocked("probe")
                    health.state.value.status
                },
                scope = scope.backgroundScope,
            )
        }
    }

    @Test
    fun aBlockStartsOneInvisibleAttemptAndAPassIsCounted() = runTest {
        val r = Rig(this)
        runCurrent()
        r.health.reportBlocked("lookup")
        r.health.reportBlocked("lookup again")
        runCurrent()
        assertEquals(1, r.attempts)
        assertEquals(1, r.auto.passes.value)
        assertEquals(RoyaleApiStatus.Verified, r.health.state.value.status)
        assertFalse(r.auto.needsUser())
        assertEquals(AutoVerifier.Phase.Idle, r.auto.state.value.phase)
    }

    @Test
    fun aFailedAttemptAsksTheUserAndPausesWithGrowingGaps() = runTest {
        val r = Rig(this)
        r.nextAttempt = ChallengeAttempt(false, "timed out on 'Just a moment...'", interactive = true)
        runCurrent()
        r.health.reportBlocked("lookup")
        runCurrent()
        assertEquals(1, r.attempts)
        assertTrue(r.auto.needsUser())
        assertTrue(r.auto.state.value.interactive)
        assertEquals(0, r.auto.passes.value)

        // Blocks during the 3 min pause start nothing.
        advanceTimeBy(2 * 60_000L)
        r.health.reportBlocked("probe")
        runCurrent()
        assertEquals(1, r.attempts)

        // After it, the next block tries again; a second failure pauses 6 min.
        advanceTimeBy(60_001L)
        r.health.reportBlocked("probe")
        runCurrent()
        assertEquals(2, r.attempts)
        val s = r.auto.state.value
        assertEquals(2, s.failures)
        assertEquals(6 * 60_000L, s.retryAt!! - r.clock())
    }

    @Test
    fun aPageThatLoadsWhileTheHttpClientStaysBlockedIsAFailure() = runTest {
        val r = Rig(this)
        r.httpGetsThrough = false
        runCurrent()
        r.health.reportBlocked("lookup")
        runCurrent()
        advanceTimeBy(AutoVerifier.CONFIRM_RETRY_MS + 1)
        runCurrent()
        assertEquals(1, r.attempts)
        assertEquals(0, r.auto.passes.value)
        assertEquals(AutoVerifier.Phase.Failed, r.auto.state.value.phase)
        assertTrue(r.auto.needsUser())
    }

    @Test
    fun aBackgroundFailureDoesNotGrowThePauseAndTheForegroundRetries() = runTest {
        val r = Rig(this)
        r.nextAttempt = ChallengeAttempt(false, "web view suspended", background = true)
        runCurrent()
        r.health.reportBlocked("probe")
        runCurrent()
        assertEquals(1, r.attempts)
        assertEquals(0, r.auto.state.value.failures)
        assertTrue(r.auto.state.value.failedInBackground)

        r.nextAttempt = ChallengeAttempt(true, "page loaded")
        advanceTimeBy(AutoVerifier.MIN_ATTEMPT_GAP_MS + 1)
        r.auto.onForeground()
        runCurrent()
        assertEquals(2, r.attempts)
        assertEquals(1, r.auto.passes.value)
        assertFalse(r.auto.needsUser())
    }

    @Test
    fun aBlockRightAfterAnAttemptWaitsForTheGapThenTries() = runTest {
        val r = Rig(this)
        runCurrent()
        r.health.reportBlocked("lookup")
        runCurrent()
        assertEquals(1, r.attempts)
        advanceTimeBy(10_000L)
        r.health.reportBlocked("lookup")
        runCurrent()
        assertEquals(1, r.attempts)
        advanceTimeBy(AutoVerifier.MIN_ATTEMPT_GAP_MS)
        runCurrent()
        assertEquals(2, r.attempts)
    }

    @Test
    fun aManualPassClearsTheFailureAndIsCounted() = runTest {
        val r = Rig(this)
        r.nextAttempt = ChallengeAttempt(false, "timed out")
        runCurrent()
        r.health.reportBlocked("lookup")
        runCurrent()
        assertTrue(r.auto.needsUser())
        advanceTimeBy(1_000L)
        r.auto.markPassed()
        runCurrent()
        assertFalse(r.auto.needsUser())
        assertEquals(1, r.auto.passes.value)
        assertEquals(0, r.auto.state.value.failures)
        assertEquals(null, r.auto.state.value.retryAt)
    }

    @Test
    fun probesComeSoonerNearTheEndOfAClearance() = runTest {
        val r = Rig(this)
        runCurrent()
        assertEquals(RoyaleApiHealth.PROBE_INTERVAL_MS, r.auto.nextProbeDelayMs())
        r.auto.markPassed()
        assertEquals(RoyaleApiHealth.PROBE_INTERVAL_MS, r.auto.nextProbeDelayMs())
        val passAt = r.auto.state.value.lastPassAt!!
        val soon = passAt + AutoVerifier.DEFAULT_LIFETIME_MS - AutoVerifier.SOON_MARGIN_MS
        assertEquals(2 * 60_000L, r.auto.nextProbeDelayMs(soon - 2 * 60_000L))
        assertEquals(AutoVerifier.FAST_PROBE_MS, r.auto.nextProbeDelayMs(soon - 10_000L))
        assertEquals(AutoVerifier.FAST_PROBE_MS, r.auto.nextProbeDelayMs(soon + 60_000L))
    }

    @Test
    fun theLifeOfAClearanceIsLearnedWhenItRunsOut() = runTest {
        val r = Rig(this)
        r.nextAttempt = ChallengeAttempt(false, "timed out")
        runCurrent()
        r.auto.markPassed()
        advanceTimeBy(20 * 60_000L)
        r.health.reportOk("probe")
        advanceTimeBy(70_000L)
        r.health.reportBlocked("probe")
        runCurrent()
        assertEquals(20 * 60_000L, r.auto.lifetimeMs)
    }

    @Test
    fun backoffDoublesUpToHalfAnHour() {
        assertEquals(
            listOf(3, 6, 12, 24, 30, 30).map { it * 60_000L },
            (1..6).map { AutoVerifier.backoffMs(it) },
        )
    }

    @Test
    fun anOfflineErrorPageDoesNotCountAsPassed() {
        val url = "https://royaleapi.com/player/search/results?q=Macle"
        assertFalse(VerifyCheck.passed("royaleapi.com/player/search/results?q=Macle", url))
        assertFalse(VerifyCheck.passed(url, url))
        assertTrue(VerifyCheck.passed("Search results - RoyaleAPI", url))
    }
}
