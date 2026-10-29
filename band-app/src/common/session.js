"use strict";

// Session state for the band probe.
//
// Owns the latest reduced display so that leaving and re-entering the page
// keeps the newest state. Deliberately free of any @system.* import: the
// transport (interconnect) lives in app.ux, the rendering in the page.
// CommonJS, like codec.js, so node --test can require it directly.

const codec = require("./codec.js");

let latest = codec.emptyDisplay();

// Reminder strength last requested by the phone (0 标准 / 1 强 / 2 最强), or null when the
// phone never said. Held here rather than in the display so it survives page re-entry and
// so a frame without the field can never reset it.
let reminderLevel = null;

// Diagnostic observability, deliberately separate from the reduced display.
// These four fields describe the transport itself (ready state, official
// diagnosis status, inbound frame summaries and link events). They must never
// feed reduceDisplay and must never change title or distanceM: the navigation
// rendering logic is untouched by this ticket.
const DIAG_INITIAL = {
  diagReady: "未探测",
  diagStatus: "未探测",
  inboundSummary: "0帧",
  linkEvent: "无"
};

function diagInitial() {
  return {
    diagReady: DIAG_INITIAL.diagReady,
    diagStatus: DIAG_INITIAL.diagStatus,
    inboundSummary: DIAG_INITIAL.inboundSummary,
    linkEvent: DIAG_INITIAL.linkEvent
  };
}

// Re-attach the diagnostic fields to a display object produced by the codec.
// codec.reduceDisplay/codec.tick never know about them, so each reducer call
// must carry them over explicitly; otherwise an inbound frame or a liveness
// tick would wipe the ready/diagnosis/inbound/link lines back to defaults.
function withDiag(display) {
  const base = display || {};
  return Object.assign({}, base, {
    diagReady: latest.diagReady !== undefined ? latest.diagReady : DIAG_INITIAL.diagReady,
    diagStatus: latest.diagStatus !== undefined ? latest.diagStatus : DIAG_INITIAL.diagStatus,
    inboundSummary:
      latest.inboundSummary !== undefined ? latest.inboundSummary : DIAG_INITIAL.inboundSummary,
    linkEvent: latest.linkEvent !== undefined ? latest.linkEvent : DIAG_INITIAL.linkEvent
  });
}

// Write-only diagnostic fields: each note* helper copies the current value,
// replaces exactly one field and stores it back. It never calls reduceDisplay
// and never touches title/distanceM/roadName/exitLabel.
function writeDiag(field, value) {
  latest = Object.assign({}, latest, { [field]: value });
  return latest;
}

function noteDiagReady(text) {
  return writeDiag("diagReady", text);
}

function noteDiagnosis(statusText) {
  return writeDiag("diagStatus", statusText);
}

function noteInbound(summaryText) {
  return writeDiag("inboundSummary", summaryText);
}

function noteLink(eventText) {
  return writeDiag("linkEvent", eventText);
}

function normalizeNow(nowMs) {
  return typeof nowMs === "number" ? nowMs : Date.now();
}

// Bounded inbound-frame ring: the receive timestamp of each handled frame, at
// most INBOUND_RING_CAPACITY entries, oldest dropped once full. It is plain
// module state, deliberately kept outside the reduced display: it is never
// passed to reduceDisplay, no display field is derived from it, and no
// diagnostic line renders it. The page consumes it through inboundStats().
const INBOUND_RING_CAPACITY = 600;
const INBOUND_WINDOW_MS = 60000;

let inboundSlots = [];
let inboundStart = 0;
let inboundCount = 0;

// One call per handled frame, including heartbeats and unparsable frames.
function recordInbound(nowMs) {
  if (inboundCount < INBOUND_RING_CAPACITY) {
    inboundSlots[(inboundStart + inboundCount) % INBOUND_RING_CAPACITY] = nowMs;
    inboundCount += 1;
    return;
  }
  // Full: the ring keeps the newest 600 by overwriting the oldest slot.
  inboundSlots[inboundStart] = nowMs;
  inboundStart = (inboundStart + 1) % INBOUND_RING_CAPACITY;
}

// Independent inbound-frame counters. `retained` is how many timestamps the
// bounded ring currently holds (never more than 600); `count` is how many of
// them fall inside the last 60 s (timestamp <= now and now - ts < 60000).
function inboundStats(nowMs) {
  const now = normalizeNow(nowMs);
  let count = 0;
  for (let i = 0; i < inboundCount; i += 1) {
    const ts = inboundSlots[(inboundStart + i) % INBOUND_RING_CAPACITY];
    if (ts <= now && now - ts < INBOUND_WINDOW_MS) {
      count += 1;
    }
  }
  return { count, retained: inboundCount };
}

function resetInbound() {
  inboundSlots = [];
  inboundStart = 0;
  inboundCount = 0;
}

