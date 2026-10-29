package com.minavigatering.probe.navigation

import android.content.Context
import com.minavigatering.probe.protocol.NavCodec
import com.minavigatering.probe.protocol.NavProtocol
import com.minavigatering.probe.protocol.StateMessage

/**
 * The only snapshot a cold start is allowed to show: position and turn identity are gone,
 * so it must not look like an active instruction (work order W-643).
 *
 * Kept outside the `NavigationSettings` class so the host JVM tests can call it directly.
 */
fun strippedRecovery(state: StateMessage, now: Long, mode: String): StateMessage = state.copy(
    id = NavigationSettings.RESTORE_ID_PREFIX + now,
    sentAtMs = now,
    navStatus = NavProtocol.STATUS_UNRECOGNIZED,
    action = null,
    exitNumber = null,
    distanceM = null,
    roadName = null,
    actionId = null,
    heartbeat = false,
    mode = if (mode == NavProtocol.MODE_WALK) NavProtocol.MODE_WALK else NavProtocol.DEFAULT_MODE,
)

class NavigationSettings(context: Context) {
    companion object {
        /** Id prefix of a cold-start snapshot, so the screen tells a restore apart from a fault. */
        const val RESTORE_ID_PREFIX = "restore-"

        /**
         * Mode used before the user ever picks one. Cycling is the default because this build
         * targets wrist navigation on a bike; a stored `walk` is still honoured once written.
         */
        const val DEFAULT_MODE = NavProtocol.DEFAULT_MODE
    }

    private val prefs = context.getSharedPreferences("navigation", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var mode: String
        // Only two values are legal; anything else (missing, corrupted, older build) falls back
        // to DEFAULT_MODE instead of silently becoming walking.
        get() = if (prefs.getString("mode", DEFAULT_MODE) == NavProtocol.MODE_WALK) {
            NavProtocol.MODE_WALK
        } else {
            NavProtocol.MODE_CYCLE
        }
        set(value) { require(value in listOf(NavProtocol.MODE_WALK, NavProtocol.MODE_CYCLE))
            prefs.edit().putString("mode", value).apply() }
    /** Last background restore outcome: a short de-identified word set, never a raw message. */
    var lastRestoreText: String
        get() = prefs.getString("lastRestoreText", "") ?: ""
        set(value) { prefs.edit().putString("lastRestoreText", value).apply() }
    /** Wall clock of that outcome, so a stale attempt is never read as a fresh one. */
    var lastRestoreAtMs: Long
        get() = prefs.getLong("lastRestoreAtMs", 0L)
        set(value) { prefs.edit().putLong("lastRestoreAtMs", value).apply() }
    /** Home switch: whether starting navigation also launches the band navigation page. */
    var autoLaunchBand: Boolean
        get() = prefs.getBoolean("autoLaunchBand", true)
        set(value) { prefs.edit().putBoolean("autoLaunchBand", value).apply() }

    /**
     * Reminder strength sent to the band (0 标准 / 1 强 / 2 最强).
     *
     * Amplitude itself cannot be raised from an app — that is the watch's own
     * 设置 → 声音与振动 → 振动强度 switch — so this only changes how long and how often
     * the motor is driven. A stored value outside the three levels falls back to the default.
     */
    var vibeLevel: Int
        get() = NavProtocol.normalizeVibeLevel(prefs.getInt("vibeLevel", NavProtocol.DEFAULT_VIBE_LEVEL))
        set(value) {
            val level = NavProtocol.normalizeVibeLevel(value)
            prefs.edit().putInt("vibeLevel", level).apply()
        }
    /** Last auto launch outcome: a short de-identified word set, never a raw message. */
    var lastAutoLaunchText: String
        get() = prefs.getString("lastAutoLaunchText", "") ?: ""
        set(value) { prefs.edit().putString("lastAutoLaunchText", value).apply() }
    /** Wall clock of that auto launch outcome, so a stale result is never read as fresh. */
    var lastAutoLaunchAtMs: Long
        get() = prefs.getLong("lastAutoLaunchAtMs", 0L)
        set(value) { prefs.edit().putLong("lastAutoLaunchAtMs", value).apply() }
    fun save(state: StateMessage) { prefs.edit().putString("latest", NavCodec.encodeToString(state)).apply() }
    fun restore(now: Long): StateMessage? = runCatching {
        val raw = prefs.getString("latest", null) ?: return@runCatching null
        val state = NavCodec.decodeFromString(raw) as StateMessage
        // Persisted active routes cannot be trusted after process death without a fresh source snapshot.
        strippedRecovery(state, now, mode)
    }.getOrNull()
}
