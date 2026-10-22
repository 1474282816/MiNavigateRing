# 荣耀辅助机（HONOR AAP-AN00）手机端探针证据

本文件由 `scripts/honor-phone-probe.py` 追加写入：每次运行新增一节，**不覆盖旧证据**。

三列证据互相独立，不得互相替代：

| 列 | 含义 |
|---|---|
| 荣耀手机端 | 工单选中的 HONOR 辅助机（ro.serialno 为 `<HONOR_SERIAL>`）上的手机端事实 |
| K90 手机端 | 目标机 K90；本单未在其上执行任何命令 |
| 手环通信 | 手环 9 的绑定、消息通道与后台能力；本单不执行、不推断 |

`unverified` 一律表示**本次运行没有取得对应证据**，不表示通过，也不表示失败。

无匹配或设备掉线时脚本退出码为 2，矩阵记 unverified；退出码 0 只表示本次真实 ADB 安装并启动成功。

---
## 运行记录 2026-10-13 02:25:33 +0800（工单 W-615）

- 脚本：`scripts/honor-phone-probe.py`；APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK SHA256（本次实测，非前置条件）：`ae1d1cd04aa9c9dadc824db7dcd644a05d47cd8e66be76a6e89e325bef1380a6`
- 目标选择条件：`ro.serialno`=`<HONOR_SERIAL>` 且 `ro.product.brand`=`HONOR` 且 `ro.product.model`=`AAP-AN00`（三项同时成立；传输名相似不作为依据）
- 传输顺序策略：目标 mDNS 传输（`adb-<HONOR_SERIAL>*_adb-tls-connect._tcp`）优先，其余 `device` 传输在后

### 传输探测

| 顺序 | ADB 序列 | state |
|---|---|---|
| 1 | `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` | device |
| 2 | `<LAN_IP>:42151` | device |

### 身份查询结果

| ADB 序列 | 判定 | ro.serialno | ro.product.brand | ro.product.model | ro.product.marketname | ro.build.version.release | ro.build.display.id |
|---|---|---|---|---|---|---|---|
| `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` | 目标匹配 | <HONOR_SERIAL> | HONOR | AAP-AN00 | (空) | 16 | AAP-AN00 10.0.0.175(C00E170R104P3) |

### 荣耀手机端执行结果

| 项 | 值 |
|---|---|
| 选中的 ADB 序列 | `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` |
| 身份属性 | `ro.serialno=<HONOR_SERIAL>`、`ro.product.brand=HONOR`、`ro.product.model=AAP-AN00` |
| `adb -s adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk` 退出码 | **0** |
| 安装输出 | stdout=Performing Streamed Install / Success |
| `adb -s adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp shell am start -n com.minavigatering.probe/.MainActivity` 退出码 | **0** |
| am start 输出 | stdout=Starting: Intent { cmp=com.minavigatering.probe/.MainActivity } |
| 本次安装包版本 | 0.1.0 |

### 手机包事实（不构成手环绑定证据）

| 命令 | 退出码 |
|---|---|
| `dumpsys package com.minavigatering.probe` | 0 |
| `dumpsys package com.mi.health` | 0 |
| `pm path com.xiaomi.wearable` | 1 |

- `com.mi.health` 在本机已安装。**这只是一条手机端包事实，不能据此认为当前手环已绑定或可以迁移。**


### 三列证据矩阵

| 验证项 | 荣耀手机端 | K90手机端 | 手环通信 |
|---|---|---|---|
| 安装（`adb install -r`） | 已执行，真实退出码 0（序列 `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp`） | unverified | unverified |
| 手机锁屏 | unverified | unverified | unverified |
| 手环熄屏 | unverified | unverified | unverified |
| 回表盘后继续更新 | unverified | unverified | unverified |
| 震动 | unverified | unverified | unverified |
| 5 秒心跳 | unverified | unverified | unverified |
| 15 秒超时 | unverified | unverified | unverified |
| 通信恢复后最新状态 | unverified | unverified | unverified |

- 荣耀列的锁屏、熄屏、回表盘、震动、5 秒心跳、15 秒超时、恢复后最新状态均记 unverified：MainActivity 未启动 ProbeForegroundService，且本单不得锁屏或接受授权弹窗。
- K90 手机端各列均记 unverified：本单未在 K90 上执行任何 adb 命令。
- 手环通信各列均记 unverified：本单不执行、不推断手环侧能力。
- 补充说明：本单没有取得荣耀设备上 `ro.product.marketname` 的非空取值，因此与荣耀WIN的营销名对应仍未由属性证实。
- 边界声明：以上荣耀手机端结果只覆盖该辅助机的安装与启动，不能代表 K90 手机端或手环通信的最终验收，也不能作为手环侧能力的证据。
- 本次运行进程退出码：**0**

