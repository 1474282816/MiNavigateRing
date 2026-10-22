package com.minavigatering.probe.transport

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage

/** Synthetic, finite device-validation sequence; never a real navigation route. */
class TimedProbeSequence(private val startedElapsedMs: Long, private val runId: String) {
    companion object {
        /** Marks a synthetic regression probe frame; never a real navigation identity. */
        const val ID_PREFIX = "bg-"
    }

    private val delays = longArrayOf(15_000, 25_000, 35_000, 45_000)
    private var next = 0

    @Synchronized
    fun due(elapsedMs: Long, sentAtMs: Long): List<StateMessage> {
        val frames = mutableListOf<StateMessage>()
        while (next < delays.size && elapsedMs - startedElapsedMs >= delays[next]) {
            val step = next++
            frames += StateMessage(
                id = "$ID_PREFIX$runId-${step + 1}", sentAtMs = sentAtMs,
                link = NavProtocol.LINK_CONNECTED, navStatus = NavProtocol.STATUS_ACTIVE,
                action = if (step == 3) "left" else "right",
                actionId = "bg-$runId-${if (step == 3) "left" else "right"}",
                distanceM = intArrayOf(300, 200, 30, 100)[step],
                roadName = "后台合成测试", mode = NavProtocol.MODE_WALK, heartbeat = false,
            )
        }
        return frames
    }

    /** True once all four frames have been produced; nothing else is pending. */
    @Synchronized
    fun isFinished(): Boolean = next >= delays.size
}
