# 首页 IPTV 小窗、时钟天气与底部快捷方式调整

日期：2026-10-29
范围：`app/`（完整版）。Lite 没有 IPTV 小窗、大时钟天气和底部快捷方式替换，本次不改。

## 背景

feature/iptv 分支上首页右上角有直播小窗，底部是 App 磁贴。参照 `../arclauncher` 的首页后，需要做四项调整：

1. 小窗右边与底部最后一个磁贴卡片对齐。
2. 顶部状态胶囊与屏幕上沿的边距减半，给小窗腾出高度。
3. 非屏保时的大时钟、天气按 arclauncher 的样式重做。
4. 底部那排磁贴支持替换应用。

已确认的取舍：

- 天气继续用 Open-Meteo，不接当贝日历接口；扩展湿度和明日预报。
- 大时钟改成 arclauncher 样式，保留 Aerial「时钟：左上 / 右上」设置；小时钟模式和屏保时钟不变。
- 底部磁贴只是快捷方式，下翻列表必须仍然完整。替换采用交换式：新 App 到该位置，原 App 去新 App 原来的位置，两个都保留、列表无重复。
- 替换入口只出现在底部第一排的普通 App 磁贴上。

## 需求 1、2：小窗对齐与胶囊边距

### 现状

`ui/tv/TvSlotGeometry.kt` 用同一个「屏幕边距」`gap` 同时决定：

- 窗口右边离屏幕右边多远；
- 窗口顶部离胶囊多远；
- 窗口底部离底板多远。

有底板时窗口右边对齐底板外沿（磁贴右 + `PlatePadH` 26dp），`gap` 是 30dp。直接把窗口右边改成磁贴右沿后，`gap` 会变成 56dp，窗口底边上移、整体变矮，和需求 2 相反。

### 改法

把「窗口右边」与「竖直边距」拆开：

```kotlin
val tileRight = margin + pitchX * (columns - 1) + tileW
// The window's right edge lines up with the last tile card, not the plate's outer edge.
val windowRight = tileRight
// The gap the window keeps from the plate and the status pill is still the old
// screen-edge gap: the plate keeps its 26dp overhang, so this stays 30dp.
val edgeGap = screenW - (tileRight + if (plate) PlatePadH else 0.dp)
val rowTop = if (plate) topRowY - PlatePadV else topRowY
val top = PillTop + PillHeight + edgeGap * (2f / 3f)
val bottom = (rowTop - edgeGap).coerceAtLeast(top + MinSlotHeight)
```

- `PillTop` 从 28dp 改为 14dp。
- `LargeClockTopMin` 同步改为 `PillTop`（14dp），保持双排时大时钟与胶囊齐平的关系。
- 窗口宽度仍由高度和屏幕比例决定；图标位置 `iconRight = tileRight` 不变。

### 结果（960×540、5 列、有底板）

- 右边：930dp → 904dp，与最后一个磁贴卡片右沿重合。
- 顶部：82dp → 68dp（胶囊上移 14dp）。
- 底部保持 249.7dp，高度 167.7dp → 181.7dp。
- 无底板时只有右边变化，顶部同样上移 14dp。

### 涉及文件

- `app/src/main/java/app/orchard/tv/ui/tv/TvSlotGeometry.kt`
- `app/src/main/java/app/orchard/tv/ui/home/HomeChrome.kt`（`PillTop`）
- `app/src/test/java/app/orchard/tv/ui/tv/TvSlotGeometryTest.kt`

## 需求 3：首页大时钟与天气

### 现状

`HomeChrome.ShelfClock` 同时用于首页大时钟和屏保时钟（`Screensaver.ClockSaver` 也调用它）。首页大时钟是三行锁屏式：日期 + 农历、104sp 粗体时间、天气一行。需求只改「非屏保时」，所以不能直接改 `ShelfClock` 的公共外观。

### 改法

在 `HomeChrome.kt` 新增首页专用的大时钟组件（例如 `HomeShelfClock`），`HomeScreen` 的大时钟分支改用它；`Screensaver.kt` 继续用 `ShelfClock`，屏保视觉不变。

新的首页时钟按 arclauncher 的 1280 设计稿 ×0.75 换算到 960 画布：

