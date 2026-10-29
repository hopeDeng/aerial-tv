# Aerial 原首页叠加 IPTV 小窗（修订版）

日期：2026-10-26
状态：已确认，实现中
取代：[2026-10-26-home-iptv-window-design.md](2026-10-26-home-iptv-window-design.md)（dock + 全部应用页那版方向不对：它另加了一个首页；本方案在 aerial-tv 原有首页上修改，网格是唯一首页）
目标设备：创维 8N07_J9000（Android 8.0），小米 MFTR0 / TCL MT5879 回归

## 1. 原则

- 首页布局、网格、底板、时钟、天气、壁纸、屏保过渡、控制中心全部保持 HEAD 原样，只新增一个「直播槽」。
- 复用未提交的 `tv/` 播放与源模块（`iptv_sources.json` → `assets/hnyd.m3u` 回退、ExoPlayer HLS、错误重试）；UI 层（`ui/tv/`）与首页接线按本方案重写。
- 删除：`HomeDock.kt`、`DockStore.kt`、`DockStoreTest.kt`、`vm.allApps` 及所有 dock/全部应用页分支。

## 2. 布局（画布 960×540dp）

- 直播槽：`right = 56dp`（与网格边距一致）、`top = 74dp`（= clockY）、`380×214dp`（16:9）。位于状态胶囊之下、第一排磁贴之上，不遮挡任何现有元素。
- 收起态：槽内居中 96dp 玻璃圆角方块 + 播放三角（无频道/播放全失败时是「不可播放」占位，Glyph.Offline）。
- 展开态：380×214 小窗，圆角 20dp，静止 1dp 白 18% 描边，聚焦 2dp 白 90%；钉住时右上角一个小图钉标记。
- 槽**跟着网格滚**：取的是网格同一个滚动偏移（`offset(y = -scroll)`），不是浮在首页之上的一层。下翻到 `scroll > collapsePx/2`（时钟淡出那个阈值，也就是焦点进第 2 排）时，图标和小窗一起滑出画布顶边，同时取消钉住、停播（`TvEvent.LeftHome`）；上翻回第 1 排图标回到原位，但不自动续播，要焦点进窗才预览。
- 大时钟布局不受影响；**大时钟 + clockRight** 与槽重叠（同以 74dp 起、同贴右）→ 槽优先，时钟落回左上。小时钟在状态胶囊那一行（28~62dp），本就在槽之上，不受影响。

## 3. 焦点路线

- 第一排最右磁贴按「上」→ 直播槽（原来回弹；其余磁贴「上」照旧回弹）。
- 直播槽：上→胶囊、下→第一排最右磁贴、左→天气行、右→无；OK/左右/Menu 见 §4；Home 键从全屏回首页。
- 胶囊：新增「下」→ 直播槽；其余不变。
- 天气行可聚焦（沿用 ShelfClock 的 Pressable 改法）：OK→城市选择（CityPicker）、右→直播槽、下→网格第一格。
- 开机、HOME、回首页：默认焦点仍是网格第一格；不自动聚焦直播槽、不自动播放。

## 4. 状态机（`TvWindow`）

状态：`Collapsed`（图标）、`Preview`（展开播放，焦点在窗、未钉住）、`Pinned`（展开播放、已钉住、焦点可在别处）、`Fullscreen`、`Stopped`（无频道/全失败的不可播放占位）。

