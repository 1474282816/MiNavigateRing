package com.minavigatering.probe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minavigatering.probe.navigation.NavigationPhase
import com.minavigatering.probe.navigation.NavigationUiModel

/**
 * The dark "band mirror" hero card: it renders exactly what the band is showing (or why nothing
 * is being shown) for the currently mapped phase.
 *
 * Stateless: it only consumes [ui] and design tokens from [NavigationTheme]. Every dynamic string
 * comes from [ui]; no action id is ever put on screen and an unknown action is never drawn as a
 * default direction — [actionIconRes] returns null and no icon is rendered.
 */
@Composable
fun NavigationHeroCard(ui: NavigationUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NavigationTheme.Dimens.heroCorner),
        colors = CardDefaults.cardColors(containerColor = NavigationTheme.Colors.heroBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NavigationTheme.Dimens.heroPadding),
            verticalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap),
        ) {
            heroLabelFor(ui.phase)?.let { HeroLabel(it) }
            when (ui.phase) {
                NavigationPhase.ACTIVE -> ActiveHero(ui)
                NavigationPhase.PERMISSION_OFF,
                NavigationPhase.LISTENER_DISCONNECTED -> PermissionHero(ui)
                else -> RingHero(ui)
            }
        }
    }
}

/** Small heading above the card content; null means the phase shows no heading. */
private fun heroLabelFor(phase: NavigationPhase): String? = when (phase) {
    NavigationPhase.ACTIVE -> "手环上显示的内容"
    NavigationPhase.PERMISSION_OFF, NavigationPhase.LISTENER_DISCONNECTED -> "需要处理"
    else -> null
}

@Composable
private fun HeroLabel(text: String) {
    Text(
        text = text,
        color = NavigationTheme.Colors.heroLabel,
        fontSize = NavigationTheme.TextSize.caption,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * Full-width stage body shared by the three hero phases: it only centres its children
 * horizontally, it never changes font size, colour or content.
 */
@Composable
private fun HeroStage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NavigationTheme.Dimens.heroGap),
        content = content,
    )
}

/** Valid instruction: green action vector icon, action name, distance, road and roundabout exit. */
@Composable
private fun ActiveHero(ui: NavigationUiModel) = HeroStage {
    // Unknown or absent action -> no icon at all; never a fallback direction.
    actionIconRes(ui.actionId)?.let { res ->
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(NavigationTheme.Dimens.iconSize),
        )
    }
    HeroText(ui.headline, NavigationTheme.Colors.heroArrow, NavigationTheme.TextSize.heroAction)
    ui.distanceText?.let {
        // 48sp distance is explicitly medium weight; the ring/headline texts keep their own weight.
        Text(
            text = it,
            modifier = Modifier.fillMaxWidth(),
            color = NavigationTheme.Colors.heroDistance,
            fontSize = NavigationTheme.TextSize.heroDistance,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
    ui.roadName?.let {
        HeroText(it, NavigationTheme.Colors.heroRoad, NavigationTheme.TextSize.heroRoad)
    }
    ui.exitText?.let {
        HeroText(it, NavigationTheme.Colors.heroExit, NavigationTheme.TextSize.exit)
    }
    HeroText(ui.guidance, NavigationTheme.Colors.heroCaption, NavigationTheme.TextSize.caption)
}

/** Waiting / connecting / bridge off / unavailable / ended: the three-layer mirror ring. */
@Composable
private fun RingHero(ui: NavigationUiModel) = HeroStage {
    val tint = when (ui.phase) {
        NavigationPhase.BRIDGE_OFF, NavigationPhase.UNAVAILABLE -> NavigationTheme.Colors.statusOff
        else -> NavigationTheme.Colors.heroArrow
    }
    Box(Modifier.size(NavigationTheme.Dimens.ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawMirrorRing(tint) }
    }
    HeroText(ui.headline, NavigationTheme.Colors.heroHeadline, NavigationTheme.TextSize.heroAction)
    HeroText(ui.guidance, NavigationTheme.Colors.heroCaption, NavigationTheme.TextSize.caption)
}

/** Permission off / listener disconnected: amber ring with a warning glyph. */
@Composable
private fun PermissionHero(ui: NavigationUiModel) = HeroStage {
    val tint = NavigationTheme.Colors.warning
    Box(Modifier.size(NavigationTheme.Dimens.ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawMirrorRing(tint) }
        Canvas(Modifier.size(NavigationTheme.Dimens.warningIconSize)) {
            if (ui.phase == NavigationPhase.PERMISSION_OFF) drawLockGlyph(tint) else drawWarningGlyph(tint)
        }
    }
    HeroText(ui.headline, NavigationTheme.Colors.heroHeadline, NavigationTheme.TextSize.heroAction)
    HeroText(ui.guidance, NavigationTheme.Colors.heroPermissionCaption, NavigationTheme.TextSize.caption)
}

@Composable
private fun HeroText(text: String, color: Color, size: androidx.compose.ui.unit.TextUnit) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        color = color,
        fontSize = size,
        textAlign = TextAlign.Center,
    )
}

/** Three concentric strokes (radius ratio 34:22:9) with a solid centre, alphas from the tokens. */
private fun DrawScope.drawMirrorRing(tint: Color) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    val stroke = Stroke(width = NavigationTheme.Dimens.ringStrokeWidth.toPx())
    drawCircle(tint.copy(alpha = NavigationTheme.Alphas.ringOuter), NavigationTheme.Dimens.ringOuterRadius.toPx(), centre, style = stroke)
    drawCircle(tint.copy(alpha = NavigationTheme.Alphas.ringMiddle), NavigationTheme.Dimens.ringMiddleRadius.toPx(), centre, style = stroke)
    drawCircle(tint, NavigationTheme.Dimens.ringCenterRadius.toPx(), centre)
}

/** Canvas-drawn padlock: body plus an open shackle arc. Used for PERMISSION_OFF. */
private fun DrawScope.drawLockGlyph(tint: Color) {
    val w = size.width
    val h = size.height
    drawArc(
        color = tint,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.28f, h * 0.1f),
        size = Size(w * 0.44f, h * 0.46f),
        style = Stroke(width = w * 0.09f, cap = StrokeCap.Round),
    )
    drawRoundRect(
        color = tint,
        topLeft = Offset(w * 0.16f, h * 0.42f),
        size = Size(w * 0.68f, h * 0.5f),
        cornerRadius = CornerRadius(w * 0.1f),
    )
}

/** Canvas-drawn warning triangle with an exclamation mark. Used for LISTENER_DISCONNECTED. */
private fun DrawScope.drawWarningGlyph(tint: Color) {
    val w = size.width
    val h = size.height
    val triangle = Path().apply {
        moveTo(w * 0.5f, h * 0.08f)
        lineTo(w * 0.96f, h * 0.9f)
        lineTo(w * 0.04f, h * 0.9f)
        close()
    }
    drawPath(triangle, tint, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
    drawLine(tint, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.5f, h * 0.62f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
    drawCircle(tint, radius = w * 0.05f, center = Offset(w * 0.5f, h * 0.76f))
}
