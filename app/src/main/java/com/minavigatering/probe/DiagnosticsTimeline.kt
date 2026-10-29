package com.minavigatering.probe

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.minavigatering.probe.navigation.NavigationSettings
import com.minavigatering.probe.navigation.isIgnoringBatteryOptimizations
import com.minavigatering.probe.navigation.miFitnessAppDetailsIntent
import com.minavigatering.probe.navigation.requestIgnoreBatteryOptimizations
import com.minavigatering.probe.navigation.startActivitySafely
import com.minavigatering.probe.transport.EntryTimestamp
import com.minavigatering.probe.transport.RuntimeTimeline
import com.minavigatering.probe.transport.TimelineEntry
import com.minavigatering.probe.transport.TimelineEvent
import com.minavigatering.probe.transport.TimelineStatistics
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断页的运行时间线与后台策略区（工作项 W-710，W-711 分区与“最近一次”）。
 *
 * 本文件只放 Composable 与它们专用的私有格式 helper（工作项 W-711）：不新增 ViewModel，也不复制或
 * 重写 BackgroundPolicy.kt 里的安全启动 helper（W-709 已完成，本单只接线）。
 *
 * 显示的每个字段都已脱敏：类别枚举、四布尔、毫秒、墙钟时间，以及协议/SDK/ACK id 的
 * 后 6 位。这里永远不出现通知正文、路名、坐标、账号、节点 id 或密钥。字段含义与
 * 仍未被验证的实机项见 docs/lockscreen-minimal.md。
 *
 * 两个 Body 都只产出卡片内容：外层分区卡、标题与折叠由 DiagnosticsScreen 统一提供，
 * 因此这里不再画自己的 titleSmall 标题，也不再建立嵌套滚动容器。
 */

/** 最近 60 秒的计数摘要：通知数、发送帧数、ACK 数与 ACK 往返毫秒。 */
@Composable
private fun TimelineSummaryLine(statistics: TimelineStatistics) {
    val window = statistics.window
    val latestText = statistics.ackRtt.latestMs?.let { "${it}ms" } ?: "无样本"
    val averageText = statistics.ackRtt.averageMs?.let { "${it}ms" } ?: "无样本"
    StatLine("通知", window.notifications.toString())
    StatLine("发送帧", window.framesSent.toString())
    StatLine("ACK", window.acks.toString())
    StatLine("RTT", "$latestText 最近 / $averageText 平均")
}

/** 一行统计：标签次色、值主色等宽，和 InfoRow 同一层但保持单行紧凑。 */
@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = NavigationTheme.Colors.textSecondary,
            fontSize = NavigationTheme.TextSize.caption,
        )
        Text(
            value,
            color = NavigationTheme.Colors.textPrimary,
            fontSize = NavigationTheme.TextSize.caption,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}

/** 最近若干条时间线，新到旧；每行只有墙钟时间、elapsed 毫秒、类别与脱敏 detail。 */
private fun timelineLines(entries: List<TimelineEntry>, clock: SimpleDateFormat): String =
    if (entries.isEmpty()) "尚无时间线记录。"
    else entries.joinToString("\n") { entry ->
        "${clock.format(Date(entry.timestamp.wallMs))} ｜ +${entry.timestamp.elapsedMs}ms" +
            " ｜ ${entry.event} ｜ ${entry.detail}"
    }

/** “最近一次”的一行定义：一个标签对应一类或多类事件（高德四类合并成一行）。 */
private data class RecentCategory(val label: String, val events: List<TimelineEvent>)

/** 14 行“最近一次”，顺序固定；高德四类取最近一条并把四类累计次数求和。 */
private val RECENT_CATEGORIES = listOf(
    RecentCategory("发送轮次", listOf(TimelineEvent.SEND_ROUND)),
    RecentCategory("发送帧", listOf(TimelineEvent.FRAME_SENT)),
    RecentCategory("SDK Task成功", listOf(TimelineEvent.SDK_TASK_SUCCEEDED)),
    RecentCategory("SDK Task失败", listOf(TimelineEvent.SDK_TASK_FAILED)),
    RecentCategory("ACK", listOf(TimelineEvent.ACK_RECEIVED)),
    RecentCategory(
        "高德通知",
        listOf(
            TimelineEvent.AMAP_NOTIFICATION_ACTIVE,
            TimelineEvent.AMAP_NOTIFICATION_UNRECOGNIZED,
            TimelineEvent.AMAP_NOTIFICATION_ENDED,
            TimelineEvent.AMAP_NOTIFICATION_IGNORED,
        ),
    ),
    RecentCategory("监听连上", listOf(TimelineEvent.LISTENER_CONNECTED)),
    RecentCategory("监听断开", listOf(TimelineEvent.LISTENER_DISCONNECTED)),
    RecentCategory("屏幕灭", listOf(TimelineEvent.SCREEN_OFF)),
    RecentCategory("屏幕亮", listOf(TimelineEvent.SCREEN_ON)),
    RecentCategory("解锁", listOf(TimelineEvent.SCREEN_UNLOCK)),
    RecentCategory("Idle变化", listOf(TimelineEvent.DEVICE_IDLE_CHANGED)),
    RecentCategory("省电变化", listOf(TimelineEvent.POWER_SAVE_CHANGED)),
    RecentCategory("恢复结果", listOf(TimelineEvent.RECOVERY_RESULT)),
)

