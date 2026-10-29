package com.minavigatering.probe.transport

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import com.xiaomi.xms.wearable.Wearable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * One-shot diagnostic run of the wear launch path (work order W-738).
 *
 * Answers exactly two questions, in a fixed order: did the SDK launch task succeed, and did
 * the watch start answering afterwards. It sends a bare idle walk frame as the probe, so a
 * watch page that was already open shows up as an ack during the pre-check and stops the run
 * before anything is launched. Every result line is logged under [TAG]; node ids and
 * exception message bodies never reach a line, so the output can be collected as evidence.
 *
 * This type only runs when someone calls [run]; it is not wired to the service, a broadcast,
 * a notification listener or `onCreate`.
 */
class WearLaunchProbe(
    context: Context,
    private val transport: XiaomiWearableTransport,
) {

    private val nodeApi = Wearable.getNodeApi(context)

    /** Probe frame counter; only one run exists at a time, so a plain field is enough. */
    private var probeSeq = 0

    companion object {
        private val running = AtomicBoolean(false)
        private const val TAG = "MiRingWearLaunch"
        private const val NO_NODE_LINE = "no-node"
        private const val PRE_ACK_LINE = "pre-ack=yes 手环导航页已在运行，请先退出再测"
    }

    /** Runs the probe once. A second concurrent call returns immediately without any line. */
    suspend fun run(onLine: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        try {
            execute(onLine)
        } finally {
            running.set(false)
        }
    }

    private suspend fun execute(onLine: (String) -> Unit) {
        val node = resolveNode()
        if (node.isNullOrBlank()) {
            emit(NO_NODE_LINE, onLine)
            return
        }
        emit(queryInstalled(node), onLine)
        if (awaitPreAck(onLine)) return
        val confirmedUri = launchCandidates(node, onLine)
        if (confirmedUri == null) {
            emit("done confirmed=none", onLine)
            return
        }
        emit("confirmed uri=$confirmedUri", onLine)
        emit("done confirmed=$confirmedUri", onLine)
    }

    /** (a) Known node first; a null result means one refresh attempt, then give up. */
    private suspend fun resolveNode(): String? {
        val known = transport.currentNodeId
        if (!known.isNullOrBlank()) return known
        return suspendCancellableCoroutine { cont ->
            transport.refreshNode { node -> cont.resumeOnce(node) }
        }
    }

    /** (b) Installed flag read only through listeners; a throwing call reports its class name. */
    private suspend fun queryInstalled(node: String): String =
        suspendCancellableCoroutine { cont ->
            try {
                nodeApi.isWearAppInstalled(node)
                    .addOnSuccessListener { installed -> cont.resumeOnce(installedLine(installed)) }
                    .addOnFailureListener { error -> cont.resumeOnce(installedFailureLine(error)) }
            } catch (t: Throwable) {
                cont.resumeOnce(installedFailureLine(t))
            }
        }

    private fun installedLine(installed: Boolean?): String =
        if (installed == true) "installed=true" else "installed=false"

    private fun installedFailureLine(error: Throwable): String =
        "installed=fail:" + error.javaClass.simpleName

    /** (c) Pre-check: an ack on the bare frame means the watch page is already open. */
    private suspend fun awaitPreAck(onLine: (String) -> Unit): Boolean {
        val baseline = ackCount()
        sendProbe()
        if (pollAck(baseline, WearLaunchPlan.PRE_ACK_WAIT_MS) == null) return false
        emit(PRE_ACK_LINE, onLine)
        return true
    }

    /** (d) Candidates in plan order; returns the confirmed URI, or null when none worked. */
    private suspend fun launchCandidates(node: String, onLine: (String) -> Unit): String? {
        val uris = WearLaunchPlan.CANDIDATE_URIS
        uris.forEachIndexed { offset, uri ->
            val attempt = launchOne(node, uri)
            emit(WearLaunchPlan.formatLine(offset + 1, attempt), onLine)
            if (WearLaunchPlan.isConfirmed(attempt)) return uri
            if (offset != uris.lastIndex) delay(WearLaunchPlan.BETWEEN_ATTEMPTS_MS)
        }
        return null
    }

    /** One launch plus its ack window. A failed task is reported without any wait for an ack. */
    private suspend fun launchOne(node: String, uri: String): WearLaunchPlan.WearLaunchAttempt {
        val failure = launchTask(node, uri)
        if (failure != null) {
            return WearLaunchPlan.WearLaunchAttempt(uri, taskOk = false, failure = failure, ackWithinMs = null)
        }
        delay(WearLaunchPlan.POST_LAUNCH_SETTLE_MS)
        val baseline = ackCount()
        sendProbe()
        val ackWithinMs = pollAck(baseline, WearLaunchPlan.ACK_WAIT_MS)
        return WearLaunchPlan.WearLaunchAttempt(uri, taskOk = true, failure = null, ackWithinMs = ackWithinMs)
    }

    /** Launch task read only through listeners; returns the exception simple name on failure. */
    private suspend fun launchTask(node: String, uri: String): String? =
        suspendCancellableCoroutine { cont ->
            try {
                nodeApi.launchWearApp(node, uri)
                    .addOnSuccessListener { cont.resumeOnce(null) }
                    .addOnFailureListener { error -> cont.resumeOnce(error.javaClass.simpleName) }
            } catch (t: Throwable) {
                cont.resumeOnce(t.javaClass.simpleName)
            }
        }

    /** Sends one bare idle cycle frame and maps the transport phases onto the shared session. */
    private fun sendProbe() {
        probeSeq += 1
        val id = "launch-probe-" + System.currentTimeMillis().toString(16) + "-" + probeSeq
        val state = StateMessage(
            id = id,
            sentAtMs = System.currentTimeMillis(),
            link = NavProtocol.LINK_CONNECTED,
            navStatus = NavProtocol.STATUS_IDLE,
            mode = NavProtocol.DEFAULT_MODE,
            heartbeat = false,
        )
        ProbeRuntime.session.rememberInstruction(id)
        transport.sendState(state) { phase -> onSendPhase(phase) }
    }

    /** Same phase mapping as `MainActivity.send`, minus the state update and service intents. */
    private fun onSendPhase(phase: SendPhase) {
        when (phase) {
            is SendPhase.Queued -> ProbeRuntime.session.onQueued(phase.messageId, phase.nodeId)
            is SendPhase.TaskSucceeded -> ProbeRuntime.session.onTaskSuccess(phase.messageId)
            is SendPhase.TaskFailed -> ProbeRuntime.session.onTaskFailure(phase.messageId, phase.reason)
        }
    }

    /** Milliseconds from the poll start until the ack count grew, or null on timeout. */
    private suspend fun pollAck(baseline: Long, waitMs: Long): Long? {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start <= waitMs) {
            if (ackCount() > baseline) return SystemClock.elapsedRealtime() - start
            delay(WearLaunchPlan.ACK_POLL_MS)
        }
        return null
    }

    private fun ackCount(): Long = RuntimeTimeline.shared.totalCount(TimelineEvent.ACK_RECEIVED)

    private suspend fun emit(line: String, onLine: (String) -> Unit) {
        Log.i(TAG, line)
        withContext(Dispatchers.Main.immediate) { onLine(line) }
    }

    /** Resumes at most once: an SDK task may report success and failure late, never twice. */
    private fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
        if (isActive) resume(value) {}
    }
}
