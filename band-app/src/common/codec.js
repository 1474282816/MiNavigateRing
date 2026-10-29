"use strict";

// CommonJS codec for the Vela band probe.
// Chinese action labels mirror design/NAVIGATION_ICONS.md. The 14 action ids
// mirror band-app/src/common/icons.js (copied read-only from design data).

const ACTION_LABELS = {
  "left": "左转",
  "right": "右转",
  "straight": "直行",
  "slight-left": "向左前方",
  "slight-right": "向右前方",
  "sharp-left": "向左后方",
  "sharp-right": "向右后方",
  "keep-left": "靠左行驶",
  "keep-right": "靠右行驶",
  "uturn-left": "左侧掉头",
  "uturn-right": "右侧掉头",
  "roundabout-enter": "进入环岛",
  "roundabout-exit": "驶出环岛",
  "arrive": "到达目的地"
};

const VALID_ACTIONS = new Set(Object.keys(ACTION_LABELS));

// Turn reminders fire once per actionId; advisory reminders (walk 30 m /
// cycle 100 m) also fire once per actionId and are merged into the turn
// vibration when the first message for that id is already inside the threshold.
const TURN_THRESHOLD_WALK_M = 30;
const TURN_THRESHOLD_CYCLE_M = 100;
// Each reminder history keeps at most the newest 32 ids. Bounded memory on the
// band, and a long-forgotten id is allowed to remind again.
const REMINDER_HISTORY_LIMIT = 32;
// Local receive-time based liveness timeout: no new frame for 15 s means the
// band shows the connecting state again and hides the stale turn.
const STALE_AFTER_MS = 15000;

// On-demand vibration patterns for the phone-side diagnosis screen. `vibrate()`
// only offers short/long on this hardware and `vibrator.start()` is documented
// as unsupported on REDMI Watch 6, so repeated patterns are stitched together
// from single calls. Anything outside this set is ignored, never guessed.
const VIBE_PATTERNS = new Set(["short", "long", "short3", "long2", "long3"]);

// Turn-reminder strength, chosen on the phone: 0 标准 / 1 强 / 2 最强.
//
// The motor amplitude belongs to the watch's own 设置 → 声音与振动 → 振动强度 switch and
// `start()`/`stop()`/`getSystemDefaultMode()` are all documented as unsupported on REDMI
// Watch 6, so nothing here can make one pulse harder. What it can do is keep the motor
// driven: 强 repeats the long pulse twice, 最强 three times. 800 ms between pulses is
// wider than the long pulse itself, so each beat lands as its own jolt instead of being
// swallowed by a still-running vibration task.
const REMINDER_GAP_MS = 800;
const REMINDER_DEFAULT_LEVEL = 1;

function normalizeVibeLevel(value) {
  const level = toNumber(value);
  if (level === 0 || level === 1 || level === 2) {
    return level;
  }
  // Unknown (or absent) means "the band keeps what it already plays"; the caller then
  // falls back to REMINDER_DEFAULT_LEVEL rather than guessing a strength level.
  return null;
}

// Steps are {mode, atMs}: atMs is the offset from the reminder start, so the caller can
// schedule the whole burst up front while the first beat still fires immediately.
function reminderPatternOf(level) {
  const normalized = normalizeVibeLevel(level);
  const effective = normalized === null ? REMINDER_DEFAULT_LEVEL : normalized;
  if (effective === 0) {
    return [{ mode: "short", atMs: 0 }];
  }
  const count = effective === 2 ? 3 : 2;
  const steps = [];
  for (let i = 0; i < count; i += 1) {
    steps.push({ mode: "long", atMs: i * REMINDER_GAP_MS });
  }
  return steps;
}

function toNumber(value) {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim() !== "") {
    const n = Number(value);
    if (Number.isFinite(n)) {
      return n;
    }
  }
  return null;
}

function toStringOrNull(value) {
  return typeof value === "string" && value !== "" ? value : null;
}

