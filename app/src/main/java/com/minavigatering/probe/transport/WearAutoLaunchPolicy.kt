package com.minavigatering.probe.transport

/**
 * Pure-Kotlin auto-launch policy for the band app (work order W-741).
 *
 * Decides whether a navigation-start signal should open the band page, or why it is skipped.
 * It holds no Android or Xiaomi API, performs no launch and does no I/O, so a host JVM test can
 * pin the decision order exactly. The caller owns the side effect: it launches only on
 * [WearAutoLaunchDecision.Launch] and calls [onNavigationEnded] when navigation ends.
 */
class WearAutoLaunchPolicy {

    private val lock = Any()

    /** Whether this session already produced a launch; cleared by [onNavigationEnded]. */
    private var launchedThisSession = false

    /**
     * Decision for one navigation-start signal. The checks run in a fixed order: setting, bridge,
     * node, page alive, already launched. Only the first fully satisfied call of a session
     * records "launched"; every skip leaves that record untouched.
     */
    fun onNavigationStarted(input: WearAutoLaunchInput): WearAutoLaunchDecision {
        synchronized(lock) {
            if (!input.settingOn) return WearAutoLaunchDecision.Skip(SETTING_OFF)
            if (!input.bridgeOn) return WearAutoLaunchDecision.Skip(BRIDGE_OFF)
            if (!input.nodePresent) return WearAutoLaunchDecision.Skip(NO_NODE)
            if (isPageAnswering(input.ackAgeMs)) return WearAutoLaunchDecision.Skip(PAGE_ALIVE)
            if (launchedThisSession) return WearAutoLaunchDecision.Skip(ALREADY_LAUNCHED)
            launchedThisSession = true
            return WearAutoLaunchDecision.Launch
        }
    }

    /** Navigation ended: the next session may launch again. */
    fun onNavigationEnded() {
        synchronized(lock) {
            launchedThisSession = false
        }
    }

    /** A recent enough ack means the page is already up and answering, so launching is redundant. */
    private fun isPageAnswering(ackAgeMs: Long?): Boolean {
        return ackAgeMs != null && ackAgeMs <= PAGE_ALIVE_WINDOW_MS
    }

    companion object {
        /** The auto-launch setting is off. */
        const val SETTING_OFF: String = "SETTING_OFF"

        /** The phone-to-band bridge is off. */
        const val BRIDGE_OFF: String = "BRIDGE_OFF"

        /** No band node is present. */
        const val NO_NODE: String = "NO_NODE"

        /** The band page is already alive and answering, so no launch is needed. */
        const val PAGE_ALIVE: String = "PAGE_ALIVE"

        /** This session already launched once. */
        const val ALREADY_LAUNCHED: String = "ALREADY_LAUNCHED"

        /** An ack no older than this window counts as "the page is answering". */
        const val PAGE_ALIVE_WINDOW_MS: Long = 20_000L
    }
}

/**
 * Everything the policy needs to decide, all measured before the call.
 *
 * @param settingOn the user's auto-launch setting.
 * @param bridgeOn whether the phone-to-band bridge is currently on.
 * @param nodePresent whether a band node is present.
 * @param ackAgeMs milliseconds since the most recent band ack, or null when there was none.
 */
data class WearAutoLaunchInput(
    val settingOn: Boolean,
    val bridgeOn: Boolean,
    val nodePresent: Boolean,
    val ackAgeMs: Long?,
)

/** Result of one navigation-start signal. */
sealed class WearAutoLaunchDecision {

    /** Launch the band page; the caller performs the side effect. */
    data object Launch : WearAutoLaunchDecision()

    /**
     * Do not launch.
     *
     * @param reason one of [WearAutoLaunchPolicy.SETTING_OFF], [WearAutoLaunchPolicy.BRIDGE_OFF],
     *   [WearAutoLaunchPolicy.NO_NODE], [WearAutoLaunchPolicy.PAGE_ALIVE],
     *   [WearAutoLaunchPolicy.ALREADY_LAUNCHED].
     */
    data class Skip(val reason: String) : WearAutoLaunchDecision()
}