// Handle one inbound frame. Returns the reduced display, whether the band
// should vibrate, and the ack object to send back (null when none is due).
// codec.reduceDisplay yields a pure display object and knows nothing about
// the diagnostic fields, so they are re-attached afterwards: an inbound frame
// must not silently reset the ready/diagnosis/inbound/link lines.
function onMessage(raw, nowMs) {
  const now = normalizeNow(nowMs);
  // Decode exactly once and hand the decoded object to the reducer. The
  // reducer still accepts a string itself, but passing the decoded value stops
  // the very same JSON string from being parsed again one layer down. A string
  // that does not parse becomes an empty object here, which is exactly what
  // codec.decodeMessage's own fallback produces, so the visible outcome of a
  // malformed frame is unchanged (暂无导航, no ack).
  const decoded = codec.decodeMessage(raw);
  // A frame carrying a legal level updates the reminder strength; null (absent or invalid)
  // leaves the previous choice alone so an idle frame cannot downgrade the reminder.
  if (decoded.vibeLevel !== null) {
    reminderLevel = decoded.vibeLevel;
  }
  recordInbound(now);
  // A vibration test frame carries no navigation meaning: the reducer is skipped so
  // the band keeps showing whatever turn it had before the test button was pressed.
  const vibe = codec.vibePatternOf(decoded);
  let display;
  if (vibe === null) {
    display = codec.reduceDisplay(latest, decoded, now);
    latest = withDiag(display);
  } else {
    display = latest;
  }

  let ack = null;
  // Only real data frames are acknowledged: no ack for heartbeats and no ack
  // for frames without a usable id.
  if (decoded.heartbeat !== true && typeof decoded.id === "string" && decoded.id !== "") {
    ack = {
      v: 1,
      kind: "ack",
      id: "ack-" + now,
      refId: decoded.id,
      sentAtMs: now
    };
  }

  return { display, vibrate: vibe === null && display.vibrate === true, vibe, ack };
}

// Apply the local 15 s liveness timeout and persist the result. Like
// onMessage, the diagnostic fields survive the tick unchanged.
function tick(nowMs) {
  latest = withDiag(codec.tick(latest, normalizeNow(nowMs)));
  return latest;
}

// The stored newest display, untouched (no implicit ticking against wall
// clock: callers decide when liveness is re-evaluated). The four diagnostic
// fields are carried out as well; they are never part of the reducer state.
function current() {
  return Object.assign({}, latest, {
    diagReady: latest.diagReady !== undefined ? latest.diagReady : DIAG_INITIAL.diagReady,
    diagStatus: latest.diagStatus !== undefined ? latest.diagStatus : DIAG_INITIAL.diagStatus,
    inboundSummary:
      latest.inboundSummary !== undefined ? latest.inboundSummary : DIAG_INITIAL.inboundSummary,
    linkEvent: latest.linkEvent !== undefined ? latest.linkEvent : DIAG_INITIAL.linkEvent
  });
}

// The transport went away: show the connecting state and hide the stale turn
// instead of leaving an old instruction on screen.
function onLinkClosed(nowMs) {
  const now = normalizeNow(nowMs);
  latest = Object.assign({}, latest, {
    title: "连接中",
    distanceM: null,
    roadName: "",
    exitLabel: "",
    receivedAtMs: now,
    vibrate: false
  });
  return latest;
}

// The transport came back, but no frame has arrived yet: stay in the
// connecting state and keep the old turn hidden until a valid frame proves
// what is actually current.
function onLinkOpen(nowMs) {
  const now = normalizeNow(nowMs);
  latest = Object.assign({}, latest, {
    title: "连接中",
    distanceM: null,
    roadName: "",
    exitLabel: "",
    receivedAtMs: now,
    vibrate: false
  });
  return latest;
}

// Reminder strength to use right now: the last legal level from the phone, or the default
// when it never sent one. app.ux asks for this instead of holding its own copy, so the page
// and the session can never disagree about how hard the next turn should buzz.
function vibeLevel() {
  return reminderLevel === null ? codec.REMINDER_DEFAULT_LEVEL : reminderLevel;
}

// The burst to play for the next turn reminder: [{mode, atMs}, ...].
function reminderPattern() {
  return codec.reminderPatternOf(vibeLevel());
}

// Test-only reset. The page must never call this on destroy: doing so would
// wipe the state that onShow is supposed to read back. The diagnostic fields
// go back to their initial values too.
function resetForTest() {
  latest = Object.assign(codec.emptyDisplay(), diagInitial());
  reminderLevel = null;
  resetInbound();
  return latest;
}

module.exports = {
  onMessage,
  tick,
  current,
  vibeLevel,
  reminderPattern,
  onLinkClosed,
  onLinkOpen,
  noteDiagReady,
  noteDiagnosis,
  noteInbound,
  noteLink,
  inboundStats,
  resetForTest
};