// Normalize an incoming message into a stable shape. The raw `action` value is
// preserved verbatim: unknown actions are never rewritten (never to "straight").
// Identity is carried by the explicit actionId string, never by the action text.
function decodeMessage(message) {
  let raw = message;
  if (typeof raw === "string") {
    try {
      raw = JSON.parse(raw);
    } catch (err) {
      raw = {};
    }
  }
  if (!raw || typeof raw !== "object") {
    raw = {};
  }
  return {
    v: raw.v,
    kind: raw.kind,
    id: raw.id,
    // keep the original action text; do not map unknown values to straight
    action: raw.action,
    // identity for de-duplication: explicit string or null, never the action
    actionId: toStringOrNull(raw.actionId),
    mode: toStringOrNull(raw.mode),
    distanceM: toNumber(raw.distanceM),
    roadName: typeof raw.roadName === "string" ? raw.roadName : "",
    exitNumber: raw.exitNumber === undefined ? null : raw.exitNumber,
    navStatus: raw.navStatus,
    link: raw.link,
    heartbeat: raw.heartbeat === true,
    sentAtMs: toNumber(raw.sentAtMs),
    // Diagnosis-only channel: a vibration request, never part of the display state.
    vibe: toStringOrNull(raw.vibe),
    // Reminder strength for the *turn* vibration; null means "no change requested".
    vibeLevel: normalizeVibeLevel(raw.vibeLevel)
  };
}

// Returns the vibration pattern a frame asks for, or null for a normal frame.
// Kept separate from reduceDisplay so a vibration test can never change the
// title, distance, road or action the band is showing.
function vibePatternOf(message) {
  const m = decodeMessage(message);
  if (m.vibe !== null && VIBE_PATTERNS.has(m.vibe)) {
    return m.vibe;
  }
  return null;
}

function emptyDisplay() {
  return {
    title: "连接中",
    distanceM: null,
    roadName: "",
    exitLabel: "",
    actionId: null,
    mode: "cycle",
    remindedTurn: [],
    remindedAdvisory: [],
    receivedAtMs: 0,
    vibrate: false
  };
}

// Copy the fields that a reducer branch does not overwrite. `vibrate` is never
// carried: every branch states it explicitly. The two reminder histories are
// shared by reference, not copied: every branch here is read-only, so a
// heartbeat, a link event, a tick or a repeated frame must not pay for a copy
// of the whole array. The active branch creates a new array only when it
// actually appends an id, leaving the caller's prev untouched.
function carryPrev(prev) {
  const p = prev || {};
  return {
    title: p.title !== undefined ? p.title : "连接中",
    distanceM: p.distanceM !== undefined ? p.distanceM : null,
    roadName: p.roadName !== undefined ? p.roadName : "",
    exitLabel: p.exitLabel !== undefined ? p.exitLabel : "",
    actionId: p.actionId !== undefined ? p.actionId : null,
    // Default matches the phone-side default (cycling); a carried mode always wins.
    mode: typeof p.mode === "string" ? p.mode : "cycle",
    remindedTurn: Array.isArray(p.remindedTurn) ? p.remindedTurn : [],
    remindedAdvisory: Array.isArray(p.remindedAdvisory) ? p.remindedAdvisory : [],
    receivedAtMs: p.receivedAtMs !== undefined ? p.receivedAtMs : 0
  };
}

// A reminder history keeps at most the newest REMINDER_HISTORY_LIMIT ids; the
// oldest fall out once the cap is reached. The returned array is always a fresh
// one, so `history` is never mutated: a codec call must not modify its prev.
function withIdAppended(history, id) {
  const next =
    history.length >= REMINDER_HISTORY_LIMIT
      ? history.slice(history.length - REMINDER_HISTORY_LIMIT + 1)
      : history.slice();
  next.push(id);
  return next;
}

// exitNumber is independent data: null stays empty, never fabricated.
function exitLabelFrom(exitNumber) {
  if (exitNumber === null || exitNumber === undefined || exitNumber === "") {
    return "";
  }
  return "第" + exitNumber + "出口";
}

const LINK_INVALID = new Set(["connecting", "disconnected"]);

function thresholdFor(mode) {
  return mode === "cycle" ? TURN_THRESHOLD_CYCLE_M : TURN_THRESHOLD_WALK_M;
}

// Every "there is no navigation to show" outcome (ended, idle, unrecognized
// status or unknown action) shares one title, so the band never shows a stale
// or guessed direction.
const NO_NAVIGATION_TITLE = "暂无导航";

