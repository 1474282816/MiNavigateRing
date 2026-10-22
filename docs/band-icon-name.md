# 手环应用名称、版本号与圆形图标（W-697 / vc9）

本文件记录本次构建实际断言出的数值。所有数字来自 `band-app/dist/rpk-build-record.json`
所指 RPK 内打包的 `logo.png`，由一次性内联 node 断言读取，不是肉眼观察，也不是实机结论。

## 改了什么

### 1. 应用列表名称与版本号（`band-app/src/manifest.json`）

| 字段 | 改前 | 改后 |
| --- | --- | --- |
| `name` | `MiNavigateRing Probe` | `导航环` |
| `versionCode` | `8` | `9` |
| `versionName` | `0.2.0-navigation`（未改） | `0.2.0-navigation`（未改） |

本次只改 `name` 与 `versionCode` 两行，其余字段（`package`、`versionName`、`icon`、
`minPlatformVersion`、`deviceTypeList`、`features`、`config`、`router`）保持原样。

`versionName` 刻意不动：门禁 `scripts/test-product-runtime.js` 期望的 RPK 文件名
`com.minavigatering.probe.debug.0.2.0-navigation.rpk` 由它派生（`EXPECTED_RPK_NAME`），
改名会直接让门禁判失败。

### 2. 图标形状（`scripts/build-rpk.js` 的 `writeLogo`）

`writeLogo` 只改这一个函数及其紧邻注释，PNG 编码（`crc32` / `pngChunk`）、
192×192、8 位 RGBA（color type 6）与打包流程全部保持原状。打包侧
`scripts/build-rpk.js` 的 192×192 尺寸校验（约 :514）未改动。

改动要点：

- 圆盘居中，圆心 `(96,96)`，直径 `136px`（半径 `68`）。
- 圆外 `alpha=0`，四角完全透明；圆边缘在半径 ±0.5px 之间线性过渡，做约 1px 抗锯齿。
- 圆内底色保持 `rgb(23,38,30)`。
- 左转箭头沿用原有比例（原 tip `(28,96)`、head base `x=70 y=48..144`、
  shaft `x=70..150 y=78..114`），整体 `0.67` 倍缩放并重新居中到圆盘中心。
- 箭头颜色由暗绿 `rgb(36,100,66)` 改为亮绿 `rgb(0,255,153)`，与手环页标题同色
  （`index.ux` 的 `#00ff99`，门禁 `scripts/test-product-runtime.js:458` 同样断言此色）。

## 数值对照（本次构建实测）

断言对象：本次构建 RPK 内 `logo.png`（`rpkPath` =
`band-app/dist/com.minavigatering.probe.debug.0.2.0-navigation.rpk`，
`rpkSize=54019`，`rpkSha256=8aa76e1c…`，`record.result=success`，`record.versionCode=9`）。

下表的"改后"列是本次构建产物的实测值；"改前"列由改动前的 `writeLogo` 源码推出
（整幅 `BG=[23,38,30,255]` 铺满、箭头色 `[36,100,66,255]`、无透明度分支），
不是对旧 vc8 产物重新测量得到的，仅作对照用。

| 指标 | 改前（由旧 writeLogo 源码推出） | 改后（本次 vc9 产物实测） | 断言要求 |
| --- | --- | --- | --- |
| 尺寸 | 192×192 | 192×192 | = 192×192 |
| 位深 / colorType | 8 / 6 | 8 / 6 | = 8 / 6 |
| 四角 alpha | 255（不透明底色铺满） | `[0,0,0,0]` | 全为 0 |
| 中心 (96,96) alpha | 255 | 255（`[0,255,153,255]`） | = 255 |
| 非透明像素包围盒 | 192×192 | `[28,28,164,164]` = 137×137 | 宽高 ≤ 140 |
| 不透明像素占比 | 约 1.0（100%） | `0.3887`（14329 / 36864） | 0.30–0.45 |
| 亮绿 `rgb(0,255,153)` 不透明像素 | 0（原色为 `36,100,66`） | `2241` | > 0 |
| 抗锯齿像素（0<alpha<255） | 0 | `416` | > 0 |

原始断言输出：

```json
{"rpkPath":"band-app\\dist\\com.minavigatering.probe.debug.0.2.0-navigation.rpk","width":192,"height":192,"bitDepth":8,"colorType":6,"corners":[0,0,0,0],"center":[0,255,153,255],"bbox":[28,28,164,164],"bboxWidth":137,"bboxHeight":137,"visible":14745,"opaque":14329,"opaqueRatio":0.3886990017361111,"green":2241,"antialias":416}
```

对照结论：图标从"铺满整块方形的不透明底色 + 暗绿箭头"变成"明显小于画布的透明圆形徽章 +
亮绿箭头"，圆外全透明、箭头像素在深色底上可见（亮绿像素数从 0 变为 2241）。

## 为什么提到 versionCode 9

`versionCode` 是设备侧识别"这是不是一次新安装/新版本"的依据。上一版 vc8 已经在设备上出现过，
如果这次只换图标和名称而 `versionCode` 仍为 8，安装/覆盖时设备与工具链可能认为没有新版本，
应用列表里看到的仍是旧图标或旧名称。提升到 9 让本次产物成为一个可区分的新版本
（构建记录里的诊断文件名也带 `vc9`，见 `scripts/build-rpk.js` 的 `diagnosticName`）。
本次没有改 APK 侧版本，也没有操作设备。

## 仍未验证什么

- **手环应用列表里的实际观感未验证。** 本次只证明了打包进 RPK 的 `logo.png` 的数值，
  没有在任何真实手环上安装或打开应用列表，因此不能声明图标在实机上"好看/正确显示"。
- **图标大小是否合适未验证。** 圆盘直径 136px、占比 38.9% 是按工单建议值取的，
  真机小屏上是否偏大或偏小、透明圆在系统列表底色上是否清晰，都还没有实机证据。
- **亮绿箭头在低亮度实机上的可读性未验证。** 只证明了像素颜色正确，未做亮度/对比度实机检查。
- 门禁 `scripts/test-product-runtime.js` 自身声明 `notDevicePass: true`、`notAmapPass: true`，
  它跑的是本地渲染，不是官方 QEMU 固件或真机。

综上：本次交付的是"可数值核验的图标与名称改动"，**不是**实机通过。
