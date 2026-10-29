package com.minavigatering.probe

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.minavigatering.probe.navigation.NavigationPhase
import com.minavigatering.probe.navigation.NavigationUiModel
import com.minavigatering.probe.protocol.NavProtocol

/** 无状态八块容器，顺序固定：标题、hero 插槽、状态卡、条件清单、模式分段、提醒强度分段、阶段主按钮、页脚。 */
data class NavigationContentState(val ui: NavigationUiModel, val status: NavigationStatus, val settings: NavigationChecklist)
/** 状态点用的布尔值，永不回读文字。 */
data class NavigationStatus(val enabled: Boolean, val running: Boolean, val nodePresent: Boolean)
/** 清单输入，同样是普通布尔。 */
data class NavigationChecklist(val access: Boolean, val batteryExempt: Boolean, val restoreFailed: Boolean)
/** [NavigationContent] 的回调；Android 目标由后续工单接线。 */
data class NavigationContentActions(
    val onMode: (String) -> Unit,
    /** Reminder strength 0 标准 / 1 强 / 2 最强, already validated by the screen. */
    val onVibeLevel: (Int) -> Unit,
    val onMain: () -> Unit, val onDiagnostics: () -> Unit, val settings: NavigationSettingActions,
)
/** 清单行与权限阶段主按钮背后的设置目标。 */
data class NavigationSettingActions(
    val notifications: () -> Unit, val battery: () -> Unit, val appDetails: () -> Unit, val miFitness: () -> Unit,
)
/** 固定入口；[hero] 仅插槽，本文件不渲染主卡，也不读 Context 或偏好。 */
@Composable
fun NavigationContent(state: NavigationContentState, actions: NavigationContentActions, hero: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .background(NavigationTheme.Colors.pageBackground)
            .safeDrawingPadding()
            // The seven blocks can exceed a small screen; the page stays scrollable as before.
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = NavigationTheme.Dimens.pagePadding,
                vertical = NavigationTheme.Dimens.pageVerticalPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.blockGap),
    ) {
        TitleHeader(state.ui.modeLabel)
        hero()
        StatusCard(state.ui, state.status)
        if (checklistVisible(state.ui.phase)) SettingsCard(state.settings, actions.settings)
        ModeSegment(state.ui.modeLabel, actions.onMode)
        ReminderSegment(state.ui.vibeLabel, actions.onVibeLevel)
        MainPhaseButton(state.ui, actions.settings, actions.onMain)
        FooterLink(actions.onDiagnostics)
    }
}
private fun checklistVisible(phase: NavigationPhase) = phase in setOf(
    NavigationPhase.PERMISSION_OFF, NavigationPhase.BRIDGE_OFF,
    NavigationPhase.LISTENER_DISCONNECTED, NavigationPhase.CONNECTING,
)
@Composable
private fun TitleHeader(modeLabel: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("导航环", color = NavigationTheme.Colors.textPrimary, fontSize = NavigationTheme.TextSize.pageTitle, fontWeight = FontWeight.Medium)
            Text("高德骑行 · 步行导航", color = NavigationTheme.Colors.textSecondary, fontSize = NavigationTheme.TextSize.body)
        }
        ModeChip(modeLabel)
    }
}
@Composable
private fun ModeChip(label: String) {
    Box(
        modifier = Modifier
            .background(NavigationTheme.Colors.softPrimaryBackground, RoundedCornerShape(50))
            .padding(horizontal = NavigationTheme.Dimens.heroGap, vertical = smallGap),
    ) {
        Text(label, color = NavigationTheme.Colors.softPrimaryForeground, fontSize = NavigationTheme.TextSize.caption)
    }
}
@Composable
private fun StatusCard(ui: NavigationUiModel, status: NavigationStatus) {
    WhiteCard {
        StatusRow(bridgeDotColor(status), ui.bridgeLine)
        ui.bandLine?.let { StatusRow(nodeDotColor(status), it) }
    }
}
private fun bridgeDotColor(status: NavigationStatus): Color = when {
    !status.enabled -> NavigationTheme.Colors.statusOff
    !status.running -> NavigationTheme.Colors.statusPending
    else -> NavigationTheme.Colors.statusOk
}
private fun nodeDotColor(status: NavigationStatus): Color =
    if (status.nodePresent) NavigationTheme.Colors.statusOk else NavigationTheme.Colors.statusPending
