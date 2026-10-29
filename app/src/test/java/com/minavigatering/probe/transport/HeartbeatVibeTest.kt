package com.minavigatering.probe.transport

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A vibration request is one-shot. The heartbeat is built by `copy`, so without an explicit
 * clear the band would repeat the pattern every 5 s until the next real navigation frame.
 */
class HeartbeatVibeTest {

    private val vibrationFrame = StateMessage(
        id = "vibe-short-1",
        sentAtMs = 1_000,
        navStatus = NavProtocol.STATUS_IDLE,
        mode = NavProtocol.DEFAULT_MODE,
        vibe = NavProtocol.VIBE_SHORT,
    )

    @Test
    fun heartbeatDropsTheVibrationRequest() {
        val heartbeat = heartbeatOf(vibrationFrame, 5_000)
        assertNull(heartbeat.vibe)
        assertEquals(true, heartbeat.heartbeat)
        assertEquals("hb-5000", heartbeat.id)
        assertEquals(5_000L, heartbeat.sentAtMs)
    }

    @Test
    fun heartbeatKeepsEverythingElseItInherited() {
        val heartbeat = heartbeatOf(vibrationFrame, 5_000)
        assertEquals(NavProtocol.STATUS_IDLE, heartbeat.navStatus)
        assertEquals(NavProtocol.DEFAULT_MODE, heartbeat.mode)
        assertEquals(NavProtocol.LINK_CONNECTED, heartbeat.link)
    }

    @Test
    fun aNavigationFrameWithoutVibrationHeartbeatsUnchanged() {
        val navigation = StateMessage(
            id = "amap-1",
            sentAtMs = 1_000,
            navStatus = NavProtocol.STATUS_ACTIVE,
            action = "left",
            distanceM = 50,
            actionId = "amap-turn-1",
            mode = NavProtocol.DEFAULT_MODE,
        )
        val heartbeat = heartbeatOf(navigation, 6_000)
        assertNull(heartbeat.vibe)
        assertEquals("left", heartbeat.action)
        assertEquals(50, heartbeat.distanceM)
    }
}
