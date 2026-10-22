package com.minavigatering.probe.transport

import com.minavigatering.probe.protocol.AckMessage
import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the probe session state boundaries (work order W-618).
 *
 * They never start the service, never touch the SDK and never claim a real watch ack:
 * only the local bookkeeping (queued / task result / real inbound ack) is checked.
 */
class ProbeSessionTest {

    private fun activeState(
        id: String,
        distanceM: Int?,
        actionId: String = "probe-left-1",
    ) = StateMessage(
        id = id,
        sentAtMs = 1_700_000_000_000,
        link = NavProtocol.LINK_CONNECTED,
        navStatus = NavProtocol.STATUS_ACTIVE,
        action = "left",
        exitNumber = null,
        distanceM = distanceM,
        roadName = "\u5408\u6210\u9053\u8def",
        actionId = actionId,
        mode = NavProtocol.MODE_WALK,
        heartbeat = false,
    )

    @Test
    fun `queued and channel task success never become a watch ack`() {
        val session = ProbeSession()
        session.rememberInstruction("probe-1")

        session.onQueued("probe-1", "node-1")
        assertTrue("queued text: ${session.lastSendText}", session.lastSendText.contains("已排队"))
        assertEquals(ProbeSession.NO_ACK_YET, session.lastAckText)

        session.onTaskSuccess("probe-1")
        assertTrue("task text: ${session.lastSendText}", session.lastSendText.contains("通道任务成功"))
        assertTrue("task text: ${session.lastSendText}", session.lastSendText.contains("不是手环回执"))
        assertEquals(ProbeSession.NO_ACK_YET, session.lastAckText)

        session.onTaskFailure("probe-1", "boom")
        assertTrue("failure text: ${session.lastSendText}", session.lastSendText.contains("通道任务失败"))
        assertEquals(ProbeSession.NO_ACK_YET, session.lastAckText)
    }

    @Test
    fun `only a matching refId updates the ack text`() {
        val session = ProbeSession()
        session.rememberInstruction("probe-1")

        val matching = AckMessage(id = "ack-1", refId = "probe-1", sentAtMs = 1_700_000_001_000)
        assertTrue(session.onInbound(matching))
        assertTrue("ack text: ${session.lastAckText}", session.lastAckText.contains("probe-1"))

        val foreign = AckMessage(id = "ack-2", refId = "other", sentAtMs = 1_700_000_002_000)
        assertFalse(session.onInbound(foreign))
        assertTrue("ack text: ${session.lastAckText}", session.lastAckText.contains("probe-1"))
        assertFalse("foreign refId must not overwrite", session.lastAckText.contains("other"))
    }

    @Test
    fun `heartbeat ack is not an instruction ack and distance stays put`() {
        val session = ProbeSession()
        val original = activeState(id = "probe-1", distanceM = 300)
        session.rememberInstruction(original.id)
        session.start(original)

        val heartbeat = session.heartbeat(1_700_000_005_000)
        assertTrue(heartbeat != null)
        val beat = heartbeat!!
        assertTrue(beat.heartbeat)
        assertEquals(300, beat.distanceM)
        assertNotEquals(original.id, beat.id)

        // The heartbeat id never entered instructionIds, so its ack cannot become an
        // instruction receipt.
        val beatAck = AckMessage(id = "ack-hb", refId = beat.id, sentAtMs = 1_700_000_005_100)
        assertFalse(session.onInbound(beatAck))
        assertEquals(ProbeSession.NO_ACK_YET, session.lastAckText)
        assertFalse(beat.id in session.instructionIds)

        // Sending a heartbeat must not move the stored distance either.
        assertEquals(300, session.latestState?.distanceM)
    }

    @Test
    fun `diagnostic failures are visible and never become a watch ack`() {
        val session = ProbeSession()
        session.rememberInstruction("probe-1")

        // Nothing probed yet: the lines say so instead of implying a working channel.
        assertEquals(ProbeSession.NOT_PROBED, session.hostText)
        assertEquals(ProbeSession.NOT_PROBED, session.nodeText)
        assertEquals(ProbeSession.NOT_PROBED, session.permissionText)
        assertEquals(ProbeSession.NOT_PROBED, session.sdkErrorText)

        session.onHostStatus("宿主服务 API level 查询失败：DeviceDisconnectedException")
        session.onNodeStatus("节点获取失败：DeviceDisconnectedException")
        session.onPermissionStatus("权限检查失败：PermissionDeniedException")
        session.onSdkError("PermissionDeniedException", "permission denied by host")

        assertTrue("host: ${session.hostText}", session.hostText.contains("DeviceDisconnectedException"))
        assertTrue("node: ${session.nodeText}", session.nodeText.contains("DeviceDisconnectedException"))
        assertTrue("permission: ${session.permissionText}", session.permissionText.contains("PermissionDeniedException"))
        assertTrue("sdk error: ${session.sdkErrorText}", session.sdkErrorText.contains("PermissionDeniedException"))

        // A denied permission or a dead phone channel is not a watch receipt.
        assertEquals(ProbeSession.NO_ACK_YET, session.lastAckText)
    }

