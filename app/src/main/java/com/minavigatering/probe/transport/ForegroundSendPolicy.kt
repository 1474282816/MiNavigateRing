package com.minavigatering.probe.transport

import com.minavigatering.probe.protocol.StateMessage

/**
 * Decides which foreground snapshot may be sent and when (work order W-643).
 *
 * Pure Kotlin on purpose: no `android.*` and no `com.xiaomi.*`, so host JVM tests can drive
 * the concurrency boundary without a device. Rules: only the newest non-heartbeat snapshot
 * matters; a heartbeat is never content; a session restart forces one resend of `latest`
 * without inventing a new turn identity.
 */
class ForegroundSendPolicy {

    companion object {
        /** Minimum gap between two replays of the same `latest` content snapshot. */
        const val CONTENT_INTERVAL_MS = 10_000L
    }

    /** Single slot, last-writer-wins: a concurrent UPDATE overwrites an older pending one. */
    private var pending: StateMessage? = null

    /** A fresh instance must replay once (cold start); [onSessionRestart] marks a restart. */
    private var forceResend: Boolean = true

    private var lastContentSentMs: Long = 0L

    /** Offers the newest decoded snapshot; heartbeats are dropped, others replace pending. */
    @Synchronized
    fun offer(state: StateMessage) {
        if (state.heartbeat) return
        pending = state
    }

    /** Marks the session as (re)started: the next content round resends `latest`. */
    @Synchronized
    fun onSessionRestart() {
        forceResend = true
    }

    /** True only when the node is known and content is waiting, so the node wait is pointless. */
    @Synchronized
    fun shouldSkipNodeWait(nodeKnown: Boolean): Boolean = nodeKnown && pending != null

    /**
     * Takes the next content snapshot, or null when there is nothing to send now.
     *
     * With an unknown node nothing is consumed: pending and the forced resend survive, so the
     * round after the node appears still delivers them.
     */
    @Synchronized
    fun takeContent(nowMs: Long, nodeKnown: Boolean, latest: StateMessage?): StateMessage? {
        if (!nodeKnown) return null

        val offered = pending
        pending = null
        val content = when {
            offered != null -> offered
            forceResend || nowMs - lastContentSentMs >= CONTENT_INTERVAL_MS ->
                latest?.takeIf { !it.heartbeat }
            else -> null
        } ?: return null

        lastContentSentMs = nowMs
        forceResend = false
        return content
    }
}
