# 体感震动测试（诊断页）

用途：验证 REDMI Watch 6 在「转弯提醒」时的震动是否被感知到，以及不同震动模式的强弱差异。
入口：手机端 App → 首页「连接诊断与测试」→ 滚动到「体感震动测试（手环震动，不改显示）」。

## 为什么只有 4 种模式

小米官方 Vela 文档（`@system.vibrator`）的支持明细里，REDMI Watch 6 只在 `vibrate` 一行：

| 接口 | REDMI Watch 6 | 说明 |
|---|---|---|
| `vibrate({mode})` | 支持 | 只有 `short` / `long` 两种 |
| `start({duration, interval, count})` | **不支持** | 只有 Xiaomi Watch S5 支持 |
| `stop(id)` | **不支持** | 同上 |
| `getSystemDefaultMode()` | **不支持** | 无法读系统强度档位 |

所以**没法调强度**，也没有 `duration`/`count` 参数。手环端的「三连短震」「双长震」是用多次
`vibrate()` 加 `setTimeout` 拼出来的：三连的间隔 260 ms / 520 ms，双长的间隔 900 ms。
间隔必须大于单次脉冲的尾巴，否则在体感上会连成一次。

## 四种模式

| 按钮 | 协议值 | 手环实际动作 | 用途 |
|---|---|---|---|
| 短震（1×短） | `short` | `vibrate({mode:"short"})` | **真实转弯提醒用的就是这个**，测感知强度 |
| 长震（1×长） | `long` | `vibrate({mode:"long"})` | 对比长脉冲是否明显更强 |
| 三连短震（3×短） | `short3` | short ×3，间隔 260 ms | 判断"弱"是不是因为单次太短而非电机弱 |
| 双长震（2×长） | `long2` | long ×2，间隔 900 ms | 最强组合，用来确认电机没被省电策略压低 |

短震都感知不到，就不是模式问题，是电机/省电设置问题（手环设置 → 振动强度）。

## 协议与实现

- 手机端 `StateMessage.vibe`（`NavProtocol.ALLOWED_VIBES` 白名单）携带请求，帧 `id` 形如
  `vibe-short-<ts>`。未知值在编解码两侧都会被丢弃（手机端 `decodeState` 丢弃，手环端
  `codec.vibePatternOf` 丢弃），手环不需要猜。
- 手环端 `session.onMessage` 对带 `vibe` 的帧**跳过 reducer**：只震动 + 回 ack，
  屏幕上的转向和距离保持不动，所以真实导航中按按钮不会打断显示。
- 心跳帧显式清空 `vibe`（`heartbeatOf` 里 `vibe = null`），否则按一次按钮会让手环每 5 秒振一次。
- 手机端诊断页按钮走 `MainActivity.sendVibe`，**故意绕开 `send()`**：不写 `session.latestState`、
  不过导航引擎，避免测试帧变成导航状态。

## 排查

```bash
adb logcat -s MiRingVibe          # 手机上：pattern 发出 / SDK phase / 是否找到节点
adb logcat -s MiRingTimeline      # FRAME_SENT + ACK_RECEIVED rttMs=...
```

- 只有 `send pattern=...` 没有 `phase`：`transport.sendState` 没回调，看手环是否连上。
- `result=NoNode`：官方 SDK 没发现节点，先确认小米运动健康里表是连着的。
- 有 `ACK_RECEIVED` 但手环不震：**手环端 RPK 是旧版**（不含 `vibe` 支持），重装
  `navring-band-432x514.rpk` 即可。

## 测试覆盖

- `band-app/test/vibe.test.js`（6 项）：每种模式的 vibrate 调用次数与 mode、三连/双长必须是
  分次调度而不是同步连发、vibe 帧不改显示且仍回 ack、真实转弯提醒仍只震一次、未知模式不触发电机。
- `band-app/test/codec.test.js`、`session.test.js`：白名单过滤与 display 不变性。
- 手机端 `VibeProtocolTest`（4 项）、`HeartbeatVibeTest`（3 项）：编解码白名单、心跳清空 `vibe`。

## 提醒强度三档（vibeLevel）

振幅改不了，这一点必须先说清楚：`@system.vibrator` 在 REDMI Watch 6 上只开放了
`vibrate({mode})`，`start()` / `stop()` / `getSystemDefaultMode()` 均在官方「不支持设备」列里，
所以第三方应用拿不到振幅旋钮。真正改振幅的是手表自己的
**设置 → 声音与振动 → 振动强度 → 强劲**，这一项改了，本应用发出的每一次震动都会变强。

应用层能改的是「驱动多久、驱动几次」，因此加了 `vibeLevel`：

| 档位 | 手环实际动作 | 大约时长 |
|---|---|---|
| 0 标准 | `short` ×1（旧版行为） | 一次短脉冲 |
| 1 强（默认） | `long` ×2，间隔 800 ms | 约 2 秒两次顿感 |
| 2 最强 | `long` ×3，间隔 800 ms | 约 3 秒三次顿感 |

800 ms 的间隔比 `long` 脉冲本身更宽，两次震动是分开的，不会并成一次。
选 2 还觉得弱，说明马达本身或系统档位的问题，不是这一层能解决的。

- 手机端 `NavProtocol.VIBE_LEVEL_*` + `NavigationSettings.vibeLevel`（0/1/2，
  非法值回落默认 1）；首页「提醒强度」分段控件即时写入并推一帧 UPDATE。
- 协议字段 `StateMessage.vibeLevel`（可空：`null` = 手环保持当前档位，不降级）。
- 手环端 `codec.reminderPatternOf(level)` 返回 `[{mode, atMs}]`，`app.ux` 一次性排好定时；
  第一拍同步触发，心跳/空闲帧不会把它改回标准。
- 诊断页新增「三长震（3×长，最强档实际效果）」，用来在不改设置的情况下预听最强档。

```bash
adb shell "run-as com.minavigatering.probe cat /data/data/com.minavigatering.probe/shared_prefs/navigation.xml" | grep vibeLevel
# 期望：<int name="vibeLevel" value="2" />
```
