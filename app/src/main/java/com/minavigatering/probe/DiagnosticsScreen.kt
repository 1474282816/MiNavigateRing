package com.minavigatering.probe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minavigatering.probe.engine.NavigationEngine
import com.minavigatering.probe.protocol.NavCodec
import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage
import com.minavigatering.probe.transport.ProbeActions
import com.minavigatering.probe.transport.ProbeForegroundService
import com.minavigatering.probe.transport.ProbeRuntime
import com.minavigatering.probe.transport.RuntimeTimeline
import com.minavigatering.probe.transport.SendPhase
import com.minavigatering.probe.transport.SendResult
import com.minavigatering.probe.transport.TimelineEvent
import com.minavigatering.probe.transport.XiaomiWearableTransport
import kotlinx.coroutines.delay

/**
 * 连接诊断页（原探针页）：链路概览 + 七个可折叠分区。
 *
 * 这个页面用来排查手机到手表这条链路，不是产品主界面。它只说手机侧看到的事实 ——
 * 「已入队」「SDK Task 成功」都不等于手环回执，只有 ACK 才算，所以回执单独占一行。
 *
 * 排版沿用主页的令牌（[NavigationTheme]）与共用件（[UiKit]）：深色概览卡对应主页的手环
 * 镜像卡，白底分区卡对应主页的状态卡，主/次按钮同色同高。逻辑与回调与改造前逐条一致，
 * 只换了呈现：原来二十多个裸 Text/Button 铺成一面墙，现在按「链路 → 通道 → 震动测试 →
 * 报文 → 会话 → 时间线 → 后台策略 → 自动拉起 → 原始报文」分组，低频项默认收起。
 */
