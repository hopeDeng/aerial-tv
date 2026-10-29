# Aerial 直播：小窗收束 + 全屏照搬 CBN 播放器

日期：2026-10-27
状态：已确认，待实现
修订：[2026-10-26-iptv-window-on-original-home.md](2026-10-26-iptv-window-on-original-home.md)（那版的「钉住」「小窗换台」「小窗开频道列表」全部作废；首页接线、跟随滚动、TextureView 出画面这几条保留）
参照实现：`/Users/hope_deng/works/TVLauncher/my-tv-launcher/work/player/src/cn/cbn/coocaa/`（Java + LibVLC：`TvInputActivity.java`、`EpgData.java`、`SourceStore.java`、`M3uParser.java`）
目标设备：创维 8N07_J9000（Android 8.0）为主，小米 MFTR0 / TCL MT5879 回归

## 1. 原则

- 小窗只做一件事：出画面。**没有钉住、不换台、不开任何面板**；OK 直接进全屏，所有 IPTV 操作都在全屏里。
- 全屏的按键语义、覆盖层层级、直播源优选算法、EPG 数据源与刷新，**照搬**参照实现；只有尺寸按我们的 960×540dp 画布等比重排（参照里是 1920px 制的像素值，换算成 dp，比例不变）。
- 参照实现里确认是死代码的部分不照搬（见 §8 A 条）。
- 分 4 个里程碑，每段都在创维真机验证过了才进下一段（见 §9）。

## 2. 状态机（`tv/TvStateMachine.kt`）

`TvWindow` 从 `Collapsed / Preview / Pinned / Fullscreen / Stopped` 收成 **`Collapsed`(图标) / `Preview`(小窗) / `Fullscreen` / `Stopped`**(不可播的图标占位)。`TvState.panelOpen` 与 `TvEvent.PanelsOpened/PanelsClosed` 删除（面板只活在全屏里，由全屏自己管层级）。

| 事件 | 结果 | 效果 |
|---|---|---|
| `WindowGotFocus`（Collapsed，有频道） | `Preview` focused | `Play` |
| `WindowGotFocus`（Collapsed，无频道） | `Stopped` focused | — |
| `WindowLostFocus`（Preview） | `Collapsed` | **`Stop`** |
| `WindowClicked`（Preview） | `Fullscreen` | `EnterFullscreen` |
| `WindowClicked`（Stopped） | 重试：`Preview` focused | `Play` |
| `FullscreenExited`（BACK） | `Collapsed` | `Stop` |
| `Idle`（Preview 且焦点在窗、有频道） | `Fullscreen` | `EnterFullscreen`（屏保不接管） |
| `Idle` / `ManualScreensaver` / `AppLaunched` / `LeftHome`（Preview/Fullscreen） | `Collapsed` | `Stop` |
| `PlaybackFailed`（Preview/Collapsed） | `Stopped` | `Stop` |
| `PlaybackFailed`（Fullscreen） | 留在 `Fullscreen`，`playback=Error` | 全屏 OSD 显示「所有源均失败」 |

未列出的组合（`Collapsed`/`Stopped` 收到 `Idle`、`ManualScreensaver`、`WindowLostFocus` 等）一律 keep + `None`，与现版 `keep(state)` 相同。

`Pinned` 相关分支与图钉点全删。文案改动（五语同步）：`tv_preview_hint`「按 OK 钉住小窗」改写为「按 OK 全屏播放」；`tv_fullscreen_hint`「OK：频道列表 · 返回：退出」在 **M2** 重写为 §4 的键位摘要（M1 不动它，此刻键位还没换）；`tv_no_source` 保留，但只在全屏与图标占位上画，修掉现在展开判断缺失导致文字溢出小窗的毛病（`TvWindow.kt:189-195`）。小窗按键：LEFT → 天气胶囊，RIGHT → 不动，UP/DOWN → 原样（UP 给胶囊、DOWN 给网格顶行末位），**LEFT/RIGHT 不再换台**，**MENU 不再开频道列表**。

