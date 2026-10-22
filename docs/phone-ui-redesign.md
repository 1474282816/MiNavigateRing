# 手机导航主页重设计交付说明

范围：手机端日常导航页（`NavigationScreen`）的视觉重设计接线与交付记录。本文件只说明这次重设计怎么落地、依据是什么、哪些还没验证；不重复需求，也不宣称实机通过。

## 设计来源

- 视觉设计令牌、结构七块、主卡按阶段的内容表、清单四行与按钮语义以 `.coagent/specs/phone-navigation-ui.md` 的「视觉设计（2026-10-15 重设计）」一节为准；该节的「不变量」同样适用。
- 行为边界（八个阶段、提示文案、权限与启停、1 秒轮询、诊断页入口）来自同规格的前半部分与 `PROJECT_SPEC.md` 第 4、5、8 节，本次不改。
- 动作语义与 14 个图标的中文名/使用边界来自 `design/NAVIGATION_ICONS.md`（设计版本 1.1）。
- 设计思路：浅色页面（页面背景 `#F3F5F4`）上放一张深色「手环镜像」主卡，让手机上看到的就是手环该显示的内容；其余用白底细边框卡片，主色深绿，警示琥珀色。状态栏保持深色图标（页面是浅色）。

## 令牌与图标资源来源

- 所有色值、字号、间距、圆角、描边都集中在 `NavigationTheme.kt`，值逐条取自规格的令牌表；页面里不散落魔法数字。除距离 48sp 显式中等粗外，本次没有改动任何已有令牌；按 r2 终审的视觉修正意见新增了三个命名令牌：`Colors.segmentTrack`（`#E4E9E6`）、`Dimens.pageVerticalPadding`（12dp）、`Dimens.segmentTrackPadding`（3dp）。
- 距离 48sp 的粗度在 `HeroCard.kt` 中直接写成 `FontWeight.Medium`，没有给通用的 `HeroText` 加参数，避免影响主卡其它文字。
- 14 个动作矢量图标为 `app/src/main/res/drawable/nav_*.xml`：`nav_left`、`nav_right`、`nav_straight`、`nav_slight_left`、`nav_slight_right`、`nav_sharp_left`、`nav_sharp_right`、`nav_keep_left`、`nav_keep_right`、`nav_uturn_left`、`nav_uturn_right`、`nav_roundabout_enter`、`nav_roundabout_exit`、`nav_arrive`。
- 资源名规则 `nav_<动作 id，连字符改下划线>`；路径来自 `design/design-data.json` 的 `paths`，统一 84×84 视口（VectorDrawable `viewportWidth/Height=84`，尺寸 84dp）。
- 填充或描边的分法：`left`、`right`、`straight` 三个闭合箭头用填充（`fillColor=#00FF99`，无描边）；其余 11 个用描边（`fillColor=#00000000`、`strokeColor=#00FF99`、`strokeWidth=9`、圆头圆角）。
- `actionIconRes(actionId)` 纯函数做 id → 资源映射；未知 id 与 null 返回空，不显示图标，也绝不回退成直行。

## 主卡八阶段内容

| 阶段 | 图标/图形 | 标题 | 其它 |
| --- | --- | --- | --- |
| 有效导航 | 动作矢量图标 96dp，`#00FF99` | 动作名（`headline`），`#00FF99`，20sp | 距离（`#FFFFFF`，48sp 中等粗）、路名（有才显示，`#00DFFF`）、出口（有才显示，`#FFDD00`）、说明 |
| 等待高德导航 | 三层圆环 72dp（半径比 34:22:9，描边透明度 0.22 与 0.5，圆心实心），`#00FF99` | `headline`，白，20sp | 说明（`guidance`） |
| 通信连接中 | 同上，`#00FF99` | `headline`，白，20sp | 说明 |
| 桥接关闭 | 同上，灰 `#888780` | `headline`，白，20sp | 说明 |
| 信息暂不可用 | 同上，灰 `#888780` | `headline`，白，20sp | 说明 |
| 导航已结束 | 同上，`#00FF99` | `headline`，白，20sp | 说明 |
| 权限未开 | 琥珀 `#FFDD00` 圆环 + 锁形 | `headline`，白，20sp | 说明，字色 `#CBD5CF` |
| 通知读取未连接 | 琥珀圆环 + 警示三角 | `headline`，白，20sp | 说明，字色 `#CBD5CF` |

小标题：有效导航写「手环上显示的内容」，权限未开与通知读取未连接写「需要处理」，其余不写。未知动作经 `mapNavigationUi` 落到「信息暂不可用」，画灰环且不显示图标——不默认直行、不猜方向或环岛出口。

## 终审视觉修正（契约 r2 验收 7）

