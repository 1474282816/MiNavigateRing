"use strict";

// Vibration bench regression test.
//
// The phone-side diagnosis screen asks for a pattern by sending a `vibe` field. This test
// loads the real app.ux, wires a fake host whose vibrator records every mode, and proves:
//   - each pattern reaches the vibrator with the documented mode,
//   - "3 short" and "2 long" really become three/two calls (REDMI Watch 6 has no start()),
//   - a vibration frame leaves the navigation display alone and is still acked,
//   - a normal turn reminder still vibrates exactly once, unaffected by the new channel.

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const nodeVm = require("node:vm");

const srcDir = path.resolve(__dirname, "..", "src");
const parse5 = require(path.resolve(__dirname, "..", "node_modules", "aiot-parse5"));

const APP_UX = path.join(srcDir, "app.ux");
const SESSION_JS = path.join(srcDir, "common", "session.js");

const T0 = 1700000000000;

function findScriptElement(fragment) {
  let hit = null;
  const walk = (node) => {
    if (hit) {
      return;
    }
    if (node.nodeName === "script") {
      hit = node;
      return;
    }
    (node.childNodes || []).forEach(walk);
  };
  walk(fragment);
  return hit;
}

// Loads app.ux in a vm with a fake host. `pendingTimers` collects setTimeout callbacks so the
// test decides when the burst steps fire (the real page must not vibrate synchronously three
// times; the gaps are what make the three beats distinguishable).
function boot() {
  const session = require(SESSION_JS);
  session.resetForTest();

  const modes = [];
  const pendingTimers = [];
  let connect = null;

  const fakeInterconnect = {
    instance() {
      connect = {
        onmessage: null,
        onopen: null,
        onclose: null,
        onerror: null,
        sent: [],
        send(payload) {
          this.sent.push(payload && payload.data);
        },
        getReadyState(options) {
          options.success({ status: 2 });
        },
        diagnosis(options) {
          options.success({ status: 1001 });
        }
      };
      return connect;
    }
  };
  const fakeVibrator = {
    vibrate(options) {
      modes.push(options && options.mode);
    }
  };

  const text = fs.readFileSync(APP_UX, "utf8");
  const element = findScriptElement(parse5.parseFragment(text, { scriptingEnabled: false }));
  assert.ok(element, "app.ux must contain a <script> element");
  const body = parse5.serialize(element);
  const code = body
    .replace(/^\s*<script[^>]*>/i, "")
    .replace(/<\/script>\s*$/i, "")
    .replace(
      'import session from "./common/session.js";',
      'const session = require("./common/session.js");'
    )
    .replace("export default", "module.exports =");

  const sandbox = {
    module: { exports: {} },
    require(id) {
      if (id === "@system.interconnect") {
        return fakeInterconnect;
      }
      if (id === "@system.vibrator") {
        return fakeVibrator;
      }
      if (id.startsWith(".")) {
        return require(path.resolve(srcDir, id));
      }
      return require(id);
    },
    console,
    Date,
    Math,
    JSON,
    Set,
    Map,
    Object,
    Array,
    Number,
    String,
    Boolean,
    Error,
    TypeError,
    setTimeout(cb) {
      pendingTimers.push(cb);
      return pendingTimers.length;
    },
    setInterval() {
      return 0;
    },
    clearTimeout() {},
    clearInterval() {}
  };
  sandbox.exports = sandbox.module.exports;
  new nodeVm.Script(code, { filename: APP_UX }).runInContext(nodeVm.createContext(sandbox));

  const def = sandbox.module.exports;
  def.onCreate();

  // Drain every queued timer in order, including timers queued by those callbacks.
  const flushTimers = () => {
    let guard = 0;
    while (pendingTimers.length > 0) {
      const cb = pendingTimers.shift();
      cb();
      guard += 1;
      assert.ok(guard < 20, "timer chain did not settle");
    }
  };

  return { def, session, get connect() { return connect; }, modes, flushTimers };
}

function activeLeft(overrides) {
  return Object.assign(
    {
      id: "amap-1",
      navStatus: "active",
      action: "left",
      actionId: "a1",
      distanceM: 80,
      roadName: "中山路",
      sentAtMs: T0
    },
    overrides
  );
}

test("short and long patterns each produce exactly one vibrate call", () => {
  const boot1 = boot();
  boot1.connect.onmessage({ data: { id: "vibe-short-1", vibe: "short" } });
  assert.deepStrictEqual(boot1.modes, ["short"]);

  const boot2 = boot();
  boot2.connect.onmessage({ data: { id: "vibe-long-1", vibe: "long" } });
  assert.deepStrictEqual(boot2.modes, ["long"]);
});

