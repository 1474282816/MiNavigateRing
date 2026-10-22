"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const nodeVm = require("node:vm");

const srcDir = path.resolve(__dirname, "..", "src");
const APP_UX = path.join(srcDir, "app.ux");

const session = require(path.resolve(__dirname, "..", "src", "common", "session.js"));

const T0 = 1700000000000;

function active(overrides) {
  return Object.assign(
    { navStatus: "active", action: "left", distanceM: 300, roadName: "中山路", sentAtMs: T0 },
    overrides
  );
}

// Test 1: actionId de-duplication, once-per-id turn and advisory reminders,
// merged when the first frame of an id already starts inside the threshold.
test("actionId de-duplication and 30/100m reminders fire once per id", () => {
  session.resetForTest();

  // Same actionId: the second distance update must not vibrate again.
  const a = session.onMessage(active({ actionId: "t1", distanceM: 300 }), T0);
  assert.strictEqual(a.vibrate, true);
  const b = session.onMessage(active({ actionId: "t1", distanceM: 200 }), T0 + 1000);
  assert.strictEqual(b.vibrate, false);
  assert.strictEqual(b.display.distanceM, 200);

  // Another actionId, still a left turn: it is a new identity, so it vibrates.
  const c = session.onMessage(active({ actionId: "t2", distanceM: 300 }), T0 + 2000);
  assert.strictEqual(c.vibrate, true);
  assert.strictEqual(c.display.title, "左转");

  // Walk threshold is 30 m: same id entering it vibrates once, then repeats
  // keep quiet.
  const d = session.onMessage(active({ actionId: "t2", distanceM: 30 }), T0 + 3000);
  assert.strictEqual(d.vibrate, true);
  const e = session.onMessage(active({ actionId: "t2", distanceM: 20 }), T0 + 4000);
  assert.strictEqual(e.vibrate, false);
  assert.strictEqual(e.display.distanceM, 20);

  // Cycle threshold is 100 m, and a new id already inside the threshold is
  // merged into the single turn vibration.
  const f = session.onMessage(active({ actionId: "t3", mode: "cycle", distanceM: 90 }), T0 + 5000);
  assert.strictEqual(f.vibrate, true);
  const g = session.onMessage(active({ actionId: "t3", mode: "cycle", distanceM: 50 }), T0 + 6000);
  assert.strictEqual(g.vibrate, false);
  assert.strictEqual(g.display.distanceM, 50);
});

// Test 2: local-receive-time 15 s timeout, ack fields, unknown action and
// null exitNumber handling.
test("tick after 15s without a new frame shows connecting and hides stale data", () => {
  session.resetForTest();

  const shown = session.onMessage(
    active({ actionId: "t1", distanceM: 300, roadName: "中山路" }),
    T0
  ).display;
  assert.strictEqual(shown.title, "左转");
  assert.strictEqual(shown.distanceM, 300);

  // Still fresh: re-entering the page re-reads the newest turn and distance.
  const reread = session.current();
  assert.strictEqual(reread.title, "左转");
  assert.strictEqual(reread.distanceM, 300);

  const late = session.tick(T0 + 15001);
  assert.strictEqual(late.title, "连接中");
  assert.strictEqual(late.distanceM, null);
  assert.strictEqual(late.roadName, "");
});

test("ack carries the message id, heartbeat has no ack and keeps distance", () => {
  session.resetForTest();

  const data = session.onMessage(
    active({ actionId: "t1", id: "msg-7", distanceM: 300, roadName: "中山路" }),
    T0
  );
  assert.ok(data.ack !== null);
  assert.strictEqual(data.ack.refId, "msg-7");
  assert.strictEqual(data.ack.kind, "ack");

  const beat = session.onMessage({ heartbeat: true, distanceM: 999, id: "msg-8" }, T0 + 5000);
  assert.strictEqual(beat.ack, null);
  assert.strictEqual(beat.display.distanceM, 300);
  assert.strictEqual(beat.display.title, "左转");
});

