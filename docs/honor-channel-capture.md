# 荣耀辅助机（HONOR AAP-AN00）手机侧通道采集证据

本文件由 `scripts/honor-channel-capture.py` 追加写入：每次运行新增一节，**不覆盖旧证据**。

三列证据互相独立，不得互相替代：

| 列 | 含义 |
|---|---|
| 荣耀手机端 | 工单选中的 HONOR 辅助机（`ro.serialno` = `<HONOR_SERIAL>`）上的手机侧事实 |
| K90手机端 | 目标机 K90；本单未在其上执行任何命令 |
| 手环通信 | 手环 9 的绑定、消息通道与后台能力；本单不执行、不推断 |

`unverified` 一律表示**本次运行没有取得对应证据**，不表示通过，也不表示失败。
只有回执行真的出现「手环确认 refId=probe-1」或「手环确认 refId=probe-2」时，才可写
「取得手环确认」；其余任何文字都写「未取得回执」，不得写 pass。

---
## 运行记录 2026-10-13 15:05:16 +0800（荣耀辅助机手机侧通道采集）

- 脚本：`scripts/honor-channel-capture.py`；APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK SHA256（本次实测）：`285150f719ed2b9de155dbd11933478fbe16b91eccf9819ea890f3e235d31a8e`
- APK 身份闸门（在**任何 adb 调用之前**执行）：ok
- Android 版本约束：`versionCode=1`、`versionName=0.1.0`（由 `identity_gate` 强制，见 `scripts/print-apk-identity.py`）
- 目标身份：`ro.serialno`=`<HONOR_SERIAL>` 且 `ro.product.brand`=`HONOR` 且 `ro.product.model`=`AAP-AN00`（三项同时成立）

### 传输探测

| 顺序 | ADB 序列 | state |
|---|---|---|
| 1 | `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` | device |
| 2 | `<LAN_IP>:42151` | device |

### 身份查询结果

| ADB 序列 | 判定 | ro.serialno | ro.product.brand | ro.product.model |
|---|---|---|---|---|
| `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` | 目标匹配 | <HONOR_SERIAL> | HONOR | AAP-AN00 |

### 荣耀手机端执行结果

| 项 | 值 |
|---|---|
| 选中的 ADB 序列 | `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` |
| 身份属性 | `ro.serialno=<HONOR_SERIAL>`、`ro.product.brand=HONOR`、`ro.product.model=AAP-AN00` |
| `adb -s adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp install -r <APK>` 退出码 | **0** |
| install 输出 | stdout=Performing Streamed Install / Success |
| `adb -s adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp shell am start -n com.minavigatering.probe/.MainActivity` 退出码 | **0** |
| am start 输出 | stdout=Starting: Intent { cmp=com.minavigatering.probe/.MainActivity } |

### 四行通道诊断（手机侧，非手环回执）

| 行 | 值 |
|---|---|
| 宿主服务： | unverified |
| 节点： | unverified |
| 权限： | unverified |
| 最近 SDK 错误： | unverified |
| 链路： | unverified |
| 最近一次发送状态： | unverified |
| 最近一次回执： | unverified |


### 两次发送尝试（合成夹具）

| 顺序 | 按钮 | 点击 | 发送状态 | 回执 | 回执判定 |
|---|---|---|---|---|---|
| 1 | 左转 300 米 | unverified | unverified | unverified | not-receipt（未取得回执） |
| 2 | 左转 200 米 | unverified | unverified | unverified | not-receipt（未取得回执） |


### 手机包事实（不构成手环绑定证据）

- 只读 `dumpsys package com.mi.health`（退出码 0）：`com.mi.health` 在本机已安装。**这只是一条手机端包事实，不是手环绑定，也不构成手环通信证据。**

### 三列证据矩阵

| 验证项 | 荣耀手机端 | K90手机端 | 手环通信 |
|---|---|---|---|
| 四行通道诊断可见 | unverified | unverified | unverified |
| 左转 300 米发送 | unverified | unverified | unverified |
| 左转 200 米发送 | unverified | unverified | unverified |
| 手环确认 refId 回执 | 未取得回执（unverified） | unverified | unverified |
| 锁屏 | unverified | unverified | unverified |
| 熄屏 | unverified | unverified | unverified |
| 回表盘 | unverified | unverified | unverified |
| 5 秒心跳 | unverified | unverified | unverified |
| 15 秒超时 | unverified | unverified | unverified |

- 荣耀列的四行诊断与两次发送取自 `uiautomator` XML：能抽到的行照录，抽不到的行写 unverified。
- K90手机端各列均记 unverified：本单未在 K90 上执行任何 adb 命令。
- 手环通信各列均记 unverified：本单不执行、不推断手环侧能力。
- 锁屏、熄屏、回表盘、5 秒心跳、15 秒超时均记 unverified：本单不执行这些动作。
- 合成夹具声明：「左转 300 米（合成夹具）」「左转 200 米（同一 actionId，合成夹具）」只用于协议/状态验证，**不代表已验收的高德真实解析**。
- 前提 `tech.pingx.watchface` 蓝牙是否已断开：unverified（脚本不能代用户断开）。
- 边界声明：以上只覆盖荣耀辅助机的手机侧采集，不能代表 K90 手机端或手环通信的最终验收。
- 本次运行进程退出码：**0**
