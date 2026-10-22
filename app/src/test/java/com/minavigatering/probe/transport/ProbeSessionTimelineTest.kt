package com.minavigatering.probe.transport

import com.minavigatering.probe.protocol.AckMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM test for the send/ack round trip recorded by [ProbeSession] (work order W-707).
 *
 * The clocks are test-owned, so the measured round trip is exact and no device or SDK is
 * involved. The inbound messages here are synthetic fixtures, **not** real watch receipts:
 * the test only proves the local bookkeeping of the timeline.
 *
 * The single `@Test` runs the private scenes below in their original order, so no function
 * grows past the 40 line budget while every assertion of the original scenario survives.
 */
class ProbeSessionTimelineTest {

    private val wall = 1_700_000_000_000L

    /** Test-owned monotonic clock; the scenes below move it forward by assignment. */
    private var elapsed = 1_000L

    @Test
    fun `sent instruction ack records monotonic rtt, unknown and unsent ids do not`() {
        val timeline = RuntimeTimeline()
        val session = ProbeSession(timeline, { wall }, { elapsed })
        assertRememberAloneRecordsNoRoundTrip(timeline, session)
        assertSentAckRecordsMonotonicRtt(timeline, session)
        assertRepeatAndUnknownAcksAreNotCounted(timeline, session)
        assertResendMeasuresTheNewestFrame(timeline, session)
        assertTaskOutcomesAreDeIdentified(timeline, session)
    }

    private fun assertRememberAloneRecordsNoRoundTrip(timeline: RuntimeTimeline, session: ProbeSession) {
        // Only a real send contributes a round trip: remembering alone must not. The return
        // value keeps the old rule (a remembered id is recognised), only the counters stay put.
        session.rememberInstruction("probe-1")
        assertTrue(session.onInbound(AckMessage(id = "ack-0", refId = "probe-1", sentAtMs = wall)))
        assertEquals(TimelineWindowCounts(0, 0, 0), timeline.statistics(elapsed).window)

        session.onQueued("probe-1", "node-1")
        assertEquals("one frame queued", 1, timeline.statistics(elapsed).window.framesSent)
    }

    private fun assertSentAckRecordsMonotonicRtt(timeline: RuntimeTimeline, session: ProbeSession) {
        elapsed = 1_300L
        assertTrue(session.onInbound(AckMessage(id = "ack-1", refId = "probe-1", sentAtMs = wall)))
        val afterAck = timeline.statistics(elapsed)
        assertEquals(1, afterAck.window.acks)
        assertEquals("rtt must be monotonic now - sentAt", 300L, afterAck.ackRtt.latestMs)
        assertEquals(300L, afterAck.ackRtt.averageMs)
        assertEquals("a task success is not a receipt, so still one frame", 1, afterAck.window.framesSent)
    }

    private fun assertRepeatAndUnknownAcksAreNotCounted(timeline: RuntimeTimeline, session: ProbeSession) {
        // A repeat ack of the same id keeps the receipt semantics but is not counted twice.
        assertTrue(session.onInbound(AckMessage(id = "ack-1b", refId = "probe-1", sentAtMs = wall)))
        assertEquals("duplicate ack must not double count", 1, timeline.statistics(elapsed).window.acks)

        // An unknown refId is refused outright and adds nothing.
        assertFalse(session.onInbound(AckMessage(id = "ack-2", refId = "unknown-9", sentAtMs = wall)))
        // A remembered but never sent id is equally excluded (see probe-1 above).
        session.rememberInstruction("probe-2")
        assertTrue("the receipt text still moves for a remembered id", session.onInbound(AckMessage(id = "ack-3", refId = "probe-2", sentAtMs = wall)))
        assertEquals("unsent id must not be counted", 1, timeline.statistics(elapsed).window.acks)
    }

    private fun assertResendMeasuresTheNewestFrame(timeline: RuntimeTimeline, session: ProbeSession) {
        // A resend refreshes the send stamp, so the next ack measures the newest frame.
        elapsed = 5_000L
        session.onQueued("probe-1", "node-1")
        elapsed = 5_500L
        session.onInbound(AckMessage(id = "ack-4", refId = "probe-1", sentAtMs = wall))
        val resent = timeline.statistics(elapsed)
        assertEquals("frames: one per queued send attempt", 2, resent.window.framesSent)
        assertEquals("each accepted ack is one arrival", 2, resent.window.acks)
        assertEquals("rtt follows the refreshed send stamp", 500L, resent.ackRtt.latestMs)
        assertEquals((300L + 500L) / 2, resent.ackRtt.averageMs)
    }

    private fun assertTaskOutcomesAreDeIdentified(timeline: RuntimeTimeline, session: ProbeSession) {
        // Task outcomes are recorded de-identified: short id plus a whitelisted category.
        elapsed = 6_000L
        session.onTaskSuccess("synthetic-message-9f8e7d")
        session.onTaskFailure("synthetic-message-9f8e7d", "sendMessage failed: DeviceDisconnectedException happened")
        val details = timeline.snapshot(2).map { it.detail }
        assertTrue("task detail: $details", details.none { it.contains("DeviceDisconnectedException") })
        assertTrue("task detail: $details", details.any { it.contains("reason=disconnected") })
        assertTrue("short id only: $details", details.all { it.contains("id=9f8e7d") })
        assertTrue("raw id must not leak: $details", details.none { it.contains("synthetic-message-9f8e7d") })
    }
}