检视者终审对照真机观感意图提出、原规格漏写、随后补入的三项修正，本票只改这三项，行为、文案与七块结构一字不变：

1. **主卡内容水平居中**：有效导航、圆环阶段、权限阶段三段内容各自装在一个 `fillMaxWidth` 的居中 `Column`（`horizontalAlignment = Alignment.CenterHorizontally`）里，图标、动作名、距离、路名、出口、说明都居中；`HeroText` 与独立的距离 `Text` 都设 `textAlign = TextAlign.Center` 并满宽，说明文字窄屏多行时逐行居中。主卡顶部小标题（`HeroLabel`，即「手环上显示的内容」「需要处理」）仍靠左，主卡外层 Column 与卡片内边距不变。字号与颜色一律不变。
2. **模式分段控件做成圆角胶囊**：外层一条轨道（圆角 50%、底色取命名令牌 `segmentTrack` `#E4E9E6`、内边距取命名令牌 3dp），两个选项各自圆角 50%（先 `clip` 再 `clickable`，点击区域与视觉一致），选中项主色底白字、未选中项透明底深色字；选项最小高度仍是 48dp 触控目标，回调仍传 `NavProtocol.MODE_WALK` / `MODE_CYCLE`，行为不变。
3. **页面纵向内边距**：内容区在 `safeDrawingPadding` 之内、滚动容器之外再取上下各 12dp 命名令牌内边距，避免标题贴住状态栏、页脚贴住导航栏；水平 20dp、背景、滚动与七块顺序均保留。

三项均只改视觉，不新增测试；现有全部单测作为回归。

## 设置清单四行与按钮目标

清单卡只在权限未开、桥接关闭、通知读取未连接、通信连接中四阶段出现，行目标全部在 `NavigationScreen` 回调内接真实系统入口：

| 行 | 状态显示 | 按钮 | 实际目标 |
| --- | --- | --- | --- |
| 高德通知读取 | 已允许显示对勾 | 查看 / 允许 | `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` |
| 后台运行不受限 | 已豁免显示对勾 | 仅未豁免时显示「允许」 | `requestIgnoreBatteryOptimizations(context)`（直接请求，失败回退到电池设置页；不是裸 intent） |
| 本应用省电策略与自启动 | — | 打开 | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` + 本应用 package Uri |
| 小米运动健康后台不受限 | — | 去设置 | `miFitnessAppDetailsIntent()`（官方 App `com.mi.health` 的应用信息页） |

若开机恢复失败过（`prefs.lastRestoreText` 以 `failed` 开头），卡片底部加一行提示「需要打开一次应用恢复同步」。

主按钮语义：权限未开为实心「允许高德通知读取」、通知读取未连接为实心「查看通知权限」，两者都去通知读取设置；桥接关闭为实心「开启导航桥」；其余为描边「关闭导航桥」。启停逻辑与原页面一致。

## 文件职责

| 文件 | 职责 |
| --- | --- |
| `NavigationScreen.kt` | 只保留状态、副作用与回调：权限请求与 `launchOrStart`、进入页面恢复、1 秒轮询与 `listenerDownMs`、模式偏好与 UPDATE、桥启停与 `refreshCurrent`、诊断分支；四项设置与诊断回调在 Screen 内组合，Android 路由不出这个文件。 |
| `NavigationComponents.kt` | 无状态七块容器与状态卡/清单卡/分段控件/主按钮/页脚；本文件不读 Context、不读偏好、不渲染主卡。本次只给根 Column 加 `rememberScrollState()/verticalScroll`，并加上下 12dp 的命名令牌纵向内边距，保留 `fillMaxSize()`、背景、`safeDrawingPadding()` 与全部令牌；分段控件改为圆角胶囊（轨道 + 圆角选项），不改回调和触控高度。 |
| `HeroCard.kt` | 无状态「手环镜像」主卡，按阶段画图标/圆环/文字；所有动态字符串来自 `mapNavigationUi` 输出。三个阶段内容装在满宽居中 Column 里、文字居中，小标题仍靠左。 |
| `NavigationTheme.kt` | 纯令牌持有者（色值、字号、间距、圆角、描边、透明度）与 `actionIconRes` 映射；不安装全局主题、无副作用。 |

## 未验证

- 真机视觉观感（主卡深浅对比、圆环粗细、图标大小是否合意）未验证，以用户装机观感为准。
- 不同系统字体缩放、较大字号或小屏下七块是否溢出、滚动是否顺畅未验证。
- 系统处于深色模式时页面仍是浅色（本次未做深色适配），真机上状态栏图标是否清晰未验证。
- 小米 HyperOS 上各设置跳转（电池优化、应用信息、`miFitnessAppDetailsIntent`）是否都能打开对应页面未验证。
- 本文件只记录编译与单测通过、静态行为对照；不表示任何阶段已在实机通过。
