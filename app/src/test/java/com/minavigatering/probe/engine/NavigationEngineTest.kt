package com.minavigatering.probe.engine

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {

    private fun active(
        actionId: String,
        distanceM: Int?,
        action: String = "left",
        mode: String = NavProtocol.MODE_WALK,
        sentAtMs: Long = 1_000,
    ) = StateMessage(
        id = "msg-$actionId-$distanceM",
        sentAtMs = sentAtMs,
        link = NavProtocol.LINK_CONNECTED,
        navStatus = NavProtocol.STATUS_ACTIVE,
        action = action,
        distanceM = distanceM,
        roadName = "\u5408\u6210\u9053\u8def",
        actionId = actionId,
        mode = mode,
    )

    @Test
    fun `unknown action becomes unrecognized and never straight`() {
        val engine = NavigationEngine()
        val outcome = engine.accept(
            active(actionId = "probe-mystery-1", distanceM = 150, action = "mystery"),
            nowMs = 1_000,
        )

        assertEquals(NavProtocol.STATUS_UNRECOGNIZED, outcome.state.navStatus)
        assertEquals("信息暂不可用", outcome.state.statusText)
        assertFalse(outcome.state.action == "straight")
        assertEquals("mystery", outcome.state.action)
        assertTrue(outcome.reminders.isEmpty())
    }

    @Test
    fun `heartbeat does not change distance action or status`() {
        val engine = NavigationEngine()
        engine.accept(active(actionId = "probe-left-1", distanceM = 300), nowMs = 1_000)

        val before = engine.displayState(nowMs = 1_500)
        val heartbeat = active(actionId = "probe-left-1", distanceM = 300, sentAtMs = 1_400).copy(
            id = "hb-1400",
            heartbeat = true,
            distanceM = 250,
            action = "right",
            mode = NavProtocol.MODE_CYCLE,
        )
        val outcome = engine.accept(heartbeat, nowMs = 1_500)
        val after = outcome.state

        assertEquals(300, before.distanceM)
        assertEquals(300, after.distanceM)
        assertEquals("left", after.action)
        assertEquals(NavProtocol.STATUS_ACTIVE, after.navStatus)
        assertTrue(outcome.reminders.isEmpty())
    }

    @Test
    fun `link turns connecting after 15000ms without a report`() {
        val engine = NavigationEngine()
        engine.accept(active(actionId = "probe-left-1", distanceM = 300), nowMs = 1_000)

        assertEquals(NavProtocol.LINK_CONNECTED, engine.linkAt(nowMs = 1_000 + NavProtocol.LINK_TIMEOUT_MS))
        assertEquals(NavProtocol.LINK_CONNECTING, engine.linkAt(nowMs = 1_000 + NavProtocol.LINK_TIMEOUT_MS + 1))
        assertEquals("连接中", engine.displayState(nowMs = 1_000 + NavProtocol.LINK_TIMEOUT_MS + 1).statusText)
    }

    @Test
    fun `no new reminder is produced from stale instructions`() {
        val engine = NavigationEngine()
        engine.accept(active(actionId = "probe-left-1", distanceM = 300), nowMs = 1_000)

        // 20s later the link is stale; a distance update must not be treated as live.
        val stale = engine.displayState(nowMs = 21_000)
        assertEquals(NavProtocol.LINK_CONNECTING, stale.link)
        assertTrue(engine.accept(active(actionId = "probe-left-1", distanceM = 20), nowMs = 21_000).reminders.isEmpty())
    }

    @Test
    fun `new instruction reminds once and distance-only updates stay silent`() {
        val engine = NavigationEngine()

        val first = engine.accept(active(actionId = "probe-left-1", distanceM = 300), nowMs = 1_000)
        assertEquals(listOf(ReminderKind.TURN), first.reminders.map { it.kind })

        val second = engine.accept(active(actionId = "probe-left-1", distanceM = 200), nowMs = 2_000)
        assertTrue(second.reminders.isEmpty())

        val third = engine.accept(active(actionId = "probe-left-1", distanceM = 200), nowMs = 3_000)
        assertTrue(third.reminders.isEmpty())
    }

    @Test
    fun `walk advisory fires once at 30 metres`() {
        val engine = NavigationEngine()
        engine.accept(active(actionId = "probe-left-1", distanceM = 300), nowMs = 1_000)

        val approaching = engine.accept(active(actionId = "probe-left-1", distanceM = 30), nowMs = 2_000)
        assertEquals(listOf(ReminderKind.ADVISORY), approaching.reminders.map { it.kind })

        val again = engine.accept(active(actionId = "probe-left-1", distanceM = 20), nowMs = 3_000)
        assertTrue(again.reminders.isEmpty())
    }

    @Test
    fun `cycle threshold is 100 metres and a new in-range instruction merges reminders`() {
        val engine = NavigationEngine()
        val outcome = engine.accept(
            active(actionId = "probe-left-2", distanceM = 90, mode = NavProtocol.MODE_CYCLE),
            nowMs = 1_000,
        )
        // First appearance already inside the threshold: exactly one merged reminder.
        assertEquals(listOf(ReminderKind.TURN), outcome.reminders.map { it.kind })

        val followUp = engine.accept(
            active(actionId = "probe-left-2", distanceM = 50, mode = NavProtocol.MODE_CYCLE),
            nowMs = 2_000,
        )
        assertTrue(followUp.reminders.isEmpty())
    }

    @Test
    fun `ended and idle never carry a fabricated manoeuvre`() {
        val engine = NavigationEngine()
        engine.accept(active(actionId = "probe-left-1", distanceM = 300), nowMs = 1_000)

        val ended = engine.accept(
            StateMessage(
                id = "probe-ended",
                sentAtMs = 2_000,
                navStatus = NavProtocol.STATUS_ENDED,
                actionId = null,
                action = null,
                distanceM = null,
                mode = NavProtocol.MODE_WALK,
            ),
            nowMs = 2_000,
        )
        assertEquals("导航已结束", ended.state.statusText)
        assertTrue(ended.reminders.isEmpty())

        val idle = engine.accept(
            StateMessage(
                id = "probe-idle",
                sentAtMs = 3_000,
                navStatus = NavProtocol.STATUS_IDLE,
                actionId = null,
                action = null,
                distanceM = null,
                mode = NavProtocol.MODE_WALK,
            ),
            nowMs = 3_000,
        )
        assertEquals("空闲", idle.state.statusText)
        assertNull(idle.state.action)
        assertNull(idle.state.distanceM)
        assertTrue(idle.reminders.isEmpty())
    }
}