---
## 运行记录 2026-10-13 11:26:45 +0800（荣耀辅助机探针）

- 脚本：`scripts/honor-phone-probe.py`；APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK SHA256（本次实测，非前置条件）：`f516de2e28a14c2338a376f2ae97f0ea37f9e4a7bca7bd68c1d1e297b4b4bc31`
- APK 身份记录：`app/build/outputs/apk/debug/apk-identity.txt`；闸门判定：ok
- 身份闸门在**任何 adb 调用之前**执行：记录中的 sha256 必须与本次 APK 字节一致，且不得等于旧包哈希 `ae1d1cd04aa9c9dadc824db7dcd644a05d47cd8e66be76a6e89e325bef1380a6`
- 目标选择条件：`ro.serialno`=`<HONOR_SERIAL>` 且 `ro.product.brand`=`HONOR` 且 `ro.product.model`=`AAP-AN00`（三项同时成立；传输名相似不作为依据）
- 传输顺序策略：目标 mDNS 传输（`adb-<HONOR_SERIAL>*_adb-tls-connect._tcp`）优先，其余 `device` 传输在后

### 传输探测

| 顺序 | ADB 序列 | state |
|---|---|---|
| 1 | `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` | device |
| 2 | `<LAN_IP>:42151` | device |

### 身份查询结果

| ADB 序列 | 判定 | ro.serialno | ro.product.brand | ro.product.model | ro.product.marketname | ro.build.version.release | ro.build.display.id |
|---|---|---|---|---|---|---|---|
| `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` | 目标匹配 | <HONOR_SERIAL> | HONOR | AAP-AN00 | (空) | 16 | AAP-AN00 10.0.0.175(C00E170R104P3) |

### 荣耀手机端执行结果

| 项 | 值 |
|---|---|
| 选中的 ADB 序列 | `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` |
| 身份属性 | `ro.serialno=<HONOR_SERIAL>`、`ro.product.brand=HONOR`、`ro.product.model=AAP-AN00` |
| `adb -s adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk` 退出码 | **0** |
| 安装输出 | stdout=Performing Streamed Install / Success |
| `adb -s adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp shell am start -n com.minavigatering.probe/.MainActivity` 退出码 | **0** |
| am start 输出 | stdout=Starting: Intent { cmp=com.minavigatering.probe/.MainActivity } |
| 本次安装包版本 | 0.1.0 |

### 手机包事实（不构成手环绑定证据）

| 命令 | 退出码 |
|---|---|
| `dumpsys package com.minavigatering.probe` | 0 |
| `dumpsys package com.mi.health` | 0 |
| `pm path com.xiaomi.wearable` | 1 |

- `com.mi.health` 在本机已安装。**这只是一条手机端包事实，不能据此认为当前手环已绑定或可以迁移。**


### 三列证据矩阵

| 验证项 | 荣耀手机端 | K90手机端 | 手环通信 |
|---|---|---|---|
| 安装（`adb install -r`） | 已执行，真实退出码 0（序列 `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp`） | unverified | unverified |
| 手机锁屏 | unverified | unverified | unverified |
| 手环熄屏 | unverified | unverified | unverified |
| 回表盘后继续更新 | unverified | unverified | unverified |
| 震动 | unverified | unverified | unverified |
| 5 秒心跳 | unverified | unverified | unverified |
| 15 秒超时 | unverified | unverified | unverified |
| 通信恢复后最新状态 | unverified | unverified | unverified |

- 荣耀列的锁屏、熄屏、回表盘、震动、5 秒心跳、15 秒超时、恢复后最新状态均记 unverified：MainActivity 未启动 ProbeForegroundService，且本单不得锁屏或接受授权弹窗。
- K90 手机端各列均记 unverified：本单未在 K90 上执行任何 adb 命令。
- 手环通信各列均记 unverified：本单不执行、不推断手环侧能力。
- 补充说明：本次运行未取得荣耀设备上 `ro.product.marketname` 的非空取值，因此与荣耀WIN的营销名对应仍未由属性证实。
- 边界声明：以上荣耀手机端结果只覆盖该辅助机的安装与启动，不能代表 K90 手机端或手环通信的最终验收，也不能作为手环侧能力的证据。
- 本次运行进程退出码：**0**