- 只显示时间，不再显示日期和农历，仍跟随 `timeText(now)` 的 12/24 小时设置。
- 时间：Inter Display，`FontWeight.W300`，约 84sp，行高约 1.0，保留轻微黑色阴影。
- 时间下方约 9dp 是天气区：
  - 第一行：天气图标约 60dp，右侧 15dp，温度约 22.5sp（W500），再右 9dp 是天气条件约 15sp。
  - 第二行：城市约 11sp；有天气数据时右侧显示「湿度 N%」胶囊，底色 `0x99009988`、边框 `0x6600E5CC`、圆角 999，内边距约 7.5dp × 1.5dp，文字约 10sp。
  - 第三行：约 9dp 间距后显示「明日」+ 明日天气图标约 13.5dp + 「天气 最高°/最低°」。
- 天气图标继续用 Aerial 的 `Glyph`（`glyphFor`），条件文案继续用现有 `conditionName`，不引入 arclauncher 的图标资源。
- 左侧位置对齐磁贴边距（56dp），顶部从 80dp 改为约 49dp。
- 右上位置仍由 `AerialSettings.clockRight` 决定，整块右对齐，天气行的图标和文字顺序仍从左到右。
- 保留天气区的聚焦交互：聚焦天气行时显示焦点框，OK 打开城市选择器，IPTV 小窗按左键仍进入天气区。
- 保留进入屏保时的过渡：时间移动到屏保位置（复用 `toSaverClock` 的逻辑）；新增的天气块按原来的 400ms 淡出，唤醒时按原延迟淡入。
- `HomeScreen.GridMetrics` 的 `LargeClockHeight` 从 152dp 改为按新块实测高度（约 190dp），双排布局的让位继续沿用现有公式。

### 天气数据

`weather/Weather.kt` 的 `WeatherNow` 扩展：

```kotlin
data class WeatherNow(
    val celsius: Int,
    val kind: WeatherKind,
    val day: Boolean,
    val high: Int? = null,
    val low: Int? = null,
    val humidity: Int? = null,
    val tomorrow: TomorrowWeather? = null,
)

data class TomorrowWeather(val kind: WeatherKind, val high: Int?, val low: Int?)
```

- Open-Meteo 请求增加 `current=relative_humidity_2m`、`daily=weather_code,temperature_2m_max,temperature_2m_min`、`forecast_days=2`。
- 今天仍取 daily 第 0 项；明日取第 1 项，明日最高/最低对应 `temperature_2m_max/min`。
- 旧版本缓存的天气没有湿度和明日字段时，界面显示「--」/「明日天气待更新」，不崩溃，下一次刷新后补齐。
- 不把任何新的第三方服务器接进应用。

### 新增界面文字

`res/values`、`values-zh`、`values-ru`、`values-uk`、`values-kk` 五份都要加，占位符一致：

- `weather_humidity`：「湿度 %1$d%%」
- `weather_tomorrow`：「明日」
- `weather_tomorrow_pending`：「明日天气待更新」
- 复用现有的加载中 / 不可用 / 最高最低文案。

### 涉及文件

- `app/src/main/java/app/orchard/tv/ui/home/HomeChrome.kt`
- `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt`
- `app/src/main/java/app/orchard/tv/weather/Weather.kt`
- `app/src/main/res/values*/strings.xml`（五种语言）
- 屏保 `Screensaver.kt` 不改

## 需求 4：底部磁贴替换应用

### 入口

- 只在底部第一排（`index < AerialSettings.homeColumns`）的普通 App 磁贴上显示。
- 文件夹磁贴、信号源磁贴、第二排及以下不显示。
- 长按（MENU）打开现有 `AppMenu`，在「移动」下方新增一行「替换应用」。

### 选择器

- 新增 `Overlay.ReplaceApp(slotKey: String)`（或等价的、带 slot index 的状态）。
- 选择器是一个玻璃面板，复用 `HiddenAppsSheet` 的卡片网格样式：
  - 标题「替换应用」；
  - 说明「选择要放到这个位置的 App」；
  - 列出当前下翻列表中的普通 App：`apps.drop(columns).filter { it.folder == null && it.source == null }`；
  - 4 列、可滚动，焦点进入第一张卡片；
  - 没有候选时显示「没有可替换的应用」；
  - Back、点击空白关闭，回到桌面（焦点回原磁贴）。
