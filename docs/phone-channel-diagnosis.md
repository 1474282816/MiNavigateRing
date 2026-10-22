# 手机探针通道诊断（静态步骤说明）

本文件是**静态步骤说明**，描述如何用手机探针 App 的「通道诊断」四行读出手机侧通道状态。
本文件**不执行任何设备操作**，也**不记录任何 APK 哈希**，更**不写 pass**。

凡未实测项一律写 `unverified`：`unverified` 表示**没有取得对应证据**，不表示通过，也不表示失败。

## 1. 这一步要解决的问题

W-624 之后，探针页面只在**发送导航指令**时才有反馈：不发指令时看不出宿主服务、节点与权限
到底处于什么状态，SDK 失败也只落到 logcat。本文件描述的是**失败可见的诊断**：

- 是否连上官方宿主服务（含宿主服务 API level）；
- 是否解析到节点（节点 id 是否非空；可显示节点名）；
- 自定义消息通道所需的**设备管理权限**当前是已授予、未授予、被拒绝，还是根本没有可调用的节点；
- **最近一次 SDK 异常的类简单名**（例如 `PermissionDeniedException`）与脱敏后的 message。

**这不是认定「权限缺失」为唯一根因，也不是实机回执验收。** 诊断行只描述**手机侧**事实。

## 2. 术语边界（不得混淆）

| 层级 | 含义 | 是否手环回执 |
|---|---|---|
| 已排队 | 本地通道接受了报文，SDK `Task` 仍 pending | **不是** |
| 通道任务成功 | SDK `Task` 成功返回 | **不是** |
| 权限已授予 / 节点存在 | 手机侧前置条件满足 | **不是** |
| 手环确认 | 收到 `AckMessage`，且 `refId` 对应**已发送过**的 id | **是**，仅此一种 |

页面上的回执行默认显示「尚未收到回执」；只有在 `onInbound` 收到已记住 id 的 `AckMessage`
时才会变成「手环确认 refId=…」。**通道任务成功、权限授予、节点存在都不得当成手环回执。**

## 3. 安装前准备（先断开安装工具的蓝牙，保持账号绑定）

> 以下为**待执行**的静态步骤，本文件本身不执行。

1. **先断开 `tech.pingx.watchface` 的蓝牙连接**（安装工具/表盘工具的蓝牙通道），
   避免安装工具占用或干扰宿主通道。
2. **保持账号绑定不变**：不断开官方 App 的账号绑定，**不迁移手环绑定**，
   **不解绑、不恢复出厂、不升级固件、不修改手机系统安全设置**。
3. 确认目标手机身份后再安装：**只在本单确认过的荣耀辅助机**上安装——
   序列 `<HONOR_SERIAL>`、品牌 `HONOR`、型号 `AAP-AN00`（三项**同时**成立）。
   传输名相似**不作为**依据；K90 与手环不在本步骤范围内。
4. 安装对象是**本单验证时 stdout 里的那个新 debug APK**（`assembleDebug` 产物）。
   本文件**不记录、也不比对 APK 哈希**。
5. 安装后**打开探针 App**，即进入探针页面。

## 4. 读取四行诊断

打开探针页面后，页面上方「通道诊断（手机侧，非手环回执）」区有四行：

| 行 | 读什么 | 说明 |
|---|---|---|
| 宿主服务 | 宿主服务 API level 或连接事件 | 未探测时显示「（尚未探测）」 |
| 节点 | 节点 id 是否非空（可显示节点名） | 不显示账号、AuthKey 或任何密钥 |
| 权限 | 设备管理权限：已授予 / 未授予 / 检查失败 / 无节点 | 无节点时是「无节点，未调用 checkPermission」 |
| 最近 SDK 错误 | SDK 异常**类简单名** + 脱敏 message | 类名原样保留，例如 `PermissionDeniedException` |

