package com.minavigatering.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.minavigatering.probe.navigation.NavigationSettings
import com.minavigatering.probe.transport.WearLaunchProbe
import com.minavigatering.probe.transport.XiaomiWearableTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Refresh interval of the last auto launch line: one second, like BackgroundPolicyDiagnostics. */
private const val AUTO_LAUNCH_REFRESH_INTERVAL_MS = 1_000L

/**
 * Diagnostic entry point for the one-shot wear launch experiment (work order W-739).
 *
 * The run starts only from the button below: nothing here runs on composition, and no
 * service, broadcast receiver or notification listener calls into [WearLaunchProbe].
 * Result lines live in memory only; a new run clears the previous ones.
 * The auto launch switch above the button (work order W-744) only reads and writes
 * [NavigationSettings.autoLaunchBand]; it does not start the experiment.
 *
 * 与时间线区一致，这里只产出卡片内容，标题与折叠在外层分区卡。
 */
@Composable
fun WearLaunchDiagnosticsBody(transport: XiaomiWearableTransport) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }

    AutoLaunchBandSection()
    Text(
        "先让手环退出导航页回到表盘再点；每个候选之间会停 4 秒，" +
            "请看手环有没有亮屏、有没有打开导航环页面，并把看到的告诉检视者。",
        color = NavigationTheme.Colors.textSecondary,
        fontSize = NavigationTheme.TextSize.caption,
    )
    PrimaryAction(
        label = if (running) "运行中…" else "拉起手环导航页（实验）",
        onClick = {
            if (!running) {
                lines.clear()
                running = true
                scope.launch {
                    try {
                        val probe = WearLaunchProbe(context.applicationContext, transport)
                        probe.run(onLine = { line -> lines.add(line) })
                    } finally {
                        running = false
                    }
                }
            }
        },        modifier = Modifier.fillMaxWidth(),
        enabled = !running,
    )
    if (lines.isNotEmpty()) {
        MonoBlock(lines.joinToString("\n"))
    }
}

/** Auto launch explanation plus its switch, shared with the home switch's preference key. */
@Composable
private fun AutoLaunchBandSection() {
    val context = LocalContext.current
    val settings = remember { NavigationSettings(context) }
    var autoLaunchBand by remember { mutableStateOf(settings.autoLaunchBand) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "开始导航时自动拉起手环导航页",
                color = NavigationTheme.Colors.textPrimary,
                fontSize = NavigationTheme.TextSize.body,
            )
            LastAutoLaunchLine(settings)
        }
        Switch(
            checked = autoLaunchBand,
            onCheckedChange = { value ->
                autoLaunchBand = value
                settings.autoLaunchBand = value
            },
            // 不设颜色就会落到 Material 默认主题色，和主页的墨绿主色不一致。
            colors = SwitchDefaults.colors(
                checkedThumbColor = NavigationTheme.Colors.onPrimary,
                checkedTrackColor = NavigationTheme.Colors.primary,
                uncheckedThumbColor = NavigationTheme.Colors.cardBackground,
                uncheckedTrackColor = NavigationTheme.Colors.segmentTrack,
                uncheckedBorderColor = NavigationTheme.Colors.cardBorder,
            ),
        )
    }
}

/** Last auto launch outcome, refreshed once per second; no record at all reads as 尚无. */
@Composable
private fun LastAutoLaunchLine(settings: NavigationSettings) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    var text by remember { mutableStateOf(settings.lastAutoLaunchText) }
    var atMs by remember { mutableStateOf(settings.lastAutoLaunchAtMs) }

    LaunchedEffect(settings) {
        while (true) {
            text = settings.lastAutoLaunchText
            atMs = settings.lastAutoLaunchAtMs
            delay(AUTO_LAUNCH_REFRESH_INTERVAL_MS)
        }
    }

    val line = if (text.isEmpty() || atMs <= 0L) "最近一次：尚无"
    else "最近一次：$text（${clock.format(Date(atMs))}）"
    Text(line, color = NavigationTheme.Colors.textSecondary, fontSize = NavigationTheme.TextSize.caption)
}
