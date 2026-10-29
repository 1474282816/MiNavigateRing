"use strict";

// Redmi Watch 6 (432x514) 适配版 RPK 构建器。
//
// 用法（仓库根目录）：
//   node scripts/build-rw6.js
//
// 做法：把 rw6/ 下的覆盖文件临时写入 band-app/src，调用 aiot-toolkit 构建，
// 然后把源码原样还原（失败也还原）。原版 192x490 的产物和源码都不动，可重复执行。
// 产物：dist-rw6/com.minavigatering.probe.debug.<versionName>.rpk

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { spawnSync } = require("child_process");

const repoRoot = path.resolve(__dirname, "..");
const bandApp = path.join(repoRoot, "band-app");
const srcDir = path.join(bandApp, "src");
const overDir = path.join(repoRoot, "rw6");
const toolkitBin = path.join(bandApp, "node_modules", "aiot-toolkit", "lib", "bin.js");
const distDir = path.join(bandApp, "dist");
const outDir = path.join(repoRoot, "dist-rw6");

// 覆盖清单：相对 band-app/src 的路径
const OVERRIDES = ["manifest.json", path.join("pages", "index", "index.ux")];

function fail(msg) {
  console.error("[build-rw6] " + msg);
  process.exit(1);
}

function sha256(buf) {
  return crypto.createHash("sha256").update(buf).digest("hex");
}

if (!fs.existsSync(toolkitBin)) {
  fail("band-app/node_modules/aiot-toolkit 缺失，先在 band-app 里执行 npm install");
}
if (!fs.existsSync(path.join(srcDir, "logo.png"))) {
  fail("band-app/src/logo.png 缺失，先跑一次 node scripts/build-rpk.js 生成图标");
}

const manifest = JSON.parse(fs.readFileSync(path.join(overDir, "manifest.json"), "utf8"));
const versionName = manifest.versionName;
const expectedName = "com.minavigatering.probe.debug." + versionName + ".rpk";

// 备份，供 finally 还原
const backups = new Map();
OVERRIDES.forEach((rel) => {
  const target = path.join(srcDir, rel);
  backups.set(rel, fs.existsSync(target) ? fs.readFileSync(target) : null);
});

let aiotExitCode = null;
let error = null;
try {
  OVERRIDES.forEach((rel) => {
    const from = path.join(overDir, rel);
    const to = path.join(srcDir, rel);
    if (!fs.existsSync(from)) {
      throw new Error("缺少覆盖文件 " + from);
    }
    fs.mkdirSync(path.dirname(to), { recursive: true });
    fs.copyFileSync(from, to);
  });

  const build = spawnSync(process.execPath, [toolkitBin, "build"], {
    cwd: bandApp,
    shell: false,
    encoding: "utf8",
    timeout: 300000
  });
  aiotExitCode = build.error ? null : build.status;
  const out = (build.stdout || "") + (build.stderr || "");
  if (build.error || build.status !== 0) {
    throw new Error(
      "aiot build 失败（" +
        (build.error ? "spawn " + build.error.code : "exit " + build.status) +
        "）：" + out.trim().split("\n").slice(-15).join(" / ")
    );
  }
} catch (e) {
  error = e;
} finally {
  // 无论成败都还原源码，仓库里不残留覆盖文件
  OVERRIDES.forEach((rel) => {
    const target = path.join(srcDir, rel);
    const prev = backups.get(rel);
    if (prev === null) {
      fs.rmSync(target, { force: true });
    } else {
      fs.writeFileSync(target, prev);
    }
  });
}
if (error) {
  fail(error.message);
}

const rpkPath = path.join(distDir, expectedName);
if (!fs.existsSync(rpkPath)) {
  fail("构建返回 0 但未生成 " + rpkPath);
}

fs.mkdirSync(outDir, { recursive: true });
const outPath = path.join(outDir, expectedName);
fs.copyFileSync(rpkPath, outPath);

const bytes = fs.readFileSync(outPath);
const digest = sha256(bytes);

// 校验产物里的 designWidth，避免"构建成功但配置没生效"
const AdmZip = require(path.join(bandApp, "node_modules", "adm-zip"));
const packaged = JSON.parse(new AdmZip(outPath).getEntry("manifest.json").getData().toString("utf8"));
if (packaged.config.designWidth !== 432) {
  fail("产物 designWidth = " + packaged.config.designWidth + "，期望 432");
}

console.log(
  JSON.stringify(
    {
      result: "success",
      aiotExitCode: aiotExitCode,
      rpkPath: path.relative(repoRoot, outPath),
      bytes: bytes.length,
      sha256: digest,
      versionName: packaged.versionName,
      versionCode: packaged.versionCode,
      designWidth: packaged.config.designWidth,
      package: packaged.package
    },
    null,
    2
  )
);