页面进入时会**自动**执行：服务 API level 查询、服务连接监听注册、节点刷新；
**有节点时**再执行一次权限**检查**。进入页面**不会**自动请求设备管理权限。

两个入口：

- **「刷新通道诊断」**：重跑上面的只读诊断。
- **「请求设备管理权限」**：只有在**节点 id 非空**时才真正调用 SDK 的
  `requestPermission(nodeId, Permission.DEVICE_MANAGER)`。无节点时显示
  「无节点，未调用 requestPermission」，**不调用 SDK**。

SDK 的四个异常类（`PermissionDeniedException`、`DeviceDisconnectedException`、
`AppNotInstalledException`、`SignatureVerifyFailedException`）都只继承 `Exception`
并只有 `String` 构造器，**没有错误码**。诊断里展示的是类简单名与脱敏 message，
**不编造数字错误码**。

供参考，SDK 侧签名（`javap` 于 `app/libs/xms-wearable-lib_1.4_release.aar`）：

- `AuthApi.checkPermission(String, Permission) -> Task<Boolean>`
- `AuthApi.requestPermission(String, Permission...) -> Task<Permission[]>`
- `ServiceApi.getServiceApiLevel() -> Task<Integer>`
- `ServiceApi.registerServiceConnectionListener / unregisterServiceConnectionListener(OnServiceConnectionListener)`

## 5. 依次发送左转 300 → 左转 200

> 以下为**待执行**的静态步骤，本文件本身不执行。

1. 在探针 App 点「左转 300 米」（合成夹具，`id=probe-1`）。
2. 在探针 App 点「左转 200 米」（合成夹具，`id=probe-2`，同一 `actionId`）。
3. 每次记录：发送状态行（已排队 / 通道任务成功 / 通道任务失败）与回执行。

**回执判定只有一种**：页面回执行出现「手环确认 refId=…」，且该 `refId` 对应**已发送过**的 id
（例如 `probe-1` / `probe-2`）。其余任何文字都**不算**回执。

## 6. 真实阻塞：不得写成成功

以下都是**真实阻塞**，必须照实记录，**不得写成 SDK 成功**：

- **宿主未绑定**：诊断显示宿主服务不可用或节点为空。
- **权限拒绝**：诊断显示「设备管理权限：未授予」或 SDK 异常类为 `PermissionDeniedException`。
- **节点为空**：诊断显示「无节点，未调用 checkPermission」/「无节点，未调用 requestPermission」，
  此时 SDK **没有被调用**，不能记成权限已授予或通道可用。
- **无 refId 回执**：没有出现「手环确认 refId=…」，回执行仍是「尚未收到回执」——
  这**不得当作 pass**，只能记为 `unverified`/阻塞。

合成夹具道路名与转向只用于协议/状态验证，**不代表已验收的高德真实解析**。
没有高德实测通知时，不得用合成夹具宣称中文真实解析已验收。

## 7. 未验证矩阵

| 验证项 | 荣耀手机端 | K90手机端 | 手环通信 |
|---|---|---|---|
| 四行诊断可见 | unverified | unverified | unverified |
| 左转 300→200 发送 | unverified | unverified | unverified |
| 手环确认 refId 回执 | unverified | unverified | unverified |
| 5 秒心跳 / 15 秒超时 | unverified | unverified | unverified |
| 锁屏 / 熄屏 / 回表盘 | unverified | unverified | unverified |

- 荣耀手机端：本文件为静态说明，**未执行**任何安装或点击。
- K90 手机端：**未执行任何命令**。
- 手环通信：不执行、不推断手环侧能力。

## 8. 边界声明

- 本文件只覆盖**荣耀辅助机**的手机侧诊断读取步骤，**不能**代表 K90 手机端或手环通信的最终验收。
- 本文件**不记录 APK 哈希**，**不写 pass**。
- 本文件不修改手环绑定，不读取、不展示、也不向用户索取 AuthKey / 账号密钥。
