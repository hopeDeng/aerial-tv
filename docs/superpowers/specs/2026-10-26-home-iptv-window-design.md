# Aerial 首页 IPTV 小窗改造设计

日期：2026-10-26
状态：**已作废** — 方向不对（另加了一个首页+dock，替掉了原网格首页）。改为在原有首页上叠加小窗，见 [2026-10-26-iptv-window-on-original-home.md](2026-10-26-iptv-window-on-original-home.md)。
目标设备：创维 8N07_J9000（Android 8.0，1920×1080，density 240），其他 Android TV 兼容

## 1. 背景与目标

Aerial 现在是完整的 tvOS 风格应用网格。参考 `arclauncher` 的首页，把 Aerial 首页改成「大时钟 + 天气、右上 IPTV 小窗、底部 6 槽 dock」，并接入电视上中国广电 APK（`cn.cbn.coocaa`）已经配置好的直播频道。

目标：

1. 开机进入 Aerial 首页，小窗默认播放上次频道，焦点在小窗。
2. 焦点在小窗时，到达 Aerial 屏保时间自动全屏播放。
3. 焦点不在小窗时，到达屏保时间停止播放、隐藏小窗（缩成播放图标），正常进入 Aerial 屏保。
4. 天气行可用方向键聚焦，OK 打开「省份 → 城市」选择并立即生效。
5. 样式、材质、字体、动效沿用 Aerial 现有实现，不引入新的视觉语言。

## 2. 非目标

- 不解决创维固件接管 HOME 键、默认桌面设置失败、Aerial 进程被系统杀的问题；这些属于系统层，另行处理。
- 不修改中国广电 APK，不读取它的私有 SharedPreferences。
- 不在 Aerial 内编辑频道/源；频道编辑仍在中国广电 APK 里完成。
- 不新增直播录制、回看、EPG。
- 不改动手机设置页的天气城市设置（保留现有能力，电视端新增入口）。

## 3. 现状

- `HomeScreen.kt`：5 列应用网格（`COLUMNS = 5`），`GridMargin = 56.dp`、`GridGap = 28.dp`；支持滚动、文件夹、移动、长按菜单、拖动。包含 `ShelfClock` / `CornerClock`、壁纸、屏保过渡、状态胶囊。
- `ShelfClock` 已有日期、时间、天气（`Weather.place`、温度、天气现象、高低温），但不可聚焦。
- `MainActivity.kt`：`restartIdleTimer()` 按 `AerialSettings.screensaverMinutes` 调度 `showScreensaver`；`startScreensaver(asked)` 支持 Aerial/System 两种屏保；`NEVER` 时不调度。
- `HomeViewModel.kt`：`apps`（已按用户顺序排好的 `AppEntry`）、`folderApps`、`Overlay` 状态。
- `Weather.kt`：`choose(name)` suspend 函数，支持中文城市名；`Cities.load()` 读取 `assets/cities-cn.tsv`，含 `province`、`rank`。
- `app/build.gradle.kts`：已有 `media3-exoplayer:1.8.1`、`media3-datasource`、`media3-database`；**没有** `media3-exoplayer-hls`。
- 电视上的 `/sdcard/iptv_sources.json`：中国广电 APK 写出的频道配置，结构为
  `{"channels":[{"name","enabled","currentSource","sources":[{"url","label","enabled"}]}]}`。
- 中国广电 APK 用 VLC 播放；Aerial 将用 Media3/ExoPlayer 播放同一批 HLS 源。

## 4. 已确认的设计决策

| 决策点 | 结论 |
| --- | --- |
| 首页应用区 | 改为 6 槽 dock；完整网格移到「全部应用」页 |
| 频道来源 | 复用 `/sdcard/iptv_sources.json`，失败回退 `assets/hnyd.m3u` |
| 上次频道 | Aerial 自己记录（频道名 + 源 URL）；首次用第一个启用频道 |
| 焦点离开小窗（未到屏保） | 继续播放，不缩小、不停播 |
| 屏保且焦点不在小窗 | 停播、隐藏小窗为播放图标、进入 Aerial 屏保 |
| 屏保且焦点在小窗 | 自动全屏播放，不进入屏保 |
| 屏保唤醒后 | 回首页，小窗保持图标；焦点到图标展开播放，失焦立即停播缩回图标 |
| 自动全屏 | Aerial 内实现；左右换台、OK 频道列表、Back 退出 |
| 小窗按键 | OK 全屏、左右换台、上到状态胶囊、下到 dock、MENU 频道列表 |
| 天气城市 | 省份 → 城市两级列表 |
| 应用 dock | 默认取 Aerial 应用顺序前 6 个；长按 OK/MENU 替换 |
| 全部应用页 | dock 按 Down 打开，复用现有网格/文件夹/排序；打开时小窗暂停并隐藏，关闭后恢复 |

