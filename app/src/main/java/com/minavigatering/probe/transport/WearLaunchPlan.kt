package com.minavigatering.probe.transport

/**
 * Pure-Kotlin plan for the Wear launch probe (work order W-736).
 *
 * Holds only what the probe is allowed to try and how a single attempt is reported: the fixed
 * candidate URI order, the timing constants, and the result line shape. It performs no launch,
 * touches no Android or Xiaomi API, and keeps no state, so a host JVM test can pin it exactly.
 */
object WearLaunchPlan {

    /** Candidate URIs in fixed probe order; the first one that works wins. */
    val CANDIDATE_URIS: List<String> = listOf(
        "pages/index",
        "/pages/index",
        "/",
        "hap://app/com.minavigatering.probe/pages/index",
        "com.minavigatering.probe",
    )

    /**
     * The one URI the auto-launch glue opens (work order W-745): the first probe candidate,
     * used instead of walking the whole candidate list on every navigation start.
     */
    const val LAUNCH_URI = "pages/index"

    /** Quiet wait before the ack reader is opened, so the launch itself is not counted as an ack. */
    const val PRE_ACK_WAIT_MS = 4_000L

    /** Settle time after a launch task returns before the next attempt is considered. */
    const val POST_LAUNCH_SETTLE_MS = 3_000L

    /** Upper bound for waiting on the wear-side acknowledgement. */
    const val ACK_WAIT_MS = 6_000L

    /** Pause between two candidate attempts, so attempts cannot overlap on the device. */
    const val BETWEEN_ATTEMPTS_MS = 4_000L

    /** Poll interval while waiting for the acknowledgement. */
    const val ACK_POLL_MS = 250L

    /**
     * One launch attempt: which URI, whether the launch task itself succeeded ([taskOk] is null
     * when it never ran), the exception simple name on failure, and the ack latency if any.
     */
    data class WearLaunchAttempt(
        val uri: String,
        val taskOk: Boolean?,
        val failure: String?,
        val ackWithinMs: Long?,
    )

    /**
     * Single evidence line for one attempt. Carries no node id and no exception message body,
     * so the line can be logged and collected without leaking private detail.
     */
    fun formatLine(index: Int, attempt: WearLaunchAttempt): String {
        val head = "#$index uri=${attempt.uri}"
        return when {
            attempt.taskOk == true && attempt.ackWithinMs != null ->
                "$head task=ok ack=yes@${attempt.ackWithinMs}ms"
            attempt.taskOk == true -> "$head task=ok ack=no"
            else -> "$head task=fail:${attempt.failure}"
        }
    }

    /** True only when the launch task succeeded and the wear side actually acknowledged. */
    fun isConfirmed(attempt: WearLaunchAttempt): Boolean =
        attempt.taskOk == true && attempt.ackWithinMs != null
}
