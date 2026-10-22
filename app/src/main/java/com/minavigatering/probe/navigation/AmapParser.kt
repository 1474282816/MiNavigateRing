package com.minavigatering.probe.navigation

/** Text-only parser. Never infers an arrow from an unknown notification or icon. */
data class AmapInstruction(val status: String, val action: String? = null,
    val distanceM: Int? = null, val roadName: String? = null, val exitNumber: Int? = null)

/**
 * Closeout states are built here so "listener gone" and "route ended" can never be
 * confused with a real turn: both carry no action, and the removal grace is neither an
 * arrival nor an end until no tracked Amap notification is left.
 */
object AmapCloseout {
    const val REMOVAL_GRACE_MS = 5_000L
    fun listenerDisconnected(): AmapInstruction = AmapInstruction(status = "unrecognized")
    fun navigationEnded(): AmapInstruction = AmapInstruction(status = "ended")
    fun afterRemovalGrace(noTrackedAmapNotifications: Boolean): AmapInstruction? =
        if (noTrackedAmapNotifications) AmapInstruction(status = "unrecognized") else null
}

object AmapParser {
    const val PACKAGE = "com.autonavi.minimap"
    private val directions = listOf(
        "向左掉头" to "uturn-left", "左侧掉头" to "uturn-left", "向右掉头" to "uturn-right",
        "右侧掉头" to "uturn-right",
        "驶出环岛" to "roundabout-exit", "离开环岛" to "roundabout-exit",
        "进入环岛" to "roundabout-enter", "左后方" to "sharp-left", "右后方" to "sharp-right",
        "左前方" to "slight-left", "右前方" to "slight-right",
        "靠左" to "keep-left", "靠右" to "keep-right", "左转" to "left", "右转" to "right",
        "直行" to "straight", "继续向前" to "straight", "到达目的地" to "arrive")
    private val distance = Regex("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|米)(?:后|处|\\s|，|,|$)")
    private val road = Regex("(?:进入|驶入|转入|沿着|沿)\\s*([^，。；\\n]{1,40}?(?:大道|公路|街|路|巷|桥))")
    private val exit = Regex("第\\s*(\\d+)\\s*(?:个)?出口")
    fun parse(packageName: String, fields: List<String>): AmapInstruction? {
        if (packageName != PACKAGE) return null
        val text = fields.filter { it.isNotBlank() }.distinct().joinToString("\n")
        if (listOf("导航已结束", "结束导航", "已退出导航").any { it in text }) return AmapInstruction("ended")
        val found = directions.firstOrNull { it.first in text }
        val match = distance.find(text)
        // Ads, weather and generic Amap notices are not navigation.
        val navigationContext = listOf("导航中", "正在导航", "路线重新规划", "重新规划路线").any { it in text }
        if (!navigationContext && !(match != null && (found != null || "掉头" in text)) && found?.second != "arrive") return null
        val metres = match?.let {
            val value = it.groupValues[1].toDoubleOrNull() ?: return@let null
            val m = value * if (it.groupValues[2] == "米") 1 else 1000
            if (m.isFinite() && m >= 0 && m <= 100000) kotlin.math.round(m).toInt() else null
        }
        return AmapInstruction(if (found == null) "unrecognized" else "active", found?.second,
            metres, road.find(text)?.groupValues?.get(1)?.trim(), exit.find(text)?.groupValues?.get(1)?.toIntOrNull())
    }
}

/**
 * Distance updates preserve a turn ID; a new turn or route session creates one.
 *
 * [tag] only widens the id shape to `amap-turn-<tag>-<n>` so two listener instances of the
 * same process cannot share a turn number; an empty tag keeps the historical
 * `amap-turn-<n>` form. It never changes which notification starts a new turn.
 */
class AmapTurnTracker(private val tag: String = "") {
    private var last: AmapInstruction? = null
    private var turn = 0L
    fun actionId(next: AmapInstruction): String {
        val previous = last
        if (previous == null || previous.status != "active" || next.action != previous.action ||
            next.roadName != previous.roadName || next.exitNumber != previous.exitNumber ||
            (previous.distanceM != null && next.distanceM != null && previous.distanceM <= 100 &&
                next.distanceM > previous.distanceM + 100)) turn++
        last = next
        return if (tag.isEmpty()) "amap-turn-$turn" else "amap-turn-$tag-$turn"
    }
}