- 信号源、文件夹和隐藏的 App 不作为替换候选。

### 交换规则

- `LayoutStore` 新增按 key 精确交换位置的方法（例如 `swap(a: String, b: String)`）：在 `order` 中交换两个 key 的位置，不改变列表长度、不碰文件夹和隐藏集合。
- 确认选择后：
  - 记下 `slotKey = apps[slotIndex].key` 和 `newKey`；
  - `swap(slotKey, newKey)`；
  - `arrange()` 重新生成首页；
  - 关闭选择器，把焦点放到 `newKey`，`indexAfterOverlay` 取交换后 `newKey` 在 `apps` 中的位置。
- 结果：新 App 占据该底部磁贴，原 App 去新 App 原来的位置；其他底部磁贴不动，下翻列表仍然完整、无重复。
- 手机页整理和 `LayoutStore` 的排序同步反映这次交换。

### 菜单与文字

`AppMenu` 增加一个回调（例如 `onReplace: (String) -> Unit`）和一个「是否显示替换项」的参数；`HomeScreen` 根据 `index < columns`、`app.folder == null`、`app.source == null` 传入。

五种语言新增：

- `menu_replace`：「替换应用」
- `replace_title`：「替换应用」
- `replace_hint`：「选择要放到这个位置的 App」
- `replace_empty`：「没有可替换的应用」

### 涉及文件

- `app/src/main/java/app/orchard/tv/apps/LayoutStore.kt`
- `app/src/main/java/app/orchard/tv/HomeViewModel.kt`
- `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt`
- `app/src/main/java/app/orchard/tv/ui/home/Overlays.kt`
- `app/src/main/res/values*/strings.xml`（五种语言）

## 非目标

- 不改 Lite 模块。
- 不改 IPTV 全屏、频道列表、EPG、手机频道管理页。
- 不改屏保时钟、小时钟、控制中心里的时间天气。
- 不改天气服务提供商，不新增第三方 SDK 或内容服务器。
- 不改底部快捷方式的数量；仍然是每排 4/5/6 个。

## 测试与验证

### 单元测试

- 更新 `TvSlotGeometryTest`：
  - 有底板时 `windowRight` = 磁贴右沿，`windowTop` 比旧值上移 14dp，高度增加 14dp；
  - 无底板时右边仍对齐磁贴；
  - 低行高时最小高度仍为 60dp。
- 为 `LayoutStore.swap` 增加测试：两个 key 交换、其他顺序不变、文件夹/隐藏不受影响。
- 把 `Weather` 的 JSON 解析提取成可测的内部纯函数，覆盖旧响应缺湿度、缺明日字段时不抛异常。

### 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew compileStandardDebugKotlin testStandardDebugUnitTest
./gradlew assembleStandardRelease assembleGuardRelease
```

### 真机验证（完成后才能说「已实现」）

- 在电视上用 release 版，执行 `cmd package compile -m speed -f app.orchard.tv`。
- 截图确认：
  - 小窗右边缘与底部最后一个磁贴卡片右边缘重合；
  - 胶囊上沿距屏幕 14dp，小窗高度比原来多约 14dp；
  - 大时钟为细体时间 + 天气图标/温度/条件/城市湿度/明日；
  - 左/右两种时钟位置都正常；
  - 双排布局下两排磁贴不被新时钟挡住；
  - 长按底部磁贴出现「替换应用」，选择后该位置换新 App，原 App 在下翻列表里，其他底部磁贴不动。
- 没有真机验证前，HANDOFF 里只记录「已实现、待验证」，不要写「已完成」。

## 风险与注意

- `ShelfClock` 与屏保共用：一定要把首页新样式做成独立组件，避免屏保时钟跟着变。
- 旧天气缓存没有新字段：所有新字段都按可空处理。
- 替换只对底部第一排普通 App 生效；文件夹、信号源和第二排不要误显示菜单项。
- 小窗几何的 `edgeGap` 是「旧屏幕边距」，不要再直接用 `screenW - windowRight`，否则窗口会变矮。
- 双排布局的时钟让位高度依赖 `LargeClockHeight`，新时钟块实测后再定值，不要沿用 152dp。