@Composable
fun ProbeScreen(transport: XiaomiWearableTransport, onBack: () -> Unit) {
    val context = LocalContext.current
    val session = ProbeRuntime.session
    val engine = remember { NavigationEngine() }
    var lastEnvelope by remember { mutableStateOf<String?>(null) }
    var sendText by remember { mutableStateOf(session.lastSendText) }
    var ackText by remember { mutableStateOf(session.lastAckText) }
    var linkText by remember { mutableStateOf(NavProtocol.LINK_CONNECTING) }
    var lastStatus by remember { mutableStateOf("（尚未发送）") }
    var hostText by remember { mutableStateOf(session.hostText) }
    var nodeText by remember { mutableStateOf(session.nodeText) }
    var permissionText by remember { mutableStateOf(session.permissionText) }
    var sdkErrorText by remember { mutableStateOf(session.sdkErrorText) }

    // 回执年龄每秒重算一次：用单调时钟相减，睡眠期间也连续，墙钟差会跨挂起跳变。
    var ackAgeMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            ackAgeMs = ackAgeMsNow()
            delay(ACK_AGE_REFRESH_MS)
        }
    }

    fun refreshFromSession() {
        sendText = session.lastSendText
        ackText = session.lastAckText
    }

    fun refreshDiagnostics() {
        hostText = session.hostText
        nodeText = session.nodeText
        permissionText = session.permissionText
        sdkErrorText = session.sdkErrorText
    }

    /**
     * Runs the read-only channel diagnostics.
     *
     * It never mints a receipt: these lines only describe the phone side. The device
     * manager permission is *checked* here, never requested — an automatic grant prompt
     * on page entry is explicitly out of scope.
     */
    fun runChannelDiagnostics() {
        transport.attachDiagnostics(session)
        transport.registerDiagnostics()
        // Until the diagnostics come back the link must not claim to be connected.
        linkText = NavProtocol.LINK_CONNECTING
        transport.queryServiceApiLevel { _, _ ->
            runOnUiThreadCompat { refreshDiagnostics() }
        }
        transport.refreshNode { node ->
            if (node != null) transport.ensureListening()
            // Records "no node, checkPermission not called" when there is no node.
            transport.checkDeviceManagerPermission { _, _ ->
                runOnUiThreadCompat { refreshDiagnostics() }
            }
            runOnUiThreadCompat { refreshDiagnostics() }
        }
    }

    // Auto-run once on page entry: host service API level, connection listener and node
    // refresh, then a permission *check* only. No requestPermission here.
    LaunchedEffect(Unit) { runChannelDiagnostics() }

    // The watch receipt only ever comes from the session's inbound path. The activity is
    // handed inbound messages by ProbeRuntime, so wrap the handler to hop to the UI thread.
    transport.setInboundHandler { message ->
        val accepted = session.onInbound(message)
        if (accepted) {
            runOnUiThreadCompat {
                ackText = session.lastAckText
                sendText = session.lastSendText
                ackAgeMs = ackAgeMsNow()
            }
        }
    }

    var pendingStartAction by remember { mutableStateOf(ProbeActions.START) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            context.startForegroundService(
                Intent(context, ProbeForegroundService::class.java)
                    .setAction(pendingStartAction),
            )
        } else {
            session.onSubmitFailed("通知权限未授予，未启动前台会话")
            refreshFromSession()
        }
    }

    fun startSession(action: String = ProbeActions.START) {
        pendingStartAction = action
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startForegroundService(
                Intent(context, ProbeForegroundService::class.java)
                    .setAction(action),
            )
        }
    }

    fun stopSession() {
        context.startService(
            Intent(context, ProbeForegroundService::class.java)
                .setAction(ProbeActions.STOP),
        )
    }

    fun send(state: StateMessage) {
        // Non-heartbeat sends register their own identity so only their own ack counts,
        // and the service learns the newest state before it heartbeats it.
        session.rememberInstruction(state.id)
        session.update(state)
        context.startService(
            Intent(context, ProbeForegroundService::class.java)
                .setAction(ProbeActions.UPDATE)
                .putExtra(ProbeForegroundService.EXTRA_STATE, NavCodec.encodeToString(state)),
        )

        val outcome = engine.accept(state, System.currentTimeMillis())
        lastEnvelope = NavCodec.encodeToString(state)
        lastStatus = outcome.state.statusText

        when (val result = transport.sendState(state) { phase ->
            when (phase) {
                is SendPhase.Queued -> {
                    session.onQueued(phase.messageId, phase.nodeId)
                    runOnUiThreadCompat { refreshFromSession() }
                }

                is SendPhase.TaskSucceeded -> {
                    session.onTaskSuccess(phase.messageId)
                    runOnUiThreadCompat { refreshFromSession() }
                }

                is SendPhase.TaskFailed -> {
                    session.onTaskFailure(phase.messageId, phase.reason)
                    runOnUiThreadCompat { refreshFromSession() }
                }
            }
        }) {
            is SendResult.Queued -> {
                linkText = NavProtocol.LINK_CONNECTED
                session.onQueued(result.messageId, result.nodeId)
            }

            is SendResult.NoNode -> {
                linkText = NavProtocol.LINK_CONNECTING
                session.onNoNode(result.reason)
            }

            is SendResult.Failed -> {
                linkText = NavProtocol.LINK_DISCONNECTED
                session.onSubmitFailed(result.reason)
            }
        }
        refreshFromSession()
    }

    /**
     * Diagnosis only: asks the band to play one vibration pattern.
     *
     * Deliberately bypasses [send]. A vibration request is not navigation state — routing it
     * through [send] would store it as `session.latestState` (whose heartbeat would re-send it
     * every 5 s) and would push a turn-less frame through the navigation engine, blanking the
     * home screen. Here the frame only travels over the transport and is acked normally.
     */
    fun sendVibe(pattern: String) {
        require(pattern in NavProtocol.ALLOWED_VIBES) { "unsupported vibe '$pattern'" }
        val now = System.currentTimeMillis()
        val frame = StateMessage(
            id = "vibe-$pattern-$now",
            sentAtMs = now,
            link = NavProtocol.LINK_CONNECTED,
            navStatus = NavProtocol.STATUS_IDLE,
            mode = NavProtocol.DEFAULT_MODE,
            vibe = pattern,
        )
        // Identity is registered so the ack can be attributed to this frame.
        session.rememberInstruction(frame.id)
        lastEnvelope = NavCodec.encodeToString(frame)
        Log.i(VIBE_LOG_TAG, "send pattern=$pattern id=${frame.id}")
        val result = transport.sendState(frame) { phase ->
            when (phase) {
                is SendPhase.Queued -> session.onQueued(phase.messageId, phase.nodeId)

                is SendPhase.TaskSucceeded -> session.onTaskSuccess(phase.messageId)

                is SendPhase.TaskFailed -> session.onTaskFailure(phase.messageId, phase.reason)
            }
            Log.i(VIBE_LOG_TAG, "phase=${phase.javaClass.simpleName} pattern=$pattern")
            runOnUiThreadCompat { refreshFromSession() }
        }
        when (result) {
            is SendResult.Queued -> {
                linkText = NavProtocol.LINK_CONNECTED
                session.onQueued(result.messageId, result.nodeId)
            }

            is SendResult.NoNode -> {
                linkText = NavProtocol.LINK_CONNECTING
                session.onNoNode(result.reason)
            }

            is SendResult.Failed -> {
                linkText = NavProtocol.LINK_DISCONNECTED
                session.onSubmitFailed(result.reason)
            }
        }
        lastStatus = "震动:$pattern"
        Log.i(VIBE_LOG_TAG, "result=$result pattern=$pattern")
        refreshFromSession()
    }

    // 分区折叠状态：只存在本次进入页面的内存里，退出诊断页即回到默认。
    val openSections = remember { mutableStateMapOf<String, Boolean>() }
    fun toggle(key: String) { openSections[key] = !(openSections[key] ?: false) }

    DiagnosticsPage(
        input = DiagnosticsInput(
            linkText = linkText,
            lastStatus = lastStatus,
            ackAgeMs = ackAgeMs,
            hostText = hostText,
            nodeText = nodeText,
            permissionText = permissionText,
            sdkErrorText = sdkErrorText,
            sendText = sendText,
            ackText = ackText,
            lastEnvelope = lastEnvelope,
            sessionRunning = ProbeRuntime.session.running,
        ),
        actions = DiagnosticsActions(
            onBack = onBack,
            onRefresh = { runChannelDiagnostics() },
            onRequestPermission = {
                transport.requestDeviceManagerPermission { _, _ ->
                    runOnUiThreadCompat { refreshDiagnostics() }
                }
            },
            onStartSession = { startSession() },
            onStopSession = { stopSession() },
            onScreenOffTest = { startSession(ProbeForegroundService.ACTION_SCREEN_OFF_TEST) },
            onVibe = { sendVibe(it) },
            onSendProbe = { send(it) },
        ),
        isOpen = { key, default -> openSections[key] ?: default },
        onToggleSection = ::toggle,
        transport = transport,
    )
}