// Pure display reducer. Never returns a fallback action of "straight".
function reduceDisplay(prev, message, nowMs) {
  const base = carryPrev(prev);
  const m = decodeMessage(message);
  const now = typeof nowMs === "number" ? nowMs : Date.now();

  // 1. heartbeat only refreshes liveness; it must not change title or distance.
  if (m.heartbeat === true) {
    return Object.assign({}, base, {
      receivedAtMs: now,
      vibrate: false
    });
  }

  // 2. link down: an explicit connecting state, judged from the link field
  //    only (never from sentAtMs), and the stale turn is hidden.
  if (LINK_INVALID.has(m.link)) {
    return Object.assign({}, base, {
      title: "连接中",
      distanceM: null,
      roadName: "",
      exitLabel: "",
      receivedAtMs: now,
      vibrate: false
    });
  }

  // 3. navigation explicitly ended.
  if (m.navStatus === "ended") {
    return Object.assign({}, base, {
      title: NO_NAVIGATION_TITLE,
      distanceM: null,
      roadName: "",
      exitLabel: "",
      receivedAtMs: now,
      vibrate: false
    });
  }

  // 4. explicit idle: no identity is kept either.
  if (m.navStatus === "idle") {
    return Object.assign({}, base, {
      title: NO_NAVIGATION_TITLE,
      distanceM: null,
      roadName: "",
      exitLabel: "",
      actionId: null,
      receivedAtMs: now,
      vibrate: false
    });
  }

  // 5. unrecognized status or unknown action: explicit unavailable state,
  //    never a straight fallback. The raw action stays readable via
  //    decodeMessage().action.
  if (m.navStatus === "unrecognized" || !VALID_ACTIONS.has(m.action)) {
    return Object.assign({}, base, {
      title: NO_NAVIGATION_TITLE,
      distanceM: null,
      roadName: "",
      exitLabel: "",
      receivedAtMs: now,
      vibrate: false
    });
  }

  // 6. valid active state.
  const mode = m.mode !== null ? m.mode : base.mode;
  const threshold = thresholdFor(mode);
  const id = m.actionId;
  // Shared by reference until an id is actually appended: a distance update of
  // an already reminded turn allocates nothing.
  let remindedTurn = base.remindedTurn;
  let remindedAdvisory = base.remindedAdvisory;
  let vibrate = false;

  if (id !== null) {
    const newTurn = remindedTurn.indexOf(id) === -1;
    const inThreshold = m.distanceM !== null && m.distanceM <= threshold;
    if (newTurn) {
      // A new turn always vibrates once. If it already starts inside the
      // advisory threshold, that reminder is merged into the same vibration.
      vibrate = true;
      remindedTurn = withIdAppended(remindedTurn, id);
      if (inThreshold && remindedAdvisory.indexOf(id) === -1) {
        remindedAdvisory = withIdAppended(remindedAdvisory, id);
      }
    } else if (inThreshold && remindedAdvisory.indexOf(id) === -1) {
      // Same turn entering the advisory threshold for the first time.
      vibrate = true;
      remindedAdvisory = withIdAppended(remindedAdvisory, id);
    }
  }

  return {
    title: ACTION_LABELS[m.action],
    distanceM: m.distanceM,
    roadName: m.roadName,
    exitLabel: exitLabelFrom(m.exitNumber),
    actionId: id,
    mode,
    remindedTurn,
    remindedAdvisory,
    receivedAtMs: now,
    vibrate
  };
}

// Local liveness tick: needs no new frame. After 15 s without a received frame
// the display degrades to the connecting state and hides distance and road.
function tick(prev, nowMs) {
  const base = carryPrev(prev);
  const now = typeof nowMs === "number" ? nowMs : Date.now();
  if (base.receivedAtMs > 0 && now - base.receivedAtMs > STALE_AFTER_MS) {
    return Object.assign({}, base, {
      title: "连接中",
      distanceM: null,
      roadName: "",
      exitLabel: "",
      vibrate: false
    });
  }
  return Object.assign({}, base, { vibrate: false });
}

// A message is a valid state (worth acking) only when it carries a recognized
// active navigation action.
function isValidState(message) {
  const m = decodeMessage(message);
  return m.navStatus === "active" && VALID_ACTIONS.has(m.action);
}

module.exports = {
  ACTION_LABELS,
  VALID_ACTIONS,
  NO_NAVIGATION_TITLE,
  VIBE_PATTERNS,
  REMINDER_DEFAULT_LEVEL,
  REMINDER_GAP_MS,
  normalizeVibeLevel,
  reminderPatternOf,
  decodeMessage,
  reduceDisplay,
  tick,
  isValidState,
  vibePatternOf,
  emptyDisplay
};
