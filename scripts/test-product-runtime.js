"use strict";

// Product-runtime regression for the band navigation UI.
//
// Run from the repository root:
//   node scripts/test-product-runtime.js
//   node scripts/test-product-runtime.js --rpk <absolute path to a .rpk>
//   node scripts/test-product-runtime.js --rpk <absolute path> --negative missing-png
//
// Default (no --rpk) mode is unchanged: copy the three authorised runtime files
// from the read-only directory C:/program1/bes-qemu/miband9/rpk_runtime into the
// ignored build/navigation-runtime/ copy, port the PNG adaptation by exact anchor
// matching, install the two build dependencies into band-app/node_modules when
// absent (node + npm-cli.js, never a cmd shim, never --save), build the real RPK
// through node scripts/build-rpk.js (reusing the existing app/signing pems, no
// key generated), then drive the real template + real 1000 ms timer.
//
// --rpk <abs path> mode is non-rebuilding and non-signing: it loads the given
// RPK directly (no ensureBuildDeps, no buildRpk, no pem/private key, no
// scripts/build-rpk.js spawn). The three runtime files are still copied read-only
// and patched by exact anchor. The fixed vc7 artefact has its size and SHA256
// asserted and re-verified after the run, so the images are bound to the exact
// package bytes. Every output PNG must be newly generated, correctly signed,
// non-empty and 192x490; a render error or a missing PNG aborts with exit 1
// instead of being swallowed into PRODUCT_UI_PASS.
//
// It only prints PRODUCT_UI_PASS plus a machine-readable summary on success. It
// never claims a real-device or AMap pass: the host is a bounded Node runtime,
// not the official QEMU firmware.

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const assert = require("assert");
const { spawnSync } = require("child_process");

const repoRoot = path.resolve(__dirname, "..");
const bandApp = path.join(repoRoot, "band-app");
const runtimeDir = path.join(repoRoot, "build", "navigation-runtime");
const previewDir = path.join(repoRoot, "build", "navigation-preview");
const frozenRuntimeDir = "C:/program1/bes-qemu/miband9/rpk_runtime";
const RUNTIME_FILES = ["quickapp_vm.js", "rpk_loader.js", "rpk_renderer.js"];
const EXPECTED_RPK_NAME = "com.minavigatering.probe.debug.0.2.0-navigation.rpk";
const PNG_SIG_HEX = "89504e470d0a1a0a";
const CACHE_DECL = "var __webpack_module_cache__ = {}";
const CACHE_GLOBAL = "__webpack_module_cache__ = global";

// Fixed vc7 artefact identity. In --rpk mode, when the resolved path matches this
// path (Windows case-insensitive), the size and SHA256 must match exactly.
const FIXED_RPK_PATH = "C:/program1/MiNavigateRing/deliverables/MiNavigateRing-Navigation-vc7-9654691d.rpk";
const FIXED_RPK_SIZE = 52581;
const FIXED_RPK_SHA256 = "9654691d80a30a6c0bf1cfe1c992cf15c7b3d0296d1da09f1032a2db2931f3ea";
const NEGATIVE_MISSING_PNG = "missing-png";

const processStartMs = Date.now();
const sha256 = (buf) => crypto.createHash("sha256").update(buf).digest("hex");

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
function die(message) {
  console.error("[test-product-runtime] " + message);
  process.exit(1);
}

// Any uncaught throw / unhandled rejection is a hard failure: print the stack and
// exit non-zero, never letting a half-rendered run reach PRODUCT_UI_PASS.
process.on("uncaughtException", (err) => {
  console.error("[test-product-runtime] FAILED");
  console.error(err && err.stack ? err.stack : err);
  process.exit(1);
});
process.on("unhandledRejection", (err) => {
  console.error("[test-product-runtime] FAILED");
  console.error(err && err.stack ? err.stack : err);
  process.exit(1);
});

