# RPK 构建说明（静态）

本文件是**静态说明**，不由构建脚本改写。每次构建的动态结果只写入
`band-app/dist/rpk-build-record.json`（`band-app/dist/` 已被 `band-app/.gitignore` 忽略）
并打印到 stdout；本文件中不存在"当前最新哈希"这类随时间变化的结论。

## 构建

```bash
node scripts/build-rpk.js
```

- 脚本：`scripts/build-rpk.js`，从仓库根目录运行。
- 使用的工具包：`aiot-toolkit` 2.0.5，读取 `band-app/src/manifest.json`
  （`sourceRoot` 固定为 `src`，清单图标 `/logo.png` 也相对 `src` 解析）。
- 签名：`SignUtil` 用 `path.join(<tempProject>, signRoot)` 解析证书，因此
  `signRoot` 必须保持**相对字符串 `sign`**；绝对路径会指向工具包复制目录之外，
  导致回退到工具包自带证书。
- 证书来源：`app/signing/private.pem` 与 `app/signing/certificate.pem` 被复制到
  `band-app/sign/debug/`。**缺少 app/signing 下的 pem 时构建失败，且不生成新钥匙。**
- 依赖安装：仅当 `band-app/node_modules/aiot-toolkit/lib/bin.js` 不存在时，
  用 `process.execPath` 调用 `npm-cli.js` 执行 `npm install`（`shell: false`，
  `timeout: 180000`）；不使用 `npm.cmd`/`npx.cmd`，不设置 `shell: true`。
- 构建调用同样使用 `process.execPath` + 本地 CLI（`shell: false`，
  `timeout: 180000`）。
- 包名：`com.minavigatering.probe`；versionName：`0.1.0`。

## 动态记录

- 路径：`band-app/dist/rpk-build-record.json`（gitignore）。
- 字段：`result`（`success` 或 `failure`）、`argv`（`node scripts/build-rpk.js`）、
  `aiotExitCode`、`rpkPath`（相对仓库根，失败为 `null`）、`bytes`、`sha256`、
  `versionName`、`usedProjectCert`、`message`。
- 成功时同一份 JSON 与 RPK 绝对路径、字节数、SHA256 也打印到 stdout；
  失败时 JSON 打印到 stderr 并以非 0 退出。
- 构建失败（证书缺失、`src/manifest.json` 缺失、证书字节不一致、`npm-cli.js`
  缺失、安装失败、aiot 非 0、未使用 `sign/debug/private.pem`、找不到 `.rpk`）
  一律记为 `result: failure`，且**不会**把失败或成功写回本文件。

## 检查

```bash
node scripts/build-rpk.js --check
```

`--check` 是只读入口，在任何写文件之前处理：

1. 记录 `docs/rpk-build.md` 的字节；
2. 以 `shell: false`、`timeout: 180000` 重新运行本脚本（不传 `--check`）；
3. 要求该文件字节与运行前完全一致，且子进程退出码为 0；
4. 读取 `band-app/dist/rpk-build-record.json`，要求 `result` 为 `success`；
5. 用记录中的 `rpkPath` 读取 RPK，要求 `sha256` 与 `bytes` 与记录一致；
6. 全部通过则输出 `check-ok` 并以退出码 0 结束。

`--check` 自身不写 `docs/rpk-build.md`，也不写 `rpk-build-record.json`。