/** 一行“最近一次”的文本；没有记录时显示“尚无”，不显示 0 或推测时间。 */
private fun recentOnceText(
    timeline: RuntimeTimeline,
    category: RecentCategory,
    nowElapsedMs: Long,
    clock: SimpleDateFormat,
): String {
    val latest = category.events.mapNotNull { timeline.lastSeen(it) }.maxByOrNull { it.elapsedMs }
        ?: return "尚无"
    val total = category.events.sumOf { timeline.totalCount(it) }
    return "${clock.format(Date(latest.wallMs))} · ${secondsAgo(latest, nowElapsedMs)} 秒前 · 累计 $total 次"
}

/** 距最近一次的秒数：单调时钟相减，不用墙钟差值，未来记录按 0 处理。 */
private fun secondsAgo(latest: EntryTimestamp, nowElapsedMs: Long): Long =
    ((nowElapsedMs - latest.elapsedMs).coerceAtLeast(0L)) / 1_000L

/** 14 行“最近一次”，随外层诊断一起滚动；标签左、最近时间和次数右。 */
@Composable
private fun RecentOnceLines(timeline: RuntimeTimeline, nowElapsedMs: Long) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column(verticalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap)) {
        RECENT_CATEGORIES.forEach { category ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    category.label,
                    modifier = Modifier.weight(1f),
                    color = NavigationTheme.Colors.textSecondary,
                    fontSize = NavigationTheme.TextSize.caption,
                )
                Text(
                    recentOnceText(timeline, category, nowElapsedMs, clock),
                    color = NavigationTheme.Colors.textPrimary,
                    fontSize = NavigationTheme.TextSize.caption,
                )
            }
        }
    }
}

/**
 * 每秒刷新计数摘要、14 行“最近一次”、40 条系统事件与 20 条最近传输。
 *
 * 摘要的“发送帧”只来自 FRAME_SENT（一次入队报文），不等于 SEND_ROUND（一次心跳循环
 * 轮次），也不等于 SDK Task 成功；ACK 计数只来自手环回执。窗口内没有 ACK 时 RTT 显示
 * “无样本”，不显示 0。“N 秒前”用 SystemClock.elapsedRealtime 减记录时的 elapsed，
 * 不用墙钟算差。两块列表都随外层滚动，不建立嵌套滚动容器。
 */
@Composable
fun RuntimeTimelineDiagnosticsBody() {
    val timeline = RuntimeTimeline.shared
    val stateRows = 40
    val trafficRows = 20
    var nowElapsedMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var statistics by remember { mutableStateOf(timeline.statistics(nowElapsedMs)) }
    var stateEntries by remember { mutableStateOf(timeline.stateSnapshot(stateRows)) }
    var trafficEntries by remember { mutableStateOf(timeline.trafficSnapshot(trafficRows)) }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LaunchedEffect(timeline) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            nowElapsedMs = now
            statistics = timeline.statistics(now)
            stateEntries = timeline.stateSnapshot(stateRows)
            trafficEntries = timeline.trafficSnapshot(trafficRows)
            delay(1_000L)
        }
    }

    CardLabel("最近 60 秒")
    TimelineSummaryLine(statistics)
    CardLabel("最近一次（每秒刷新，“N 秒前”取自单调时钟）")
    RecentOnceLines(timeline, nowElapsedMs)
    CardLabel("系统事件 · 最近 $stateRows 条（新到旧）")
    MonoBlock(timelineLines(stateEntries, clock))
    CardLabel("最近传输 · 最近 $trafficRows 条（新到旧）")
    MonoBlock(timelineLines(trafficEntries, clock))
}

/**
 * 每秒刷新电池优化豁免状态与最近一次后台恢复结果。
 *
 * 两个按钮只调用 BackgroundPolicy.kt 的既有组合 helper：请求豁免失败会自动回退到电池
 * 优化设置页；“打开小米运动健康应用信息”打开官方 App 的详情页，供用户自行检查其后台
 * 策略。按下按钮不等于授权已经生效，是否生效只看上面这行“电池优化豁免：是/否”。
 */
@Composable
fun BackgroundPolicyDiagnosticsBody() {
    val context = LocalContext.current
    val settings = remember { NavigationSettings(context) }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var restoreResult by remember { mutableStateOf(settings.lastRestoreText) }
    var restoreAtMs by remember { mutableStateOf(settings.lastRestoreAtMs) }

    LaunchedEffect(context) {
        while (true) {
            exempt = isIgnoringBatteryOptimizations(context)
            restoreResult = settings.lastRestoreText
            restoreAtMs = settings.lastRestoreAtMs
            delay(1_000L)
        }
    }

    val restoreResultText = restoreResult.ifEmpty { "尚无记录" }
    val restoreAtText = if (restoreAtMs <= 0L) "尚无记录" else clock.format(Date(restoreAtMs))
    InfoRow("电池优化豁免", if (exempt) "是" else "否")
    InfoRow("最近一次后台恢复", "$restoreResultText（$restoreAtText）")
    Text(
        "ok / failed:异常类名 / skipped 只是启动调用的结果，不表示锁屏链路已生效。",
        color = NavigationTheme.Colors.textSecondary,
        fontSize = NavigationTheme.TextSize.caption,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap)) {
        SecondaryAction("允许后台运行", { requestIgnoreBatteryOptimizations(context) }, Modifier.weight(1f))
        SecondaryAction("小米运动健康", { startActivitySafely(context, miFitnessAppDetailsIntent()) }, Modifier.weight(1f))
    }
}