## 5. 首页布局

画布 960×540 dp，由 `MainActivity` 统一缩放。

```
┌──────────────────────────────────────────────────────────────┐
│  [时钟 日期/时间/天气]                        [状态胶囊]      │
│                                                              │
│                                          ┌────────────────┐  │
│                                          │                │  │
│                                          │   IPTV 小窗    │  │
│                                          │   500×281      │  │
│                                          └────────────────┘  │
│                                                              │
│   [dock 1] [2] [3] [4] [5] [6]                （底部 36dp）   │
└──────────────────────────────────────────────────────────────┘
```

### 5.1 层级

从下到上：

1. `HomeBackground`（现有壁纸、玻璃底板、屏保过渡）。
2. 小窗/图标（播放 Surface 与占位）。
3. `ShelfClock`、状态胶囊、dock。
4. 现有 overlay（控制中心、应用菜单、文件夹、壁纸）。
5. `TvOverlays`（全屏直播、频道列表、城市选择）。
6. Aerial 屏保（现有 `Screensaver`）。

### 5.2 时钟与天气

- 首页固定使用大时钟 `ShelfClock`，左上，left = 56dp，top = 28dp。
- 首页不再响应 `AerialSettings.clockRight` / `largeClock`；这两项只影响全部应用页和屏保。
- 天气行（`ShelfClock` 的第三行）增加可聚焦能力：
  - 条件：`AerialSettings.showWeather && Weather.now != null`。
  - 触发方式用 `Pressable`（符合项目约定），焦点态为白色 1dp 圆角描边 + 轻微背景提亮；按下时沿用现有 `Pressable` 的按压反馈。
  - OK 打开城市选择。
  - 不可聚焦时，焦点顺序跳过天气。

### 5.3 小窗与播放图标

- 展开尺寸 500×281dp（16:9），right = 56dp，top = 74dp；left = 404dp，bottom = 355dp。
- 圆角 20dp，1dp 白色 22% 描边；焦点态 2dp 白色 90% 描边。`Lite` 效果下不画外发光。
- 视频用 `SurfaceView`（`AndroidView`），由 `TvPlayer` 绑定；圆角用 `ViewOutlineProvider` 裁剪，兼容 Android 8。
- `SurfaceView` 自身不获取焦点；焦点在小窗外层 Box，按键由该 Box 的 `onKeyEvent` 处理。全屏同理。
- 加载中：小窗中央显示 Aerial 风格的小进度指示；错误：显示「无法播放，正在切换…」或「暂无可用频道」，不显示系统错误文本。
- 图标态：同一区域中央 96×96 玻璃圆角方块，白色播放三角。焦点态放大 1.06（`Motion` spring）并显示描边。
- 小窗底部换台 OSD：频道名，2.5 秒淡出，使用现有 `Type.Headline` / 玻璃胶囊样式。
- 无可用频道（`Stopped`）：同一区域显示与图标同尺寸的不可播放占位；占位可聚焦，OK 重试、MENU 打开频道列表、左右不换台。
- 全屏播放失败一轮后自动退出全屏，回到首页 `Stopped` 占位；不自动重试，用户按 OK 或换台时重试。

### 5.4 dock

- 6 个槽，每槽 120×120dp，间距 24dp；总宽 840dp，left = 60dp，bottom = 36dp；顶边 384dp。
- 使用现有 `AppTile` 的图标、名称、焦点样式；聚焦时名称显示在图标下方。
- 空槽显示「+」玻璃方块。
- 槽位存储：`DockStore`（SharedPreferences `dock`，键 `slots`，逗号分隔 6 个 `AppEntry.key`）。
- 默认值：`vm.apps` 中前 6 个非文件夹项。文件夹、隐藏应用不自动进 dock。
- 长按 OK 或按 MENU：打开应用选择器（复用全部应用页，标题「选择应用」，OK 填入当前槽，Back 取消）。选择器打开期间小窗暂停并隐藏，关闭后恢复原状态。
- dock 上按 Down 打开全部应用页；在槽位上按 OK 启动应用/信号源/快捷方式。

