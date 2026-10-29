# Redmi Watch 6 适配与验证清单

本目录（`rw6/`）是给 REDMI Watch 6（432×514，澎湃OS 3 / Vela）跑的覆盖层，不改动原版 192×490 的源码。

## 为什么需要适配

原版 `band-app/src/manifest.json` 里 `config.designWidth = 192`，全部尺寸按小米手环 9 NFC 的
192×490 长条屏书写。Vela 运行时会按 `屏幕宽 / designWidth` 缩放 `px`：

| 设备 | 屏宽 | designWidth | 缩放 | 140px 图标实际 | 64px 距离实际 |
|---|---|---|---|---|---|
| 小米手环 9 NFC | 192 | 192 | 1.00 | 140 px | 64 px |
| REDMI Watch 6（原版直跑） | 432 | 192 | 2.25 | 315 px | 144 px |
| REDMI Watch 6（本适配层） | 432 | 432 | 1.00 | 180 px | 92 px |

原版直跑时内容总高约 690 px，屏幕只有 514 px，底部路名/出口会被裁掉。

## 改了哪两个文件

1. `manifest.json`
   - `config.designWidth`: 192 → **432**（1 单位 = 1 物理像素）
   - `versionName`: `0.2.0-navigation` → `0.2.0-rw6`
   - `versionCode`: 11 → **12**（装表工具靠它区分版本）
   - 其余（包名、权限、router、`deviceTypeList`）保持原样
2. `pages/index/index.ux`
   - 图标 140 → 180，标题 42 → 44，距离 52/64 → 76/92，路名 34 → 40，出口 26 → 30，诊断行 15 → 18

垂直预算：180 + 44 + 92 + 40 + 30 = 386；展开六行诊断再加约 108，合计约 494 < 514。

## 构建

```bash
# 仓库根目录
node scripts/build-rw6.js
```

产物：`dist-rw6/com.minavigatering.probe.debug.0.2.0-rw6.rpk`

脚本把 `rw6/` 覆盖文件临时写入 `band-app/src`，构建完（成功或失败）都还原源码。前置条件：
`band-app/node_modules` 已安装、`band-app/src/logo.png` 已存在（先跑一次 `node scripts/build-rpk.js`）。

## 装表与观察项

用 AstroBox 安装上面的 RPK，逐项记录：

| 检查项 | 期望 | 实际 | 说明 |
|---|---|---|---|
| 能否安装 | 安装成功，应用列表出现「导航环」 | | 失败说明签名/包名/最小平台版本不兼容 |
| 图标与名称 | 圆形绿色左转箭头 + 「导航环」 | | 图标是构建脚本生成的 192×192 PNG |
| 页面能否打开 | 显示「连接中」，背景纯黑 | | 打不开 = 页面路由或运行时 API 不支持 |
| 布局是否溢出 | 图标/标题完整，底部不被裁 | | 溢出说明缩放规则与假设不同 |
| 长按标题 | 展开 6 行诊断（ready / 诊断 / 入帧 / 链路 / 生命周期 / 性能） | | 长按无反应 = 手势事件未送达 |
| `ready` 行内容 | `未探测` 或 `ready:N` | | 出现「异常初始化」= `system.interconnect` 模块缺失或初始化失败 |
| 能否卸载 | AstroBox 里能正常卸载 | | HyperOS 3.100.043 有用户反馈卸载入口失效 |

## 已知限制

- 手环端在没有手机端配合时只能验证渲染与 interconnect 模块存在性，拿不到真实导航数据。
- 手机端 APK 需要从小米官方示例包取 `xms-wearable-lib_1.4_release.aar`（见 `docs/sdk-provenance.md`），
  且 RPK 与 APK 必须**同包名同证书**——本 RPK 用的是 `app/signing/` 里生成的那份证书，
  对应的 APK 也必须用同一份证书签名，否则官方 interconnect 会拒绝握手。