    @Test
    fun `channel task success still is not a watch receipt`() {
        val session = ProbeSession()
        session.rememberInstruction("probe-1")

        session.onTaskSuccess("probe-1")
        session.onPermissionStatus("设备管理权限：未授予")
        session.onSdkError("AppNotInstalledException", "watch app missing")

        assertTrue("task text: ${session.lastSendText}", session.lastSendText.contains("不是手环回执"))
        assertEquals(ProbeSession.NO_ACK_YET, session.lastAckText)

        // Only a real inbound ack for a remembered id may move the receipt text.
        val ack = AckMessage(id = "ack-1", refId = "probe-1", sentAtMs = 1_700_000_001_000)
        assertTrue(session.onInbound(ack))
        assertTrue("ack text: ${session.lastAckText}", session.lastAckText.contains("probe-1"))
    }

    @Test
    fun `redactDiagnostic removes AuthKey and other credential wording`() {
        val session = ProbeSession()

        assertEquals("[redacted]", session.redactDiagnostic("AuthKey"))

        val mixed = session.redactDiagnostic("authKey=abc ACCOUNT=u Token=t SECRET=s")
        assertFalse("leaked authkey: $mixed", mixed.lowercase().contains("authkey"))
        assertFalse("leaked account: $mixed", mixed.lowercase().contains("account"))
        assertFalse("leaked token: $mixed", mixed.lowercase().contains("token"))
        assertFalse("leaked secret: $mixed", mixed.lowercase().contains("secret"))
        assertTrue("placeholder missing: $mixed", mixed.contains("[redacted]"))

        // The redaction also applies on the way into the SDK-error line.
        session.onSdkError("SignatureVerifyFailedException", "signature failed for AuthKey")
        assertFalse(session.sdkErrorText.lowercase().contains("authkey"))
        assertTrue(session.sdkErrorText.contains("SignatureVerifyFailedException"))
    }

    @Test
    fun `empty node permission texts are refusals, not successes`() {
        val session = ProbeSession()

        session.onPermissionStatus(ProbeSession.NO_NODE_CHECK_TEXT)
        assertTrue("check text: ${session.permissionText}", session.permissionText.contains("未调用 checkPermission"))
        assertFalse("check text: ${session.permissionText}", session.permissionText.contains("已授予"))

        session.onPermissionStatus(ProbeSession.NO_NODE_REQUEST_TEXT)
        assertTrue("request text: ${session.permissionText}", session.permissionText.contains("未调用 requestPermission"))
        assertFalse("request text: ${session.permissionText}", session.permissionText.contains("已授予"))

        assertEquals(ProbeSession.NO_ACK_YET, session.lastAckText)
    }

    @Test
    fun `describeSdkFailure keeps the class name and invents no numeric code`() {
        val session = ProbeSession()

        val text = session.describeSdkFailure("PermissionDeniedException", null)
        assertTrue("class name: $text", text.contains("PermissionDeniedException"))
        assertFalse("fabricated numeric code: $text", Regex("\\d").containsMatchIn(text))

        // An empty message must not hide the class name either.
        assertTrue(session.describeSdkFailure("DeviceDisconnectedException", "   ")
            .contains("DeviceDisconnectedException"))

        // All four SDK failure classes are shown verbatim; none has a code to report.
        for (name in listOf(
            "PermissionDeniedException",
            "DeviceDisconnectedException",
            "AppNotInstalledException",
            "SignatureVerifyFailedException",
        )) {
            val line = session.describeSdkFailure(name, null)
            assertTrue("missing $name in $line", line.contains(name))
            assertFalse("numeric code in $line", Regex("\\d").containsMatchIn(line))
        }
    }

    @Test
    fun `service commands keep state across restarts and stop heartbeats`() {
        val session = ProbeSession()

        val start = StateMessage(
            id = "probe-1",
            sentAtMs = 1_700_000_000_000,
            link = NavProtocol.LINK_CONNECTED,
            navStatus = NavProtocol.STATUS_ACTIVE,
            action = "left",
            distanceM = 300,
            actionId = "probe-left-1",
            mode = NavProtocol.MODE_WALK,
        )
        assertEquals(
            ServiceCommandResult.KeepRunning,
            applyServiceCommand(session, ProbeActions.START, start),
        )
        assertTrue(session.running)
        assertEquals(300, session.latestState?.distanceM)

        // A null intent (repeat start) must not wipe what is already known.
        assertEquals(
            ServiceCommandResult.KeepRunning,
            applyServiceCommand(session, null, null),
        )
        assertTrue(session.running)
        assertEquals(300, session.latestState?.distanceM)
        assertEquals("probe-1", session.latestState?.id)

        val update = start.copy(id = "probe-2", distanceM = 200)
        assertEquals(
            ServiceCommandResult.KeepRunning,
            applyServiceCommand(session, ProbeActions.UPDATE, update),
        )
        assertEquals(200, session.latestState?.distanceM)

        assertEquals(
            ServiceCommandResult.Stop,
            applyServiceCommand(session, ProbeActions.STOP, null),
        )
        assertFalse(session.running)
        // Stopping keeps the last known state for display; it only stops the heartbeats.
        assertEquals(200, session.latestState?.distanceM)
        assertNull(session.heartbeat(1_700_000_010_000))
    }
}