### 5.5 状态胶囊

- 位置、内容、控制中心交互保持不变（TopEnd，top 28dp，right = 56 - 14dp）。
- 小窗 top 74dp 保证与状态胶囊底部（约 62dp）留 12dp 间距。

### 5.6 全部应用页

从现有 `HomeScreen` 抽出网格部分，成为 `AllAppsScreen`：

- 保留：5 列网格、滚动、拖动、文件夹、长按菜单、移动、隐藏、壁纸入口、现有设置。
- 顶部保留 `ShelfClock`（按 `AerialSettings.largeClock/clockRight`）或 `CornerClock`，与现在一致。
- 打开方式：dock 按 Down（浏览）或 dock 长按/MENU（选择槽位）。
- 打开时：若小窗正在播放，`pause()` 并隐藏小窗；关闭后 `resume()` 并恢复小窗（焦点回 dock 上次槽位）。
- 打开前是图标态/不可播放占位：保持原状态，不自动播放。
- 选择器模式：标题「选择应用」，OK 把应用填入当前 dock 槽，Back 取消；不启动应用。
- Back 关闭，回到 dock。

## 6. 焦点模型

### 6.1 焦点节点

1. 小窗（仅 `Window` 状态）、播放图标（`Icon` 状态）或不可播放占位（`Stopped`）；三者占同一位置，同一时间只出现一个。
2. 天气行（仅可显示天气时）。
3. dock 的 6 个槽。
4. 状态胶囊。
5. 全部应用页内的现有网格/文件夹/菜单。
6. 全屏播放器、频道列表、城市选择各自独占焦点。

### 6.2 首页按键

| 当前焦点 | 按键 | 行为 |
| --- | --- | --- |
| 小窗 | OK | 全屏 |
| 小窗 | Left / Right | 上一/下一频道，显示 OSD，保存上次频道 |
| 小窗 | Up | 状态胶囊 |
| 小窗 | Down | dock 上次聚焦槽（默认第 1 槽） |
| 小窗 | Menu | 频道列表 |
| 图标 | 方向键聚焦 / OK | 展开小窗并播放（`Preview`） |
| 图标 | Left | 天气（可聚焦时）；否则无 |
| 图标 | Up | 状态胶囊 |
| 图标 | Down | dock 上次聚焦槽 |
| 占位（`Stopped`） | OK | 重试当前频道 |
| 占位（`Stopped`） | Menu | 频道列表 |
| 占位（`Stopped`） | Left | 天气（可聚焦时）；否则无 |
| 占位（`Stopped`） | Up / Down | 状态胶囊 / dock |
| 天气 | OK | 城市选择 |
| 天气 | Right | 小窗；若图标态，聚焦图标并展开（`Preview`） |
| 天气 | Down | dock 第 1 槽 |
| 天气 | Up | 状态胶囊 |
| dock 槽 | Left/Right | 相邻槽 |
| dock 第 1 槽 | Up | 天气（可聚焦时）；否则小窗/图标 |
| dock 其余槽 | Up | 小窗；若图标态，聚焦图标并展开（`Preview`） |
| dock 槽 | Down | 全部应用页 |
| dock 槽 | OK | 启动；空槽打开选择器 |
| dock 槽 | Menu / 长按 OK | 替换槽位 |
| 状态胶囊 | Left | 天气（可聚焦时） |
| 状态胶囊 | Down | 小窗/图标 |
| 状态胶囊 | OK / Menu | 现有控制中心 |
| 首页任意焦点（无全屏/面板） | Back | `AerialSettings.backStartsScreensaver` 开启时手动屏保；关闭时无 |
| 首页任意焦点（无全屏/面板） | HOME | 聚焦小窗/图标/占位（若存在），否则 dock 第 1 槽 |

说明：

