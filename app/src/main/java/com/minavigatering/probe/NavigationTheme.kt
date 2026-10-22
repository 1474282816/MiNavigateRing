package com.minavigatering.probe

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Central design tokens for the phone navigation UI (2026-10-15 redesign).
 *
 * Authority: .coagent/specs/phone-navigation-ui.md, section "视觉设计（2026-10-15 重设计）/令牌".
 * Every colour, dp and sp used by the navigation screen lives here so no magic numbers are
 * scattered across composables. This is a plain token holder: it installs no global theme and
 * has no side effects. Stateless composables may read it directly.
 */
object NavigationTheme {

    /** Colours taken verbatim from the design token table. */
    object Colors {
        /** 页面背景 — full page. */
        val pageBackground = Color(0xFFF3F5F4)

        /** 卡片底 — status card, settings checklist card. */
        val cardBackground = Color(0xFFFFFFFF)

        /** 卡片边框 0.5dp. */
        val cardBorder = Color(0xFFDFE5E2)

        /** 主文字. */
        val textPrimary = Color(0xFF14201B)

        /** 次文字 / 说明. */
        val textSecondary = Color(0xFF5B6B63)

        /** 主色 — primary buttons, selected segment, links; white text goes on top. */
        val primary = Color(0xFF0F6E56)

        /** Text drawn on [primary]. */
        val onPrimary = Color(0xFFFFFFFF)

        /** 柔和主色底 — mode chip. */
        val softPrimaryBackground = Color(0xFFD8EFE6)

        /** 柔和主色字 — mode chip. */
        val softPrimaryForeground = Color(0xFF085041)

        /** 主卡底 — the band mirror card. */
        val heroBackground = Color(0xFF0B0F0D)

        /** 主卡箭头与动作名. */
        val heroArrow = Color(0xFF00FF99)

        /** 主卡距离. */
        val heroDistance = Color(0xFFFFFFFF)

        /** 主卡标题文字（圆环与权限阶段，白色）. */
        val heroHeadline = Color(0xFFFFFFFF)

        /** 主卡路名. */
        val heroRoad = Color(0xFF00DFFF)

        /** 主卡出口. */
        val heroExit = Color(0xFFFFDD00)

        /** 主卡说明. */
        val heroCaption = Color(0xFF9FB3AA)

        /** 主卡说明 — permission phases (PERMISSION_OFF / LISTENER_DISCONNECTED). */
        val heroPermissionCaption = Color(0xFFCBD5CF)

        /** 主卡小标题. */
        val heroLabel = Color(0xFF7F9A8E)

        /** 模式分段控件轨道底 — r2 终审视觉修正补入. */
        val segmentTrack = Color(0xFFE4E9E6)

        /** 状态点/正常. */
        val statusOk = Color(0xFF1D9E75)

        /** 状态点/待确认. */
        val statusPending = Color(0xFFBA7517)

        /** 状态点/关闭或未知. */
        val statusOff = Color(0xFF888780)

        /** 警示 — ring and icon of the hero card when permission is missing. */
        val warning = Color(0xFFFFDD00)
    }

    /** Type scale from the design token table, in sp. */
    object TextSize {
        /** 页面标题. */
        val pageTitle = 28.sp

        /** 主卡动作名. */
        val heroAction = 20.sp

        /** 主卡距离. */
        val heroDistance = 48.sp

        /** 主卡路名. */
        val heroRoad = 16.sp

        /** 出口. */
        val exit = 14.sp

        /** 正文. */
        val body = 14.sp

        /** 说明与小标题. */
        val caption = 12.sp
    }

    /** Stroke alphas of the hero card's three-layer ring. */
    object Alphas {
        /** Outer ring stroke. */
        const val ringOuter = 0.22f

        /** Middle ring stroke. */
        const val ringMiddle = 0.5f
    }

    /** Spacing and shape sizes from the design token table, in dp. */
    object Dimens {
        /** 页面左右内边距. */
        val pagePadding = 20.dp

        /** 页面内容上下内边距（在 safeDrawingPadding 之内）— r2 终审视觉修正补入. */
        val pageVerticalPadding = 12.dp

        /** 模式分段控件轨道内边距 — r2 终审视觉修正补入. */
        val segmentTrackPadding = 3.dp

        /** 块间间距. */
        val blockGap = 12.dp

        /** 普通卡片圆角. */
        val cardCorner = 16.dp

        /** 普通卡片边框宽度. */
        val cardBorderWidth = 0.5.dp

        /** 主卡圆角. */
        val heroCorner = 24.dp

        /** 主卡内边距. */
        val heroPadding = 20.dp

        /** 主卡动作矢量图标尺寸. */
        val iconSize = 96.dp

        /** 主卡三层圆环尺寸. */
        val ringSize = 72.dp

        /** 主卡内元素间距. */
        val heroGap = 8.dp

        /** 圆环外圈半径（半径比 34:22:9）. */
        val ringOuterRadius = 34.dp

        /** 圆环中圈半径. */
        val ringMiddleRadius = 22.dp

        /** 圆环中心实心半径. */
        val ringCenterRadius = 9.dp

        /** 圆环描边宽度. */
        val ringStrokeWidth = 2.dp

        /** 权限阶段圆环内警示图标尺寸. */
        val warningIconSize = 32.dp

        /** 警示三角描边宽度. */
        val warningStrokeWidth = 2.dp

        /** 最小触控目标. */
        val touchTarget = 48.dp
    }
}

/**
 * Maps a navigation action id to its VectorDrawable, or null when there is no icon.
 *
 * Pure function: it only reads [NavProtocol.ALLOWED_ACTIONS] members and returns a resource id.
 * Unknown ids and null return null — an unrecognised direction is never drawn as `straight`.
 */
fun actionIconRes(actionId: String?): Int? = when (actionId) {
    "left" -> R.drawable.nav_left
    "right" -> R.drawable.nav_right
    "straight" -> R.drawable.nav_straight
    "slight-left" -> R.drawable.nav_slight_left
    "slight-right" -> R.drawable.nav_slight_right
    "sharp-left" -> R.drawable.nav_sharp_left
    "sharp-right" -> R.drawable.nav_sharp_right
    "keep-left" -> R.drawable.nav_keep_left
    "keep-right" -> R.drawable.nav_keep_right
    "uturn-left" -> R.drawable.nav_uturn_left
    "uturn-right" -> R.drawable.nav_uturn_right
    "roundabout-enter" -> R.drawable.nav_roundabout_enter
    "roundabout-exit" -> R.drawable.nav_roundabout_exit
    "arrive" -> R.drawable.nav_arrive
    else -> null
}