test("unknown action is unavailable, never straight; null exitNumber is empty", () => {  session.resetForTest();

  const unknown = session.onMessage(
    { navStatus: "active", action: "mystery", distanceM: 100, sentAtMs: T0 },
    T0
  );
  assert.strictEqual(unknown.display.title, "暂无导航");
  assert.notStrictEqual(unknown.display.title, "直行");
  assert.strictEqual(unknown.display.distanceM, null);

  const exit = session.onMessage(
    {
      navStatus: "active",
      action: "roundabout-exit",
      actionId: "t9",
      distanceM: 50,
      exitNumber: null,
      sentAtMs: T0
    },
    T0 + 1000
  );
  assert.strictEqual(exit.display.title, "驶出环岛");
  assert.strictEqual(exit.display.exitLabel, "");
  assert.notStrictEqual(exit.display.exitLabel, "第2出口");
});

// Test 5: the diagnostic note* helpers only write their own fields and never
// touch the reduced display; resetForTest restores every initial value.
test("diagnostic notes never change the title and reset returns to initial", () => {
  session.resetForTest();

  // Initial values.
  assert.strictEqual(session.current().diagReady, "未探测");
  assert.strictEqual(session.current().diagStatus, "未探测");
  assert.strictEqual(session.current().inboundSummary, "0帧");
  assert.strictEqual(session.current().linkEvent, "无");

  // A real turn is on screen first.
  const shown = session.onMessage(active({ actionId: "t1", distanceM: 300 }), T0).display;
  assert.strictEqual(shown.title, "左转");
  assert.strictEqual(shown.distanceM, 300);

  // Every note* helper is write-only for its diagnostic field.
  session.noteDiagReady("2已断开");
  assert.strictEqual(session.current().diagReady, "2已断开");
  assert.strictEqual(session.current().title, "左转");
  assert.strictEqual(session.current().distanceM, 300);

  session.noteDiagnosis("1001对端未安装");
  assert.strictEqual(session.current().diagStatus, "1001对端未安装");
  assert.strictEqual(session.current().title, "左转");

  session.noteInbound("string id=probe-2");
  assert.strictEqual(session.current().inboundSummary, "string id=probe-2");
  assert.strictEqual(session.current().title, "左转");

  session.noteLink("open");
  assert.strictEqual(session.current().linkEvent, "open");
  assert.strictEqual(session.current().title, "左转");

  // resetForTest clears both the display and the diagnostic fields.
  session.resetForTest();
  assert.strictEqual(session.current().title, "连接中");
  assert.strictEqual(session.current().diagReady, "未探测");
  assert.strictEqual(session.current().diagStatus, "未探测");
  assert.strictEqual(session.current().inboundSummary, "0帧");
  assert.strictEqual(session.current().linkEvent, "无");
});

// Test 6: the real app.ux entry. It must decode a JSON string frame exactly
// once and hand the decoded object to the session, keep the diagnosis summary's
// original-type prefix and the malformed-frame outcome unchanged, never ack a
// heartbeat, and the module-level inbound ring must stay bounded.
//
// The script body, session.js and codec.js all run inside one vm realm whose
// JSON is a counting wrapper, so every JSON.parse on the whole path (app entry
// included) is observed. The real global JSON is never patched, so nothing
// outside this test can be affected.
const REAL_JSON = JSON;