@Composable
private fun StatusRow(dotColor: Color, line: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(dotSize).background(dotColor, CircleShape))
        Spacer(Modifier.size(smallGap))
        Text(line, color = NavigationTheme.Colors.textPrimary, fontSize = NavigationTheme.TextSize.body)
    }
}
@Composable
private fun SettingsCard(checklist: NavigationChecklist, settings: NavigationSettingActions) {
    WhiteCard {
        checklistRows(checklist, settings).forEach { SettingRow(it) }
        if (checklist.restoreFailed) {
            Text("需要打开一次应用恢复同步", color = NavigationTheme.Colors.textSecondary, fontSize = NavigationTheme.TextSize.caption)
        }
    }
}
/** [actionLabel] 为 null 时该行只显示对勾，不画按钮（电池已豁免）。 */
private data class ChecklistRow(val label: String, val done: Boolean, val actionLabel: String?, val action: () -> Unit)
private fun checklistRows(c: NavigationChecklist, s: NavigationSettingActions) = listOf(
    ChecklistRow("高德通知读取", c.access, if (c.access) "查看" else "允许", s.notifications),
    ChecklistRow("后台运行不受限", c.batteryExempt, if (c.batteryExempt) null else "允许", s.battery),
    ChecklistRow("本应用省电策略与自启动", false, "打开", s.appDetails),
    ChecklistRow("小米运动健康后台不受限", false, "去设置", s.miFitness),
)
@Composable
private fun SettingRow(row: ChecklistRow) {
    Row(Modifier.fillMaxWidth().heightIn(min = NavigationTheme.Dimens.touchTarget), verticalAlignment = Alignment.CenterVertically) {
        Text(row.label, Modifier.weight(1f), color = NavigationTheme.Colors.textPrimary, fontSize = NavigationTheme.TextSize.body)
        if (row.done) {
            Text("✓", color = NavigationTheme.Colors.statusOk, fontSize = NavigationTheme.TextSize.body, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(smallGap))
        }
        row.actionLabel?.let {
            TextButton(onClick = row.action) {
                Text(it, color = NavigationTheme.Colors.primary, fontSize = NavigationTheme.TextSize.body)
            }
        }
    }
}
@Composable
private fun ModeSegment(modeLabel: String, onMode: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NavigationTheme.Colors.segmentTrack, RoundedCornerShape(50))
            .padding(NavigationTheme.Dimens.segmentTrackPadding),
    ) {
        // Cycling first: it is the default mode, so the left-hand segment is the one already on.
        ModeOption("骑行", modeLabel == "骑行", Modifier.weight(1f)) { onMode(NavProtocol.MODE_CYCLE) }
        ModeOption("步行", modeLabel == "步行", Modifier.weight(1f)) { onMode(NavProtocol.MODE_WALK) }
    }
}

/**
 * Reminder strength segment: 标准 / 强 / 最强.
 *
 * This is not amplitude — the watch's own 设置 → 声音与振动 → 振动强度 switch owns that,
 * and third-party快应用 cannot read or write it on this model. What changes here is how many
 * long pulses the band drives per turn, which is what the wrist actually feels.
 */
@Composable
private fun ReminderSegment(vibeLabel: String, onLevel: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "提醒强度",
            color = NavigationTheme.Colors.textSecondary,
            fontSize = NavigationTheme.TextSize.caption,
        )
        Spacer(Modifier.size(smallGap))
        Row(
            modifier = Modifier
                .weight(1f)
                .background(NavigationTheme.Colors.segmentTrack, RoundedCornerShape(50))
                .padding(NavigationTheme.Dimens.segmentTrackPadding),
        ) {
            ModeOption("标准", vibeLabel == "标准", Modifier.weight(1f)) { onLevel(NavProtocol.VIBE_LEVEL_STANDARD) }
            ModeOption("强", vibeLabel == "强", Modifier.weight(1f)) { onLevel(NavProtocol.VIBE_LEVEL_STRONG) }
            ModeOption("最强", vibeLabel == "最强", Modifier.weight(1f)) { onLevel(NavProtocol.VIBE_LEVEL_MAX) }
        }
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val background = if (selected) NavigationTheme.Colors.primary else Color.Transparent
    Box(
        modifier = modifier
            .heightIn(min = NavigationTheme.Dimens.touchTarget)
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = modeTextColor(selected), fontSize = NavigationTheme.TextSize.body)
    }
}
private fun modeTextColor(selected: Boolean): Color =
    if (selected) NavigationTheme.Colors.onPrimary else NavigationTheme.Colors.textPrimary
@Composable
private fun MainPhaseButton(ui: NavigationUiModel, settings: NavigationSettingActions, onMain: () -> Unit) {
    val (label, solid, action) = when (ui.phase) {
        NavigationPhase.PERMISSION_OFF -> Triple("允许高德通知读取", true, settings.notifications)
        NavigationPhase.LISTENER_DISCONNECTED -> Triple("查看通知权限", true, settings.notifications)
        NavigationPhase.BRIDGE_OFF -> Triple("开启导航桥", true, onMain)
        else -> Triple("关闭导航桥", false, onMain)
    }
    val modifier = Modifier.fillMaxWidth().heightIn(min = NavigationTheme.Dimens.touchTarget)
    if (solid) {
        val colors = ButtonDefaults.buttonColors(
            containerColor = NavigationTheme.Colors.primary, contentColor = NavigationTheme.Colors.onPrimary,
        )
        Button(onClick = action, modifier = modifier, colors = colors) {
            Text(label, color = NavigationTheme.Colors.onPrimary, fontSize = NavigationTheme.TextSize.body)
        }
    } else {
        val colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent, contentColor = NavigationTheme.Colors.primary,
        )
        val border = BorderStroke(NavigationTheme.Dimens.cardBorderWidth, NavigationTheme.Colors.primary)
        OutlinedButton(onClick = action, modifier = modifier, border = border, colors = colors) {
            Text(label, color = NavigationTheme.Colors.primary, fontSize = NavigationTheme.TextSize.body)
        }
    }
}
@Composable
private fun FooterLink(onDiagnostics: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onDiagnostics, modifier = Modifier.heightIn(min = NavigationTheme.Dimens.touchTarget)) {
            Text("连接诊断与测试", color = NavigationTheme.Colors.primary, fontSize = NavigationTheme.TextSize.body)
            Spacer(Modifier.size(smallGap))
            Text("→", color = NavigationTheme.Colors.primary, fontSize = NavigationTheme.TextSize.body)
        }
    }
}
/** 主页白卡；实现与诊断页分区卡共用 [SurfaceCard]，两页的边距与描边因此不会漂移。 */
@Composable
private fun WhiteCard(content: @Composable ColumnScope.() -> Unit) {
    SurfaceCard(content = content)
}
/** 仅由既有令牌派生：dotSize 8dp、smallGap 4dp。 */
private val dotSize = NavigationTheme.Dimens.ringStrokeWidth * 4
private val smallGap = NavigationTheme.Dimens.ringStrokeWidth * 2
