# 设备页数据初始化补修报告（W-636 / r10 补修）

本票只覆盖页面 **data 初始化** 缺口与主机/模拟器验证判定，**不声称整条实机验收完成**。
W-634 / W-635 accepted 结论保持不变。

## 1. 官方规则与本轮缺口

官方页面数据对象规范（本轮已核实）：
<https://iot.mi.com/vela/quickapp/zh/guide/framework/script/page-data.html>

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `data` | **Object** | 组件级数据模型；属性名不能以 `$`/`_` 开头，不用保留字 |
| `public` / `protected` / `private` | Object | 页面级数据模型，影响传入数据的覆盖机制 |

规范明示：**`public`、`protected`、`private` 不能与 `data` 同时使用**，且 `data` 的类型是
`Object`，不是函数。

来源工作树 `10f0a564` 的页面把页面数据写成了 `data() { return {...}; }`。按官方规则，
宿主只会把 `data` **当作对象**读取，**不会调用**它，因此页面一个初值都没有拿到：用户 vc5
实测为 `init=NaN`、`ready/show/tick=undefined`。本票按该规则复现负例并做最小修复。

## 2. vc5 负例（只读字节副本）

`build/device-page-init/`（ignored）中保存了工作树 `band-app/dist/` 内
`com.minavigatering.probe.debug.0.1.4-refresh.rpk` 与其构建记录的**逐字节副本**，
作为只读负例，来源工作树未改动：

| 文件 | 字节 | SHA256 |
| --- | --- | --- |
| `vc5-negative-0.1.4-refresh.rpk` | 13693 | `a14572bf6ff43922346a84f77be0b5b27fd688e4d0a9fe3dc6036003c7dddc22` |
| `vc5-negative-rpk-build-record.json` | 589 | `8f95baf74cd01ebae86e6066e864d49517218293b3642f9cef4e560981560...` |

`vc5-negative-fixture.json` 记录副本来源、字节与哈希；`dual-bundle-share.js` 每次运行都
校验 `vc5-negative-0.1.4-refresh.rpk` 的 SHA256 仍等于来源 vc5 包的哈希。

在**同一 Object-only 规则**下加载该真实 page bundle：

- `data` 形状 = `function`；`$data` 的键数量 = **0**，声明的 14 个页面字段**全部未初始化**；
- `onInit` 后 `lifeText = init=NaN ready=undefined show=undefined tick=NaN`（`init` 计数
  加在从未初始化的字段上得到 `NaN`，未跑到的生命周期保持 `undefined`），与用户 vc5
  观察一致；
- 定时器回调触发后计数器仍为 `NaN`，`lifeTick` 永远不会变成数字。

说明：vc5 页面在首次 tick 时会去读共享 session，因此其 `title` 之后会变成 `左转` —— 这是
**会话读回**，不是页面数据初始化；生命周期计数始终 `NaN/undefined`，正是用户看到的现象。

## 3. 源码差异（最小修复）

`band-app/src/pages/index/index.ux`：`data() { return {...}; }` → `data: {...}`（去掉
`return` 与函数包装），**所有现初值与页面字段原样保留**；`tickTimer` 仍是非 `data` 的普通
实例属性，未被并入。`bumpLife` 未改成 `undefined` 归零掩盖问题；共享/通信层未改；
`app.ux` 的 `onCreate`、`$app.$def`、`scripts/build-rpk.js` 均未改。

`band-app/src/manifest.json`：`0.1.4-refresh/5` → `0.1.5-init/6`，包名保持
`com.minavigatering.probe`。

`band-app/test/dual-bundle-share.js`：`makePageInstance` 改为**仅接受 Object data**，
不再调用 data 函数；新增 vc5 负例断言、新包全字段初始化断言与 `--page-init-validation`
入口。`band-app/test/startup.test.js`：`index.ux onInit` 用例改为按 Object 读取 `def.data`
（断言 `typeof def.data === "object"`），不再调用 `def.data()`。

## 4. 版本 / 唯一 RPK 路径 / 构建记录

- versionName/versionCode：`0.1.5-init` / `6`
- 唯一 RPK：`band-app/dist/com.minavigatering.probe.debug.0.1.5-init.rpk`
- 字节：13673；SHA256：`154aba08dfc38e520f1317589090eead7285b5dcf5952e23f4b12c68bfdb84a2`
- 构建记录：`band-app/dist/rpk-build-record.json`（`result=success`，`aiotExitCode=0`）
- 诊断副本：`band-app/dist/diagnostic-icon-vc6-154aba08.rpk`（同上 RPK 哈希）
- 包名一致：打包 manifest `package = com.minavigatering.probe` =
  `app/build.gradle.kts` 的 `applicationId`。