- 小窗聚焦时 `Left/Right` 只换台，不能直接移到天气。到天气走「小窗 Up → 状态胶囊 Left → 天气」或「dock 第 1 槽 Up → 天气」。天气回到小窗用 Right。
- 图标态左右不换台，`Left` 可直接到天气。
- 图标态被聚焦即进入 `Preview` 并开始播放；失焦立即停止并回到图标。

### 6.3 全屏按键

| 按键 | 行为 |
| --- | --- |
| Left / Right | 上一/下一频道，OSD |
| OK / Menu | 频道列表 |
| Back | 退出全屏，回首页小窗，焦点在小窗 |
| HOME | 退出全屏，回首页（交给现有 Home 处理） |
| 其他 | 显示 OSD，不退出 |

频道列表：

- Up/Down 选择，OK 播放并关闭，Back 关闭。
- 当前频道高亮，列表可滚动；显示频道名，不显示源。

城市选择：

- 左列省份，右列城市；Left/Right 切换列，Up/Down 移动，OK 选择城市并关闭，Back 取消。
- 打开时定位到当前城市；`Weather.choose` 进行中显示加载，失败保留面板并提示。

## 7. 小窗状态机

状态：

| 状态 | 播放 | 小窗形态 | 焦点 |
| --- | --- | --- | --- |
| `Stopped` | 否 | 不可播放占位 | 占位可聚焦 |
| `Window` | 是 | 展开 | 可在小窗或别处 |
| `Preview` | 是 | 展开 | 必须在小窗 |
| `Icon` | 否 | 96×96 图标 | 可在图标或别处 |
| `Fullscreen` | 是 | 全屏 | 全屏 |
| `AllApps` | 暂停 | 隐藏 | 全部应用页 |

`Window` 内再分 `focused`（焦点是否在小窗）与 `preview`（是否由图标展开）。`Preview` 等价于 `Window(focused=true, preview=true)`。

`Screensaver` 不是 `TvWindow` 的值：Aerial 屏保由 `vm.screensaverVisible` 管理。屏保期间小窗处于 `Icon`（无频道时 `Stopped`），被屏保画面覆盖；唤醒后保持 `Icon` / `Stopped`。`Stopped` 表示没有可播放频道，显示不可播放占位。

转换：

| 事件 | 当前 | 结果 |
| --- | --- | --- |
| 开机 | 任意 | 读上次频道 → `Window(focused=true, preview=false)`；无可用频道则 `Stopped` |
| 从应用回到 Home | `resumeOnHome=true` | 播放上次频道 → `Window(focused=true, preview=false)`；不可播则 `Stopped` |
| 小窗失焦 | `Window(preview=false)` | 保持播放，`focused=false` |
| 小窗失焦 | `Window(preview=true)` | 停止 → `Icon` |
| 图标获得焦点 | `Icon` | 播放 → `Window(focused=true, preview=true)` |
| 小窗 OK，且可播放 | `Window` | `Fullscreen` |
| 小窗 OK，无可用频道 | `Stopped` / `Icon` | 不进入全屏 |
| 空闲计时到，焦点在小窗且可播放 | `Window(focused=true)` | `Fullscreen`；不启动屏保 |
| 空闲计时到，焦点在小窗但无可用频道 | `Window(focused=true)` / `Stopped` | 停止 → `Stopped`；`startScreensaver(asked=false)` |
| 空闲计时到，焦点不在小窗 | `Window(focused=false)` / `Icon` / `Stopped` | 若播放则停止 → `Icon`；`Stopped` 保持；`startScreensaver(asked=false)` |
| 空闲计时到 | `Fullscreen` | 忽略（全屏期间不调度屏保） |
| 全屏 Back | `Fullscreen` | `Window(focused=true, preview=false)`，焦点小窗，重启空闲计时 |
| 全屏播放失败（一轮重试后） | `Fullscreen` | 退出全屏 → `Stopped`，显示错误占位，不自动重试 |
| 打开全部应用 | `Window` | 暂停，记录 `wasPlaying=true` → `AllApps`，隐藏小窗 |
| 关闭全部应用 | `AllApps` 且 `wasPlaying=true` | 恢复播放 → `Window(focused=false)`，焦点回 dock |
| 关闭全部应用 | `AllApps` 且 `wasPlaying=false` | 回到打开前的 `Icon` / `Stopped`，焦点回 dock |
| 打开全部应用 | `Icon` / `Stopped` | `AllApps`，保持原状态 |
| 启动 App / 信号源 | `Window` / `Icon` / `Stopped` | 停止，记录 `resumeOnHome = (原状态为 Window)` |
| 手动屏保（Back / 立即开始） | `Window` / `Preview` / `Icon` / `Stopped` | 若播放则停止 → `Icon`；无频道保持 `Stopped`；若焦点在小窗移到 dock 第 1 槽；进入屏保，不自动全屏 |
| 屏保唤醒 | 屏保中 | 保持 `Icon` / `Stopped`；焦点保持进入屏保前的位置（若在小窗则 dock 第 1 槽）；若屏保前在全部应用页，先关闭回首页，不自动播放 |
| 屏保设置为「从不」 | 任意 | 不自动全屏、不停播；手动屏保仍按上一条 |
| 打开频道列表 / 城市选择 | `Window` / `Fullscreen` | 保持播放；面板独占焦点 |
| 屏保到点且面板打开 | 任意 | 先关闭面板，再按普通规则全屏或停播进屏保 |