**冷启动默认焦点**：`MainActivity` 首次组合 Home 且频道加载完成后，把焦点给小窗一次（`bootFocusGiven` 一次性标记）。从 App 返回、屏保唤醒、下翻回第 1 排都**不**再自动起播，焦点照旧给磁贴。

## 3. 几何（全部写成 metrics 公式，不写死数字）

新增 `TvSlotGeometry(metrics)`，返回窗口矩形和图标矩形；`HomeScreen` 的槽用它替代现在的 `size(380.dp, 214.dp)` + `padding(top=clockY, end=margin)`。

- `gap = screenW − plateRight`，其中 `plateRight = margin + pitchX·(COLUMNS−1) + tileW + PlatePadH` —— 即磁贴毛玻璃卡片到屏幕边的距离（当前 30dp）
- `pillBottom = 28 + 34`（`StatusPill` 的顶部 padding + 高度）
- 小窗：`right = plateRight`（与卡片右边对齐）、`bottom = (topRowY − PlatePadV) − gap`（下边距卡片顶边一个 gap）、`top = pillBottom + gap`（上边距顶部胶囊一个 gap）、`height = bottom − top`、**`width = height × screenW / screenH`**
- 图标：`size = tileW × tileH`（与磁贴同尺寸）、`right = margin + pitchX·(COLUMNS−1) + tileW`（与最后一个磁贴右边对齐）、`top = 小窗 top`
- `homePlate` 关掉时没有玻璃卡片：窗口的右边、以及 `gap` 的参照都改用磁贴带右边（`gap` 变成 56，小窗随之变小），图标不变
- 当前布局（960×540、margin 56、pitchX 175.2、tile 147.2×82.8、PlatePadH 26、PlatePadV 20、胶囊 28+34）算出：小窗 **280.4×157.7dp**（右边 930，左上 649.6, 92）、图标 **147.2×82.8dp**（右边 904，左上 756.8, 92）
- `one_row_home` / 磁贴带名称 / 4K 画布 等开关会改变 `topRowY`、`tileW`，公式跟着变；`height` 兜底 `max(60.dp)` 防极端布局把窗口压扁
- 槽仍取网格同一个滚动偏移（`.offset { IntOffset(0, -scroll) }`），下翻过 `collapsePx/2` 连图标一起滑出画布 —— 这条已验证，不动

**缩回动画**：焦点离开时在小窗矩形 ↔ 图标矩形之间做一次 spring 插值（`Motion` 曲线；会被打断所以用 spring），同时淡出频道名/提示、淡入图标字形；不是切换两个组件，是同一个 `Pressable` 的两个矩形。

## 4. 全屏：按键与层级（`ui/tv/TvFullscreen.kt` 重写）

层级优先级栈照搬参照实现 `onKeyDown`：`频道编辑器 > 文件选择 > 导入预览 > 频道网格 > EPG > 面板 > 裸全屏`，每层吃掉自己的键并提前返回。同一个 keyCode 250ms 内重复到达直接吞掉。

裸全屏键位：MENU=EPG 开关、UP=频道网格、DOWN=源管理面板、LEFT/RIGHT=环形换台（越界回绕）、OK=频道列表、BACK=退出全屏回桌面。音量/数字键交给系统（参照也是这么漏下去的）。

OSD：左下角一行 `序号/总数 频道名 [源N]`，换台或切源后出现，**5 秒**自动隐藏；面板/编辑器开着时不隐藏它（参照同规则）。

覆盖层（都在全屏内，都是 Compose，画布 dp）：