注：aiot-toolkit 会把构建时间戳写进 `packageInfo`，因此同名/同源的两次构建字节不同；
上面记录的是当前磁盘上唯一 0.1.5-init RPK 的实际事实（`record.sha256` 与文件哈希逐字节一致，
由 `dual-bundle-share.js` 每次运行校验）。其他 versionName 的 RPK 不在 `band-app/dist/` 内。

## 5. 测试命令与退出码

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| `node scripts/build-rpk.js` | 0 | 构建成功，产出唯一 0.1.5-init RPK（`154aba08…`，13673 字节） |
| `node band-app/test/dual-bundle-share.js --page-init-validation` | 0 | 见下（过程完整；0 **不代表**模拟器验收通过） |

## 6. 宿主回归（Object-only 规则）

`dual-bundle-share.js` 在 `--page-init-validation` 下先跑既有回归，再定向跑
`node --test band-app/test/startup.test.js band-app/test/session.test.js`（退出码原样传播，
未接管道）；定向测试 **9/9 通过，退出码 0**。

- vc5 负例：`dataShape=function`，初始化字段 0/14，`init=NaN ready/show/tick=undefined`。
- 新包：`dataShape=object`，**14/14 字段全部初始化**（title/诊断/计数均包含）；`lifeInit`、
  `lifeReady`、`lifeShow`、`lifeTick` 均为有限数字，`init>=1`。
- 计时器：`onInit` 只注册一个 interval；调用**已注册的回调**（非手调 `tick()`）后
  `lifeText` 的 `tick` 增长，计数随之增长。
- 既有行为保持：独立 cache 双 bundle、300→200 距离更新可见、普通距离静默/ack、onHide 停
  interval、onShow 恢复最新帧、异步 SDK 诊断只在宿主 flush 后落到页面、异常初始化路径。
- vc3 负例仍保留并通过。

## 7. 原生模拟器判定（与宿主回归分开）

新真实 RPK 被装载进既有只读运行层
`<本机>/bes-qemu/miband9/rpk_runtime/QuickAppRuntime`：**未**调用
`rpk_loader.buildBundle`（该函数会强制合并 webpack cache，不得作为正式加载通道），
而是按 `loadLocalMode` 形状，每个 chunk 各自 `buildSandbox` + 原样 `vm.Script` 执行，
`app.js` 的 `onCreate` 照常调用，page chunk 走 `exp.entry` 后 `ctx.$register_page$`；
每个 chunk 仍各自持有 `var __webpack_module_cache__ = {}`，**cache 未改写**。随后调用
runtime **原生 `launchPage`**，并等待**真实 1000ms 计时器**（未手调 tick）。合成
300→200 注入为宿主侧注入，非设备投递。

结果（详见 `build/device-page-init/page-init-validation.json`）：

- 原生 sandbox **没有 `$app`**（`sandboxHasApp=false`），页面走
  `sharedSession()` 的异常初始化路径：`title/诊断=异常初始化`。这是**原生能力缺口**，
  不是宿主/模板伪造的 pass。
- 原生布局是 `quickapp_vm.js:393` 起**手写的 element 列表**，没有忠实模板渲染。
- `launchPage` 期间**无异常**（`errors=[]`）；原生计时器确实推进：等待前后
  `lifeTick 0→2`（`timerAdvancedLifeTick=true`），合成 300→200 注入后 `lifeTick=4`；
  `commLog` 里留下 2 条 ack、`vibrateLog` 1 条。
- 原生 renderer 产出一帧：`build/device-page-init/native-frame.png`（2531 字节，
  SHA256 `245249bc4a51dd497ca3544d00241a2130ac698ecb378a1e940a415b97854546`），
  画面为异常初始化态。

判定：**SIM_BLOCKED**
1. 原生 sandbox 无 `$app`，`this.$app.$def` 无法解析，共享 session 不可达；
2. 原生布局为手写，无忠实模板渲染，模板绑定字段无法观察。

该入口在**主机回归已通过**且证据已保存的前提下，**退出 0 只代表验证过程完整，不代表模拟器
验收通过**。未为通过而给 native page 补 `$app`、手调初始化/计数、改模板或合并 cache；
宿主未修。

## 8. 旁证（只读 8092）

`http://127.0.0.1:8092/status` 报告 `version = "0.1.2-diag"`，`/frame.png` 另存为
`build/device-page-init/side-8092-frame.png`。该 8092 服务是**预先存在的旧实例**，
其截图为 **vc3 旧包**，未用作新包证据；现服务经 `buildBundle` 强制 cache，不能作正式
加载通道。

## 9. 边界

- 图标仍 **fail**；实机新包 **未安装/未验收**。
- 震动 / 锁屏 / 回表盘 / 超时仍 **unverified**。
- 模拟器能力不足，本票未安排实机安装。
- 本票仅补页面初始化与主机/模拟器验证，不声称整条实机验收完成。