补充：

- 全屏期间保持屏幕常亮，并取消屏保调度；退出全屏后 `restartIdleTimer()`。
- 全屏进入时不写 `vm.screensaverVisible`，屏保界面不出现。
- `Overlay.AllApps` 是页面状态，`TvWindow.AllApps` 表示小窗已暂停隐藏；两者同时存在。
- 小窗/图标/占位只在首页（`Overlay.None`，非全部应用页）显示；控制中心等 overlay 打开时保持播放（控制中心是半透明面板），但焦点在面板。到屏保时间仍按状态机处理。
- 频道列表打开时播放继续；城市选择打开时播放继续。

## 8. 屏保联动

`MainActivity` 的 `showScreensaver`：

```kotlin
private val showScreensaver = Runnable {
    if (tv.onIdleReached()) return@Runnable   // 焦点在小窗，已进入全屏
    startScreensaver(asked = false)
}
```

`TvController.onIdleReached()`：

- `Fullscreen`：返回 false，不做额外动作（全屏不调度屏保，正常不会走到这里）。
- `Window(focused=true)` 且 `playback` 为 `Playing` / `Buffering`：`enterFullscreen()`，返回 true。
- `Window(focused=true)` 但 `Stopped` / `Error`：`stopToIcon()`，返回 false。
- `AllApps`：`stopToIcon()`（若还没停），返回 false，由 `startScreensaver` 正常进入屏保。
- 其他：若播放，`stopToIcon()`；返回 false。

`startScreensaver(asked=true)`（Back / 立即开始）不调用 `onIdleReached`；先关闭频道列表/城市选择面板和全部应用页，若播放则 `tv.stopToIcon()`，再按现有逻辑启动 Aerial/System 屏保。System 模式启动失败时停留在首页。

`AerialSettings.screensaverMinutes == NEVER` 时 `restartIdleTimer` 不调度，因此不自动全屏、不停播。

`Wallpapers.screensaver == System` 时：

- 焦点在小窗且可播放：仍自动全屏（优先于系统屏保）。
- 焦点不在小窗：停播转图标，交给现有 `startScreensaver` 启动系统屏保。

频道列表或城市选择打开时开屏保：先关闭面板，再按上面的规则处理；如果焦点在小窗并进入全屏，则面板关闭、不启动屏保。

## 9. 频道与播放

### 9.1 数据源

主源：`/sdcard/iptv_sources.json`。

- 读取需要 `READ_EXTERNAL_STORAGE`。
- 解析在 `Dispatchers.IO`。
- 只保留 `enabled == true` 且至少一个启用源的频道。
- 频道顺序沿用 JSON 顺序。
- 每个频道的源：优先 `currentSource`（该源启用时），然后按 JSON 顺序循环其余启用源。
- 每次 Home 回到前台检查文件 `lastModified()`；变化则重载，按频道名保持当前频道；当前频道消失则切到第一个。
- 读取失败、文件不存在、解析为空：回退 `assets/hnyd.m3u`（现有 `SourceStore.seedFromM3u` 的格式）。仍为空则显示「暂无可用频道」。

