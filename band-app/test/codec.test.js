"use strict";

const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");

const codec = require(path.resolve(__dirname, "..", "src", "common", "codec.js"));

const T0 = 1700000000000;

test("left turn decodes 300m with Chinese label", () => {
  const d = codec.reduceDisplay(
    codec.emptyDisplay(),
    { navStatus: "active", action: "left", distanceM: 300, roadName: "中山路", sentAtMs: T0 },
    T0
  );
  assert.strictEqual(d.title, "左转");
  assert.strictEqual(d.distanceM, 300);
});

test("left turn updates to 200m", () => {
  const first = codec.reduceDisplay(
    codec.emptyDisplay(),
    { navStatus: "active", action: "left", distanceM: 300, roadName: "中山路", sentAtMs: T0 },
    T0
  );
  const second = codec.reduceDisplay(
    first,
    { navStatus: "active", action: "left", distanceM: 200, roadName: "中山路", sentAtMs: T0 + 1000 },
    T0 + 1000
  );
  assert.strictEqual(second.title, "左转");
  assert.strictEqual(second.distanceM, 200);
});

test("unknown action stays mystery and shows unavailable title", () => {
  const d = codec.reduceDisplay(
    codec.emptyDisplay(),
    { navStatus: "active", action: "mystery", distanceM: 100, roadName: "X", sentAtMs: T0 },
    T0
  );
  assert.strictEqual(d.title, "暂无导航");
  assert.strictEqual(codec.decodeMessage({ action: "mystery" }).action, "mystery");
  assert.notStrictEqual(d.title, "直行");
});

test("heartbeat true refreshes time but does not overwrite shown distance", () => {
  const shown = codec.reduceDisplay(
    codec.emptyDisplay(),
    { navStatus: "active", action: "left", distanceM: 300, roadName: "中山路", sentAtMs: T0 },
    T0
  );
  const beat = codec.reduceDisplay(
    shown,
    { heartbeat: true, distanceM: 999, sentAtMs: T0 + 5000 },
    T0 + 5000
  );
  assert.strictEqual(beat.distanceM, 300);
  assert.strictEqual(beat.title, "左转");
  assert.strictEqual(beat.receivedAtMs, T0 + 5000);
  assert.strictEqual(beat.vibrate, false);
});

test("ended navigation shows ended title with no vibration", () => {
  const d = codec.reduceDisplay(
    codec.emptyDisplay(),
    { navStatus: "ended", sentAtMs: T0 },
    T0
  );
  assert.strictEqual(d.title, "暂无导航");
  assert.strictEqual(d.vibrate, false);
});

test("disconnected link shows connecting title with no vibration", () => {
  const d = codec.reduceDisplay(
    codec.emptyDisplay(),
    { navStatus: "active", action: "left", distanceM: 300, link: "disconnected", sentAtMs: T0 },
    T0
  );
  assert.strictEqual(d.title, "连接中");
  assert.strictEqual(d.vibrate, false);
});

test("exitNumber null yields empty exit label, never 第2出口", () => {
  const d = codec.reduceDisplay(
    codec.emptyDisplay(),
    { navStatus: "active", action: "roundabout-exit", distanceM: 50, exitNumber: null, sentAtMs: T0 },
    T0
  );
  assert.strictEqual(d.title, "驶出环岛");
  assert.strictEqual(d.exitLabel, "");
});

test("new action id triggers vibration, same action id does not", () => {
  // Identity is the explicit actionId string, not the action text.
  const first = codec.reduceDisplay(
    codec.emptyDisplay(),
    { navStatus: "active", action: "left", actionId: "turn-1", distanceM: 300, sentAtMs: T0 },
    T0
  );
  assert.strictEqual(first.vibrate, true);
  const same = codec.reduceDisplay(
    first,
    { navStatus: "active", action: "left", actionId: "turn-1", distanceM: 200, sentAtMs: T0 + 1000 },
    T0 + 1000
  );
  assert.strictEqual(same.vibrate, false);
  assert.strictEqual(same.distanceM, 200);
});