/** 诊断页需要的全部只读快照，构造后不再回读 session。 */
private data class DiagnosticsInput(
    val linkText: String,
    val lastStatus: String,
    val ackAgeMs: Long?,
    val hostText: String,
    val nodeText: String,
    val permissionText: String,
    val sdkErrorText: String,
    val sendText: String,
    val ackText: String,
    val lastEnvelope: String?,
    val sessionRunning: Boolean,
)

/** 诊断页的全部动作；Android 目标全部在 [ProbeScreen] 里闭包，本文件不新建副作用。 */
private data class DiagnosticsActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onRequestPermission: () -> Unit,
    val onStartSession: () -> Unit,
    val onStopSession: () -> Unit,
    val onScreenOffTest: () -> Unit,
    val onVibe: (String) -> Unit,
    val onSendProbe: (StateMessage) -> Unit,
)

@Composable
private fun DiagnosticsPage(
    input: DiagnosticsInput,
    actions: DiagnosticsActions,
    isOpen: (String, Boolean) -> Boolean,
    onToggleSection: (String) -> Unit,
    transport: XiaomiWearableTransport,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NavigationTheme.Colors.pageBackground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = NavigationTheme.Dimens.pagePadding,
                vertical = NavigationTheme.Dimens.pageVerticalPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.blockGap),
    ) {
        DiagnosticsHeader(input.linkText, actions.onBack)
        LinkOverviewCard(input)

        SectionCard(
            title = "通道",
            subtitle = "手机侧事实，不等于是手环回执",
            expanded = isOpen("channel", true),
            onToggle = { onToggleSection("channel") },
        ) {
            InfoRow("宿主服务（小米穿戴 SDK）", input.hostText)
            InfoRow("手环节点", input.nodeText)
            InfoRow("设备管理权限", input.permissionText)
            InfoRow("最近 SDK 错误", input.sdkErrorText)
            Row(horizontalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap)) {
                SecondaryAction("重新检查", actions.onRefresh, Modifier.weight(1f))
                PrimaryAction("请求设备管理权限", actions.onRequestPermission, Modifier.weight(1f))
            }
        }

        SectionCard(
            title = "提醒震动测试",
            subtitle = "手环震动，不改手环上显示的内容",
            expanded = isOpen("vibe", true),
            onToggle = { onToggleSection("vibe") },
        ) {
            VibeBench(actions.onVibe)
        }

        SectionCard(
            title = "合成报文",
            subtitle = "协议与状态验证用，不是高德的真实解析结果",
            expanded = isOpen("probe", false),
            onToggle = { onToggleSection("probe") },
        ) {
            ProbeFrames(actions.onSendProbe)
        }

        SectionCard(
            title = "前台会话",
            subtitle = if (input.sessionRunning) "运行中" else "未运行",
            expanded = isOpen("session", false),
            onToggle = { onToggleSection("session") },
        ) {
            PrimaryAction("开始探针会话", actions.onStartSession, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap)) {
                SecondaryAction("停止会话", actions.onStopSession, Modifier.weight(1f))
                SecondaryAction("熄屏回归 45 秒", actions.onScreenOffTest, Modifier.weight(1f))
            }
            Note("熄屏回归序列：15 秒右转 300 米，25 秒 200 米，35 秒 30 米，45 秒左转 100 米。")
        }

        SectionCard(
            title = "运行时间线",
            subtitle = "记录在内存，进程退出即丢",
            expanded = isOpen("timeline", false),
            onToggle = { onToggleSection("timeline") },
        ) {
            RuntimeTimelineDiagnosticsBody()
        }

        SectionCard(
            title = "后台策略",
            subtitle = "电池优化与后台恢复结果",
            expanded = isOpen("background", false),
            onToggle = { onToggleSection("background") },
        ) {
            BackgroundPolicyDiagnosticsBody()
        }

        SectionCard(
            title = "拉起手环导航页",
            subtitle = "实验功能：自动拉起开关与一次性探测",
            expanded = isOpen("launch", false),
            onToggle = { onToggleSection("launch") },
        ) {
            WearLaunchDiagnosticsBody(transport)
        }

        SectionCard(
            title = "最近报文与回执",
            subtitle = "本机视角的最后一次收发原文",
            expanded = isOpen("frames", false),
            onToggle = { onToggleSection("frames") },
        ) {
            MonoBlock(input.lastEnvelope ?: "（尚未发送）")
            InfoRow("最近一次发送状态", input.sendText)
            InfoRow("最近一次回执", input.ackText)
        }

        Text(
            text = "com.minavigatering.probe · 诊断数据仅留在本机内存",
            modifier = Modifier.fillMaxWidth(),
            color = NavigationTheme.Colors.textSecondary,
            fontSize = NavigationTheme.TextSize.caption,
        )
    }
}

