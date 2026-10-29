package com.minavigatering.probe.protocol

/**
 * Wire protocol for the MiNavigateRing probe.
 *
 * Pure Kotlin on purpose: the unit tests exercise this without an Android SDK or the
 * xms-wearable AAR on the classpath. See PROJECT_SPEC.md section 4 (职责边界) — this layer
 * transports state only; it never matches Amap text and never renders.
 */
object NavProtocol {

    const val VERSION = 1

    const val KIND_STATE = "state"
    const val KIND_ACK = "ack"

    const val LINK_CONNECTED = "connected"
    const val LINK_CONNECTING = "connecting"
    const val LINK_DISCONNECTED = "disconnected"

    const val STATUS_IDLE = "idle"
    const val STATUS_ACTIVE = "active"
    const val STATUS_UNRECOGNIZED = "unrecognized"
    const val STATUS_ENDED = "ended"

    const val MODE_WALK = "walk"
    const val MODE_CYCLE = "cycle"

    /**
     * Mode assumed before the user picks one. Cycling is the shipped default for this build;
     * an explicit `walk` written by the user is still honoured.
     */
    const val DEFAULT_MODE = MODE_CYCLE

    /** Walk advisory threshold, metres (PROJECT_SPEC.md section 6). */
    const val WALK_THRESHOLD_M = 30

    /** Cycle advisory threshold, metres (PROJECT_SPEC.md section 6). */
    const val CYCLE_THRESHOLD_M = 100

    /** Heartbeat period, milliseconds (PROJECT_SPEC.md section 7). */
    const val HEARTBEAT_PERIOD_MS = 5_000L

    /** Link is "connecting" after this gap without an accepted report (section 7). */
    const val LINK_TIMEOUT_MS = 15_000L

    /**
     * The 14 confirmed navigation actions, see design/NAVIGATION_ICONS.md.
     * Anything outside this set is unrecognised — it must never be coerced to `straight`.
     */
    val ALLOWED_ACTIONS: List<String> = listOf(
        "left",
        "right",
        "straight",
        "slight-left",
        "slight-right",
        "sharp-left",
        "sharp-right",
        "keep-left",
        "keep-right",
        "uturn-left",
        "uturn-right",
        "roundabout-enter",
        "roundabout-exit",
        "arrive",
    )

    val ALLOWED_LINKS: List<String> = listOf(LINK_CONNECTED, LINK_CONNECTING, LINK_DISCONNECTED)
    val ALLOWED_STATUSES: List<String> = listOf(STATUS_IDLE, STATUS_ACTIVE, STATUS_UNRECOGNIZED, STATUS_ENDED)

    /**
     * Vibration patterns the band is allowed to play on demand.
     *
     * `vibrate({mode})` only ships `short` and `long` on this hardware, and `vibrator.start()`
     * (duration/interval/count) is documented as unsupported on REDMI Watch 6, so the repeated
     * patterns are stitched together from single `short`/`long` calls on the band side.
     */
    const val VIBE_SHORT = "short"
    const val VIBE_LONG = "long"
    const val VIBE_SHORT3 = "short3"
    const val VIBE_LONG2 = "long2"
    const val VIBE_LONG3 = "long3"

    val ALLOWED_VIBES: List<String> = listOf(VIBE_SHORT, VIBE_LONG, VIBE_SHORT3, VIBE_LONG2, VIBE_LONG3)

    /**
     * Strength of the *turn reminder* vibration, chosen by the user on the phone.
     *
     * The amplitude of the motor is a system setting (`getSystemDefaultMode()`, 0/1/2) and is
     * read-only for third-party apps on REDMI Watch 6 — `start()`/`stop()`/`getSystemDefaultMode()`
     * are all documented as unsupported there. What a快应用 can control is how long and how often
     * the motor is driven, so a "stronger" reminder is stitched from repeated `long` pulses with a
     * gap short enough that the motor does not fully spin down between them.
     *
     * 0 = one short (the original behaviour), 1 = two long pulses, 2 = three long pulses.
     */
    const val VIBE_LEVEL_STANDARD = 0
    const val VIBE_LEVEL_STRONG = 1
    const val VIBE_LEVEL_MAX = 2

    /** Default is 1: a single short pulse is the weakest thing this hardware can do. */
    const val DEFAULT_VIBE_LEVEL = VIBE_LEVEL_STRONG

    val ALLOWED_VIBE_LEVELS: List<Int> =
        listOf(VIBE_LEVEL_STANDARD, VIBE_LEVEL_STRONG, VIBE_LEVEL_MAX)

    /** Anything outside the three levels falls back to the default instead of being clamped silently. */
    fun normalizeVibeLevel(level: Int?): Int =
        if (level != null && level in ALLOWED_VIBE_LEVELS) level else DEFAULT_VIBE_LEVEL

    val ALLOWED_KINDS: List<String> = listOf(KIND_STATE, KIND_ACK)

    fun isAllowedAction(action: String?): Boolean = action == null || action in ALLOWED_ACTIONS
}

sealed interface Message {
    val v: Int
    val kind: String
    val id: String
    val sentAtMs: Long
}

/**
 * Navigation state report.
 *
 * Field order is fixed by the work order: v, kind, id, sentAtMs, link, navStatus, action,
 * exitNumber, distanceM, roadName, actionId, mode, heartbeat, then the optional
 * transport-only fields (vibe, vibeLevel) which are omitted when null.
 */