// Parse the explicit CLI contract. Unknown flags and unsupported negative values
// abort immediately; a relative --rpk is rejected.
function parseArgs(argv) {
  const out = { rpk: null, negative: null };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--rpk") {
      const value = argv[++i];
      if (!value) die("--rpk requires an absolute path argument");
      if (!path.isAbsolute(value)) die("--rpk must be an absolute path: " + value);
      out.rpk = value;
    } else if (arg === "--negative") {
      const value = argv[++i];
      if (!value) die("--negative requires a value");
      out.negative = value;
    } else {
      die("unknown argument: " + arg);
    }
  }
  if (out.negative !== null) {
    if (out.negative !== NEGATIVE_MISSING_PNG) die("unsupported --negative value: " + out.negative);
    if (!out.rpk) die("--negative requires --rpk");
  }
  return out;
}

const args = parseArgs(process.argv.slice(2));

// 1. Copy the authorised runtime files into the isolated build copy.
function copyRuntime() {
  for (const name of RUNTIME_FILES) {
    const src = path.join(frozenRuntimeDir, name);
    if (!fs.existsSync(src)) die("frozen runtime source is missing: " + src);
  }
  fs.mkdirSync(runtimeDir, { recursive: true });
  for (const name of RUNTIME_FILES) {
    fs.copyFileSync(path.join(frozenRuntimeDir, name), path.join(runtimeDir, name));
  }
}

// 2. Port the PNG adaptation by exact anchor matching. Each edit is
// [anchor, replacement]; the anchor must appear exactly once or we abort.
function patchFile(file, edits) {
  const abs = path.join(runtimeDir, file);
  let text = fs.readFileSync(abs, "utf8");
  for (const [anchor, replacement] of edits) {
    const first = text.indexOf(anchor);
    if (first === -1) die("missing anchor in " + file + ":\n---\n" + anchor + "\n---");
    if (text.indexOf(anchor, first + 1) !== -1) {
      die("ambiguous anchor (appears more than once) in " + file + ":\n---\n" + anchor + "\n---");
    }
    text = text.slice(0, first) + replacement + text.slice(first + anchor.length);
  }
  fs.writeFileSync(abs, text);
}

const QUICKAPP_VM_EDITS = [
  [
    "        this.renderTree = null;\n",
    "        this.renderTree = null;\n" +
      "        this.assetReader = options.assetReader || (() => null);\n"
  ],
  [
    "            const layout = layoutFromVNodes(vnode, page.style, this.width, this.height);\n" +
      "            this.renderTree = {\n" +
      "                viewport: { width: this.width, height: this.height, bg: '#f4f6f5' },",
    "            const layout = layoutFromVNodes(vnode, page.style, this.width, this.height);\n" +
      "            for (const element of layout.elements) {\n" +
      "                if (element.type === 'image') {\n" +
      "                    const asset = this.assetReader(element.src.replace(/^\\//, ''));\n" +
      "                    if (!asset || asset.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a')\n" +
      "                        throw new Error('Missing PNG asset: ' + element.src);\n" +
      "                    element.png = asset.toString('base64');\n" +
      "                }\n" +
      "            }\n" +
      "            this.renderTree = {\n" +
      "                viewport: { width: this.width, height: this.height, bg: layout.background || '#f4f6f5' },"
  ],
  [
    "        if (tag !== 'div' && tag !== 'text') {",
    "        if (tag !== 'div' && tag !== 'text' && tag !== 'image') {"
  ],
  [
    "        const fontSize = parsePx(style.fontSize, 16);\n" +
      "        const color = typeof style.color === 'string' ? style.color : '#17261e';",
    "        if (tag === 'image') {\n" +
      "            const src = resolveValue({ value: opts.src });\n" +
      "            const imageWidth = parsePx(style.width, 112);\n" +
      "            const imageHeight = parsePx(style.height, 112);\n" +
      "            elements.push({ type: 'image', src, classList, style,\n" +
      "                rect: [(width - imageWidth) / 2, cursor.y, imageWidth, imageHeight] });\n" +
      "            cursor.y += imageHeight + 2;\n" +
      "            return;\n" +
      "        }\n" +
      "        const fontSize = parsePx(style.fontSize, 16);\n" +
      "        const color = typeof style.color === 'string' ? style.color : '#17261e';"
  ],
  [
    "    return { elements, unsupported };",
    "    return { elements, unsupported, background: declarations.page && declarations.page.backgroundColor };"
  ]
];