- **频道网格**（UP）：右侧 5 列格子，格子底色按 `name.hashCode()` 取 15 色表、显示首字，选中描琥珀色；方向键移动（越界钳住、不回绕），OK 直接播并收起，BACK/MENU 收起。
- **频道列表**（OK）：沿用现有 `TvChannelList`，但搬进全屏：左侧竖列、行高按比例、标题带总数、当前频道行打 `►` 且开表即聚焦它。
- **源管理面板**（DOWN）：标题「直播源配置（N）」，每行 `► 序号. 名称【当前源/总数】`，未启用加 `[停用]`；行内操作 **播放 / 删除 / 禁用·启用 / 排序 / 进源列表**；底部栏 **导入 / 导出 / 清空**，清空两步确认。排序模式下 UP/DOWN 直接移动频道位置，每步立即落盘，OK/BACK 退出。
- **源列表子页**：标题「源列表：名称（N）」，每行 `序号.【label】url`，当前源标 `[当前]`，可切当前源 / 删除 / 禁用·启用 / 排序，改动即时落盘。
- **添加·修改频道 + 屏幕键盘**：居中编辑框（名称上限 40、URL 上限 300，名称空 → 提示「频道名不能为空」），键盘 8 列 × 6 行，字符表逐字照搬参照 `TvInputActivity.buildKbd()`（`TvInputActivity.java:982-990`）：`01234567` / `89./:-_?` / `&=%+abcd` / `efghijkl` / `mnopqrst` / `uvwxyz␣␣`，功能行 **删除 / 确定 / 取消 / 切行**，MENU 或「切行」在名称与 URL 间切换。
- **导入**：扫描 `/sdcard` 下 `.m3u/.m3u8/.txt`（跳过点开头目录、单文件 <5MB）→ 文件列表 → 解析预览（全部预勾选，底栏 **全选 / 取消全选 / 确认导入 / 取消**）→ **合并**语义：按规范化频道名模糊匹配（精确优先，其次 ≥3 字前缀且拒绝数字尾巴），URL 精确去重，标签取文件名（不含扩展名），标签为 `hnyd` 时重排优选 → 落盘 → 提示「已导入 N 个频道」。空文件/读失败各有提示。
- **导出**：写 `/sdcard/iptv_export.m3u`，`#EXTM3U` + 每频道 `#EXTINF:-1,名称` + 当前源 URL。

## 5. 直播源优选与持久化（`tv/TvSourceStore.kt` 新增，`tv/TvPlayer.kt` 改）

算法照搬参照实现：

1. 启动时把 `label == "hnyd"` 的源稳定地提到该频道源列表最前（**只在内存里重排，不改文件里的顺序**）。
2. 起播用 `currentSource`；越界或该源已停用则退到第一个已启用源。
3. 起播后 **6000ms** 探测一次：没出画面（Media3 侧判据：未到 `STATE_READY` 或 `isPlaying=false` 或 `currentPosition` 仍为 0）判失败，原因记 `timeout`。
4. 失败（error / end / timeout）→ 环形切到下一个已启用源（`(from+k)%n`，k=1..n）；切换有 **3000ms** 防抖，且一次只允许一个切换在飞。
5. 切一圈回到起点（`next < 0 || next == attemptStart`）→ 停止，报「所有源均失败」；**不无限重试、不换频道**。
6. `currentSource` 与频道/源的 `enabled` 写回 `/sdcard/iptv_sources.json`。

写回比参照更保守（户主授权改动这份机顶盒侧文件，但只按最小 diff）：保留原数组顺序、保留我们不认识的字段（加载时留住原始 `JSONObject` 树，只改 `currentSource`/`enabled` 这几个值）、临时文件 + rename 原子写、只在真有变化时写。文件形状与参照一致：顶层只有 `channels`，频道字段顺序 `name/enabled/currentSource/sources`，源字段 `url/label/enabled`，`label` 缺省 `"manual"`，紧凑无缩进 UTF-8。

`/sdcard/cbn_last_channel`、`/sdcard/cbn_last_url` 这两个参照用的外部文件**不写**，上次频道/源仍记在 `TvPrefs`（SharedPreferences）。

