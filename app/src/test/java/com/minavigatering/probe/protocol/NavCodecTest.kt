package com.minavigatering.probe.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavCodecTest {

    @Test
    fun `left 300 then left 200 round trip`() {
        val first = StateMessage(
            id = "probe-1",
            sentAtMs = 1_700_000_000_000,
            link = NavProtocol.LINK_CONNECTED,
            navStatus = NavProtocol.STATUS_ACTIVE,
            action = "left",
            exitNumber = null,
            distanceM = 300,
            roadName = "\u5408\u6210\u9053\u8def",
            actionId = "probe-left-1",
            mode = NavProtocol.MODE_WALK,
            heartbeat = false,
        )

        val decodedFirst = NavCodec.decode(NavCodec.encode(first)) as StateMessage
        assertEquals(first, decodedFirst)
        assertEquals("left", decodedFirst.action)
        assertEquals(300, decodedFirst.distanceM)
        assertEquals("probe-left-1", decodedFirst.actionId)
        assertEquals("\u5408\u6210\u9053\u8def", decodedFirst.roadName)

        val second = first.copy(
            id = "probe-2",
            distanceM = 200,
        )
        val decodedSecond = NavCodec.decode(NavCodec.encode(second)) as StateMessage
        assertEquals(second, decodedSecond)
        // Same instruction identity: the distance changed but the action did not.
        assertEquals(decodedFirst.actionId, decodedSecond.actionId)
        assertEquals(200, decodedSecond.distanceM)
    }

    @Test
    fun `unknown action survives round trip and is not straight`() {
        val outgoing = StateMessage(
            id = "probe-3",
            sentAtMs = 1_700_000_000_500,
            link = NavProtocol.LINK_CONNECTED,
            navStatus = NavProtocol.STATUS_ACTIVE,
            action = "mystery",
            distanceM = 150,
            roadName = "\u5408\u6210\u9053\u8def",
            actionId = "probe-mystery-1",
            mode = NavProtocol.MODE_WALK,
        )

        val decoded = NavCodec.decode(NavCodec.encode(outgoing)) as StateMessage
        assertEquals("mystery", decoded.action)
        assertFalse(NavProtocol.isAllowedAction(decoded.action))
        assertTrue(NavProtocol.isAllowedAction("left"))
    }

    @Test
    fun `ack round trip preserves reference`() {
        val ack = AckMessage(
            id = "ack-1",
            refId = "probe-1",
            sentAtMs = 1_700_000_001_000,
        )
        val decoded = NavCodec.decode(NavCodec.encode(ack)) as AckMessage
        assertEquals(ack, decoded)
        assertEquals("probe-1", decoded.refId)
    }

    @Test
    fun `exit number defaults to null and is never inferred`() {
        val state = StateMessage(
            id = "probe-roundabout",
            sentAtMs = 1_700_000_002_000,
            navStatus = NavProtocol.STATUS_ACTIVE,
            action = "roundabout-enter",
            actionId = "probe-roundabout-1",
            mode = NavProtocol.MODE_WALK,
        )
        val decoded = NavCodec.decode(NavCodec.encode(state)) as StateMessage
        assertNull(decoded.exitNumber)
        assertNull(decoded.distanceM)
        assertNull(decoded.roadName)
    }

    @Test
    fun `state field order matches the frozen schema`() {
        val text = NavCodec.encodeToString(
            StateMessage(id = "probe-1", sentAtMs = 1, heartbeat = false)
        )
        val keys = Regex("\"(v|kind|id|sentAtMs|link|navStatus|action|exitNumber|distanceM|roadName|actionId|mode|heartbeat)\":")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            listOf(
                "v", "kind", "id", "sentAtMs", "link", "navStatus", "action",
                "exitNumber", "distanceM", "roadName", "actionId", "mode", "heartbeat",
            ),
            keys,
        )
    }
}