function makeAppRealm(overrides) {
  const parseCalls = { n: 0 };
  const countingJSON = {
    parse(text) {
      parseCalls.n += 1;
      return REAL_JSON.parse(text);
    },
    stringify: REAL_JSON.stringify
  };

  const sandbox = {
    console,
    Math,
    JSON: countingJSON,
    Set,
    Map,
    Object,
    Array,
    Number,
    String,
    Boolean,
    Error,
    TypeError,
    setTimeout,
    setInterval,
    clearTimeout,
    clearInterval,
    module: { exports: {} }
  };
  Object.assign(sandbox, overrides || {});
  sandbox.exports = sandbox.module.exports;
  const context = nodeVm.createContext(sandbox);

  const cache = new Map();
  function loadModule(resolvedPath) {
    const key = path.resolve(resolvedPath);
    if (cache.has(key)) {
      return cache.get(key).exports;
    }
    const mod = { exports: {} };
    cache.set(key, mod);
    const source = fs.readFileSync(key, "utf8");
    const factory = new nodeVm.Script(
      "(function (exports, require, module, __filename, __dirname) {\n" + source + "\n})",
      { filename: key }
    ).runInContext(context);
    factory(
      mod.exports,
      (id) => sandbox.require(id, path.dirname(key)),
      mod,
      key,
      path.dirname(key)
    );
    return mod.exports;
  }

  sandbox.require = function requireFrom(id, baseDir) {
    if (id === "@system.interconnect") {
      return sandbox.__interconnect;
    }
    if (id === "@system.vibrator") {
      return sandbox.__vibrator;
    }
    if (id.startsWith(".")) {
      return loadModule(path.resolve(baseDir || srcDir, id));
    }
    return require(id);
  };

  const text = fs.readFileSync(APP_UX, "utf8");
  const open = text.indexOf("<script>");
  const close = text.lastIndexOf("</script>");
  assert.ok(open !== -1 && close > open, "app.ux must contain a <script> element");
  const code = text
    .slice(open + "<script>".length, close)
    .replace('import session from "./common/session.js";', 'const session = require("./common/session.js");')
    .replace("export default", "module.exports =");
  assert.ok(code.indexOf("export default") === -1, "export default was not rewritten");

  new nodeVm.Script(code, { filename: APP_UX }).runInContext(context);

  return {
    def: sandbox.module.exports,
    // The very instance app.ux required: same module state as the app sees.
    session: loadModule(path.join(srcDir, "common", "session.js")),
    codec: loadModule(path.join(srcDir, "common", "codec.js")),
    parseCalls
  };
}

