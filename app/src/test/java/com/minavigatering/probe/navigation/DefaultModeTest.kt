package com.minavigatering.probe.navigation

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locked behaviour: cycling is the shipped default. A user who picked walking keeps walking,
 * and a corrupted or missing stored value must not silently become walking again.
 */
class DefaultModeTest {

    private fun state(mode: String?) = StateMessage(id = "x", sentAtMs = 1L, mode = mode)

    @Test
    fun defaultModeIsCycling() {
        assertEquals(NavProtocol.MODE_CYCLE, NavProtocol.DEFAULT_MODE)
        assertEquals(NavProtocol.DEFAULT_MODE, NavigationSettings.DEFAULT_MODE)
    }

    @Test
    fun coldStartRecoveryKeepsWalkingOnlyWhenItWasExplicitlyWalking() {
        assertEquals(NavProtocol.MODE_WALK, strippedRecovery(state(NavProtocol.MODE_WALK), 2L, NavProtocol.MODE_WALK).mode)
        assertEquals(NavProtocol.MODE_CYCLE, strippedRecovery(state(NavProtocol.MODE_CYCLE), 2L, NavProtocol.MODE_CYCLE).mode)
        // Unknown / empty stored value falls back to the default instead of walking.
        assertEquals(NavProtocol.MODE_CYCLE, strippedRecovery(state(null), 2L, "").mode)
        assertEquals(NavProtocol.MODE_CYCLE, strippedRecovery(state(null), 2L, "scooter").mode)
    }

    @Test
    fun recoveryStripsEveryPositionFieldBeforeSending() {
        val restored = strippedRecovery(
            state(NavProtocol.MODE_WALK).copy(
                navStatus = NavProtocol.STATUS_ACTIVE,
                action = "left",
                distanceM = 40,
                roadName = "中关村大街",
                actionId = "amap-turn-1",
                exitNumber = 2,
            ),
            7L,
            NavProtocol.MODE_WALK,
        )
        assertEquals(NavProtocol.STATUS_UNRECOGNIZED, restored.navStatus)
        assertEquals(null, restored.action)
        assertEquals(null, restored.distanceM)
        assertEquals(null, restored.roadName)
        assertEquals(null, restored.actionId)
        assertEquals(null, restored.exitNumber)
        assertEquals(false, restored.heartbeat)
        assertEquals(NavigationSettings.RESTORE_ID_PREFIX + 7L, restored.id)
    }
}