test("three short beats use three scheduled calls, not one", () => {
  const app = boot();
  app.connect.onmessage({ data: { id: "vibe-short3-1", vibe: "short3" } });
  // Only the first beat has fired; the rest must be scheduled, not immediate.
  assert.deepStrictEqual(app.modes, ["short"]);
  app.flushTimers();
  assert.deepStrictEqual(app.modes, ["short", "short", "short"]);
});

test("two long beats use two scheduled calls", () => {
  const app = boot();
  app.connect.onmessage({ data: { id: "vibe-long2-1", vibe: "long2" } });
  assert.deepStrictEqual(app.modes, ["long"]);
  app.flushTimers();
  assert.deepStrictEqual(app.modes, ["long", "long"]);
});

test("a vibration frame is acked but does not touch the displayed turn", () => {
  const app = boot();
  app.connect.onmessage({ data: activeLeft({ distanceM: 80 }) });
  const beforeTitle = app.session.current().title;
  const beforeDistance = app.session.current().distanceM;
  const sentBefore = app.connect.sent.length;

  app.connect.onmessage({ data: { id: "vibe-short-1", vibe: "short" } });
  assert.strictEqual(app.session.current().title, beforeTitle);
  assert.strictEqual(app.session.current().distanceM, beforeDistance);
  // The ack still goes back, so the phone can tell the frame landed.
  assert.strictEqual(app.connect.sent.length, sentBefore + 1);
  assert.strictEqual(app.connect.sent[app.connect.sent.length - 1].refId, "vibe-short-1");
});

test("a turn reminder uses the phone's strength level, two long pulses by default", () => {
  const app = boot();
  // No level in the frame: the band falls back to 强, which is two long pulses 800 ms apart.
  app.connect.onmessage({ data: activeLeft({ actionId: "turn-1", distanceM: 300 }) });
  assert.deepStrictEqual(app.modes, ["long"]);
  app.flushTimers();
  assert.deepStrictEqual(app.modes, ["long", "long"]);
  // Same id again: no second buzz, no extra timers.
  app.connect.onmessage({ data: activeLeft({ actionId: "turn-1", distanceM: 280 }) });
  app.flushTimers();
  assert.deepStrictEqual(app.modes, ["long", "long"]);
});

test("level 0 restores the single short pulse", () => {
  const app = boot();
  app.connect.onmessage({ data: activeLeft({ actionId: "turn-1", vibeLevel: 0 }) });
  app.flushTimers();
  assert.deepStrictEqual(app.modes, ["short"]);
});

test("level 2 plays three long pulses, and an idle frame never downgrades it", () => {
  const app = boot();
  app.connect.onmessage({ data: activeLeft({ actionId: "turn-1", vibeLevel: 2 }) });
  // A frame without the field must keep the stored level, not reset it to the default.
  app.connect.onmessage({ data: { id: "idle-1", navStatus: "idle" } });
  app.connect.onmessage({ data: activeLeft({ actionId: "turn-2", distanceM: 60 }) });
  app.flushTimers();
  assert.deepStrictEqual(app.modes, ["long", "long", "long", "long", "long", "long"]);
});

test("an illegal level is ignored and the previous one stays in force", () => {
  const app = boot();
  app.connect.onmessage({ data: activeLeft({ actionId: "turn-1", vibeLevel: 0 }) });
  app.connect.onmessage({ data: activeLeft({ actionId: "turn-2", distanceM: 50, vibeLevel: 7 }) });
  app.flushTimers();
  assert.deepStrictEqual(app.modes, ["short", "short"]);
});

test("a long-pulse burst never blocks the ack", () => {
  const app = boot();
  const sentBefore = app.connect.sent.length;
  app.connect.onmessage({ data: activeLeft({ actionId: "turn-ack", distanceM: 70 }) });
  // The first pulse is synchronous, the rest are still queued, and the ack has already gone.
  assert.strictEqual(app.connect.sent.length, sentBefore + 1);
  assert.strictEqual(app.connect.sent[app.connect.sent.length - 1].refId, "amap-1");
});

test("an unknown pattern never reaches the vibrator", () => {
  const app = boot();
  app.connect.onmessage({ data: { id: "vibe-mega-1", vibe: "mega" } });
  assert.deepStrictEqual(app.modes, []);
});