---
## 运行记录 2026-10-13 11:39:58 +0800（荣耀辅助机探针）

- 脚本：`scripts/honor-phone-probe.py`；APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK SHA256（本次实测，非前置条件）：`f516de2e28a14c2338a376f2ae97f0ea37f9e4a7bca7bd68c1d1e297b4b4bc31`
- APK 身份记录：`app/build/outputs/apk/debug/apk-identity.txt`；闸门判定：ok
- 身份闸门在**任何 adb 调用之前**执行：记录中的 sha256 必须与本次 APK 字节一致，且不得等于旧包哈希 `ae1d1cd04aa9c9dadc824db7dcd644a05d47cd8e66be76a6e89e325bef1380a6`
- 目标选择条件：`ro.serialno`=`<HONOR_SERIAL>` 且 `ro.product.brand`=`HONOR` 且 `ro.product.model`=`AAP-AN00`（三项同时成立；传输名相似不作为依据）
- 传输顺序策略：目标 mDNS 传输（`adb-<HONOR_SERIAL>*_adb-tls-connect._tcp`）优先，其余 `device` 传输在后

### 传输探测

| 顺序 | ADB 序列 | state |
|---|---|---|
| 1 | `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` | device |
| 2 | `<LAN_IP>:42151` | device |

### 身份查询结果

| ADB 序列 | 判定 | ro.serialno | ro.product.brand | ro.product.model | ro.product.marketname | ro.build.version.release | ro.build.display.id |
|---|---|---|---|---|---|---|---|
| `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` | 目标匹配 | <HONOR_SERIAL> | HONOR | AAP-AN00 | (空) | 16 | AAP-AN00 10.0.0.175(C00E170R104P3) |

### 荣耀手机端执行结果

| 项 | 值 |
|---|---|
| 选中的 ADB 序列 | `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` |
| 身份属性 | `ro.serialno=<HONOR_SERIAL>`、`ro.product.brand=HONOR`、`ro.product.model=AAP-AN00` |
| `adb -s adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk` 退出码 | **0** |
| 安装输出 | stdout=Performing Streamed Install / Success |
| `adb -s adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp shell am start -n com.minavigatering.probe/.MainActivity` 退出码 | **0** |
| am start 输出 | stdout=Starting: Intent { cmp=com.minavigatering.probe/.MainActivity } |
| 本次安装包版本 | 0.1.0 |

### 手机包事实（不构成手环绑定证据）

| 命令 | 退出码 |
|---|---|
| `dumpsys package com.minavigatering.probe` | 0 |
| `dumpsys package com.mi.health` | 0 |
| `pm path com.xiaomi.wearable` | 1 |

- `com.mi.health` 在本机已安装。**这只是一条手机端包事实，不能据此认为当前手环已绑定或可以迁移。**


### 三列证据矩阵

| 验证项 | 荣耀手机端 | K90手机端 | 手环通信 |
|---|---|---|---|
| 安装（`adb install -r`） | 已执行，真实退出码 0（序列 `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp`） | unverified | unverified |
| 手机锁屏 | unverified | unverified | unverified |
| 手环熄屏 | unverified | unverified | unverified |
| 回表盘后继续更新 | unverified | unverified | unverified |
| 震动 | unverified | unverified | unverified |
| 5 秒心跳 | unverified | unverified | unverified |
| 15 秒超时 | unverified | unverified | unverified |
| 通信恢复后最新状态 | unverified | unverified | unverified |

- 荣耀列的锁屏、熄屏、回表盘、震动、5 秒心跳、15 秒超时、恢复后最新状态均记 unverified：MainActivity 未启动 ProbeForegroundService，且本单不得锁屏或接受授权弹窗。
- K90 手机端各列均记 unverified：本单未在 K90 上执行任何 adb 命令。
- 手环通信各列均记 unverified：本单不执行、不推断手环侧能力。
- 补充说明：本次运行未取得荣耀设备上 `ro.product.marketname` 的非空取值，因此与荣耀WIN的营销名对应仍未由属性证实。
- 边界声明：以上荣耀手机端结果只覆盖该辅助机的安装与启动，不能代表 K90 手机端或手环通信的最终验收，也不能作为手环侧能力的证据。
- 本次运行进程退出码：**0**