## 6. EPG（`tv/TvEpg.kt` 新增，`ui/tv/FsEpgStrip.kt` 新增）

双数据源，按频道名选：

- **CCTV**：`https://zy.api.cntv.cn/epg/getEpgInfoByChannelNew?c=<code>&serviceId=tvcctv&d=<yyyyMMdd>&t=json`，日期用 **Asia/Shanghai**；名称→代号：小写并去掉空格/`-_·\t`，找 `cctv`，特例 `5+`/`5plus`→`cctv5plus`、`4k`、`8k`，数字尾巴 1–17 → `cctvN`，其中老代号 **9→`cctvjilu`、14→`cctvchild`**。解析 `data.<code>.list[]` 的 `title/startTime/endTime`，丢掉 `start<=0`、`end<=start`、空标题，按开始时间升序。
- **其余**：tvmao HTML 抓取 + **照搬那张 80 条硬编码频道表**（`satellite/XXX`、`local/XXX`）；URL `https://www.tvmao.com` + （local→`/program/`，否则 `/program_<cat>/`）+ `<code>-<wToken>.html`，周 token `w1..w7`=本周一..周日、`w8..w14`=下周（钳在 1–14）。频道名归一化：小写、去掉 `()（）[]【】` 里的内容、在 `*` 处截断、繁→简（衛視樂）、去空格/`-_·.`、去「频道」。正则取 `<span class="(am|pm)">\s*(\d{1,2}):(\d{2})`，其后 600 字符内取 `<span class="p_show">` 作标题，去标签与实体；只有开始时间，结束=下一条开始，最后一条=基点+86400。
- HTTP：`HttpURLConnection`，连接 **8s** / 读 **10s**，UA 用参照里那个 Mozilla 串，UTF-8。
- 缓存：单槽内存缓存（同源 + 同天才算命中），无磁盘缓存、不预取邻频道。抓回来为空或最后一条已过点 → 自动抓明天。
- 刷新：**1000ms** ticker 更新当前节目、选中态、进度条；最后一条已结束且距上次尝试 >60000ms 才重新请求。无自动隐藏。
- UI：底部深色条（不透明度 0xEE、圆角、高 128dp、左右边距 20dp、底边距 16dp）+ 顶部状态行（琥珀 0xFFFFC107、15sp、单行尾部省略），下面是横滚卡片行：卡片 300×84dp、间距 6dp，已过 0xFF1D1D1D / 正在 0xFF3A2C07 / 普通 0xFF2C2C2C，选中描边琥珀 2dp、正在 0xFF8A6D1F；卡片内标题 16sp（已过 0xFF8A8A8A），下面一行 开始时间 + 胶囊进度条（轨道 0xFF55503F、已播 0xFFFFC107）+ 结束时间。状态行文案：`频道名  正在播出 节目名  约 N 分钟后结束`。无卡片时条收高到 44dp。初始选中=当前节目，滚动跟着选中走。
- 键：EPG 打开时 MENU/BACK/UP/DOWN/OK 一律收起，LEFT/RIGHT 在卡片间移动（钳边界、不回绕），**其余键全部吞掉**（EPG 开着不能换台）。开 EPG **不暂停**视频。从 EPG 不能选台。频道切换后 EPG 不自动刷新（要重新 MENU）。
- 未知频道（两张表都不认识）→ 细条 + 「该频道暂无节目单」，不发请求。失败 → 「节目单获取失败」。加载中 → 「节目单加载中…」。

## 7. 空闲时不进厂商自带屏保

创维 8N07_J9000 的 `com.tianci.ad/.ScreensaverActivity` 不走 Dream 框架（本机 `screensaver_enabled=0`、dream 组件指向 deskclock），是厂商自带的空闲计时器：实测两次都在无操作 **~120 秒** 抢屏，比我们 300 秒的屏保早。所以现成的 `own_screensaver`（抢 dream 槽）和 `screensaver_enabled=0` 两条路对它无效。

