package com.minavigatering.probe.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `vibe` field is the diagnosis screen's one-shot vibration request.
 *
 * Two things must hold: only whitelisted patterns survive a round trip (the band never has to
 * guess), and the field never leaks into a heartbeat (otherwise the band would buzz every 5 s
 * after a single test press).
 */
class VibeProtocolTest {

    @Test
    fun everyAllowedPatternSurvivesARoundTrip() {
        for (pattern in NavProtocol.ALLOWED_VIBES) {
            val frame = StateMessage(
                id = "vibe-$pattern-1",
                sentAtMs = 1_700_000_000_000,
                navStatus = NavProtocol.STATUS_IDLE,
                mode = NavProtocol.DEFAULT_MODE,
                vibe = pattern,
            )
            val decoded = NavCodec.decode(NavCodec.encode(frame)) as StateMessage
            assertEquals(pattern, decoded.vibe)
            assertEquals(frame, decoded)
        }
    }

    @Test
    fun normalFramesCarryNoVibrationRequest() {
        val frame = StateMessage(
            id = "amap-1",
            sentAtMs = 1,
            navStatus = NavProtocol.STATUS_ACTIVE,
            action = "left",
            distanceM = 50,
            actionId = "amap-turn-1",
            mode = NavProtocol.DEFAULT_MODE,
        )
        assertNull(frame.vibe)
        val decoded = NavCodec.decode(NavCodec.encode(frame)) as StateMessage
        assertNull(decoded.vibe)
    }

    @Test
    fun unknownPatternIsDroppedOnDecode() {
        val text = """{"v":1,"kind":"state","id":"vibe-x","sentAtMs":1,"vibe":"mega"}"""
        val decoded = NavCodec.decodeFromString(text) as StateMessage
        assertNull(decoded.vibe)
    }

    @Test
    fun emptyPatternIsDroppedOnDecode() {
        val text = """{"v":1,"kind":"state","id":"vibe-y","sentAtMs":1,"vibe":""}"""
        val decoded = NavCodec.decodeFromString(text) as StateMessage
        assertNull(decoded.vibe)
    }
}

/**
 * The `vibeLevel` field carries the user's reminder strength (0 标准 / 1 强 / 2 最强).
 *
 * The wire contract: only the three documented levels survive, an unknown level decodes to
 * null ("band keeps what it has") and never to 0, and the level rides along with the normal
 * navigation frame so the band learns it from the next heartbeat after a reinstall.
 */
class VibeLevelProtocolTest {

    @Test
    fun everyAllowedLevelSurvivesARoundTrip() {
        for (level in NavProtocol.ALLOWED_VIBE_LEVELS) {
            val frame = StateMessage(
                id = "amap-$level",
                sentAtMs = 1_700_000_000_000,
                navStatus = NavProtocol.STATUS_ACTIVE,
                action = "left",
                distanceM = 80,
                mode = NavProtocol.DEFAULT_MODE,
                vibeLevel = level,
            )
            val decoded = NavCodec.decode(NavCodec.encode(frame)) as StateMessage
            assertEquals(level, decoded.vibeLevel)
        }
    }

    @Test
    fun levelZeroIsAValueNotAnAbsentField() {
        val frame = StateMessage(
            id = "amap-z",
            sentAtMs = 1,
            navStatus = NavProtocol.STATUS_ACTIVE,
            action = "left",
            vibeLevel = 0,
        )
        val text = NavCodec.encodeToString(frame)
        assertEquals(true, text.contains("\"vibeLevel\":0"))
        assertEquals(0, (NavCodec.decodeFromString(text) as StateMessage).vibeLevel)
    }

    @Test
    fun unknownLevelDecodesToNullSoTheBandKeepsItsCurrentStrength() {
        val text = """{"v":1,"kind":"state","id":"amap-x","sentAtMs":1,"vibeLevel":9}"""
        assertNull((NavCodec.decodeFromString(text) as StateMessage).vibeLevel)
    }

    @Test
    fun aLegalLevelInAStringFieldIsStillAccepted() {
        // Json.asInt accepts a numeric string; the watch side must not lose a level typed as text.
        val text = """{"v":1,"kind":"state","id":"amap-s","sentAtMs":1,"vibeLevel":"2"}"""
        assertEquals(2, (NavCodec.decodeFromString(text) as StateMessage).vibeLevel)
    }

    @Test
    fun normalizeFallsBackToTheStrongDefaultForAnythingIllegal() {
        assertEquals(NavProtocol.VIBE_LEVEL_STRONG, NavProtocol.normalizeVibeLevel(null))
        assertEquals(NavProtocol.VIBE_LEVEL_STRONG, NavProtocol.normalizeVibeLevel(-1))
        assertEquals(NavProtocol.VIBE_LEVEL_STRONG, NavProtocol.normalizeVibeLevel(3))
        assertEquals(NavProtocol.VIBE_LEVEL_STANDARD, NavProtocol.normalizeVibeLevel(0))
        assertEquals(NavProtocol.VIBE_LEVEL_MAX, NavProtocol.normalizeVibeLevel(2))
    }

    @Test
    fun aHeartbeatKeepsTheLevelButDropsTheOneShotPattern() {
        val state = StateMessage(
            id = "amap-hb",
            sentAtMs = 1,
            navStatus = NavProtocol.STATUS_ACTIVE,
            action = "right",
            vibe = NavProtocol.VIBE_SHORT,
            vibeLevel = NavProtocol.VIBE_LEVEL_MAX,
        )
        val beat = com.minavigatering.probe.transport.heartbeatOf(state, 2_000)
        assertNull(beat.vibe)
        assertEquals(NavProtocol.VIBE_LEVEL_MAX, beat.vibeLevel)
    }
}
