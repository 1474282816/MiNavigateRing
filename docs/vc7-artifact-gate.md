# vc7 RPK 宿主门禁（不重建、不签名）

本文件说明如何用**显式绝对路径 `--rpk`** 直接加载既有 vc7 RPK，在 bounded Node
宿主里复验导航画面模板与图标，并保证画面生成失败不能通过。此路径**不重建、不签名**。

> 边界声明：这是 bounded Node 宿主验证，**不等于**官方 QEMU 固件，**不等于**实机
> 通过，**不等于**真实高德通过。下文的所有画面/断言证据只绑定到固定安装包字节，
> 不构成真机或真实高德验收。

## 固定包身份

- 路径：`deliverables/MiNavigateRing-Navigation-vc7-9654691d.rpk`
- 大小：`52581`
- SHA256：`9654691d80a30a6c0bf1cfe1c992cf15c7b3d0296d1da09f1032a2db2931f3ea`

`--rpk` 模式在进入主流程后、复制运行层之前读取原字节并记录 size + sha256；当解析后的
路径等于上述固定路径（Windows 大小写不敏感）时，size 与 sha256 必须与常量一致，否则
非 0 退出。成功打印 `PRODUCT_UI_PASS` 之前会再次读取原字节，必须与进入时完全一致。
任何失败路径都不会写这个文件。该模式不调用 `ensureBuildDeps()` / `buildRpk()`，不读取
pem/私钥/密码，也不 spawn `scripts/build-rpk.js`。

## 两条可直接运行的命令（仓库根目录）

正向复验（约 1 分钟，输出画面 + 断言）：

```bash
node scripts/test-product-runtime.js --rpk deliverables/MiNavigateRing-Navigation-vc7-9654691d.rpk
```

定向负例（证明缺 PNG 时确实失败，而不是被吞掉）：

```bash
python tests/test_product_runtime_gate.py
```

`tests/test_product_runtime_gate.py` 是可直接运行的独立脚本（不是 pytest 用例）。它用
`subprocess`（`shell=False`）调用
`node scripts/test-product-runtime.js --rpk <同一绝对路径> --negative missing-png`，断言
node 退出码非 0、stdout 不含 `PRODUCT_UI_PASS`、输出含 `Missing PNG asset`，并在运行
前后用 `hashlib` 核对固定 RPK 的大小与 SHA256 不变。

## 证据位置

正向运行把本次新生成的画面与断言写到（已 gitignore）：

```
build/navigation-preview/vc7-artifact-gate/
```

包含：14 个动作 PNG、`left-300.png`、`unknown.png`、`ended.png`，以及 `assertions.json`。
每张 PNG 均为本次新生成、非空、PNG 签名正确、IHDR 为 192x490；`assertions.json` 里
每条 `packageSha256` 都等于 `PRODUCT_UI_PASS` 的 `rpkSha256`，也等于再次哈希磁盘 RPK 的
结果。`unknown.png` / `ended.png` 不得复用上一张动作 PNG 的字节，`left-300.png` 与
`left.png`（200 米）字节必须不同，用于证明 300→200 的画面变化以及 unknown/ended 不保留
旧图标。

## 失败语义

`--rpk` 模式删除了旧的 `renderPreview` try/catch。渲染抛错、`toPngBuffer()` 为空、写盘
失败都会 `console.error` 后 `process.exit(1)`，不会继续走到 `PRODUCT_UI_PASS`。进程一开始
注册 `uncaughtException` 与 `unhandledRejection`，命中时打印 `FAILED` 和 stack 并以退出码 1
结束。运行层在图像资源缺失时会抛 `Missing PNG asset`，这正是负例所依赖的失败路径。