/** 顶栏：返回 + 标题 + 链路状态胶囊。主页标题区同字号同配色，只多一个返回入口。 */
@Composable
private fun DiagnosticsHeader(linkText: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 48dp 触控目标，视觉上仍是纯文字箭头，和主页的轻量页脚一致。
        Box(
            modifier = Modifier
                .size(NavigationTheme.Dimens.touchTarget)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "←",
                color = NavigationTheme.Colors.primary,
                fontSize = NavigationTheme.TextSize.heroAction,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "连接诊断",
                color = NavigationTheme.Colors.textPrimary,
                fontSize = NavigationTheme.TextSize.pageTitle,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "手机到手表这条链路",
                color = NavigationTheme.Colors.textSecondary,
                fontSize = NavigationTheme.TextSize.body,
            )
        }
        LinkChip(linkText)
    }
}

/** 链路段状态胶囊：底色是状态令牌的 14% 透明度，文字用状态本色。 */
@Composable
private fun LinkChip(linkText: String) {
    val tint = linkColor(linkText)
    Box(
        modifier = Modifier
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = NavigationTheme.Dimens.heroGap, vertical = NavigationTheme.Dimens.ringStrokeWidth * 2),
    ) {
        Text(linkText, color = tint, fontSize = NavigationTheme.TextSize.caption)
    }
}

