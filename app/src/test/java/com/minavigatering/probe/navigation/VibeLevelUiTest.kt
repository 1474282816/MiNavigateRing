package com.minavigatering.probe.navigation

import com.minavigatering.probe.protocol.NavProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reminder-strength segment must always name one of the three strengths the band can play.
 *
 * The labels are the only thing the user sees, so an out-of-range stored level must still
 * render as a real strength (强) instead of an empty chip or a raw number.
 */
class VibeLevelUiTest {

    @Test
    fun theThreeLegalLevelsMapToTheirLabels() {
        assertEquals("标准", vibeLabelOf(NavProtocol.VIBE_LEVEL_STANDARD))
        assertEquals("强", vibeLabelOf(NavProtocol.VIBE_LEVEL_STRONG))
        assertEquals("最强", vibeLabelOf(NavProtocol.VIBE_LEVEL_MAX))
    }

    @Test
    fun anIllegalLevelStillRendersAsARealStrength() {
        assertEquals("强", vibeLabelOf(-1))
        assertEquals("强", vibeLabelOf(9))
    }

    @Test
    fun theModelExposesTheSelectedStrengthForEveryPhase() {
        // No navigation yet (WAITING) and an active turn must both carry the label: the segment
        // is rendered in both phases, so a null label would blank it mid-route.
        val waiting = mapNavigationUi(
            listenerAccessGranted = true,
            bridgeEnabled = true,
            sessionRunning = true,
            bandNodePresent = true,
            selectedMode = NavProtocol.MODE_CYCLE,
            latest = null,
            vibeLevel = NavProtocol.VIBE_LEVEL_MAX,
        )
        assertEquals("最强", waiting.vibeLabel)

        val active = mapNavigationUi(
            listenerAccessGranted = true,
            bridgeEnabled = true,
            sessionRunning = true,
            bandNodePresent = true,
            selectedMode = NavProtocol.MODE_CYCLE,
            latest = com.minavigatering.probe.protocol.StateMessage(
                id = "amap-1",
                sentAtMs = 1,
                navStatus = NavProtocol.STATUS_ACTIVE,
                action = "left",
                distanceM = 40,
                actionId = "a1",
            ),
            vibeLevel = NavProtocol.VIBE_LEVEL_STANDARD,
        )
        assertEquals("标准", active.vibeLabel)
    }
}
