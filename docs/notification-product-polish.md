# 通知产品体验完善

## 本次改动
- W-673 已新增纯映射 mapNavigationUi 及两条 JUnit4 边界测试。未知动作不会改成 straight。
- 补修后主列仍消费该映射。有效导航显示中文动作、距离、路名、环岛出口（仅映射给出非空值时）和当前模式。actionId 留在模型里供以后图标使用，主列不显示 actionId，也不显示 left、slight-right 等协议标识。
- W-691：路名缺失（null、空或空白）时映射给出 roadName=null，主列因 nullable let 不显示路名行；有路名仍原样显示，不虚构也不拿其他字段顶替。依据是 K90 真实高德通知样本「32米后左转」本身不含路名。主界面实际观感仍为 unverified。
- 每个阶段一句下一步提示。首次允许通知、开启导航桥和连接手环的设置卡片只在权限未开、桥接关闭、通信连接中出现，有效导航中不常驻。护栏说明只写在本文，不上屏。等待导航阶段提示先在手环打开导航页，再在高德开始导航；该首用步骤只在 WAITING 展示，有效导航中不常驻。
- 通信连接中只由前台会话未运行，或 transport.currentNodeId 为空决定。不读消息载荷 link，不用 SDK Task 成功或回执文本判断手环已连接。该阶段不展示上一条有效指令。
- 主列不读取、不显示 ProbeRuntime.session.lastAckText。回执、错误和合成测试仍只在调试页。

## 已有功能（保留，不是本票新做）
- 默认入口已是导航页：MainActivity.onCreate 的 setContent 调用 NavigationScreen。本票未改 MainActivity.kt。
- 桥接开关仍写入 NavigationSettings.enabled；开启发送 ProbeActions.START 并 refreshCurrent，关闭发送 ProbeActions.STOP。
- 步行/骑行仍写入 NavigationSettings.mode；运行中仍发送 ProbeActions.UPDATE。再次打开 App 会读回上次模式。
- prefs.enabled 时 LaunchedEffect 仍自动恢复前台会话。
- 通知读取权限入口和后台设置入口保留。调试入口「连接诊断与测试」保留。

## 已有证据与本票未验证
- 通道此前已由用户在 K90 上观察打通：真实高德通知标题「32米后左转」解析为 active/left/32，用户确认手环收到。本票不重新排查通道，也不把该观察扩大为完整首版实机通过。
- 本票不安装、不实机、不跑合成通知。映射单测和 Kotlin 编译只证明纯状态映射与主工程可编译。
- currentNodeId 在代码里由前台心跳约每 5 秒调用 getConnectedNodes 更新；空列表或获取失败会写成 null。会话没运行时心跳不刷新。这只是手机侧已有信号，不是本票实测。
- 主界面观感：unverified。手环实际连接以及断连是否及时反映到主界面：unverified。

## W-698：安全区、两行状态与中文名称
- 安全区：NavigationScreen 根 Column 在原有 padding(24.dp) 之前先消费 Modifier.safeDrawingPadding()，内容不再画到状态栏、刘海和导航栏下面。其余布局未动。
- 页内大标题由「MiNavigateRing」改为「导航环」，副标题「高德步行 · 骑行导航」不变。应用名（manifest 的 application 与启动 Activity 两处 android:label）同步改为「导航环」。通知使用权服务标签（AndroidManifest.xml:40，MiNavigateRing 高德导航）与前台通知标题（ProbeForegroundService.kt:251）本票未改。
- 主界面桥/节点状态改为两行，由桥开关、前台会话是否运行、手机侧是否发现手环节点三个已有信号决定（不读 permission/latest/link/ACK）：

