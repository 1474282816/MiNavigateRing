package com.minavigatering.probe.transport

import com.minavigatering.probe.protocol.AckMessage
import com.minavigatering.probe.protocol.Message
import com.minavigatering.probe.protocol.StateMessage

/** Nanoseconds per millisecond; [ProbeSession]'s default monotonic clock counts nanoseconds. */
private const val NANOS_PER_MILLISECOND = 1_000_000L

/** Intent actions the probe page sends to [ProbeForegroundService]. */
object ProbeActions {
    const val START = "com.minavigatering.probe.action.START"
    const val STOP = "com.minavigatering.probe.action.STOP"
    const val UPDATE = "com.minavigatering.probe.action.UPDATE"
}

/** What [ProbeForegroundService] has to do after handling one start command. */
enum class ServiceCommandResult { KeepRunning, Stop }

/**
 * Session state of the phone probe.
 *
 * The three delivery levels are kept strictly apart (work order W-618):
 * 1. the local channel queued the payload ([onQueued]),
 * 2. the SDK `Task` completed ([onTaskSuccess]) — still *not* a watch acknowledgement,
 * 3. the watch itself answered with an [AckMessage] for a known instruction ([onInbound]).
 *
 * Deliberately pure Kotlin: no `android.*` and no `com.xiaomi.*` import, so the host JVM
 * tests can drive every state boundary without a device. See PROJECT_SPEC.md section 7
 * (心跳证明通信仍可用，不证明手环收到指令).
 */
