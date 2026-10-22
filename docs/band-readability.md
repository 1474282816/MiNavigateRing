# 手环导航页可读性（W-692）

本文件记录 `band-app/src/pages/index/index.ux` 的静态布局放大改动：前后尺寸对照、
为什么受 192 宽限制、隐藏诊断入口怎么进，以及**仍未验证**的部分。

改动只碰两个文件：`band-app/src/pages/index/index.ux` 与本文档。通信、解析、导航引擎、
提醒语义、`scripts/`、`band-app/test/` 与 `band-app/src/common/` 一律未动。

---

## 1. 前后尺寸对照

对照基准：改动前的有效导航画面（基线提交 `e204aa46768452c006869acee170b58bc69cca3b`）。

| 元素 | class | 改动前 | 改动后 | 说明 |
| --- | --- | --- | --- | --- |
| 方向图标 | `.nav-icon` | 112×112 | **140×140** | 沿用现有 112px PNG，只放大显示尺寸（≤1.3 倍），不新增/不重绘图标资源 |
| 距离（≤3 位数） | `.distance.distance-short` | 52px | **64px** 粗体 白 | 新增静态类 |
| 距离（4 位数） | `.distance` | 52px | **52px** 粗体 白（不变） | 176px 内容宽下实测**单行** |
| 距离（≥5 位数） | `.distance` | 52px | **52px** 粗体 白（不变） | 保持基线档位与原文，**可能换行** |
| 路名 | `.road` | 26px 粗体 | **34px** 粗体 `#00dfff` | |
| 环岛出口 | `.exit` | 20px | **26px 粗体** `#ffdd00` | |
| 方向文字 | `.title` | 42px 粗体 `#00ff99` | **完全不变** | 字号与配色都保持原样 |
| 常驻诊断入口 | `.disclaimer` | 14px 文字"连接诊断/收起诊断" | **已删除** | 见第 3 节 |

距离档位由 `applyDisplay` 从**同一份** `distanceText` 的十进制数字个数派生：≤3 位走
`distance-short`（64px），≥4 位回落到基础 `distance`（52px）。距离数值本身既不截断也不
换算，语义不变。

### 数字档位实测（宿主 `measureWrappedTexts`，内容宽 176px）

| 距离文本 | 字号档 | 行数 |
| --- | --- | --- |
| `9米` / `30米` / `999米` | 64px | 1 |
| `1000米` / `9999米` | 52px | 1 |
| `10000米` / `120000米` | 52px | 2 |

**这是 Contract r2 明确认可的基线边界，不是本单自行豁免、不是放松门禁：**

- 4 位（`1000米`–`9999米`）在 52px 下保持**单行**，正是 AC1 要求的"4 位 52px 且单行"。
- 5 位及以上（`10000米` / `120000米`）沿用基线 **52px 与原文**，可能换行；Contract r2 明确
  本票**不新增也不修复**这一档。改动对它既没有变好，也没有变差。
- 本单不截断距离、不改单位、不缩小字号来回避换行；超出 `≤3 位 64px / ≥4 位 52px` 的
  档位规则不在本票范围内。

## 2. 为什么受 192 宽限制

手环 9 逻辑分辨率 192×490（见 `PROJECT_SPEC.md`）。宿主按内容宽 **176px**（192 减左右
各 8px 边距）逐字符测宽并换行。因此：

- 4 位文本（`1000米`）在 64px 下实测 **192px**，超过 176px 就会换行，所以 4 位**不能**
  统一用 64px；而 64px 对 ≤3 位（`999米` = 160px）仍单行。这就是"≤3 位 64px、≥4 位 52px"
  这条档位规则的由来。
- 4 位及以上保持 52px：其中 4 位单行；5 位以上（`10000米` 52px = 182px）仍超出 176px，
  按原文可能换行。本文档**不承诺**任意长文本单行，也不通过截断/改单位/缩字来规避。
- 路名 34px / 出口 26px 是在 176px 内仍能单行显示 `淮海中路`(136px)、`第2出口`(91px)
  的上限附近取值，再大就会换行。
- 每一行元素底部都必须 ≤490px，否则被裁掉。

数值都是**建议值**，实际以"不溢出、不意外换行"为准。

## 3. 隐藏手势：怎么进入 / 收起诊断

常驻的 `连接诊断` / `收起诊断` 文字入口已删除，主页面不再显示它。诊断块仍然**默认收起**
（`debugVisible` 初始 `false`，既有 `diagReady` / `diagStatus` / `inboundSummary` /
`linkEvent` / `lifeText` 字段一个都没改名、没删除）。

展开与收起方式：

> **长按屏幕上始终存在的方向文字（`.title`）**，即切换诊断块的展开/收起。

模板绑定为 `onlongpress="toggleDebug"`（`<text class="title" onlongpress="toggleDebug">`），
处理器沿用原有的 `toggleDebug`（`this.debugVisible = !this.debugVisible`），只是从
`onclick` 换到了 `onlongpress`。