目标设备是 Android 8，不受 scoped storage 限制。Android 10+ 的设备无法直接读 `/sdcard` 根目录，需后续用 SAF 导入；本设计不覆盖。

### 9.2 数据模型

```kotlin
data class TvSource(val url: String, val label: String, val enabled: Boolean)
data class TvChannel(
    val name: String,
    val enabled: Boolean,
    val currentSource: Int,
    val sources: List<TvSource>,
) {
    val playableSources: List<TvSource>
}
```

解析函数保持纯函数（输入 JSON 字符串，输出 `List<TvChannel>`），便于单测。

### 9.3 上次频道

`TvPrefs`（SharedPreferences `tv`）：

- `last_channel`：频道名。
- `last_source`：源 URL。

每次成功开始播放时写入。启动时：

1. 按 `last_channel` 查找频道。
2. 在频道内按 `last_source` 查找源。
3. 找不到频道 → 第一个启用频道；找不到源 → `currentSource`。
4. 都没有 → `Stopped`，显示不可播放占位。

中国广电 APK 的「上次频道」在它自己的私有 SharedPreferences 里，Aerial 无法读取；因此首次启动默认第一个启用频道，之后由 Aerial 自己记录。

### 9.4 播放器

`TvPlayer` 封装一个 `ExoPlayer` 实例：

- `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MOVIE)`，`handleAudioFocus = true`。
- `DefaultMediaSourceFactory` + HLS（新增 `androidx.media3:media3-exoplayer-hls:1.8.1`）。
- 小窗与全屏共用同一个 `ExoPlayer`；切换时重新绑定 `SurfaceView`：
  - 小窗 `SurfaceView` 显示时 `setVideoSurfaceView(windowSurface)`。
  - 全屏 `SurfaceView` 显示时 `setVideoSurfaceView(fullscreenSurface)`。
  - 解绑时 `setVideoSurfaceView(null)`。
- 状态：`mutableStateOf(TvPlayback.Idle/Buffering/Playing/Error)`、`currentChannel`、`currentSource`。
- API：`play(channel, sourceIndex)`、`next(delta)`、`stop()`、`pause()`、`resume()`、`enterFullscreen()`、`exitFullscreen()`。
- 换台：`stop()` → `clearMediaItems()` → `setMediaItem` → `prepare()` → `play()`；保留 surface。
- 播放器在主线程创建/操作；数据解析在 IO。

### 9.5 错误与重试

`Player.Listener.onPlayerError`：

1. 同频道切到下一个启用源。
2. 同频道所有源失败：切到下一个频道（按顺序，最多一轮）。
3. 一轮都失败：`stop()`，状态 `Error`，小窗显示「暂无可用频道」；不无限重试。如果此时在全屏，退出全屏回首页，显示图标/错误占位。
4. 用户手动换台、在 `Stopped` 占位上按 OK、或重新聚焦图标时重置失败计数并重试。

切换源/频道间隔 500ms，避免快速连播。OSD 显示正在尝试的频道名。

### 9.6 音频、壁纸、屏幕

- 小窗/全屏播放时暂停动态壁纸（`VideoWallpaper` 的 `moving/keep` 置 false，`LiveFilm` 停止），避免双路视频解码；停止播放后恢复。静态壁纸不受影响。
- 全屏播放时 `FLAG_KEEP_SCREEN_ON`；退出全屏清除（屏保逻辑也会管理该 flag，需避免互相清除：全屏使用独立计数或退出时重新调用 `restartIdleTimer`）。
- 小窗播放不强制常亮，沿用现有屏保计时。
- 启动 App 时停止播放，避免后台直播声音。

## 10. 天气城市

- 首页 `ShelfClock` 的天气行用 `Pressable` 包裹；OK 打开城市选择。
- 城市选择面板使用 Aerial 现有玻璃面板/列表样式，宽度约 720dp，居中。
- 左列：省份/直辖市（来自 `Cities.load` 的 `province`，去重，按数据出现顺序；空值归入「其他」）。
- 右列：该省城市，按 `rank` 升序，显示 `name`。
- 打开时定位到 `Weather.place` 匹配的城市所在省份；找不到则默认第一个省份。
- OK 选择城市 → `Weather.choose(city.name)`（suspend，显示加载），成功后关闭面板、焦点回天气；失败显示「未找到该城市」，可重试。
- Back 取消，焦点回天气。
- `Weather.place` 可能来自网络猜测；选择后 `Weather.chosen = true`，与手机页一致。