---
## 运行记录 2026-10-13 11:41:06 +0800（荣耀辅助机探针）

- 脚本：`scripts/honor-phone-probe.py`；APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK SHA256（本次实测，非前置条件）：`f516de2e28a14c2338a376f2ae97f0ea37f9e4a7bca7bd68c1d1e297b4b4bc31`
- APK 身份记录：`app/build/outputs/apk/debug/apk-identity.txt`；闸门判定：ok
- 身份闸门在**任何 adb 调用之前**执行：记录中的 sha256 必须与本次 APK 字节一致，且不得等于旧包哈希 `ae1d1cd04aa9c9dadc824db7dcd644a05d47cd8e66be76a6e89e325bef1380a6`
- 目标选择条件：`ro.serialno`=`<HONOR_SERIAL>` 且 `ro.product.brand`=`HONOR` 且 `ro.product.model`=`AAP-AN00`（三项同时成立；传输名相似不作为依据）
- 传输顺序策略：目标 mDNS 传输（`adb-<HONOR_SERIAL>*_adb-tls-connect._tcp`）优先，其余 `device` 传输在后

### 传输探测

| 顺序 | ADB 序列 | state |
|---|---|---|
| 1 | `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` | device |
| 2 | `<LAN_IP>:42151` | device |

### 身份查询结果

| ADB 序列 | 判定 | ro.serialno | ro.product.brand | ro.product.model | ro.product.marketname | ro.build.version.release | ro.build.display.id |
|---|---|---|---|---|---|---|---|
| `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` | 目标匹配 | <HONOR_SERIAL> | HONOR | AAP-AN00 | (空) | 16 | AAP-AN00 10.0.0.175(C00E170R104P3) |

### 荣耀手机端执行结果

| 项 | 值 |
|---|---|
| 选中的 ADB 序列 | `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp` |
| 身份属性 | `ro.serialno=<HONOR_SERIAL>`、`ro.product.brand=HONOR`、`ro.product.model=AAP-AN00` |
| `adb -s adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp install -r app/build/outputs/apk/debug/app-debug.apk` 退出码 | **0** |
| 安装输出 | stdout=Performing Streamed Install / Success |
| `adb -s adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp shell am start -n com.minavigatering.probe/.MainActivity` 退出码 | **0** |
| am start 输出 | stdout=Starting: Intent { cmp=com.minavigatering.probe/.MainActivity } |
| 本次安装包版本 | 0.1.0 |

### 手机包事实（不构成手环绑定证据）

| 命令 | 退出码 |
|---|---|
| `dumpsys package com.minavigatering.probe` | 0 |
| `dumpsys package com.mi.health` | 0 |
| `pm path com.xiaomi.wearable` | 1 |

- `com.mi.health` 在本机已安装。**这只是一条手机端包事实，不能据此认为当前手环已绑定或可以迁移。**


### 三列证据矩阵

| 验证项 | 荣耀手机端 | K90手机端 | 手环通信 |
|---|---|---|---|
| 安装（`adb install -r`） | 已执行，真实退出码 0（序列 `adb-<HONOR_SERIAL>-Hvz49o._adb-tls-connect._tcp`） | unverified | unverified |
| 手机锁屏 | unverified | unverified | unverified |
| 手环熄屏 | unverified | unverified | unverified |
| 回表盘后继续更新 | unverified | unverified | unverified |
| 震动 | unverified | unverified | unverified |
| 5 秒心跳 | unverified | unverified | unverified |
| 15 秒超时 | unverified | unverified | unverified |
| 通信恢复后最新状态 | unverified | unverified | unverified |

- 荣耀列的锁屏、熄屏、回表盘、震动、5 秒心跳、15 秒超时、恢复后最新状态均记 unverified：MainActivity 未启动 ProbeForegroundService，且本单不得锁屏或接受授权弹窗。
- K90 手机端各列均记 unverified：本单未在 K90 上执行任何 adb 命令。
- 手环通信各列均记 unverified：本单不执行、不推断手环侧能力。
- 补充说明：本次运行未取得荣耀设备上 `ro.product.marketname` 的非空取值，因此与荣耀WIN的营销名对应仍未由属性证实。
- 边界声明：以上荣耀手机端结果只覆盖该辅助机的安装与启动，不能代表 K90 手机端或手环通信的最终验收，也不能作为手环侧能力的证据。
- 本次运行进程退出码：**0**