| 桥开关 | 前台会话 | 手环节点 | bridgeLine | bandLine |
| --- | --- | --- | --- | --- |
| 关 | 任意 | 任意 | 开启后自动同步高德导航 | null（不显示第二行） |
| 开 | 未运行 | 任意 | 导航桥启动中 | null（不显示第二行） |
| 开 | 运行中 | 未发现 | 导航桥已开启 | 手环：未发现，正在连接 |
| 开 | 运行中 | 已发现 | 导航桥已开启 | 手环：已发现，尚未确认收到 |

- 措辞边界：节点存在只代表手机侧发现节点，手环是否收到尚未验证（无 ACK 证据），因此上屏文案不出现「已确认」「已连接」「已收到」。单测的禁词检查已覆盖 bridgeLine/bandLine 两个新字段及这三个新增禁词。
- 仍未验证（unverified）：真机上大标题是否确实不再被状态栏/刘海遮挡；两行状态在实际设备上的观感与可读性；两行状态跟随桥/会话/节点变化是否及时。本票只有纯映射单测和定向 Kotlin 编译，不构成实机观感通过。

## W-700：诊断页安全区
- 诊断页（「连接诊断与测试」）根 Column 同样消费 Modifier.safeDrawingPadding()，避开状态栏、刘海和导航栏；原因是真机截图里该页「返回导航」按钮被状态栏时钟盖住。该页其余布局与主页安全区未动。
- 仍 unverified：真机上该页顶部是否不再被遮。本票只有定向 Kotlin 编译与 NavigationUiStateTest，不构成实机观感通过。

## W-703：状态栏图标深色原因与监听器未连接提示

- 状态栏图标改深色的原因：真机截图里状态栏时钟与电量在应用浅色背景下几乎看不见，图标/背景对比度不足。主题本身的改动由独立的 W-702 负责，本票不改 themes.xml。
- 监听器未连接提示（NavigationPhase.LISTENER_DISCONNECTED）的触发条件：通知读取权限已授予、导航桥已开启，且 `AmapNotificationService.isConnected` 为 false 连续达到 5 秒（LISTENER_DOWN_GRACE_MS）。`isConnected` 只反映系统是否真的把监听器连上（onListenerConnected 置位，onListenerDisconnected 与 onDestroy 清空），不是权限开关。
- 精确文案：headline 为「通知读取未连接」，guidance 为「已允许读取通知，但系统没有把高德通知交给本应用。请在通知读取设置里把“MiNavigateRing 高德导航”关闭再开启一次，然后回到这里。」（MiNavigateRing 高德导航为通知使用权服务标签原文）。该阶段 showAsValidInstruction=false，actionLabel/actionId/distanceText/roadName/exitText 全为 null，即隐藏上一条旧指令；bridgeLine、bandLine 仍按桥开关/会话/节点三个既有入参计算，行为不变。
- 与既有阶段的优先级：权限关（PERMISSION_OFF）与桥关（BRIDGE_OFF）优先，其次是本阶段（LISTENER_DISCONNECTED），再到通信连接中（CONNECTING）、等待、结束、不可用等既有阶段。
- 入口：主界面的一次性设置卡片（原先只在权限未开、桥接关闭、通信连接中出现）现在也在本阶段显示，其中「查看通知权限」按钮会打开系统通知使用权设置 ACTION_NOTIFICATION_LISTENER_SETTINGS，正是让用户关闭再开启通知读取的入口。本票不自动打开设置页、不做 requestRebind 或组件启停切换、不做后台自愈。
- 仍未验证（unverified）：真机上状态栏图标是否清晰；覆盖安装后该提示是否出现、按指引关闭再开启通知读取后是否消失；冷启动时 5 秒宽限是否足够（系统绑定监听器的耗时因设备而异）。本票只有纯映射单测与定向 Kotlin 编译，**不构成实机通过**，不写「实机通过」。

## 后续完善清单（不在本票）
- 手环 UI、生命周期和图标可见性
- 震动
- 解析规则、传输策略、协议变更
- 关联发送失败与 ack 排查
- 高德 SDK 接入
- A01/A02 等实机长时锁屏验收