按侵入度推进，前一步实测不成立才走下一步：

1. **定性实测**（一次，6 分钟）：`svc power stayon true` + 完全不操作，看厂商屏保是否还冒出来 —— 区分「降亮/休眠路径触发」与「自带输入计时器触发」。测完必须 `stayon false` 复位。
2. 若是前者：Home 在前台、等自己屏保的那段空窗里加 `FLAG_KEEP_SCREEN_ON`（现在只有 Aerial 屏保已启动后才加，见 `MainActivity.kt:509`；小米 MFTR0 与 TCL MT5879 都验证过这一手能压住厂商屏保）。
3. 若是后者：按小米那套老办法，按功能检测到 `com.tianci.ad` 存在就把 `idleBeforeScreensaver()` 压到厂商之前（约 100 秒），让 Aerial 自己的屏保先上。
4. 前两条都不成立才让守护在 `com.tianci.ad/.ScreensaverActivity` 成为前台活动时把 `MainActivity` 拉回来。

**红线**：不停用、不卸载 `com.tianci.ad` 或任何系统应用（AGENTS.md）。厂商特殊处理一律写成按包是否存在检测，不写机型硬编码，除非确认无法检测。

## 8. 两处明确不照搬

- **A：源列表子页的死代码。** 参照里 `srcBtnSel` 恒为 0、且 `sourceMode` 下不处理 LEFT/RIGHT，所以删除/禁用/排序 三个按钮实际点不到，只有「切当前源」生效。**不照搬这个 bug**，源列表的四个操作做成真能用的。
- **B：整文件重印式写回。** 参照的 `save()` 直接 `FileOutputStream` 覆盖整份文件、且会把启动时的 `reorderPreferred` 顺序一起落盘。我们按 §5 的最小 diff 写。

## 9. 里程碑与验收

每段独立上创维真机（release + `cmd package compile -m speed -f app.orchard.tv`）验证过了才进下一段。

- **M1 收束小窗**：状态机去钉住、离焦缩回停播、§3 几何与插值动画、冷启动默认焦点一次。验证：开机有画面；焦点移开 → 缩回图标且解码器释放；OK → 全屏；BACK → 回桌面变图标；下翻离开首页停播；从 App 返回不自动起播。
- **M2 全屏骨架 + 源优选**：按键栈、250/3000ms 防抖、OSD 5 秒、频道列表搬进全屏、环形换台、6 秒探测 + 环形切源 + 最小 diff 写回 json。验证：多源频道里死源被自动跳过、`currentSource` 落盘且文件里其他字段与顺序未被动过。
- **M3 频道网格 + 源管理面板 + 导入导出 + 频道编辑器（含屏幕键盘）**。验证：导入一份 m3u 能合并、禁用/排序/删除即时生效并落盘、导出文件内容正确、清空有二次确认。
- **M4 EPG**：CNTV + tvmao 两条路、缓存与 1 秒 ticker、底部条与卡片。验证：CCTV 频道和普通卫视都能出节目单，未知频道走「暂无节目单」。
- **屏保（§7）** 与 M1–M4 并行插队，独立验证。
- 通用闸门：`./gradlew :app:testStandardDebugUnitTest assembleStandardRelease assembleGuardRelease` 通过；`TvStateMachine` 单测按 §2 表重写；新增 `TvSourceStore`（环形切换、最小 diff 写回）与 `TvEpg`（名称→代号/归一化、tvmao 正则）单测；五语文案齐全且占位符一致；没有在真机上验过的功能不得称「已完成」。

## 10. 明确不做

时移/回放/字幕、数字键直接输入频道号、音量与系统键拦截、EPG 里选台、邻频道 EPG 预取、`cbn_last_channel`/`cbn_last_url` 外部文件、以及把参照的 px 尺寸当 dp 直接用。
