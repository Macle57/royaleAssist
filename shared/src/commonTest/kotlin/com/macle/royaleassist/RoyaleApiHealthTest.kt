package com.macle.royaleassist

import com.macle.royaleassist.lookup.CloudflareBlockedException
import com.macle.royaleassist.lookup.HttpStatusException
import com.macle.royaleassist.lookup.RoyaleApiHealth
import com.macle.royaleassist.lookup.RoyaleApiStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The "RoyaleAPI needs verification" warning: which reports and probes turn it on and off. */
class RoyaleApiHealthTest {
    private var now = 1_000_000L
    private val health = RoyaleApiHealth { now }
    private val status get() = health.state.value.status

    @Test
    fun startsUnknownWithoutWarning() {
        assertEquals(RoyaleApiStatus.Unknown, status)
        assertFalse(health.state.value.needsVerification)
    }

    @Test
    fun challengeWarnsAndAPageThroughClearsIt() {
        health.reportBlocked("lookup")
        assertEquals(RoyaleApiStatus.NeedsVerification, status)
        assertTrue(health.state.value.needsVerification)
        now += 10
        health.reportOk("verifier")
        assertEquals(RoyaleApiStatus.Verified, status)
        now += 10
        health.reportBlocked("cookie expired")
        assertEquals(RoyaleApiStatus.NeedsVerification, status)
    }

    @Test
    fun aRequestStartedBeforeTheVerifierPassedIsIgnored() {
        val slowStart = now
        now += 5_000
        health.reportOk("verifier")
        health.reportBlocked("slow lookup", startedAt = slowStart)
        assertEquals(RoyaleApiStatus.Verified, status)
        assertEquals("verifier", health.state.value.source)
    }

    @Test
    fun probeChallengeWarns() = runTest {
        var asked: String? = null
        val s = health.probe({ url -> asked = url; throw CloudflareBlockedException(url) })
        assertEquals(RoyaleApiStatus.NeedsVerification, s)
        assertEquals(RoyaleApiHealth.PROBE_URL, asked)
    }

    @Test
    fun probePageClears() = runTest {
        health.reportBlocked("lookup")
        now += RoyaleApiHealth.MIN_PROBE_GAP_MS
        assertEquals(RoyaleApiStatus.Verified, health.probe({ "<html>results</html>" }))
    }

    @Test
    fun probeOtherFailuresChangeNothing() = runTest {
        assertEquals(RoyaleApiStatus.Unknown, health.probe({ throw IllegalStateException("offline") }))
        health.reportBlocked("lookup")
        now += RoyaleApiHealth.MIN_PROBE_GAP_MS
        assertEquals(RoyaleApiStatus.NeedsVerification, health.probe({ throw HttpStatusException(500, "oops") }))
    }

    @Test
    fun probeIsSkippedRightAfterAReportUnlessForced() = runTest {
        health.reportBlocked("lookup")
        var calls = 0
        now += 1_000
        assertEquals(RoyaleApiStatus.NeedsVerification, health.probe({ calls++; "page" }))
        assertEquals(0, calls)
        assertEquals(RoyaleApiStatus.Verified, health.probe({ calls++; "page" }, force = true))
        assertEquals(1, calls)
        now += RoyaleApiHealth.MIN_PROBE_GAP_MS
        health.probe({ calls++; throw CloudflareBlockedException(it) })
        assertEquals(2, calls)
        assertEquals(RoyaleApiStatus.NeedsVerification, status)
    }
}