/** 深色概览卡：对照主页的手环镜像卡，三行讲清「通没通、回执多久前、刚才做了什么」。 */
@Composable
private fun LinkOverviewCard(input: DiagnosticsInput) {
    DarkCard {
        Text(
            "链路概览",
            color = NavigationTheme.Colors.heroLabel,
            fontSize = NavigationTheme.TextSize.caption,
            fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(linkColor(input.linkText))
            Spacer(Modifier.size(NavigationTheme.Dimens.heroGap))
            Text(
                input.linkText,
                color = NavigationTheme.Colors.heroHeadline,
                fontSize = NavigationTheme.TextSize.heroAction,
            )
        }
        HeroInfoRow("最近一次手环回执", ackAgeText(input.ackAgeMs))
        HeroInfoRow("最近一次操作", input.lastStatus)
    }
}

/** 深色卡里的两行式信息：标签用 hero 弱色，值用 hero 亮色。 */
@Composable
private fun HeroInfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = NavigationTheme.Colors.heroLabel, fontSize = NavigationTheme.TextSize.caption)
        Text(value, color = NavigationTheme.Colors.heroCaption, fontSize = NavigationTheme.TextSize.body)
    }
}

/** 震动测试台：五个节拍组合，两列排布，第三行独占。 */
@Composable
private fun VibeBench(onVibe: (String) -> Unit) {
    Note(
        "手环侧 vibrate() 只开放 short / long，振幅由手表自己的「设置 → 声音与振动 → 振动强度」" +
            "决定，第三方应用改不了；这里能改的是驱动时长与次数。"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap)) {
        SecondaryAction("短震 · 1×短", { onVibe(NavProtocol.VIBE_SHORT) }, Modifier.weight(1f))
        SecondaryAction("长震 · 1×长", { onVibe(NavProtocol.VIBE_LONG) }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap)) {
        SecondaryAction("三连短震", { onVibe(NavProtocol.VIBE_SHORT3) }, Modifier.weight(1f))
        SecondaryAction("双长震", { onVibe(NavProtocol.VIBE_LONG2) }, Modifier.weight(1f))
    }
    SecondaryAction("三长震 · 最强档实际效果", { onVibe(NavProtocol.VIBE_LONG3) }, Modifier.fillMaxWidth())
    Note(
        "建议顺序：短震 → 长震 → 双长震 → 三长震。短震是旧版提醒的强度，后三个是新三档实际会震的组合；" +
            "三长震仍然弱的话，先把「振动强度」调成强劲，再判断是不是电机本身的问题。"
    )
}