const RPK_RENDERER_EDITS = [
  ["import sys, json, io\n", "import sys, json, io, base64\n"],
  [
    "        if op.get('op') != 'text':\n            continue\n",
    "        if op.get('op') == 'image':\n" +
      "            asset = Image.open(io.BytesIO(base64.b64decode(op['png']))).convert('RGBA')\n" +
      "            asset = asset.resize((int(op['w']), int(op['h'])), Image.Resampling.LANCZOS)\n" +
      "            img.paste(asset, (int(op['x']), int(op['y'])), asset)\n" +
      "            continue\n" +
      "        if op.get('op') != 'text':\n            continue\n"
  ],
  [
    "            draw.text((int(op.get('x') or 0), y), line, font=font, fill=color)",
    "            draw.text((int(op.get('x') or 0), y), line, font=font, fill=color, stroke_width=1 if op.get('bold') else 0)"
  ],
  [
    "        if (!elem || elem.type !== 'text') continue;\n" +
      "        const rect = elem.rect || [8, 0, w - 16, 16];\n" +
      "        ops.push({\n" +
      "            op: 'text',\n" +
      "            text: elem.text === undefined || elem.text === null ? '' : String(elem.text),",
    "        if (elem && elem.type === 'image') {\n" +
      "            ops.push({ op: 'image', png: elem.png, x: elem.rect[0], y: elem.rect[1], w: elem.rect[2], h: elem.rect[3] });\n" +
      "            continue;\n" +
      "        }\n" +
      "        if (!elem || elem.type !== 'text') continue;\n" +
      "        const rect = elem.rect || [8, 0, w - 16, 16];\n" +
      "        ops.push({\n" +
      "            op: 'text',\n" +
      "            bold: elem.style && elem.style.fontWeight === 'bold',\n" +
      "            text: elem.text === undefined || elem.text === null ? '' : String(elem.text),"
  ]
];

function applyRuntimePatch() {
  patchFile("quickapp_vm.js", QUICKAPP_VM_EDITS);
  patchFile("rpk_renderer.js", RPK_RENDERER_EDITS);
}

// 3. Ensure the build dependencies exist, then build the real RPK.
// Only the default (no --rpk) path may call this; --rpk mode never rebuilds.
function ensureBuildDeps() {
  if (
    fs.existsSync(path.join(bandApp, "node_modules", "aiot-toolkit")) &&
    fs.existsSync(path.join(bandApp, "node_modules", "adm-zip"))
  ) {
    return;
  }
  const candidates = [
    path.join(path.dirname(process.execPath), "node_modules", "npm", "bin", "npm-cli.js"),
    path.join(path.dirname(process.execPath), "..", "lib", "node_modules", "npm", "bin", "npm-cli.js")
  ];
  const npmCli = candidates.find((p) => fs.existsSync(p));
  if (!npmCli) die("build dependencies missing and no npm-cli.js was found next to node");
  const install = spawnSync(
    process.execPath,
    [npmCli, "install", "--no-save", "--no-audit", "--no-fund", "aiot-toolkit@2.0.5", "adm-zip"],
    { cwd: bandApp, shell: false, encoding: "utf8", timeout: 300000 }
  );
  if (install.error || install.status !== 0) {
    die(
      "installing band-app build dependencies failed (" +
        (install.error ? "spawn error " + install.error.code : "exit code " + install.status) +
        ")\n" + (install.stdout || "") + (install.stderr || "")
    );
  }
}

function buildRpk() {
  const build = spawnSync(process.execPath, [path.join(repoRoot, "scripts", "build-rpk.js")], {
    cwd: repoRoot,
    shell: false,
    encoding: "utf8",
    timeout: 300000
  });
  if (build.error || build.status !== 0) {
    die(
      "node scripts/build-rpk.js failed (" +
        (build.error ? "spawn error " + build.error.code : "exit code " + build.status) +
        ")\n" + (build.stdout || "") + (build.stderr || "")
    );
  }
  const recordPath = path.join(bandApp, "dist", "rpk-build-record.json");
  let record;
  try {
    record = JSON.parse(fs.readFileSync(recordPath, "utf8"));
  } catch (err) {
    die("cannot read " + recordPath + ": " + err.message);
  }
  if (record.result !== "success" || !record.rpkPath) {
    die("rpk-build-record.json does not describe a successful build: " + JSON.stringify(record));
  }
  const rpkPath = path.resolve(repoRoot, record.rpkPath);
  if (path.basename(rpkPath) !== EXPECTED_RPK_NAME) {
    die("built RPK is " + path.basename(rpkPath) + ", expected " + EXPECTED_RPK_NAME);
  }
  if (!fs.existsSync(rpkPath)) die("built RPK is not on disk: " + rpkPath);
  return rpkPath;
}