data class StateMessage(
    override val v: Int = NavProtocol.VERSION,
    override val kind: String = NavProtocol.KIND_STATE,
    override val id: String,
    override val sentAtMs: Long,
    val link: String = NavProtocol.LINK_CONNECTED,
    val navStatus: String = NavProtocol.STATUS_IDLE,
    val action: String? = null,
    /** Roundabout exit number, null when unknown. Never inferred (NAVIGATION_ICONS.md). */
    val exitNumber: Int? = null,
    val distanceM: Int? = null,
    val roadName: String? = null,
    val actionId: String? = null,
    val mode: String? = null,
    val heartbeat: Boolean = false,
    /**
     * On-demand vibration pattern for the diagnosis screen only (one of [ALLOWED_VIBES]).
     *
     * A frame carrying this field is a transport-only ping: the band plays the pattern and
     * acknowledges it without touching the navigation display. Normal navigation frames always
     * leave it null, so the production reminder path is unchanged.
     */
    val vibe: String? = null,
    /**
     * Reminder strength (one of [NavProtocol.ALLOWED_VIBE_LEVELS]).
     *
     * Carried on every navigation frame rather than only on the first one, so a band that was
     * asleep, reinstalled or restarted still learns the current level from the next heartbeat.
     * Null (older phone build) means the band keeps its current level.
     */
    val vibeLevel: Int? = null,
) : Message

data class AckMessage(
    override val v: Int = NavProtocol.VERSION,
    override val kind: String = NavProtocol.KIND_ACK,
    override val id: String,
    val refId: String,
    override val sentAtMs: Long,
) : Message

/** UTF-8 JSON codec. Round-trips every field listed above. */
object NavCodec {

    fun encode(message: Message): ByteArray = encodeToString(message).toByteArray(Charsets.UTF_8)

    fun encodeToString(message: Message): String = when (message) {
        is StateMessage -> Json.obj(
            listOf(
                "v" to message.v,
                "kind" to message.kind,
                "id" to message.id,
                "sentAtMs" to message.sentAtMs,
                "link" to message.link,
                "navStatus" to message.navStatus,
                "action" to message.action,
                "exitNumber" to message.exitNumber,
                "distanceM" to message.distanceM,
                "roadName" to message.roadName,
                "actionId" to message.actionId,
                "mode" to message.mode,
                "heartbeat" to message.heartbeat,
                "vibe" to message.vibe,
                "vibeLevel" to message.vibeLevel,
            )
        )

        is AckMessage -> Json.obj(
            listOf(
                "v" to message.v,
                "kind" to message.kind,
                "id" to message.id,
                "refId" to message.refId,
                "sentAtMs" to message.sentAtMs,
            )
        )
    }

    fun decode(bytes: ByteArray): Message = decodeFromString(String(bytes, Charsets.UTF_8))

    fun decodeFromString(text: String): Message {
        val root = Json.parseObject(text)
        val kind = Json.asString(root["kind"])
            ?: throw IllegalArgumentException("missing kind")
        require(kind in NavProtocol.ALLOWED_KINDS) { "unsupported kind '$kind'" }
        return when (kind) {
            NavProtocol.KIND_STATE -> decodeState(root)
            NavProtocol.KIND_ACK -> decodeAck(root)
            else -> throw IllegalArgumentException("unsupported kind '$kind'")
        }
    }

    private fun decodeState(root: Map<String, Any?>): StateMessage {
        val id = Json.asString(root["id"]) ?: throw IllegalArgumentException("state.id missing")
        val sentAtMs = Json.asLong(root["sentAtMs"])
            ?: throw IllegalArgumentException("state.sentAtMs missing")
        val link = Json.asString(root["link"]) ?: NavProtocol.LINK_CONNECTED
        require(link in NavProtocol.ALLOWED_LINKS) { "unsupported link '$link'" }
        val navStatus = Json.asString(root["navStatus"]) ?: NavProtocol.STATUS_IDLE
        require(navStatus in NavProtocol.ALLOWED_STATUSES) { "unsupported navStatus '$navStatus'" }
        return StateMessage(
            v = Json.asInt(root["v"]) ?: NavProtocol.VERSION,
            kind = NavProtocol.KIND_STATE,
            id = id,
            sentAtMs = sentAtMs,
            link = link,
            navStatus = navStatus,
            // The raw action string is preserved verbatim so an unknown direction stays
            // visibly unknown instead of being normalised to a known value.
            action = Json.asString(root["action"]),
            exitNumber = Json.asInt(root["exitNumber"]),
            distanceM = Json.asInt(root["distanceM"]),
            roadName = Json.asString(root["roadName"]),
            actionId = Json.asString(root["actionId"]),
            mode = Json.asString(root["mode"]),
            heartbeat = Json.asBoolean(root["heartbeat"]) ?: false,
            // Unknown patterns are dropped here: the band must never receive a vibration
            // request it would have to guess the meaning of.
            vibe = Json.asString(root["vibe"])?.takeIf { it in NavProtocol.ALLOWED_VIBES },
            // An unknown level becomes null (band keeps what it has), never a silent 0.
            vibeLevel = Json.asInt(root["vibeLevel"])
                ?.takeIf { it in NavProtocol.ALLOWED_VIBE_LEVELS },
        )
    }

    private fun decodeAck(root: Map<String, Any?>): AckMessage {
        val id = Json.asString(root["id"]) ?: throw IllegalArgumentException("ack.id missing")
        val refId = Json.asString(root["refId"]) ?: throw IllegalArgumentException("ack.refId missing")
        val sentAtMs = Json.asLong(root["sentAtMs"])
            ?: throw IllegalArgumentException("ack.sentAtMs missing")
        return AckMessage(
            v = Json.asInt(root["v"]) ?: NavProtocol.VERSION,
            id = id,
            refId = refId,
            sentAtMs = sentAtMs,
        )
    }
}
