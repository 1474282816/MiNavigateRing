package com.minavigatering.probe.engine

import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage

/** Reminder kinds. Distance-only updates must stay silent (PROJECT_SPEC.md section 6). */
enum class ReminderKind { TURN, ADVISORY }

data class Reminder(val kind: ReminderKind, val actionId: String?, val action: String?, val distanceM: Int?)

/**
 * Immutable snapshot handed to the UI / transport layer.
 *
 * `link` is the *effective* link state: a report older than [NavProtocol.LINK_TIMEOUT_MS]
 * reads as `connecting` and the stale instruction no longer raises new reminders.
 */
data class NavDisplayState(
    val link: String,
    val navStatus: String,
    val action: String?,
    val distanceM: Int?,
    val roadName: String?,
    val actionId: String?,
    val mode: String?,
    val exitNumber: Int?,
) {
    /** Human-readable status for the probe page, matching PROJECT_SPEC.md section 5. */
    val statusText: String
        get() = when {
            link == NavProtocol.LINK_CONNECTING -> "连接中"
            link == NavProtocol.LINK_DISCONNECTED -> "连接中"
            navStatus == NavProtocol.STATUS_ENDED -> "导航已结束"
            navStatus == NavProtocol.STATUS_UNRECOGNIZED -> "信息暂不可用"
            navStatus == NavProtocol.STATUS_IDLE -> "空闲"
            else -> "导航中"
        }
}

/** Result of feeding one report into the engine. */
data class EngineOutcome(
    val state: NavDisplayState,
    val reminders: List<Reminder>,
)

/**
 * Navigation state machine, reminders and link validity.
 *
 * Pure Kotlin so it can be unit tested without Android or the xms-wearable AAR
 * (see PROJECT_SPEC.md section 4, 导航引擎 responsibility and its boundary).
 */
class NavigationEngine {

    private var lastAcceptedAtMs: Long? = null
    private var navStatus: String = NavProtocol.STATUS_IDLE
    private var action: String? = null
    private var distanceM: Int? = null
    private var roadName: String? = null
    private var actionId: String? = null
    private var mode: String? = NavProtocol.MODE_WALK
    private var exitNumber: Int? = null

    /** Action identities that already produced their turn reminder. */
    private val turnReminded = LinkedHashSet<String>()
    /** Action identities that already produced their approach reminder. */
    private val advisoryReminded = LinkedHashSet<String>()

    /**
     * Feeds a decoded state report.
     *
     * A heartbeat refreshes liveness only: it must not change distance, action, navStatus
     * or emit a reminder (PROJECT_SPEC.md section 7).
     */
    fun accept(message: StateMessage, nowMs: Long): EngineOutcome {
        val stamp = if (message.sentAtMs > 0) message.sentAtMs else nowMs

        // A stale link must not turn an old instruction into a fresh reminder
        // (PROJECT_SPEC.md section 5, 链路断开或超时 row).
        val wasStale = lastAcceptedAtMs?.let { nowMs - it > NavProtocol.LINK_TIMEOUT_MS } ?: false

        if (message.heartbeat) {
            // Liveness only.
            lastAcceptedAtMs = stamp
            return EngineOutcome(displayState(nowMs), emptyList())
        }

        val previousActionId = actionId

        mode = message.mode ?: mode
        if (message.roadName != null) roadName = message.roadName
        exitNumber = message.exitNumber
        navStatus = message.navStatus
        action = message.action
        distanceM = message.distanceM
        actionId = message.actionId

        // Any accepted report proves the link is alive, even when it carries no new
        // instruction. Liveness is what drives the connecting/connected display.
        lastAcceptedAtMs = stamp

        // An unknown direction is an explicit "information unavailable" state. It is never
        // rewritten to `straight` (design/NAVIGATION_ICONS.md).
        if (message.action != null && !NavProtocol.isAllowedAction(message.action)) {
            navStatus = NavProtocol.STATUS_UNRECOGNIZED
            actionId = null
            return EngineOutcome(displayState(nowMs), emptyList())
        }

        when (navStatus) {
            NavProtocol.STATUS_UNRECOGNIZED, NavProtocol.STATUS_IDLE, NavProtocol.STATUS_ENDED -> {
                actionId = message.actionId
                return EngineOutcome(displayState(nowMs), emptyList())
            }
        }

        val id = message.actionId ?: return EngineOutcome(displayState(nowMs), emptyList())
        if (wasStale) {
            // The link just came back; the instruction is old, so refresh the display
            // without replaying its reminders.
            turnReminded += id
            advisoryReminded += id
            return EngineOutcome(displayState(nowMs), emptyList())
        }
        val reminders = ArrayList<Reminder>(2)
        val threshold = thresholdFor(mode)

        val isNewInstruction = id != previousActionId
        if (isNewInstruction) {
            reminders += Reminder(ReminderKind.TURN, id, message.action, message.distanceM)
            turnReminded += id
            // A new instruction that already sits inside the threshold merges the turn and
            // the approach reminder into a single event (PROJECT_SPEC.md section 6).
            if (distanceM != null && distanceM!! <= threshold) {
                advisoryReminded += id
            }
        } else {
            val within = distanceM != null && distanceM!! <= threshold
            // Distance changes alone never remind; only crossing the mode threshold does,
            // and only once per action identity.
            if (within && id !in advisoryReminded) {
                reminders += Reminder(ReminderKind.ADVISORY, id, message.action, message.distanceM)
                advisoryReminded += id
            }
        }
        return EngineOutcome(displayState(nowMs), reminders)
    }

    /** Effective link state at [nowMs]; stale reports read as `connecting`. */
    fun linkAt(nowMs: Long): String {
        val last = lastAcceptedAtMs ?: return NavProtocol.LINK_DISCONNECTED
        return if (nowMs - last > NavProtocol.LINK_TIMEOUT_MS) {
            NavProtocol.LINK_CONNECTING
        } else {
            NavProtocol.LINK_CONNECTED
        }
    }

    fun displayState(nowMs: Long): NavDisplayState = NavDisplayState(
        link = linkAt(nowMs),
        navStatus = navStatus,
        action = action,
        distanceM = distanceM,
        roadName = roadName,
        actionId = actionId,
        mode = mode,
        exitNumber = exitNumber,
    )

    private fun thresholdFor(mode: String?): Int = when (mode) {
        NavProtocol.MODE_CYCLE -> NavProtocol.CYCLE_THRESHOLD_M
        else -> NavProtocol.WALK_THRESHOLD_M
    }
}