## 11. 代码结构

### 11.1 新增

| 文件 | 职责 |
| --- | --- |
| `tv/TvChannel.kt` | `TvChannel` / `TvSource` 数据模型 |
| `tv/TvSources.kt` | 解析 `iptv_sources.json`、M3U 回退、mtime 刷新 |
| `tv/TvPrefs.kt` | 上次频道/源、`resumeOnHome` 标记 |
| `tv/TvPlayer.kt` | ExoPlayer 封装、错误重试、surface 绑定 |
| `tv/TvController.kt` | 小窗状态机、焦点状态、全屏、屏保钩子、频道切换 |
| `ui/tv/TvWindow.kt` | 小窗/图标/占位、圆角 SurfaceView、焦点与 OSD |
| `ui/tv/TvFullscreen.kt` | 全屏播放、OSD、按键 |
| `ui/tv/TvChannelList.kt` | 频道列表面板 |
| `ui/tv/TvOverlays.kt` | 根层组合全屏/列表/城市选择 |
| `ui/home/HomeDock.kt` | 6 槽 dock、应用选择器入口 |
| `ui/home/AllAppsScreen.kt` | 从现有 HomeScreen 抽出的网格页；含「选择应用」模式 |
| `ui/home/CityPicker.kt` | 省份 → 城市两级面板 |
| `apps/DockStore.kt` | dock 槽位持久化 |
| `tv/TvStateMachine.kt` | 纯状态转换函数，便于单测 |
| `app/src/main/assets/hnyd.m3u` | 内置回退源，从 `my-tv-launcher/work/player/assets/hnyd.m3u` 复制 |

### 11.2 修改

| 文件 | 改动 |
| --- | --- |
| `ui/home/HomeScreen.kt` | 改为新首页；网格抽到 `AllAppsScreen`；渲染小窗/dock；天气焦点 |
| `ui/home/HomeChrome.kt` | `ShelfClock` 增加可选天气焦点/点击参数 |
| `MainActivity.kt` | 创建 `TvController`；`showScreensaver` 联动；全屏时取消屏保调度；根层 `TvOverlays`；启动 App 停播；`onResume` 恢复/刷新源；权限请求 |
| `HomeViewModel.kt` | `Overlay.AllApps`；dock 默认值；从应用返回时 `resumeOnHome` |
| `app/build.gradle.kts` | 加 `media3-exoplayer-hls:1.8.1` |
| `AndroidManifest.xml` | 加 `READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`）；`requestLegacyExternalStorage` 说明 |
| `res/values*/strings.xml` | 新增文案五语 |
| `tools/install.sh` | 安装后 `pm grant ... READ_EXTERNAL_STORAGE`（失败不阻断） |
| `docs/devices/skyworth-coocaa.md` | 真机验证记录 |

### 11.3 关键接口

```kotlin
enum class TvWindow { Stopped, Window, Preview, Icon, Fullscreen, AllApps }

class TvController(context: Context, scope: CoroutineScope) {
    val window: State<TvWindow>
    val focused: State<Boolean>
    val playback: State<TvPlayback>
    val current: State<TvChannel?>
    val channels: State<List<TvChannel>>
    val error: State<TvError?>

    fun onHomeResumed()
    fun onAppLaunched()
    fun onWindowFocused(focused: Boolean)
    fun onWindowClicked()
    fun onChannelStep(delta: Int)
    fun playChannel(index: Int)
    fun enterFullscreen()
    fun exitFullscreen()
    fun onIdleReached(): Boolean
    fun stopToIcon()
    fun pauseForAllApps()
    fun resumeFromAllApps()
}
```

`MainActivity` 在 `setContent` 外层渲染 `TvOverlays(tv, ...)`；`HomeScreen` 接收 `tv` 并显示小窗/dock。

## 12. 存储与权限

- `READ_EXTERNAL_STORAGE`：Android 8 上运行时授权；首次启动检查，未授权则请求一次。拒绝时回退 `assets/hnyd.m3u`，并在控制中心设置页显示提示。
- 安装脚本执行 `adb shell pm grant app.orchard.tv android.permission.READ_EXTERNAL_STORAGE`，避免电视上弹窗。
- `DockStore`：SharedPreferences `dock`。
- `TvPrefs`：SharedPreferences `tv`。
- 不写入 `/sdcard/iptv_sources.json`，不修改中国广电的数据。