test("app.ux parses a frame string once and the inbound ring stays bounded", () => {
  let clock = T0;
  const acks = [];
  const vibrations = [];
  let connect = null;

  // The captured session instance is reset through the realm instance itself,
  // so the assertion below reads exactly the state the app renders from.
  const realm = makeAppRealm({
    Date: { now: () => clock },
    __interconnect: {
      instance() {
        connect = {
          onmessage: null,
          onopen: null,
          onclose: null,
          onerror: null,
          send(msg) {
            acks.push(msg.data);
          },
          getReadyState(options) {
            options.success({ status: 1 });
          },
          diagnosis(options) {
            options.success({ status: 0 });
          }
        };
        return connect;
      }
    },
    __vibrator: {
      vibrate() {
        vibrations.push("short");
      }
    }
  });
  const session = realm.session;
  session.resetForTest();

  assert.doesNotThrow(() => realm.def.onCreate());
  assert.ok(connect && typeof connect.onmessage === "function", "onmessage must be registered");

  // 1. A successful JSON string frame: parsed exactly once from the app entry,
  //    summarised with the original string prefix plus its id, rendered and acked.
  const frameText = REAL_JSON.stringify({
    v: 1,
    kind: "state",
    id: "probe-vm-9",
    navStatus: "active",
    action: "left",
    actionId: "t-vm-1",
    distanceM: 300,
    roadName: "中山路",
    sentAtMs: T0
  });
  realm.parseCalls.n = 0;
  connect.onmessage({ data: frameText });
  assert.strictEqual(realm.parseCalls.n, 1, "a JSON string frame must be parsed exactly once");
  assert.strictEqual(session.current().inboundSummary, "string id=probe-vm-9");
  assert.strictEqual(session.current().title, "左转");
  assert.strictEqual(session.current().distanceM, 300);
  assert.strictEqual(acks.length, 1);
  assert.strictEqual(acks[0].refId, "probe-vm-9");
  assert.strictEqual(vibrations.length, 1);

  // A repeated frame with the same action id is not re-parsed a second time and
  // still costs exactly one parse.
  realm.parseCalls.n = 0;
  connect.onmessage({ data: REAL_JSON.stringify({ id: "probe-vm-10", navStatus: "active", action: "left", actionId: "t-vm-1", distanceM: 200 }) });
  assert.strictEqual(realm.parseCalls.n, 1);
  assert.strictEqual(session.current().distanceM, 200);
  assert.strictEqual(acks.length, 2);
  assert.strictEqual(vibrations.length, 1, "the same action id must not vibrate again");

  // 2. A heartbeat refreshes liveness and keeps the shown turn; it arrives as an
  //    object already, so it is never parsed, never acked and never vibrated.
  realm.parseCalls.n = 0;
  connect.onmessage({ data: { heartbeat: true, id: "beat-vm", distanceM: 999 } });
  assert.strictEqual(realm.parseCalls.n, 0);
  assert.strictEqual(session.current().inboundSummary, "object id=beat-vm");
  assert.strictEqual(session.current().title, "左转");
  assert.strictEqual(session.current().distanceM, 200);
  assert.strictEqual(acks.length, 2);
  assert.strictEqual(vibrations.length, 1);

  // 3. A malformed JSON string keeps the baseline outcome item by item: the
  //    summary stays the string type (never 解析失败), the display falls back to
  //    暂无导航 and no ack and no vibration happen.
  realm.parseCalls.n = 0;
  connect.onmessage({ data: "{not json" });
  assert.strictEqual(realm.parseCalls.n, 1, "the malformed path is allowed exactly one attempt");
  assert.strictEqual(session.current().inboundSummary, "string");
  assert.notStrictEqual(session.current().inboundSummary, "解析失败");
  assert.strictEqual(session.current().title, "暂无导航");
  assert.strictEqual(session.current().distanceM, null);
  assert.strictEqual(acks.length, 2, "a malformed frame must not be acked");
  assert.strictEqual(vibrations.length, 1, "a malformed frame must not vibrate");

  // 4. The 60 s window: five frames now, one frame 70 s later. Only the newest
  //    one is still inside the window although the ring still retains all six.
  session.resetForTest();
  clock = T0;
  for (let i = 0; i < 5; i += 1) {
    connect.onmessage({ data: { heartbeat: true, id: "w-" + i } });
  }
  // The stats object crosses the vm realm boundary, so its prototype is not this
  // file's Object.prototype: assert the fields individually.
  const stats = session.inboundStats(clock);
  assert.strictEqual(stats.count, 5);
  assert.strictEqual(stats.retained, 5);
  clock = T0 + 70000;
  connect.onmessage({ data: { heartbeat: true, id: "w-late" } });
  const lateStats = session.inboundStats(clock);
  assert.strictEqual(lateStats.count, 1);
  assert.strictEqual(lateStats.retained, 6);

  // 5. The ring is bounded at 600 entries: 601 handled frames keep the newest
  //    600, and the navigation display is untouched by this bookkeeping.
  session.resetForTest();
  clock = T0;
  const before = session.current().title;
  for (let i = 0; i < 601; i += 1) {
    connect.onmessage({ data: { heartbeat: true, id: "cap-" + i } });
  }
  const capped = session.inboundStats(clock);
  assert.strictEqual(capped.retained, 600);
  assert.strictEqual(capped.count, 600);
  assert.strictEqual(session.current().title, before);

  // 6. resetForTest clears the ring as well.
  session.resetForTest();
  const cleared = session.inboundStats(clock);
  assert.strictEqual(cleared.count, 0);
  assert.strictEqual(cleared.retained, 0);
});
