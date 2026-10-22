# bes-qemu 模拟器与导航 RPK 的缺口报告（只读核对，非验收）

本文件由人工撰写一次，**不由任何脚本改写**。它记录对既有 versionCode 3 诊断 RPK 的
只读 ZIP/清单核对，以及对 bes-qemu 模拟器 QMP 桥与 NAND 校验程序的**真实失败**。
本文件的结论是：**当前 bes-qemu 模拟器不能充当导航 RPK 的运行环境**。
所有未取得实机证据的项目仍记 `unverified`，`unverified` 既不表示通过也不表示失败。

## 1. 被核对的诊断包（已核实，不改写成成功）

| 字段 | 值 |
| --- | --- |
| 路径 | `band-app/dist/com.minavigatering.probe.debug.0.1.2-diag.rpk` |
| 字节数 | 15572 |
| SHA256 | `739151eabd2a145baf199799a70ab2d66ad5dd27b0745b74684229c5c27937ce` |
| request/package | `com.minavigatering.probe` |
| versionName | `0.1.2-diag` |
| versionCode | 3 |

该包来自 VR-347，是权威诊断包；它与执行者更早的 `e2e7f592…` 包**不是同一个文件**。
本报告不运行 `node scripts/build-rpk.js`。只读核对动态结果只写入
`band-app/dist/bes-qemu-readonly.json`（`band-app/dist/` 已被 gitignore）。

## 2. 五层结论

### 2.1 第一层：ZIP / 清单可检查

诊断 RPK 是合法 ZIP，含 `manifest.json`、`app.js`、`pages/index/index.js` 等条目。
用既有 `rpk_tool.py` 的 `inspect_rpk`（只读）可读出包名 `com.minavigatering.probe`、
versionCode `3`、SHA256 与上表一致。

因此 `inspect.has_app_js` 与 `inspect.has_entry_code` 都为真，`inspect.specPass` 为真。
但必须强调边界：**`inspect.specPass` 只是"包结构完整、入口脚本存在"，不是"规范整体通过"**。
缺 `app.js` 时 `specPass` 必须为假；本包恰好两样都有，所以为真。

### 2.2 第二层：QMP 目录记录的是本次实际请求结果

本报告在 2026-10-13 由执行者**亲自再请求一次**并记录实际结果（不照抄协调者的记录）：

- `GET http://127.0.0.1:8090/status` → **HTTP 500**，响应体含
  `error: [WinError 10054] 远程主机强迫关闭了一个现有的连接。`
- `POST http://127.0.0.1:8090/qmp`，`execute=qom-get`、`path=/machine`、
  `property=install-catalog` → **HTTP 200，但 JSON 体含 `error`**（同样是 WinError 10054），
  **没有 `return` 目录**。

按 `interpret_qmp` 的判定：只有 HTTP 200、JSON 无 `error` 键、且 `return` 能按
`type:size:sha256` 逗号分隔解析时 `ok` 才为真。本次 **`qmp.ok` 为 false**。
**HTTP 200 不等于 catalog 成功**——这正是本票要纠正的写法。

### 2.3 第三层：NAND 布局本次未执行

`verify_yaffs_overlay.exe` 本次启动即失败：退出码 **3221225781（0xC0000135，DLL 缺失）**，
`interpret_nand` 记 `reason=dll_not_found`，`nand.layoutVerified` 恒为 **false**。

即便它退出 0，也**仍然**不能作为本项目 RPK 的内容一致性证据：该 C 程序的断言只比较内存
mock 的节点名（"mass"/"quickapp.rpk"/"active.rpk"），不是本项目 15572 字节 RPK 的
内容哈希。`firmware_rpk.c` 的 `scan_nand_for_rpk` 写死 `package=com.xiaomi.band9.demo`、
`version=V1.0.0`、`installed=1`、`size=1322`，**不执行 RPK JavaScript**。

### 2.4 第四层：内容一致性与重启持久化未做