/** 五个合成夹具，逐条说明它验证的是哪条协议分支。 */
@Composable
private fun ProbeFrames(onSendProbe: (StateMessage) -> Unit) {
    Note("下列道路名与转向均为合成夹具，不代表已验收的高德真实解析。")
    PrimaryAction("左转 300 米", { onSendProbe(probeFrame("probe-1", "left", 300, "probe-left-1", NavProtocol.STATUS_ACTIVE)) }, Modifier.fillMaxWidth())
    SecondaryAction("左转 200 米（同一 actionId，不改提醒）", { onSendProbe(probeFrame("probe-2", "left", 200, "probe-left-1", NavProtocol.STATUS_ACTIVE)) }, Modifier.fillMaxWidth())
    SecondaryAction("不可识别方向（action=mystery）", { onSendProbe(probeFrame("probe-3", "mystery", 150, "probe-mystery-1", NavProtocol.STATUS_ACTIVE)) }, Modifier.fillMaxWidth())
    SecondaryAction("结束导航（navStatus=ended）", {
        onSendProbe(
            StateMessage(
                id = "probe-4", sentAtMs = System.currentTimeMillis(),
                link = NavProtocol.LINK_CONNECTED, navStatus = NavProtocol.STATUS_ENDED,
                mode = NavProtocol.DEFAULT_MODE, heartbeat = false,
            )
        )
    }, Modifier.fillMaxWidth())
    SecondaryAction("空闲（无虚构转向）", {
        onSendProbe(
            StateMessage(
                id = "probe-5", sentAtMs = System.currentTimeMillis(),
                link = NavProtocol.LINK_CONNECTED, navStatus = NavProtocol.STATUS_IDLE,
                mode = NavProtocol.DEFAULT_MODE, heartbeat = false,
            )
        )
    }, Modifier.fillMaxWidth())
}

/** 一条行进中的合成转向帧；模式取默认值，不让诊断页覆盖用户选的模式。 */
private fun probeFrame(
    id: String,
    action: String,
    distanceM: Int,
    actionId: String,
    status: String,
) = StateMessage(
    id = id,
    sentAtMs = System.currentTimeMillis(),
    link = NavProtocol.LINK_CONNECTED,
    navStatus = status,
    action = action,
    exitNumber = null,
    distanceM = distanceM,
    roadName = "合成道路",
    actionId = actionId,
    mode = NavProtocol.DEFAULT_MODE,
    heartbeat = false,
)

/** 说明文字：12sp 次要色，与主页的引导文案同一层。 */
@Composable
private fun Note(text: String) {
    Text(text, color = NavigationTheme.Colors.textSecondary, fontSize = NavigationTheme.TextSize.caption)
}

/** 链路文字到状态色。未知文案按「待确认」处理，不谎报已连接。 */
private fun linkColor(linkText: String): Color = when (linkText) {
    NavProtocol.LINK_CONNECTED -> NavigationTheme.Colors.statusOk
    NavProtocol.LINK_DISCONNECTED -> NavigationTheme.Colors.statusOff
    else -> NavigationTheme.Colors.statusPending
}

/** 回执年龄的人话版本；无回执时明确说没有，不写 0 秒。 */
private fun ackAgeText(ageMs: Long?): String {
    if (ageMs == null) return "尚未收到回执"
    val seconds = ageMs / 1_000L
    return when {
        seconds <= 2 -> "刚刚（${seconds} 秒前）"
        seconds < 60 -> "${seconds} 秒前"
        else -> "${seconds / 60} 分钟前"
    }
}

/** 单调时钟下的回执年龄；没有记录返回 null。 */
private fun ackAgeMsNow(): Long? {
    val now = SystemClock.elapsedRealtime()
    return RuntimeTimeline.shared.lastSeen(TimelineEvent.ACK_RECEIVED)?.let { entry ->
        (now - entry.elapsedMs).coerceAtLeast(0L)
    }
}

/** Logcat tag for the diagnosis vibration bench, so one `adb logcat -s MiRingVibe` shows
 * whether a pattern left the phone, what the SDK phase was, and whether a node was found. */
private const val VIBE_LOG_TAG = "MiRingVibe"

/** 回执年龄刷新间隔：一秒一次，和主页轮询同频。 */
private const val ACK_AGE_REFRESH_MS = 1_000L

/** Runs [block] on the UI thread when the composable is hosted by an activity. */
private fun runOnUiThreadCompat(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}
