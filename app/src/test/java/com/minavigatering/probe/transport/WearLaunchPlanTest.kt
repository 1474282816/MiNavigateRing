package com.minavigatering.probe.transport

import com.minavigatering.probe.transport.WearLaunchPlan.WearLaunchAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM test for the Wear launch plan (work order W-736).
 *
 * Both attempts below are built in this file: they are not device readings and prove nothing
 * about a real Wear launch. Only the fixed candidate order and the result-line shape are pinned.
 */
class WearLaunchPlanTest {

    @Test
    fun `the candidate list is exactly the five fixed URIs in order, with no blank or duplicate`() {
        val uris = WearLaunchPlan.CANDIDATE_URIS
        assertEquals(
            "the probe order is frozen; changing it changes what the probe tries first",
            listOf(
                "pages/index",
                "/pages/index",
                "/",
                "hap://app/com.minavigatering.probe/pages/index",
                "com.minavigatering.probe",
            ),
            uris,
        )
        assertTrue("no candidate may be blank", uris.none { it.isBlank() })
        assertEquals("no candidate may repeat", uris.size, uris.toSet().size)
    }

    @Test
    fun `formatLine keeps the fixed line shape and isConfirmed needs both task ok and an ack`() {
        val index = 2
        val uri = "/pages/index"

        val confirmed = WearLaunchAttempt(uri = uri, taskOk = true, failure = null, ackWithinMs = 2_310L)
        assertEquals("#2 uri=/pages/index task=ok ack=yes@2310ms", WearLaunchPlan.formatLine(index, confirmed))
        assertTrue("a task ok plus a real ack is a confirmation", WearLaunchPlan.isConfirmed(confirmed))

        val noAck = WearLaunchAttempt(uri = uri, taskOk = true, failure = null, ackWithinMs = null)
        assertEquals("#2 uri=/pages/index task=ok ack=no", WearLaunchPlan.formatLine(index, noAck))
        assertFalse("a launch without an ack is not a confirmation", WearLaunchPlan.isConfirmed(noAck))

        val failed = WearLaunchAttempt(uri = uri, taskOk = false, failure = "StatusException", ackWithinMs = null)
        assertEquals("#2 uri=/pages/index task=fail:StatusException", WearLaunchPlan.formatLine(index, failed))
        assertFalse("a failed task is not a confirmation", WearLaunchPlan.isConfirmed(failed))

        val line = WearLaunchPlan.formatLine(index, failed)
        assertTrue("the failure line ends at the exception simple name, never the message body", line.endsWith("task=fail:StatusException"))
        assertFalse("the line carries no node id", line.contains("node"))
    }
}