| 事件 | 当前 | 结果 |
| --- | --- | --- |
| 图标获得焦点 | Collapsed | Preview + 从上次频道播放；playback=Error 时保持占位 |
| 失焦 | Preview | Collapsed + 停播 |
| 失焦 | Pinned | 继续播放 |
| OK | Preview | Pinned |
| OK | Pinned（窗聚焦） | Fullscreen |
| OK | Stopped | 重试（清失败集）→ Preview |
| 左右 | Preview/Pinned/Fullscreen/Stopped(不换) | 换台 + OSD，记上次频道 |
| Menu | 任意窗态 | 频道列表（播放继续；打开期间失焦不停播） |
| 城市选择打开 | Preview/Pinned | 播放继续（Preview 失焦不停播）；关闭恢复 |
| 屏保到点，窗聚焦（Preview 或 Pinned+窗聚焦） | Window | 自动 Fullscreen，不进屏保 |
| 屏保到点 | Pinned（焦点不在窗） | 取消钉住、停播 → Collapsed，进屏保 |
| 屏保到点 | Collapsed/Stopped | 直接进屏保 |
| 屏保到点 | Fullscreen | 不调度 |
| 全屏 Back | Fullscreen | → Pinned，焦点回窗 |
| 全屏 HOME | Fullscreen | → Pinned，焦点回网格第一格 |
| 启动 App / 切信号源 | 任意 | 停播取消钉住 → Collapsed（无频道 Stopped）；回首页**不**自动续播 |
| 手动屏保（Back/立即开始） | 任意首页态 | 停播 → Collapsed，进屏保；全屏则先退 |
| 屏保唤醒 | 任意 | Collapsed/Stopped，不自动播放 |
| 一轮源全失败 | 任意 | Stopped，不自动重试；OK/换台重试。失败只在本频道的源之间退，不会自己跳到别的频道 |
| 屏保「从不」 | 任意 | 不自动全屏、不停播 |

播放期间动态壁纸（航拍/我的视频）暂停避免双解码（沿用上一版 `tvBusy` 做法），停止后恢复。

## 5. 复用与改动面

- 原样保留：`tv/TvChannel|TvSources|TvPlaylist|TvPlayer`（`TvDebug` 的自建日志文件去掉了，播放事件改走 `DiagLog`，跟全项目一样受 diag 开关控制）、`ui/tv/TvSurface|TvChannelList|CityPicker|TvOverlays`、`media3-exoplayer-hls` 依赖、`READ_EXTERNAL_STORAGE`(maxSdk 32) 与 `install.sh` 授权、五语 `tv_*` 文案中不涉 dock 的条目。
- 重写：`tv/TvStateMachine`（上表）、`tv/TvController`（去 focusTarget/AllApps/resumeOnHome/picker 暂停语义，加 pin/unpin）、`ui/tv/TvWindow`（380×214、图钉、新按键）、`MainActivity`（回到 HEAD 版本再挂 tv 钩子）、`HomeScreen`（HEAD + 直播槽/天气聚焦/R2 路线）、`HomeChrome`（保留 ShelfClock 天气参数；StatusPill 加 onDown）。
- 回退：根 `build.gradle.kts` 的 AGP 8.13.0 改动（与需求无关，回到 8.12.0）；`Pressable.kt` 的 onLongClick（dock 专用）回到 HEAD。
- 文案：删 `tv_all_apps`、`tv_dock_empty`、`tv_dock_replace`；`tv_source_file` 若无引用一并删；新增「按 OK 钉住/播放」提示（五语）。

## 6. 验收

- JVM：`TvStateMachine` 按 §4 表重写单测；`TvSources` 单测保留；`./gradlew :app:testStandardDebugUnitTest assembleStandardRelease assembleGuardRelease` 通过。
- 真机（必须，release + speed compile）：小米 MFTR0 走 §3/§4 全部路径回归（含钉住、屏保到点两种焦点、启动 App 停播、五语）；创维 8N07_J9000 验证 CBN 文件读取、HLS 换台、钉住过夜不发热异常。模拟器只作开发期辅助。
- 创维 8N07_J9000 已过（2026-10-27，1.52-guard + 本地构建）：槽跟着网格滚出画布、Pinned 播放中下翻到第 2 排 → 日志 `LeftHome → Collapsed, effect=Stop`（截图见小窗随网格上滑消失），回到第 1 排图标回原位、只有焦点进窗才预览；TextureView 出画面（SurfaceView 在这台机上只有声音）。
- 排查用的两条日志（`DiagLog`，只在 diag 开时写文件）：`tv: N channels, /sdcard/iptv_sources.json readable=… [前三个频道名…]` 和 `tv: channel[下标] 频道名`。频道名和随后播放的 URL 对不上时先看这两行。