class ProbeSession(
    private val timeline: RuntimeTimeline = RuntimeTimeline.shared,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val elapsedClock: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {

    companion object {
        const val NO_SEND_YET = "（尚未发送）"

        /** Upper bound of remembered instruction ids; the oldest one is evicted first. */
        const val MAX_REMEMBERED_INSTRUCTIONS = 256

        /** Category used for a Task failure whose SDK message matches no known class. */
        const val TASK_REASON_GENERIC = "generic"
        const val NO_ACK_YET = "（尚未收到回执）"

        /** Placeholder of a diagnostic line that has not been probed yet (W-625). */
        const val NOT_PROBED = "（尚未探测）"

        /** Text used when no node id is known, so the SDK is deliberately not called. */
        const val NO_NODE_CHECK_TEXT = "无节点，未调用 checkPermission"

        /** Text used when no node id is known, so the SDK is deliberately not called. */
        const val NO_NODE_REQUEST_TEXT = "无节点，未调用 requestPermission"

        /**
         * Matches anything credential shaped. It is case insensitive on purpose: the SDK
         * may spell the same secret `AuthKey` or `authkey`.
         */
        private val SENSITIVE = Regex("(?i)authkey|account|token|secret")
    }

    private val knownInstructionIds = LinkedHashSet<String>()

    /** Monotonic send stamp per registered instruction; refreshed on every resend. */
    private val sentElapsedMs = HashMap<String, Long>()

    /** Instructions whose ack was already counted, so a repeat ack is not counted twice. */
    private val countedAckSends = HashMap<String, Long>()
    /**
     * Ids of non-heartbeat reports only. Heartbeat ids never land here, so a heartbeat
     * ack can never be mistaken for an instruction ack.
     */
    val instructionIds: Set<String>
        get() = knownInstructionIds

    @Volatile
    var latestState: StateMessage? = null
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastSendText: String = NO_SEND_YET

    @Volatile
    var lastAckText: String = NO_ACK_YET

    /**
     * Diagnostic lines of the phone channel (work order W-625).
     *
     * They make host service, node, permission and the last SDK error visible even when
     * no navigation instruction is sent. They are **not** a watch receipt: none of the
     * setters below touches [lastAckText], so only [onInbound] can update a receipt.
     */
    @Volatile
    var hostText: String = NOT_PROBED
        private set

    @Volatile
    var nodeText: String = NOT_PROBED
        private set

    @Volatile
    var permissionText: String = NOT_PROBED
        private set

    @Volatile
    var sdkErrorText: String = NOT_PROBED
        private set

    /** Records the host-service diagnostic line; never touches [lastAckText]. */
    fun onHostStatus(text: String) {
        hostText = redactDiagnostic(text)
    }

    /** Records the node diagnostic line; never touches [lastAckText]. */
    fun onNodeStatus(text: String) {
        nodeText = redactDiagnostic(text)
    }

    /** Records the permission diagnostic line; never touches [lastAckText]. */
    fun onPermissionStatus(text: String) {
        permissionText = redactDiagnostic(text)
    }

    /**
     * Records the most recent SDK failure; never touches [lastAckText].
     *
     * [simpleName] is the SDK exception class as reported by the caller, [message] is
     * redacted before it is shown.
     */
    fun onSdkError(simpleName: String, message: String?) {
        sdkErrorText = describeSdkFailure(simpleName, message)
    }

    /**
     * Removes credential-looking text so no diagnostic line can leak a secret.
     *
     * Any occurrence of `authkey`, `account`, `token` or `secret` (case insensitive) is
     * replaced by `[redacted]`.
     */
    fun redactDiagnostic(text: String?): String {
        if (text == null) return ""
        return SENSITIVE.replace(text, "[redacted]")
    }

    /**
     * Builds the "most recent SDK error" line.
     *
     * The SDK exception **class name is kept verbatim**: `PermissionDeniedException`,
     * `DeviceDisconnectedException`, `AppNotInstalledException` and
     * `SignatureVerifyFailedException` all extend `Exception` and carry only a `String`
     * constructor, so no error code exists to show and none may be invented here.
     * A blank message still keeps the class name.
     */
    fun describeSdkFailure(simpleName: String, message: String?): String {
        val name = simpleName.ifBlank { "未知异常" }
        val redacted = redactDiagnostic(message)
        return if (redacted.isBlank()) {
            "SDK 异常：$name"
        } else {
            "SDK 异常：$name message=$redacted"
        }
    }

    /** Registers a real (non-heartbeat) instruction id so its ack can be recognised. */
    @Synchronized fun rememberInstruction(id: String) {
        if (id.isNotEmpty()) knownInstructionIds.add(id)
        while (knownInstructionIds.size > MAX_REMEMBERED_INSTRUCTIONS) {
            val oldest = knownInstructionIds.first()
            sentElapsedMs.remove(oldest)
            countedAckSends.remove(oldest)
            knownInstructionIds.remove(oldest)
        }
    }

    /**
     * The payload is queued on the local channel — no remote result yet.
     *
     * Every queued frame is counted as [TimelineEvent.FRAME_SENT]. For a registered
     * instruction the real monotonic send stamp is stored, and a resend refreshes it so a
     * later ack measures the round trip of the frame that was actually sent last.
     */
    fun onQueued(messageId: String, nodeId: String) {
        lastSendText = "已排队：messageId=$messageId nodeId=$nodeId（等待通道任务结果）"
        val sentAt = elapsedClock()
        synchronized(this) {
            if (messageId in knownInstructionIds) sentElapsedMs[messageId] = sentAt
        }
        timeline.recordFrameSent(wallClock(), sentAt, messageId)
    }

    /** The SDK channel task succeeded. This is not a watch receipt. */
    fun onTaskSuccess(messageId: String) {
        lastSendText = "通道任务成功：messageId=$messageId（不是手环回执）"
        timeline.recordTask(
            wallClock(),
            elapsedClock(),
            SdkTaskOutcome(succeeded = true, messageId = messageId, reasonCategory = TASK_REASON_GENERIC),
        )
    }

    fun onTaskFailure(messageId: String, reason: String) {
        lastSendText = "通道任务失败：messageId=$messageId reason=$reason"
        timeline.recordTask(
            wallClock(),
            elapsedClock(),
            SdkTaskOutcome(
                succeeded = false,
                messageId = messageId,
                reasonCategory = taskReasonCategory(reason),
            ),
        )
    }

    fun onNoNode(reason: String) {
        lastSendText = "无可用节点：$reason"
    }

    fun onSubmitFailed(reason: String) {
        lastSendText = "提交失败：$reason"
    }

    /**
     * Handles one inbound watch message.
     *
     * @return true only when the message is an [AckMessage] referring to a remembered
     * instruction id; only then is [lastAckText] updated.
     */
    @Synchronized fun onInbound(message: Message): Boolean {
        if (message !is AckMessage) return false
        val refId = message.refId
        if (refId !in knownInstructionIds) return false
        lastAckText = "手环确认 refId=$refId"
        countAck(refId)
        return true
    }

    /**
     * Adds one ack to the timeline — but only for an instruction that was really sent.
     *
     * A remembered id with no send stamp yet adds nothing, and neither does a repeated ack
     * of a send that was already counted: the ack counters therefore never double count one
     * frame. A resend installs a fresh send stamp, so the ack of that new frame is a new
     * measurement. Both cases still return true from [onInbound] and keep the receipt text,
     * so the previous semantics do not move.
     */
    @Synchronized private fun countAck(refId: String) {
        val sentAt = sentElapsedMs[refId] ?: return
        if (sentAt == countedAckSends.put(refId, sentAt)) return
        val now = elapsedClock()
        timeline.recordAck(wallClock(), now, AckRecord(refId, now - sentAt))
    }

    /**
     * Starts the session; a null state — or a heartbeat shape — keeps whatever was known
     * before. A heartbeat UPDATE therefore can neither overwrite the stored distance and
     * action nor make its id look like an instruction (work order W-643).
     */
    fun start(state: StateMessage?) {
        running = true
        if (state != null && !state.heartbeat) latestState = state
    }

    fun update(state: StateMessage) {
        latestState = state
    }

    fun stop() {
        running = false
    }

    /**
     * Builds the next 5 s heartbeat, or null when the session is not running or nothing
     * is known yet. It never mutates [latestState], so the distance a heartbeat reports
     * cannot drift from the last real instruction.
     */
    fun heartbeat(nowMs: Long): StateMessage? {
        if (!running) return null
        val current = latestState ?: return null
        return heartbeatOf(current, nowMs)
    }
}

/**
 * Applies one service start command to the session.
 *
 * A null action (or a null intent) is a repeat start: the previous state must survive,
 * only the session is marked running (AC3: 心跳与数据有效性分开).
 */
fun applyServiceCommand(
    session: ProbeSession,
    action: String?,
    decoded: StateMessage?,
): ServiceCommandResult {
    if (action == ProbeActions.STOP) {
        session.stop()
        return ServiceCommandResult.Stop
    }
    if (decoded != null && !decoded.heartbeat) {
        session.rememberInstruction(decoded.id)
        session.update(decoded)
    }
    session.start(decoded)
    return ServiceCommandResult.KeepRunning
}

/**
 * Maps an SDK `Task` failure message to one of the whitelisted categories.
 *
 * The raw message is never handed to the timeline: only these categories are recorded, so
 * no SDK text, host package or credential wording can leak through a diagnostic entry.
 * An unrecognised message maps to [ProbeSession.TASK_REASON_GENERIC].
 */
fun taskReasonCategory(reason: String): String {
    val lower = reason.lowercase()
    return when {
        "permissiondenied" in lower || "permission" in lower -> "permission"
        "devicedisconnected" in lower || "disconnect" in lower -> "disconnected"
        "appnotinstalled" in lower || "not installed" in lower -> "app_not_installed"
        "signature" in lower -> "signature"
        "timeout" in lower || "timed out" in lower -> "timeout"
        "no connected" in lower || "no node" in lower -> "no_node"
        else -> ProbeSession.TASK_REASON_GENERIC
    }
}