// 4-6. Open the RPK, prove the per-chunk cache, drive template + timer.
const classHas = (elem, cls) => Array.isArray(elem.classList) && elem.classList.includes(cls);
const findImages = (elements) => elements.filter((e) => e && e.type === "image");
const elementTexts = (elements) =>
  elements.map((e) => (e && typeof e.text === "string" ? e.text : "")).filter((t) => t.length > 0);

async function main() {
  // The runtime prints verbose console output. The ticket wants a single
  // PRODUCT_UI_PASS line plus a machine-readable summary on success, so silence
  // stdout chatter while the regression runs (console.error still surfaces
  // failures).
  console.log = () => {};

  let rpkPath;
  let enteringBytes = null;
  let enteringSha = null;
  let enteringSize = null;
  let buildRpkInvoked = false;
  let signingInvoked = false;

  if (args.rpk) {
    // Fixed-package identity is recorded before anything is copied or loaded.
    rpkPath = args.rpk;
    const resolved = path.resolve(rpkPath);
    const matchesFixed = resolved.replace(/\\/g, "/").toLowerCase() === FIXED_RPK_PATH.toLowerCase();
    enteringBytes = fs.readFileSync(rpkPath);
    enteringSize = enteringBytes.length;
    enteringSha = sha256(enteringBytes);
    if (matchesFixed) {
      if (enteringSize !== FIXED_RPK_SIZE) {
        die("fixed RPK size " + enteringSize + " != expected " + FIXED_RPK_SIZE);
      }
      if (enteringSha !== FIXED_RPK_SHA256) {
        die("fixed RPK sha256 " + enteringSha + " != expected " + FIXED_RPK_SHA256);
      }
    }
  } else {
    ensureBuildDeps();
    rpkPath = buildRpk();
    buildRpkInvoked = true;
    signingInvoked = true;
  }

  copyRuntime();
  applyRuntimePatch();

  // The runtime is loaded only after the isolated copy has been (re)built.
  const { QuickAppRuntime } = require(path.join(runtimeDir, "quickapp_vm.js"));
  const { RpkPackage } = require(path.join(runtimeDir, "rpk_loader.js"));
  const { renderQuickAppFrame } = require(path.join(runtimeDir, "rpk_renderer.js"));
  const { ACTION_LABELS, VALID_ACTIONS } = require(path.join(repoRoot, "band-app", "src", "common", "codec.js"));

  const pkg = new RpkPackage(rpkPath);
  const rpkSha256 = pkg.sha256();
  const rpkSize = pkg.byteLength;
  if (enteringSha !== null && enteringSha !== rpkSha256) {
    die("RPK bytes changed between the identity read and the package load");
  }

  const appText = (pkg.getFile("app.js") || Buffer.alloc(0)).toString("utf8");
  const pageText = (pkg.getFile("pages/index/index.js") || Buffer.alloc(0)).toString("utf8");
  assert(appText.length > 0, "RPK is missing app.js");
  assert(pageText.length > 0, "RPK is missing pages/index/index.js");
  for (const [label, text] of [["app.js", appText], ["pages/index/index.js", pageText]]) {
    assert(text.includes(CACHE_DECL), label + " lost its own `" + CACHE_DECL + "`");
    assert(!text.includes(CACHE_GLOBAL), label + " rewrote the cache to a shared global");
  }

  const bundle = pkg.buildBundle();
  assert.strictEqual(bundle.split(CACHE_DECL).length - 1, 2, "the bundle must keep exactly two per-chunk `" + CACHE_DECL + "`");
  assert(!bundle.includes(CACHE_GLOBAL), "the bundle must not merge the caches into a global");

  // Negative mode still uses the real loader/patch/template, but the asset reader
  // returns null for exactly one packaged icon so the runtime throws
  // `Missing PNG asset` through its own image path (no $app patching, no global
  // cache, no hand-drawn PNG).
  const assetReader =
    args.negative === NEGATIVE_MISSING_PNG
      ? (name) => (name === "common/images/left.png" ? null : pkg.getFile(name))
      : (name) => pkg.getFile(name);

  const runtime = new QuickAppRuntime({ width: 192, height: 490, assetReader });
  runtime.loadApp(bundle);
  runtime.launchPage("pages/index");

  const outputDir = args.rpk ? path.join(previewDir, "vc7-artifact-gate") : previewDir;
  if (args.rpk) {
    // Guarantee the evidence directory holds only this run's fresh output.
    fs.rmSync(outputDir, { recursive: true, force: true });
  }
  fs.mkdirSync(outputDir, { recursive: true });

  const pngRecords = [];
  // Render a fresh PNG for `name`, prove it is a brand-new non-empty 192x490 PNG,
  // and record its digest bound to the loaded package hash. Any renderer throw,
  // empty buffer or write failure propagates to main().catch and exits 1.
  const writePng = (name, tree) => {
    const file = path.join(outputDir, name + ".png");
    if (fs.existsSync(file)) die("refusing to overwrite an existing PNG: " + file);
    const buffer = renderQuickAppFrame(tree).toPngBuffer();
    if (!buffer || buffer.length === 0) die("renderer produced an empty PNG for " + name);
    fs.writeFileSync(file, buffer);
    const stat = fs.statSync(file);
    if (stat.size === 0) die("written PNG is empty: " + file);
    if (stat.mtimeMs < processStartMs - 2000) die("PNG mtime predates this run: " + file);
    const bytes = fs.readFileSync(file);
    if (bytes.subarray(0, 8).toString("hex") !== PNG_SIG_HEX) die("bad PNG signature for " + name);
    const width = bytes.readUInt32BE(16);
    const height = bytes.readUInt32BE(20);
    if (width !== 192 || height !== 490) die("unexpected IHDR " + width + "x" + height + " for " + name);
    pngRecords.push({
      filename: name + ".png",
      pngSha256: sha256(buffer),
      width,
      height,
      packageSha256: rpkSha256
    });
    return buffer;
  };

  const actions = Array.from(VALID_ACTIONS);
  assert.strictEqual(actions.length, 14, "VALID_ACTIONS must contain 14 actions");

  // Initial state, before any injected frame.
  const page = runtime.currentPage;
  assert.strictEqual(page.$data.debugVisible, false, "diagnostics must start collapsed");
  const tree0 = runtime.renderTree;
  assert.strictEqual(tree0.viewport.bg, "#000000", "viewport background must be black");
  assert.strictEqual(tree0.source, "template", "render tree must come from the real template");
  assert.strictEqual(tree0.unsupported.length, 0, "no template tag may be unsupported");
  assert.strictEqual(findImages(tree0.elements).length, 0, "no icon before any frame");
  for (const text of elementTexts(tree0.elements)) {
    assert(!text.startsWith("诊断 "), "a diagnostic line must not be visible while collapsed: " + text);
  }
  const lifeTickBefore = page.$data.lifeTick;

  // The real 1000 ms timer must advance without touching page.tick.
  await pause(1150);
  assert(
    page.$data.lifeTick > lifeTickBefore,
    "the real interval timer must advance lifeTick (before=" + lifeTickBefore + ", after=" + page.$data.lifeTick + ")"
  );
  const lifeTickAfter = page.$data.lifeTick;

  const inject = (payload) => runtime.interconnectInstances[0].onmessage({ data: JSON.stringify(payload) });

  // Per-action frame assertions: title, distance, bold large colourful styles,
  // single packaged icon and no overflow. Reused for the 300 m and 200 m left
  // frames so the distance transition is checked on both.
  const assertActionFrame = (action, expectedDistance, tree) => {
    const data = page.$data;
    assert.strictEqual(data.title, ACTION_LABELS[action], "title mismatch for action " + action);
    assert.strictEqual(data.distanceText, expectedDistance, "distance text mismatch for action " + action);
    assert.strictEqual(data.debugVisible, false, "diagnostics must stay collapsed for " + action);

    assert.strictEqual(tree.viewport.bg, "#000000", "viewport must stay black for " + action);
    assert.strictEqual(tree.unsupported.length, 0, "unsupported tag while showing " + action);

    const images = findImages(tree.elements);
    assert.strictEqual(images.length, 1, "exactly one icon expected for action " + action);
    const icon = images[0];
    assert.strictEqual(icon.src, "/common/images/" + action + ".png", "icon src mismatch for action " + action);
    const iconBytes = Buffer.from(icon.png, "base64");
    assert.strictEqual(iconBytes.subarray(0, 8).toString("hex"), PNG_SIG_HEX, "icon is not a PNG for action " + action);
    const packaged = pkg.getFile("common/images/" + action + ".png");
    assert(packaged, "RPK is missing common/images/" + action + ".png");
    const source = fs.readFileSync(path.join(bandApp, "src", "common", "images", action + ".png"));
    assert(iconBytes.equals(packaged), "rendered icon differs from the packaged PNG for " + action);
    assert(iconBytes.equals(source), "rendered icon differs from band-app/src source for " + action);

    const titleElem = tree.elements.find((e) => classHas(e, "title"));
    const distanceElem = tree.elements.find((e) => classHas(e, "distance"));
    const roadElem = tree.elements.find((e) => classHas(e, "road"));
    assert(titleElem, "no title element for action " + action);
    assert(distanceElem, "no distance element for action " + action);
    assert(roadElem, "no road element for action " + action);
    assert(parseFloat(titleElem.style.fontSize) >= 42, "title font size too small for " + action);
    assert.strictEqual(titleElem.style.fontWeight, "bold", "title must be bold for " + action);
    assert.strictEqual(titleElem.style.color, "#00ff99", "title colour mismatch for " + action);
    assert(parseFloat(distanceElem.style.fontSize) >= 52, "distance font size too small for " + action);
    assert.strictEqual(distanceElem.style.fontWeight, "bold", "distance must be bold for " + action);
    assert.strictEqual(distanceElem.style.color, "#ffffff", "distance colour mismatch for " + action);
    assert.strictEqual(roadElem.style.color, "#00dfff", "road colour mismatch for " + action);
    assert.strictEqual(roadElem.style.fontWeight, "bold", "road must be bold for " + action);

    for (const elem of tree.elements) {
      assert(elem.rect[1] + elem.rect[3] <= 490, "element overflows the 490 px height for " + action + ": " + JSON.stringify(elem.rect));
    }
  };

  if (args.negative === NEGATIVE_MISSING_PNG) {
    // Inject a single valid frame whose icon asset is intentionally unavailable,
    // then force a layout pass. The runtime must throw `Missing PNG asset`; if it
    // does not, this negative case did not actually fail and we abort non-zero.
    inject({
      v: 1, kind: "state", id: "negative-left", sentAtMs: Date.now(), link: "connected",
      navStatus: "active", action: "left", distanceM: 200, roadName: "淮海中路",
      actionId: "negative-left", mode: "walk", heartbeat: false
    });
    await pause(1150);
    let negativeErr = null;
    try {
      runtime.updateLayoutTree();
    } catch (err) {
      negativeErr = err;
    }
    if (!negativeErr || !/Missing PNG asset/.test(String(negativeErr.message))) {
      die("negative missing-png did not fail");
    }
    console.error("[test-product-runtime] " + negativeErr.message);
    process.exit(1);
  }

  // 14 actions: title, distance, road, single icon, packaged bytes.
  let lastActionPng = null;
  for (const [index, action] of actions.entries()) {
    const actionId = "ui-turn-" + index;
    const base = {
      v: 1, kind: "state", sentAtMs: Date.now(), link: "connected",
      navStatus: "active", action, roadName: "淮海中路", actionId, mode: "walk", heartbeat: false
    };

    if (action === "left") {
      // First show 300 m, then the same actionId updates to 200 m: the two frames
      // must differ, proving the distance actually drives the picture.
      inject(Object.assign({}, base, { id: "ui-turn-" + index + "-300", distanceM: 300 }));
      await pause(1150);
      assert.strictEqual(page.$data.distanceText, "300米", "left must show 300米 before the update");
      const tree300 = runtime.renderTree;
      assert.strictEqual(findImages(tree300.elements).length, 1, "left 300m frame must show the icon");
      const bytes300 = writePng("left-300", tree300);
      lastActionPng = bytes300;
    }

    inject(Object.assign({}, base, { id: "ui-turn-" + index, distanceM: 200 }));
    await pause(1150);

    const tree = runtime.renderTree;
    assertActionFrame(action, "200米", tree);
    const bytes = writePng(action, tree);
    if (action === "left") {
      assert(!bytes.equals(lastActionPng), "left 300m and left 200m PNG bytes must differ");
    }
    lastActionPng = bytes;
  }

  // Unknown direction hides the stale icon.
  inject({
    v: 1, kind: "state", id: "unknown", sentAtMs: Date.now(), link: "connected",
    navStatus: "active", action: "left", actionId: "unknown-prev", distanceM: 200,
    roadName: "淮海中路", heartbeat: false
  });
  await pause(1150);
  inject({
    v: 1, kind: "state", id: "unknown-bad", sentAtMs: Date.now(), link: "connected",
    navStatus: "active", action: "not-a-direction", actionId: "unknown-bad", heartbeat: false
  });
  await pause(1150);
  assert.strictEqual(page.$data.title, "暂无导航", "unknown direction must be unavailable");
  assert.strictEqual(findImages(runtime.renderTree.elements).length, 0, "unknown direction must hide the icon");
  const unknownPng = writePng("unknown", runtime.renderTree);
  assert(!unknownPng.equals(lastActionPng), "unknown.png must not reuse the previous action PNG");

  // Navigation ended hides the stale icon.
  inject({ v: 1, kind: "state", id: "ended", sentAtMs: Date.now(), link: "connected", navStatus: "ended" });
  await pause(1150);
  assert.strictEqual(page.$data.title, "暂无导航", "ended navigation must show the ended title");
  assert.strictEqual(findImages(runtime.renderTree.elements).length, 0, "ended navigation must hide the icon");
  const endedPng = writePng("ended", runtime.renderTree);
  assert(!endedPng.equals(lastActionPng), "ended.png must not reuse the previous action PNG");

  if (typeof page.clearTickTimer === "function") page.clearTickTimer();

  // The package bytes must be byte-for-byte identical to what was read on entry.
  if (enteringBytes !== null) {
    const finalBytes = fs.readFileSync(rpkPath);
    if (!finalBytes.equals(enteringBytes)) die("fixed RPK bytes changed during the run");
    const finalSha = sha256(finalBytes);
    if (finalSha !== rpkSha256) die("fixed RPK sha256 changed during the run: " + finalSha);
  }

  const assertions = {
    rpkPath: path.resolve(rpkPath).replace(/\\/g, "/"),
    rpkSize,
    rpkSha256,
    pngs: pngRecords
  };
  fs.writeFileSync(path.join(outputDir, "assertions.json"), JSON.stringify(assertions, null, 2));

  const summary = {
    actions: actions.length,
    rpkPath: path.resolve(rpkPath).replace(/\\/g, "/"),
    rpkSha256,
    rpkSize,
    lifeTickBefore,
    lifeTickAfter,
    cacheRewritten: false,
    buildRpkInvoked,
    signingInvoked,
    hostBound: "bounded-node-host",
    notOfficialQemuFirmware: true,
    notDevicePass: true,
    notAmapPass: true
  };
  if (args.rpk) summary.rpkUnchanged = true;
  process.stdout.write("PRODUCT_UI_PASS " + JSON.stringify(summary) + "\n");
  process.exit(0);
}

main().catch((err) => {
  console.error("[test-product-runtime] FAILED");
  console.error(err && err.stack ? err.stack : err);
  process.exit(1);
});