- **内容一致性**：`inspect_rpk` 只对诊断包算 SHA256；模拟器侧 NAND 上没有任何本项目
  RPK 的内容哈希可对，且 QMP catalog 查询失败。此项 `unverified`。
- **重启持久化**：未做。本票不注入 flash.journal、不重启模拟器、不写入任何 NAND。
  此项 `unverified`。

### 2.5 第五层：真实 JS 未执行

**`inspect.jsRuntimeExecuted` 恒为 false。** 诊断包里的 `app.js` / `pages/index/index.js`
从未被任何 Vela JS 运行时加载或执行。`firmware_rpk.c` 的扫描逻辑不执行 RPK
JavaScript；`verify_yaffs_overlay` 只做内存 mock 断言。模拟器**没有**把诊断包当成
导航应用跑起来，也**没有**渲染页面、也**没有**把任何一帧送到屏幕。

## 3. 纠正旧说法：rpk_tool 已有 QMP install-catalog 跟踪

既有 `<本机>/bes-qemu/miband9/rpk_tool.py` **已包含**一条 QMP `install-catalog`
跟踪路径（`get_installed_catalog` 读取 `/machine` 的 `install-catalog` 属性，`install_rpk`
会 `qom-set` 注入安装文件）。因此旧说法"模拟器完全没有安装入口"**不成立**，本报告将其
**纠正**。

但必须同时指出并**保留**另一点：**"跟踪目录"不是"JS 运行时"**。install-catalog 里出现
一个 type 64 条目，只说明模拟器登记了一个包，**不表示**该包的 Vela JavaScript 被加载、
被路由、被渲染或被执行。**没有 JS 运行时**这一判断不变——本报告只是纠正"完全没有安装
入口"的说法，绝不删除"没有 JS 运行时"的判断。

本票**不调用** `install_rpk`、不发送 `qom-set install-file`、不发送 `remove-file`，
不运行 `verify_miband9.py` 或 `run_full_rpk_test.py`，也不修改 bes-qemu 任何文件。
这里描述 install-catalog 是对既有源码的只读阅读，不是执行。

## 4. 与验收标准的关系（逐条标注，不置 pass）

- **AC3 送达**：本项目导航 RPK 的送达仍 **unverified**。模拟器未运行该包，不构成送达。
- **真实 refId**：仍 **unverified**。模拟器没有任何真实高德通知、SDK 通道或手环回执。
- **锁屏 / 熄屏 / 回表盘**：仍 **unverified**。模拟器无法替代实机的锁屏、熄屏、返回表盘。
- **图标可见**：仍 **unverified**。本报告未渲染也未截图任何诊断包界面。

**本报告不把模拟器计为导航 RPK 运行或送达 pass。**

## 5. 缺口与后续验证条件

要让模拟器真正成为可用的一环，至少还需要：

1. 一个**真实的 Vela JS 运行时**，能加载并路由诊断包的 `app.js`/`pages/index`。
2. 一个**隔离的 `flash.journal`**（不在既有共享 flash 上写），避免污染既有模拟状态。
3. **UART 断言**：从串口日志断言 JS 已被加载、页面已进入。
4. **像素断言**：从帧缓冲断言导航页面被真实渲染。

在上述条件全部具备之前，模拟器不能用于导航 RPK 的通过判定。**本票不修改 bes-qemu 实现**
（包括不为其添加 JS 引擎），只交付本缺口报告与只读探测证据。

## 6. 边界与口径

- 实机结果在取得设备证据之前一律 `unverified`；本文件不写任何设备 pass。
- 脚本退出码 `0` 只表示**报告写完**，不表示 QMP/NAND 成功，也不表示送达。
- HTTP 200、`specPass=true`、NAND mock 断言文字、demo 包名
  `com.xiaomi.band9.demo`，**都不得**写成导航 RPK 已启动。
- 本文件不记录私钥、账号密钥或任何原始路线文本。