- 方向文字在任何状态下都在屏（`连接中`/`暂无导航`/`异常初始化`），所以
  入口始终可触达。
- 截图/宿主预览**不能**验证真机手势，见第 4 节。

## 4. 仍未验证 / 不声称通过

- **低亮度实机可读性未验证**：本单只给出 192×490 的主机渲染 PNG 与字号度量，没有手环 9
  实机在户外/夜间亮度下的观感证据，也没有实机亮度对比测试。
- **长按手势在真机上是否可用未验证**：宿主 `quickapp_vm.js` 的 `triggerEvent` 只调用页面
  处理器，不模拟触摸；因此本次只能证明"处理器 `toggleDebug` 往返可展开/收起"以及模板
  编译接受了 `onlongpress` 绑定，**不能**证明真机手势能被识别。真机手势需在实际设备上另测。
- 本次交付是**主机预览**，不是实机通过，也不是高德实测通知。

## 5. 证据与复现

- 既有默认产品门禁：`node scripts/test-product-runtime.js`（默认模式，含真实构建与 14 动作
  渲染，输出 `PRODUCT_UI_PASS`），产物目录 `build/navigation-preview/`。
- 单测：`node --test band-app/test/startup.test.js band-app/test/session.test.js band-app/test/codec.test.js`。
- 颜色/字号下限、诊断初始收起、垂直 ≤490 等门禁断言全部保持通过。

### 5.1 产物目录与文件名（一次通过运行的实测结果）

目录：`build/navigation-preview/`（`build/` 被 `.gitignore` 忽略，PNG 不入库，只由门禁
每次重新生成）。本次通过的运行生成 17 张 **192×490** PNG 加 `assertions.json`：

| 文件名 | SHA256（前 12 位） |
| --- | --- |
| `left-300.png` | `0db072f55d59` |
| `left.png` | `35536d069fbb` |
| `right.png` | `2b505e348cfb` |
| `straight.png` | `3e69bf8aa5bb` |
| `slight-left.png` | `4436701c5c92` |
| `slight-right.png` | `dc0b6b757675` |
| `sharp-left.png` | `361c239ecf0d` |
| `sharp-right.png` | `9fa05011f68e` |
| `keep-left.png` | `a6c6a012f485` |
| `keep-right.png` | `a46cfad82ad9` |
| `uturn-left.png` | `932f08fc1a50` |
| `uturn-right.png` | `9bb0e9b4e5ef` |
| `roundabout-enter.png` | `1f1e84f21552` |
| `roundabout-exit.png` | `6dc4dab8edf3` |
| `arrive.png` | `c48c5039c3c3` |
| `unknown.png` | `baaff52dd121` |
| `ended.png` | `6df15076c4e9` |

同一次运行的 `assertions.json` 记录 `rpkSha256` 与 `rpkSize`（本轮清理生成目录后的那次通过
运行：`rpkSha256 = 8efadf28…f0b64`、`rpkSize = 52715`），每个 PNG 条目都带自己的 SHA256
并绑定这个包摘要。`left-300.png` 与 `left.png` 字节不同，证明距离确实驱动了画面。

上表 17 张 PNG 的摘要**逐次重跑完全相同**。但 RPK 包本身的 sha256/size 每次构建会小幅
变化（`52713`/`52714`/`52710`/`52715`，打包时间戳所致），所以交付证据以**每次运行的
`assertions.json`** 为准；PNG 内容与包摘要的绑定也不因此失效。

### 5.2 重跑门禁前必须先清掉 `build/navigation-preview/`

门禁默认模式**不清空**输出目录，遇到已存在的 PNG 会直接失败，这是脚本的既有行为：

```
[test-product-runtime] refusing to overwrite an existing PNG: ...\build\navigation-preview\left-300.png
```

（`scripts/test-product-runtime.js` 中 `writePng()` 对同名文件调用 `die(...)`；只有 `--rpk`
模式才会先 `rmSync` 输出目录。本单未修改 `scripts/`。）

因此，同一工作树里**第二次**直接跑默认门禁会以退出码 1 结束，这与界面改动无关：上次遗留的
17 张 PNG 会挡住首次写入。这是一个**重跑前提**，不随本次改动而改变。上一轮冻结命令正是被
遗留产物堵住而以退出码 1 结束，所以清理已经写进流程。重跑默认门禁前先删除该生成目录：

```bash
rm -rf build/navigation-preview   # 只删门禁自己重新生成的产物
node scripts/test-product-runtime.js
```

`build/navigation-preview/` 是门禁**每次重新生成**的产物目录（`build/` 被 `.gitignore` 忽略，
PNG 不入库），所以"该目录此刻是否在盘上"只是**瞬时状态**，不是交付事实：

- 本单交付前会清掉这个生成目录，让冻结命令能以同一默认 argv 一次跑通并由门禁自行重建上表
  的 17 张 PNG。
- 平台复验时会用同一默认 argv 再跑一次并**重新生成**预览；目录与文件名与上表一致。如果需要
  逐字核对摘要，请以**复验那次运行自己的 `assertions.json`** 为准。
