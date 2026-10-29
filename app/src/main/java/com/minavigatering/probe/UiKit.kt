package com.minavigatering.probe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 主页与诊断页共用的展示件：卡片、按钮、状态点、折叠分区。
 *
 * 全部数值与颜色只从 [NavigationTheme] 取，因此两个页面天然同一套视觉；这里不持有任何
 * 状态，也不读 Context 或偏好。新增页面若需要不同尺寸，请先在 NavigationTheme 里加令牌，
 * 不要在本文件写魔法数。
 */

/** 白底描边卡片，主页状态卡与诊断分区卡共用。 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = NavigationTheme.Dimens.heroPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NavigationTheme.Dimens.cardCorner),
        colors = CardDefaults.cardColors(containerColor = NavigationTheme.Colors.cardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(NavigationTheme.Dimens.cardBorderWidth, NavigationTheme.Colors.cardBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap),
            content = content,
        )
    }
}

/** 深色卡片：主页的手环镜像卡与诊断页的链路概览卡共用同一底色。 */
@Composable
fun DarkCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = NavigationTheme.Dimens.heroPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NavigationTheme.Dimens.heroCorner),
        colors = CardDefaults.cardColors(containerColor = NavigationTheme.Colors.heroBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap),
            content = content,
        )
    }
}

/** 8dp 状态圆点；颜色由调用方按状态语义给（ok / pending / off）。 */
@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(NavigationTheme.Dimens.ringStrokeWidth * 4).background(color, CircleShape))
}

/** 深色卡里的一行「状态点 + 文字」。 */
@Composable
fun DotLine(color: Color, text: String, textColor: Color = NavigationTheme.Colors.textPrimary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.size(tinyGap))
        Text(text, color = textColor, fontSize = NavigationTheme.TextSize.body)
    }
}

/** 卡片内的小标题（灰、12sp），用于卡片内再分块。 */
@Composable
fun CardLabel(text: String) {
    Text(
        text = text,
        color = NavigationTheme.Colors.textSecondary,
        fontSize = NavigationTheme.TextSize.caption,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * 诊断页的信息行：标签在上、值在下。
 *
 * 值经常是长文本（节点名、SDK 异常、路径），左右分栏会把长值挤成一条细缝，
 * 所以统一用两行式，值可以自由换行。[mono] 给报文、日志这类需要等宽的字段。
 */
@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = NavigationTheme.Colors.textSecondary, fontSize = NavigationTheme.TextSize.caption)
        Text(
            text = value,
            color = NavigationTheme.Colors.textPrimary,
            fontSize = NavigationTheme.TextSize.body,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

/** 深色等宽块：报文与时间线这类原始数据放在白卡里不刺眼，也不抢正文注意力。 */
@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(NavigationTheme.Colors.heroBackground, RoundedCornerShape(NavigationTheme.Dimens.cardCorner))
            .padding(NavigationTheme.Dimens.heroPadding),
    ) {
        Text(
            text = text,
            color = NavigationTheme.Colors.heroCaption,
            fontSize = NavigationTheme.TextSize.caption,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 主操作：主色填充。 */
@Composable
fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = NavigationTheme.Dimens.touchTarget),
        colors = ButtonDefaults.buttonColors(
            containerColor = NavigationTheme.Colors.primary,
            contentColor = NavigationTheme.Colors.onPrimary,
        ),
    ) {
        Text(label, color = NavigationTheme.Colors.onPrimary, fontSize = NavigationTheme.TextSize.body)
    }
}

/** 次操作：主色描边。用于「刷新 / 请求权限 / 停止」这类非主路径动作。 */
@Composable
fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = NavigationTheme.Dimens.touchTarget),
        border = BorderStroke(NavigationTheme.Dimens.cardBorderWidth, NavigationTheme.Colors.primary),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = NavigationTheme.Colors.primary,
        ),
    ) {
        Text(label, color = NavigationTheme.Colors.primary, fontSize = NavigationTheme.TextSize.body)
    }
}

/** 紧凑次操作：文字按钮，用于卡片内的「查看 / 允许 / 去设置」。 */
@Composable
fun InlineAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = NavigationTheme.Colors.primary,
        fontSize = NavigationTheme.TextSize.body,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .heightIn(min = NavigationTheme.Dimens.touchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = NavigationTheme.Dimens.heroGap, horizontal = tinyGap),
    )
}

/**
 * 可折叠分区卡。
 *
 * 诊断页有八块内容，全部铺开就是之前那面文字墙；这里统一成「标题行 + 内容」，
 * 标题行永远是 48dp 触控目标，右侧 ▾/▸ 提示当前状态。[onToggle] 为 null 时不可折叠。
 */
@Composable
fun SectionCard(
    title: String,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SurfaceCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .heightIn(min = NavigationTheme.Dimens.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = NavigationTheme.Colors.textPrimary,
                    fontSize = NavigationTheme.TextSize.body,
                    fontWeight = FontWeight.Medium,
                )
                subtitle?.let {
                    Text(it, color = NavigationTheme.Colors.textSecondary, fontSize = NavigationTheme.TextSize.caption)
                }
            }
            if (onToggle != null) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    color = NavigationTheme.Colors.textSecondary,
                    fontSize = NavigationTheme.TextSize.body,
                )
            }
        }
        AnimatedVisibility(
            visible = onToggle == null || expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap),
                content = content,
            )
        }
    }
}

/** 4dp：最小视觉间隔，由 2dp 描边令牌倍乘得出，避免另立魔法数。 */
private val tinyGap = NavigationTheme.Dimens.ringStrokeWidth * 2