// Test 9: the reminder histories are shared by reference when nothing is
// appended (no whole-array copy for heartbeats, ticks or repeated turn frames),
// the caller's prev is never mutated or detached, each history is capped at the
// newest 32 ids, and a real append does not disturb the previous array or the
// reminder rules.
test("reminder histories are shared unless an id is appended, capped at 32", () => {
  const TURN_IDS = [];
  for (let i = 0; i < 32; i += 1) {
    TURN_IDS.push("h" + i);
  }
  // A fully populated, frozen history: any in-place push/shift/slice would throw
  // here in this strict-mode module, which is exactly the "never modify prev"
  // contract being checked.
  const prev = codec.emptyDisplay();
  prev.title = "左转";
  prev.actionId = "h31";
  prev.distanceM = 300;
  prev.receivedAtMs = T0;
  prev.remindedTurn = Object.freeze(TURN_IDS.slice());
  prev.remindedAdvisory = Object.freeze(TURN_IDS.slice());
  Object.freeze(prev);

  // A repeated turn at a far distance: a plain distance update, nothing new, so
  // both histories must be the very same arrays (no copy).
  const repeat = codec.reduceDisplay(
    prev,
    { navStatus: "active", action: "left", actionId: "h31", distanceM: 280, sentAtMs: T0 + 1000 },
    T0 + 1000
  );
  assert.strictEqual(repeat.vibrate, false);
  assert.strictEqual(repeat.distanceM, 280);
  assert.strictEqual(repeat.remindedTurn, prev.remindedTurn);
  assert.strictEqual(repeat.remindedAdvisory, prev.remindedAdvisory);

  // A heartbeat refreshes liveness only and must share both arrays too.
  const beat = codec.reduceDisplay(prev, { heartbeat: true }, T0 + 2000);
  assert.strictEqual(beat.remindedTurn, prev.remindedTurn);
  assert.strictEqual(beat.remindedAdvisory, prev.remindedAdvisory);

  // A tick keeps the same arrays as well.
  const ticked = codec.tick(prev, T0 + 3000);
  assert.strictEqual(ticked.remindedTurn, prev.remindedTurn);
  assert.strictEqual(ticked.remindedAdvisory, prev.remindedAdvisory);

  // A link event hides the turn but must not copy the histories either.
  const down = codec.reduceDisplay(
    prev,
    { navStatus: "active", action: "left", actionId: "h31", link: "disconnected", sentAtMs: T0 + 4000 },
    T0 + 4000
  );
  assert.strictEqual(down.remindedTurn, prev.remindedTurn);
  assert.strictEqual(down.remindedAdvisory, prev.remindedAdvisory);

  // A genuinely new turn: a fresh array of the newest 32 ids, the oldest id out,
  // and the previous array left untouched.
  const turnPrev = codec.emptyDisplay();
  turnPrev.remindedTurn = Object.freeze(TURN_IDS.slice());
  Object.freeze(turnPrev);
  const grown = codec.reduceDisplay(
    turnPrev,
    { navStatus: "active", action: "left", actionId: "h32", distanceM: 300, sentAtMs: T0 + 5000 },
    T0 + 5000
  );
  assert.strictEqual(grown.vibrate, true);
  assert.notStrictEqual(grown.remindedTurn, turnPrev.remindedTurn);
  assert.strictEqual(grown.remindedTurn.length, 32);
  assert.strictEqual(grown.remindedTurn.indexOf("h0"), -1, "the oldest id must fall out");
  assert.strictEqual(grown.remindedTurn[31], "h32");
  assert.strictEqual(turnPrev.remindedTurn.length, 32);
  assert.strictEqual(turnPrev.remindedTurn[0], "h0");
  assert.strictEqual(turnPrev.remindedTurn[31], "h31");
  assert.strictEqual(grown.remindedAdvisory.length, 0);

  // A new id inside the walk threshold merges into the one turn vibration and
  // appends to both histories, still bounded at 32 and still without touching
  // the previous arrays.
  const merged = codec.reduceDisplay(
    turnPrev,
    { navStatus: "active", action: "left", actionId: "h33", distanceM: 20, sentAtMs: T0 + 6000 },
    T0 + 6000
  );
  assert.strictEqual(merged.vibrate, true);
  assert.strictEqual(merged.remindedTurn.length, 32);
  assert.strictEqual(merged.remindedTurn[31], "h33");
  assert.strictEqual(merged.remindedTurn.indexOf("h0"), -1);
  assert.strictEqual(merged.remindedAdvisory.length, 1);
  assert.strictEqual(merged.remindedAdvisory[0], "h33");
  assert.strictEqual(turnPrev.remindedAdvisory.length, 0);
  assert.strictEqual(turnPrev.remindedTurn.length, 32);
  assert.strictEqual(turnPrev.remindedTurn[31], "h31");

  // A repeated id entering the advisory threshold for the first time: only the
  // advisory history grows, the turn history is shared by reference.
  const advisoryPrev = codec.emptyDisplay();
  advisoryPrev.actionId = "h31";
  advisoryPrev.remindedTurn = Object.freeze(TURN_IDS.slice());
  advisoryPrev.remindedAdvisory = Object.freeze(["other"]);
  Object.freeze(advisoryPrev);
  const advisory = codec.reduceDisplay(
    advisoryPrev,
    { navStatus: "active", action: "left", actionId: "h31", distanceM: 30, sentAtMs: T0 + 7000 },
    T0 + 7000
  );
  assert.strictEqual(advisory.vibrate, true);
  assert.strictEqual(advisory.remindedTurn, advisoryPrev.remindedTurn);
  assert.notStrictEqual(advisory.remindedAdvisory, advisoryPrev.remindedAdvisory);
  assert.deepStrictEqual(advisory.remindedAdvisory, ["other", "h31"]);
  assert.deepStrictEqual(advisoryPrev.remindedAdvisory, ["other"]);

  // The same id already inside the threshold a second time stays quiet, and the
  // advisory history is shared by reference then.
  const quiet = codec.reduceDisplay(
    advisory,
    { navStatus: "active", action: "left", actionId: "h31", distanceM: 25, sentAtMs: T0 + 8000 },
    T0 + 8000
  );
  assert.strictEqual(quiet.vibrate, false);
  assert.strictEqual(quiet.remindedTurn, advisory.remindedTurn);
  assert.strictEqual(quiet.remindedAdvisory, advisory.remindedAdvisory);
});
