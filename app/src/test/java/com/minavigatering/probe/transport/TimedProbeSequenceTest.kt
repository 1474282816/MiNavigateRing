package com.minavigatering.probe.transport

import org.junit.Assert.*
import org.junit.Test

class TimedProbeSequenceTest {
    @Test fun deadlinesIdentityAndCompletionAreDeterministic() {
        val sequence = TimedProbeSequence(1_000, "runA")
        assertTrue(sequence.due(15_999, 10).isEmpty())
        val turn = sequence.due(16_000, 20).single()
        assertEquals("right", turn.action)
        assertEquals(300, turn.distanceM)
        assertFalse(turn.heartbeat)
        assertTrue(sequence.due(16_000, 21).isEmpty())
        val distance = sequence.due(26_000, 30).single()
        val near = sequence.due(36_000, 40).single()
        assertEquals(turn.actionId, distance.actionId)
        assertEquals(turn.actionId, near.actionId)
        assertEquals(30, near.distanceM)
        assertNotEquals(turn.id, distance.id)
        assertFalse(
            "the fourth frame is still pending, so the sequence is not finished",
            sequence.isFinished(),
        )
        val secondTurn = sequence.due(46_000, 50).single()
        assertEquals("left", secondTurn.action)
        assertNotEquals(turn.actionId, secondTurn.actionId)
        assertTrue(
            "all four frames produced, so the sequence is finished",
            sequence.isFinished(),
        )
        listOf(turn, distance, near, secondTurn).forEach {
            assertTrue(
                "synthetic frame id must carry the probe prefix: ${it.id}",
                it.id.startsWith(TimedProbeSequence.ID_PREFIX),
            )
        }
        assertTrue(sequence.due(Long.MAX_VALUE, 60).isEmpty())
    }

    @Test fun delayedLoopSendsEachDueFrameOnlyOnceAndNewRunHasFreshIds() {
        val sequence = TimedProbeSequence(0, "runA")
        assertFalse("nothing is due before the first delay elapses", sequence.isFinished())
        val frames = sequence.due(45_000, 60)
        assertEquals(4, frames.size)
        assertTrue("all four frames were produced in this round", sequence.isFinished())
        frames.forEach {
            assertTrue(
                "synthetic frame id must carry the probe prefix: ${it.id}",
                it.id.startsWith(TimedProbeSequence.ID_PREFIX),
            )
        }
        assertEquals(4, frames.map { it.id }.distinct().size)
        assertTrue(sequence.due(45_000, 61).isEmpty())
        assertNotEquals(frames.first().actionId,
            TimedProbeSequence(0, "runB").due(15_000, 60).single().actionId)
    }
}