## 13. 文案

新增字符串（五个 `values` 都要有，占位符一致）：

- `tv_all_apps`：全部应用 / All apps
- `tv_dock_empty`：添加应用
- `tv_choose_app`：选择应用
- `tv_no_channels`：暂无可用频道
- `tv_cannot_play`：无法播放，正在切换…
- `tv_channel_list`：频道列表
- `tv_city_province`：省份
- `tv_city_city`：城市
- `tv_city_other`：其他
- `tv_city_not_found`：未找到该城市
- `tv_weather_city_hint`：按 OK 选择城市
- `tv_permission_channels`：需要读取频道配置的权限

英文、中文、俄语、乌克兰语、哈萨克语各一份；文案 key 与现有命名风格一致。

## 14. 测试与验收

自动化：

- `TvSources.parse` 单测：启用过滤、源排序、空源、坏 JSON、M3U 回退。
- `TvStateMachine` 单测：第 7 节转换表逐条覆盖。
- dock 默认值与槽位替换单测（纯函数）。
- `./gradlew assembleStandardRelease assembleGuardRelease` 通过。
- 现有测试全绿。

真机（创维 8N07_J9000）：

1. 开机/回桌面：小窗播放上次频道，焦点在小窗。
2. 左右换台，OSD 正确，重启后仍是该频道。
3. OK 全屏；左右换台；OK 频道列表；Back 回首页小窗。
4. 焦点在小窗，等屏保时间：自动全屏，不出现屏保图；按键操作正常。
5. 焦点移到 dock/天气，等屏保时间：停播、小窗变图标、进入 Aerial 屏保。
6. 唤醒屏保：回首页，图标仍在；聚焦图标展开播放；移开焦点立即停播缩回图标。
7. 天气 OK：省份→城市选择，选择后天气立即更新，重启后保留。
8. dock 长按替换应用；Down 打开全部应用；打开时小窗暂停隐藏，返回恢复。
9. 屏保设置为「从不」：不自动全屏、不停播。
10. 手动屏保：停播转图标，正常屏保，不自动全屏。
11. 播放时动态壁纸暂停；停止后恢复。
12. 五种语言切换无缺失/错位。
13. 权限被拒绝时回退内置源，不崩溃。
14. 全屏时频道全部失败：自动退出全屏回首页 `Stopped` 占位，按 OK 可重试。
15. 频道列表/城市选择打开时到屏保：面板先关闭，再按焦点位置全屏或进屏保。
16. 焦点顺序：小窗 Up→状态胶囊→Left→天气；dock 第 1 槽 Up→天气；天气 Right→小窗/图标。

## 15. 风险与已知限制

1. **默认桌面/HOME 键/进程被杀**：创维固件仍会接管 HOME、杀 Aerial；本次不解决，装机后需继续处理系统层。
2. **scoped storage**：Android 10+ 无法直接读 `/sdcard/iptv_sources.json`；目标电视 Android 8 可用，其他设备需 SAF 导入。
3. **HLS 兼容性**：部分源可能依赖 VLC 特性；ExoPlayer 失败时自动切源/频道，仍可能无可用源。
4. **双视频解码**：小窗播放时暂停动态壁纸；若仍有性能问题，需在真机上降级壁纸或缩小解码分辨率。
5. **SurfaceView 切换**：小窗/全屏共用播放器，切换 surface 时可能出现短暂黑帧；真机验证，必要时用 `TextureView` 或双 Surface 预热。
6. **屏保时间**：现有 `idleBeforeScreensaver()` 会取 Aerial 设置与固件屏保时间的较小值，自动全屏可能早于用户设置；沿用现有逻辑，不额外改动。
7. **频道配置更新**：中国广电 APK 改写 JSON 时，Aerial 在下一次回首页才刷新；播放中不会热更新。
8. **签名**：本地没有 `local/signing.properties` 时构建使用 debug 签名，无法覆盖官方 release 安装；真机验证前需解决签名或卸载重装（卸载会丢 Aerial 设置/壁纸）。
