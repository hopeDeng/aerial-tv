# 首页 IPTV 小窗、时钟天气与底部快捷方式实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-10-29-home-iptv-layout-clock-dock-design.md` 调整小窗对齐、胶囊边距、首页大时钟天气，并给底部磁贴加替换应用。

**Architecture:** 先改可单测的纯逻辑（小窗几何、LayoutStore 交换、天气解析），再改 Compose 界面（首页时钟、替换选择器），最后统一构建和真机验证。首页时钟做成独立组件，屏保继续用 `ShelfClock`；替换采用按 key 精确交换，不新增快捷方式数据。

**Tech Stack:** Kotlin、Jetpack Compose、Open-Meteo、JUnit 4、Robolectric、Gradle。

> 项目规则：没有主人明确要求，不要执行 git commit。下面的步骤只改文件、跑测试和构建。

---

### Task 1: 小窗右边对齐磁贴，胶囊上移

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/ui/tv/TvSlotGeometry.kt`
- Modify: `app/src/main/java/app/orchard/tv/ui/home/HomeChrome.kt:518`
- Modify: `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt:123-136`
- Test: `app/src/test/java/app/orchard/tv/ui/tv/TvSlotGeometryTest.kt`

- [ ] **Step 1: 写失败的几何测试**

把 `TvSlotGeometryTest` 改成下面这样（保留原有测试结构，只改期望值和用例名）：

```kotlin
package app.orchard.tv.ui.tv

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The slot's two resting places, lined up with Home's tiles, its plate and the status pill. */
class TvSlotGeometryTest {
    /** The 960x540dp canvas at the layout Home actually uses: five tiles, a plate, two rows. */
    private fun canvas(plate: Boolean = true, topRowY: Float = 299.7f) = tvSlotGeometry(
        screenW = 960.dp,
        screenH = 540.dp,
        margin = 56.dp,
        pitchX = 175.2.dp,
        tileW = 147.2.dp,
        tileH = 82.8.dp,
        columns = 5,
        topRowY = topRowY.dp,
        plate = plate,
    )

    @Test fun `the window lines up with the last tile and keeps the screen aspect`() {
        val g = canvas()
        assertEquals(904f, g.windowRight.value, 0.05f)
        assertEquals(68f, g.windowTop.value, 0.05f)
        assertEquals(181.7f, g.windowHeight.value, 0.05f)
        assertEquals(323.02f, g.windowWidth.value, 0.05f)
    }

    @Test fun `the icon is the last tile`() {
        val g = canvas()
        assertEquals(904f, g.iconRight.value, 0.05f)
        assertEquals(68f, g.iconTop.value, 0.05f)
        assertEquals(147.2f, g.iconWidth.value, 0.05f)
        assertEquals(82.8f, g.iconHeight.value, 0.05f)
    }

    @Test fun `a taller row gives a taller window`() {
        // One-row layout: the row sits low, so the window grows between the pill and the plate.
        val g = canvas(topRowY = 407.2f)
        assertEquals(289.2f, g.windowHeight.value, 0.05f)
        assertEquals(g.windowHeight.value * 960f / 540f, g.windowWidth.value, 0.05f)
    }

    @Test fun `without a plate the window lines up with the tiles instead`() {
        val g = canvas(plate = false)
        assertEquals(904f, g.windowRight.value, 0.05f)
        assertEquals(56f, (960.dp - g.windowRight).value, 0.05f)
    }

    @Test fun `a row under the pill floors the height rather than inverting`() {
        val g = canvas(topRowY = 120f)
        assertEquals(60f, g.windowHeight.value, 0.05f)
        assertEquals(106.67f, g.windowWidth.value, 0.05f)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:testStandardDebugUnitTest --tests app.orchard.tv.ui.tv.TvSlotGeometryTest
```

Expected: FAIL，`windowRight` 仍是 930，`windowTop` 仍是 82。

- [ ] **Step 3: 改常量和几何**

`HomeChrome.kt`：

```kotlin
/** The status pill's place: Home hangs it here, and the live window's top gap is measured from it. */
val PillTop = 14.dp
val PillHeight = 34.dp
```

`HomeScreen.kt` 的常量改成：

```kotlin
/** Where the large clock's time sits: the arclauncher layout, 65dp at 1280 scaled to 960. */
private val LargeClockTop = 49.dp

/**
 * From the large clock's top to where the shelf may start: the time, the weather block and its
 * tomorrow line end some 173 dp down, with a little room so two rows never press on it.
 */
private val LargeClockHeight = 190.dp

/**
 * The highest the large clock may go to make room for two rows, level with the status pill. Only
 * at the left: at the right it would run into the pill.
 */
private val LargeClockTopMin = PillTop
```

`TvSlotGeometry.kt` 的函数体改成：

```kotlin
fun tvSlotGeometry(
    screenW: Dp,
    screenH: Dp,
    margin: Dp,
    pitchX: Dp,
    tileW: Dp,
    tileH: Dp,
    columns: Int,
    topRowY: Dp,
    plate: Boolean,
): TvSlotGeometry {
    val tileRight = margin + pitchX * (columns - 1) + tileW
    // The window's right edge lines up with the last tile card, not the plate's outer edge.
    val windowRight = tileRight
    // The vertical gap is still the old screen-edge gap: the plate keeps its overhang, so the
    // window does not shrink just because its right edge moved in.
    val edgeGap = screenW - (tileRight + if (plate) PlatePadH else 0.dp)
    val rowTop = if (plate) topRowY - PlatePadV else topRowY
    // Closer to the pill than to the plate: the top margin is two thirds of the screen-edge gap.
    val top = PillTop + PillHeight + edgeGap * (2f / 3f)
    val bottom = (rowTop - edgeGap).coerceAtLeast(top + MinSlotHeight)
    val height = bottom - top
    return TvSlotGeometry(
        screenW = screenW,
        windowTop = top,
        windowRight = windowRight,
        // The aspect ratio first: Dp only divides by Dp to a Float, so the screen's width/height
        // fraction is taken before it scales the height.
        windowWidth = height * (screenW / screenH),
        windowHeight = height,
        iconTop = top,
        iconRight = tileRight,
        iconWidth = tileW,
        iconHeight = tileH,
    )
}
```

同时把文件顶部的注释改成：

```kotlin
/**
 * The live slot's two resting places, in the canvas Home lays its grid in. The window's right edge
 * lines up with the last tile's, its bottom and top sit one screen-edge gap off the plate and the
 * status pill, and its width follows the screen's aspect ratio. The icon is a tile: the last tile
 * of the top row's size and right edge.
 */
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
./gradlew :app:testStandardDebugUnitTest --tests app.orchard.tv.ui.tv.TvSlotGeometryTest
```

Expected: PASS。

- [ ] **Step 5: 编译主代码**

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: BUILD SUCCESSFUL。

---

### Task 2: LayoutStore 精确交换两个位置

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/apps/LayoutStore.kt:168-186`
- Test: `app/src/test/java/app/orchard/tv/apps/LayoutStoreTest.kt`

- [ ] **Step 1: 写失败的交换测试**

在 `LayoutStoreTest` 的 `moveOntoItselfOrAnUnknownAppChangesNothing` 后面加：

```kotlin
    @Test fun swapExchangesTwoAppsAndLeavesTheRest() {
        val s = store()
        s.swap("test.a", "test.d")
        assertEquals(listOf("test.d", "test.b", "test.c", "test.a"), s.home())
    }

    @Test fun swapKeepsFoldersAndHiddenAppsInPlace() {
        val s = store()
        val id = s.putInFolder("test.b", null, "Kids")
        s.hide("test.c")
        s.swap("test.a", "test.d")
        assertEquals(listOf("test.d", FOLDER_KEY_PREFIX + id, "test.a"), s.home())
        s.unhide("test.c")
        assertEquals(listOf("test.d", FOLDER_KEY_PREFIX + id, "test.c", "test.a"), s.home())
    }

    @Test fun swapOntoItselfOrAnUnknownAppChangesNothing() {
        val s = store()
        s.swap("test.b", "test.b")
        s.swap("test.b", "test.missing")
        s.swap("test.missing", "test.b")
        assertEquals(listOf("test.a", "test.b", "test.c", "test.d"), s.home())
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
./gradlew :app:testStandardDebugUnitTest --tests app.orchard.tv.apps.LayoutStoreTest
```

Expected: FAIL，`swap` 不存在。

- [ ] **Step 3: 实现 swap**

在 `LayoutStore` 的 `move` 方法前加：

```kotlin
    /**
     * Swaps [a] and [b] where they stand in Home's order. "Replace App" uses this: the chosen app
     * takes the tile's place and the tile's app goes where the chosen one was, so both stay on
     * Home and the list keeps its length.
     */
    fun swap(a: String, b: String) {
        if (a == b) return
        val list = order.toMutableList()
        val ia = list.indexOf(a)
        val ib = list.indexOf(b)
        if (ia < 0 || ib < 0) return
        list[ia] = b
        list[ib] = a
        order = list
        save()
    }
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
./gradlew :app:testStandardDebugUnitTest --tests app.orchard.tv.apps.LayoutStoreTest
```

Expected: PASS，原有 move/folder/hidden 测试也全部通过。

---

### Task 3: 天气数据加湿度与明日，解析可测

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/weather/Weather.kt`
- Test: `app/src/test/java/app/orchard/tv/weather/WeatherTest.kt`

- [ ] **Step 1: 写失败的解析测试**

新建 `app/src/test/java/app/orchard/tv/weather/WeatherTest.kt`：

```kotlin
package app.orchard.tv.weather

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Open-Meteo readings, including the fields added for the arclauncher-style weather block. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WeatherTest {
    @Test fun `today and tomorrow come from the daily arrays`() {
        val w = weatherFrom(
            JSONObject(
                """{
                  "current":{"temperature_2m":21.4,"weather_code":3,"is_day":1,"relative_humidity_2m":66},
                  "daily":{"weather_code":[3,61],"temperature_2m_max":[24.2,20.1],"temperature_2m_min":[15.8,12.4]}
                }""",
            ),
        )
        assertEquals(21, w.celsius)
        assertEquals(WeatherKind.Cloudy, w.kind)
        assertEquals(24, w.high)
        assertEquals(16, w.low)
        assertEquals(66, w.humidity)
        assertEquals(WeatherKind.Rain, w.tomorrow?.kind)
        assertEquals(20, w.tomorrow?.high)
        assertEquals(12, w.tomorrow?.low)
    }

    @Test fun `an old reading without humidity or tomorrow still parses`() {
        val w = weatherFrom(
            JSONObject(
                """{
                  "current":{"temperature_2m":-3.6,"weather_code":0,"is_day":0},
                  "daily":{"temperature_2m_max":[1.0],"temperature_2m_min":[-5.0]}
                }""",
            ),
        )
        assertEquals(-4, w.celsius)
        assertEquals(1, w.high)
        assertEquals(-5, w.low)
        assertNull(w.humidity)
        assertNull(w.tomorrow)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
./gradlew :app:testStandardDebugUnitTest --tests app.orchard.tv.weather.WeatherTest
```

Expected: FAIL，`weatherFrom` 和 `humidity`/`tomorrow` 不存在。

- [ ] **Step 3: 扩展数据模型**

把 `Weather.kt` 的数据类改成：

```kotlin
enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder }

/** Tomorrow's condition and range; null in a reading saved by an older version. */
data class TomorrowWeather(val kind: WeatherKind, val high: Int?, val low: Int?)

/**
 * [high] and [low] are today's, shown under the large clock; [humidity] and [tomorrow] join them
 * for the arclauncher-style weather block. All but the first three are null in a reading saved by
 * an older version.
 */
data class WeatherNow(
    val celsius: Int,
    val kind: WeatherKind,
    val day: Boolean,
    val high: Int? = null,
    val low: Int? = null,
    val humidity: Int? = null,
    val tomorrow: TomorrowWeather? = null,
)
```

- [ ] **Step 4: 提取解析函数并扩展请求**

把 `fetch()` 末尾改成：

```kotlin
        // weather_code is for today and tomorrow: the arclauncher-style block shows tomorrow too.
        val json = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day,relative_humidity_2m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&forecast_days=2&timezone=auto",
            ),
        )
        return weatherFrom(json)
    }

    /** Off the main thread, and with no Android calls, so it is unit tested as a plain function. */
    internal fun weatherFrom(json: JSONObject): WeatherNow {
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        fun day(key: String, index: Int) =
            daily?.optJSONArray(key)?.optDouble(index)?.takeIf { !it.isNaN() }?.roundToInt()
        val tomorrowCode = daily?.optJSONArray("weather_code")?.optInt(1, -1)?.takeIf { it >= 0 }
        return WeatherNow(
            celsius = current.getDouble("temperature_2m").roundToInt(),
            kind = kindOf(current.getInt("weather_code")),
            day = current.optInt("is_day", 1) == 1,
            high = day("temperature_2m_max", 0),
            low = day("temperature_2m_min", 0),
            humidity = current.optDouble("relative_humidity_2m", Double.NaN)
                .takeIf { !it.isNaN() }?.roundToInt(),
            tomorrow = tomorrowCode?.let {
                TomorrowWeather(kindOf(it), day("temperature_2m_max", 1), day("temperature_2m_min", 1))
            },
        )
    }
```

注意：`fetch()` 里原来的 `val current`、`val daily`、`fun today` 和 `return WeatherNow(...)` 全部删掉，只保留新的请求和 `return weatherFrom(json)`。

- [ ] **Step 5: 运行测试，确认通过**

```bash
./gradlew :app:testStandardDebugUnitTest --tests app.orchard.tv.weather.WeatherTest
```

Expected: PASS。

---

### Task 4: 把湿度和明日写进天气缓存

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/weather/Weather.kt:71-109`

- [ ] **Step 1: 读缓存时恢复新字段**

把 `init` 里构造 `WeatherNow` 的那段改成：

```kotlin
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            val tomorrowKind = runCatching {
                WeatherKind.valueOf(prefs.getString("tomorrow_kind", null) ?: "")
            }.getOrNull()
            if (kind != null) now = WeatherNow(
                prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true),
                high = prefs.getInt("high", NONE).takeIf { it != NONE },
                low = prefs.getInt("low", NONE).takeIf { it != NONE },
                humidity = prefs.getInt("humidity", NONE).takeIf { it != NONE },
                tomorrow = tomorrowKind?.let {
                    TomorrowWeather(
                        it,
                        prefs.getInt("tomorrow_high", NONE).takeIf { h -> h != NONE },
                        prefs.getInt("tomorrow_low", NONE).takeIf { l -> l != NONE },
                    )
                },
            )
        }
```

- [ ] **Step 2: 保存时写入新字段**

把 `refresh()` 里的 `prefs.edit { ... }` 改成：

```kotlin
                prefs.edit {
                    putLong("fetched_at", System.currentTimeMillis())
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                    putInt("high", reading.high ?: NONE)
                    putInt("low", reading.low ?: NONE)
                    putInt("humidity", reading.humidity ?: NONE)
                    val tomorrow = reading.tomorrow
                    if (tomorrow == null) {
                        remove("tomorrow_kind")
                        remove("tomorrow_high")
                        remove("tomorrow_low")
                    } else {
                        putString("tomorrow_kind", tomorrow.kind.name)
                        putInt("tomorrow_high", tomorrow.high ?: NONE)
                        putInt("tomorrow_low", tomorrow.low ?: NONE)
                    }
                }
```

- [ ] **Step 3: 编译并跑全部单测**

```bash
./gradlew :app:compileStandardDebugKotlin :app:testStandardDebugUnitTest
```

Expected: BUILD SUCCESSFUL，Task 1–3 的测试全部通过。

---

### Task 5: 首页大时钟改成 arclauncher 样式

> 前置：本任务用到 `weather_humidity`、`weather_tomorrow`、`weather_tomorrow_pending`。先执行 Task 6 的 Step 1，再回来执行本任务；Task 6 剩下的菜单文字可以稍后做。

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/ui/theme/Theme.kt:80-96`
- Modify: `app/src/main/java/app/orchard/tv/ui/home/HomeChrome.kt`（新增组件，保留 `ShelfClock`）
- Modify: `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt:772-793`

- [ ] **Step 1: 加首页时钟的字体样式**

在 `Theme.kt` 的 `Type.ClockDetail` 后面加：

```kotlin
    /** Home's large clock after arclauncher: a light time over a weather block, no date. */
    val HomeClock = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.W300,
        fontSize = 84.sp, letterSpacing = (-0.01).em, color = Palette.Label,
    )
    val HomeWeatherTemp = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W500,
        fontSize = 22.5.sp, color = Palette.Label,
    )
    val HomeWeatherDetail = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W500,
        fontSize = 15.sp, color = Palette.Label,
    )
    val HomeWeatherMeta = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W500,
        fontSize = 11.25.sp, color = Palette.Label,
    )
    val HomeWeatherPill = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W500,
        fontSize = 9.75.sp, color = Palette.Label,
    )
```

- [ ] **Step 2: 新增首页时钟组件**

在 `HomeChrome.kt` 的 `ShelfClock` 后面、`ShelfWeather` 前面加：

```kotlin
/** The weather capsule's colours, as arclauncher draws them. */
private val HumidityGlass = Color(0x99009988)
private val HumidityEdge = Color(0x6600E5CC)

/**
 * Home's large clock after arclauncher: the time in light digits, no date, and under it the
 * weather: icon, temperature and condition, the city with a humidity capsule, then tomorrow.
 * The screensaver keeps [ShelfClock]; this one only stands on Home.
 */
@Composable
fun HomeShelfClock(
    now: LocalDateTime,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    toSaver: () -> Float = { 0f },
    saving: Boolean = false,
    away: Boolean = false,
    /** The weather block as a control, where the remote leads from it; null keeps it plain. */
    weather: WeatherControls? = null,
) {
    val drift = heldDrift(saving, now)
    val shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), blurRadius = 7.5f)
    val dim = Color.White.copy(alpha = 0.9f)
    val reduced = LocalReducedMotion.current
    val asideGone by animateFloatAsState(
        if (away) 1f else 0f,
        if (reduced) snap() else if (away) tween(400, easing = Motion.EaseOut)
        else tween(400, delayMillis = CLOCK_BACK_MS * 7 / 10, easing = Motion.EaseOut),
        label = "home-clock-aside",
    )
    val aside = Modifier.graphicsLayer {
        alpha = 1f - asideGone
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        BasicText(
            timeText(now),
            style = Type.HomeClock.copy(shadow = shadow),
            modifier = Modifier
                .toSaverClock(toSaver, Type.HomeClock.fontSize, 0.dp, drift)
                .capBox(Type.HomeClock.fontSize),
        )
        Weather.now?.takeIf { AerialSettings.showWeather }?.let { w ->
            if (weather == null) {
                Box(aside) { HomeWeather(w, shadow, dim, Color.White) }
            } else Pressable(
                onClick = weather.onClick,
                focusRequester = weather.focusRequester,
                focusedScale = 1.04f,
                onKey = weatherKeys(weather),
                modifier = aside,
            ) { state ->
                Box(Modifier.focusHalo(state.focused)) {
                    HomeWeather(
                        w,
                        shadow,
                        if (state.focused) Palette.OnFocus.copy(alpha = 0.72f) else dim,
                        if (state.focused) Palette.OnFocus else Color.White,
                    )
                }
            }
        }
    }
}

/** The weather under [HomeShelfClock], laid out as arclauncher's HomeClockWeather. */
@Composable
private fun HomeWeather(
    w: WeatherNow,
    shadow: Shadow,
    detail: Color,
    main: Color,
) {
    val place = Weather.place?.takeIf { it.length <= 12 } ?: "--"
    Column(Modifier.padding(top = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyphFor(w), main, 60.dp, Modifier.padding(end = 15.dp))
            BasicText(
                "${w.celsius}°",
                style = Type.HomeWeatherTemp.copy(color = main, shadow = shadow),
            )
            BasicText(
                stringResource(conditionName(w.kind)),
                style = Type.HomeWeatherDetail.copy(color = detail, shadow = shadow),
                modifier = Modifier.padding(start = 9.dp),
            )
        }
        Row(Modifier.padding(top = 4.5.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(place, style = Type.HomeWeatherMeta.copy(color = detail, shadow = shadow))
            w.humidity?.let { humidity ->
                BasicText(
                    stringResource(R.string.weather_humidity, humidity),
                    style = Type.HomeWeatherPill.copy(color = detail),
                    modifier = Modifier
                        .padding(start = 9.dp)
                        .clip(RoundedCornerShape(50))
                        .background(HumidityGlass)
                        .border(1.dp, HumidityEdge, RoundedCornerShape(50))
                        .padding(horizontal = 7.5.dp, vertical = 1.5.dp),
                )
            }
        }
        val tomorrow = w.tomorrow
        Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                stringResource(R.string.weather_tomorrow),
                style = Type.HomeWeatherDetail.copy(color = detail, shadow = shadow),
            )
            if (tomorrow == null) {
                BasicText(
                    stringResource(R.string.weather_tomorrow_pending),
                    style = Type.HomeWeatherMeta.copy(color = detail, shadow = shadow),
                    modifier = Modifier.padding(start = 9.dp),
                )
            } else {
                GlyphIcon(
                    glyphFor(tomorrow.kind, day = true),
                    main,
                    13.5.dp,
                    Modifier.padding(start = 9.dp),
                )
                BasicText(
                    stringResource(conditionName(tomorrow.kind)),
                    style = Type.HomeWeatherMeta.copy(color = main, shadow = shadow),
                    modifier = Modifier.padding(start = 6.dp),
                )
                if (tomorrow.high != null && tomorrow.low != null) {
                    BasicText(
                        stringResource(R.string.weather_high_low, tomorrow.high, tomorrow.low),
                        style = Type.HomeWeatherMeta.copy(color = detail, shadow = shadow),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: 给 glyphFor 加按 kind/day 取图的重载**

把原来的 `glyphFor(w: WeatherNow)` 改成：

```kotlin
private fun glyphFor(w: WeatherNow): Glyph = glyphFor(w.kind, w.day)

private fun glyphFor(kind: WeatherKind, day: Boolean): Glyph = when (kind) {
    WeatherKind.Clear -> if (day) Glyph.Sun else Glyph.Moon
    WeatherKind.PartlyCloudy -> if (day) Glyph.CloudSun else Glyph.Cloud
    WeatherKind.Cloudy -> Glyph.Cloud
    WeatherKind.Fog -> Glyph.CloudFog
    WeatherKind.Rain -> Glyph.CloudRain
    WeatherKind.Snow -> Glyph.CloudSnow
    WeatherKind.Thunder -> Glyph.CloudBolt
}
```

- [ ] **Step 4: 首页改用新组件**

`HomeScreen.kt` 里原来调用 `ShelfClock(...)` 的那段（大时钟分支）只改函数名和参数名：

```kotlin
                    HomeShelfClock(
                        now = now,
                        alignEnd = atEnd,
                        weather = weatherControls,
                        modifier = Modifier
                            .align(if (atEnd) Alignment.TopEnd else Alignment.TopStart)
                            .padding(horizontal = metrics.margin)
                            .padding(top = metrics.clockY)
                            // No offscreen buffer while it fades: it would be the clock's capBox, and the
                            // Chinese, the icon and the shadows above and below it were cut off (#727).
                            .graphicsLayer {
                                alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        toSaver = { clockToSaver.value },
                        saving = vm.screensaverVisible,
                        away = carrying,
                    )
```

`Screensaver.kt` 不动，仍然调用 `ShelfClock`。

- [ ] **Step 5: 编译**

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: BUILD SUCCESSFUL。`Box`、`background`、`border`、`clip`、`RoundedCornerShape`、`Shadow`、`Color` 等 `HomeChrome.kt` 已导入，不需要新增 import。

---

### Task 6: 五种语言补齐新文字

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values-uk/strings.xml`
- Modify: `app/src/main/res/values-kk/strings.xml`

- [ ] **Step 1: 在每份 strings.xml 的 `weather_high_low` 后加三行**

英文：

```xml
    <string name="weather_humidity">Humidity %1$d%%</string>
    <string name="weather_tomorrow">Tomorrow</string>
    <string name="weather_tomorrow_pending">Tomorrow\'s weather is not in yet</string>
```

中文：

```xml
    <string name="weather_humidity">湿度 %1$d%%</string>
    <string name="weather_tomorrow">明日</string>
    <string name="weather_tomorrow_pending">明日天气待更新</string>
```

俄语：

```xml
    <string name="weather_humidity">Влажность %1$d%%</string>
    <string name="weather_tomorrow">Завтра</string>
    <string name="weather_tomorrow_pending">Погода на завтра ещё не загружена</string>
```

乌克兰语：

```xml
    <string name="weather_humidity">Вологість %1$d%%</string>
    <string name="weather_tomorrow">Завтра</string>
    <string name="weather_tomorrow_pending">Погода на завтра ще не завантажена</string>
```

哈萨克语：

```xml
    <string name="weather_humidity">Ылғалдылық %1$d%%</string>
    <string name="weather_tomorrow">Ертең</string>
    <string name="weather_tomorrow_pending">Ертеңгі ауа райы әлі жүктелмеді</string>
```

- [ ] **Step 2: 在每份 strings.xml 的 `menu_move` 后加一行**

英文：

```xml
    <string name="menu_replace">Replace App</string>
```

中文：

```xml
    <string name="menu_replace">替换应用</string>
```

俄语：

```xml
    <string name="menu_replace">Заменить приложение</string>
```

乌克兰语：

```xml
    <string name="menu_replace">Замінити застосунок</string>
```

哈萨克语：

```xml
    <string name="menu_replace">Қолданбаны ауыстыру</string>
```

- [ ] **Step 3: 在每份 strings.xml 的 `hidden_empty` 后加替换选择器的文字**

英文：

```xml
    <string name="replace_title">Replace App</string>
    <string name="replace_hint">Choose the app to put in this spot</string>
    <string name="replace_empty">No other app to put here</string>
```

中文：

```xml
    <string name="replace_title">替换应用</string>
    <string name="replace_hint">选择要放到这个位置的 App</string>
    <string name="replace_empty">没有可替换的应用</string>
```

俄语：

```xml
    <string name="replace_title">Заменить приложение</string>
    <string name="replace_hint">Выберите приложение для этого места</string>
    <string name="replace_empty">Нет другого приложения для этого места</string>
```

乌克兰语：

```xml
    <string name="replace_title">Замінити застосунок</string>
    <string name="replace_hint">Виберіть застосунок для цього місця</string>
    <string name="replace_empty">Немає іншого застосунку для цього місця</string>
```

哈萨克语：

```xml
    <string name="replace_title">Қолданбаны ауыстыру</string>
    <string name="replace_hint">Осы орынға қоятын қолданбаны таңдаңыз</string>
    <string name="replace_empty">Бұл орынға қоятын басқа қолданба жоқ</string>
```

- [ ] **Step 4: 检查五份占位符一致**

```bash
grep -n "weather_humidity\|replace_title\|menu_replace" app/src/main/res/values*/strings.xml
```

Expected: 五个目录都出现，`weather_humidity` 都是 `%1$d%%`。

---

### Task 7: 抽出共用 App 选择器，新增加「替换应用」面板

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/ui/home/Overlays.kt:331-401`

- [ ] **Step 1: 把 HiddenAppsSheet 的主体抽成 AppPickerSheet**

把整个 `HiddenAppsSheet` 替换成下面三个函数（`AnimatedVisibility` 的原主体原样搬进 `AppPickerSheet`）：

```kotlin
/** Hidden apps, to put back on Home. Reached from Control Center. */
@Composable
fun HiddenAppsSheet(
    visible: Boolean,
    apps: List<AppEntry>,
    onUnhide: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AppPickerSheet(
        visible = visible,
        title = stringResource(R.string.hidden_title),
        hint = stringResource(if (apps.isEmpty()) R.string.hidden_empty else R.string.hidden_hint),
        apps = apps,
        onPick = { onUnhide(it.key) },
        onDismiss = onDismiss,
    )
}

/** The app to put on a bottom tile, in the same list Hidden Apps uses. */
@Composable
fun ReplaceAppSheet(
    visible: Boolean,
    apps: List<AppEntry>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AppPickerSheet(
        visible = visible,
        title = stringResource(R.string.replace_title),
        hint = stringResource(if (apps.isEmpty()) R.string.replace_empty else R.string.replace_hint),
        apps = apps,
        onPick = { onPick(it.key) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun AppPickerSheet(
    visible: Boolean,
    title: String,
    hint: String,
    apps: List<AppEntry>,
    onPick: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(visible, apps.size) {
        if (visible) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = Motion.EaseOut)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150, easing = Motion.EaseOut)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(660.dp)
                    .consumeTaps()
                    // The canvas is 540 dp tall: past three rows the grid scrolls inside the sheet.
                    .heightIn(max = 480.dp)
                    .liquidGlass(28.dp)
                    .padding(28.dp),
            ) {
                BasicText(title, style = Type.LargeTitle)
                BasicText(
                    hint,
                    style = Type.Callout, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                )
                // Focus moving to a row out of view scrolls it in (Compose brings focused items into view).
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        // Room for the focused tile's 1.1x scale, so the edge rows are not clipped.
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    apps.chunked(4).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                            row.forEachIndexed { c, app ->
                                Pressable(
                                    onClick = { onPick(app) },
                                    focusRequester = if (r == 0 && c == 0) first else null,
                                    focusedScale = 1.1f,
                                ) { state ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        MiniTile(app, state.focused, Modifier.size(132.dp, 74.dp))
                                        Spacer(Modifier.height(8.dp))
                                        BasicText(
                                            app.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel),
                                            modifier = Modifier.width(132.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: 给 AppMenu 加替换项参数**

在 `AppMenu` 的参数列表里，`onMove` 后加：

```kotlin
    /** A bottom-row tile can be swapped for another app. */
    showReplace: Boolean = false,
    onReplace: (String) -> Unit = {},
```

在 `MenuPage.Main` 的 `else` 分支中，`MenuRow(Glyph.Move, ...)` 后面加：

```kotlin
                            if (showReplace) {
                                MenuRow(
                                    Glyph.Switches,
                                    stringResource(R.string.menu_replace),
                                    { onReplace(shownApp.key) },
                                )
                            }
```

- [ ] **Step 3: 编译**

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: BUILD SUCCESSFUL。此时 `AppMenu` 新参数都有默认值，旧调用仍能编译。

---

### Task 8: 接上替换状态、候选列表和焦点

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/HomeViewModel.kt:54-63`
- Modify: `app/src/main/java/app/orchard/tv/HomeViewModel.kt:542-568`（hide/unhide 之后）
- Modify: `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt:1044-1117`

- [ ] **Step 1: 加 Overlay 与 ViewModel 方法**

`HomeViewModel.kt` 的 `Overlay` 里加：

```kotlin
    /** The bottom tile whose app is being replaced; [key] is the tile's current app. */
    data class ReplaceApp(val key: String) : Overlay
```

在 `unhide` 后面加：

```kotlin
    /** Puts [newKey] on [slotKey]'s tile; the two swap places, so both stay on Home. */
    fun replaceApp(slotKey: String, newKey: String) {
        store.swap(slotKey, newKey)
        arrange()
        overlay = Overlay.None
    }

    /** The apps a bottom tile can be replaced with: the rest of Home, no folders or inputs. */
    fun replaceCandidates(columns: Int): List<AppEntry> =
        apps.drop(columns).filter { it.folder == null && it.source == null }
```

- [ ] **Step 2: HomeScreen 计算菜单是否显示替换项**

把 AppMenu 调用前的那段改成：

```kotlin
        val menu = overlay as? Overlay.AppMenu
        // A menu opened in a folder closes back to the folder.
        fun closeMenu() { vm.overlay = menu?.inFolder?.let { Overlay.Folder(it) } ?: Overlay.None }
        val menuApp = menu?.let { vm.entry(it.key) }
        val menuIndex = menu?.let { m -> apps.indexOfFirst { it.key == m.key } } ?: -1
        AppMenu(
            app = menuApp,
            anchor = menu?.anchor,
            inFolder = menu?.inFolder != null,
            folders = vm.folders(),
            onMove = { vm.startMove(it) },
            showReplace = menu?.inFolder == null && menuApp != null &&
                menuApp.folder == null && menuApp.source == null && menuIndex in 0 until columns,
            onReplace = { key -> vm.overlay = Overlay.ReplaceApp(key) },
```

- [ ] **Step 3: 加替换选择器**

在 `HiddenAppsSheet(...)` 调用后面加：

```kotlin
        val replacing = overlay as? Overlay.ReplaceApp
        ReplaceAppSheet(
            visible = replacing != null,
            apps = replacing?.let { vm.replaceCandidates(columns) }.orEmpty(),
            onPick = { newKey ->
                val slotKey = replacing?.key ?: return@ReplaceAppSheet
                indexAfterOverlay = apps.indexOfFirst { it.key == slotKey }
                focusAfterOverlay = newKey
                vm.replaceApp(slotKey, newKey)
            },
            onDismiss = { vm.overlay = Overlay.None },
        )
```

- [ ] **Step 4: 编译并跑全部单测**

```bash
./gradlew :app:compileStandardDebugKotlin :app:testStandardDebugUnitTest
```

Expected: BUILD SUCCESSFUL，全部单测通过。

---

### Task 9: 统一验证

**Files:**
- 无新增文件

- [ ] **Step 1: 跑单测**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:testStandardDebugUnitTest
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 跑 lint**

```bash
./gradlew :app:lintStandardRelease
```

Expected: BUILD SUCCESSFUL；不要新增 warning-as-error。

- [ ] **Step 3: 构建 release**

```bash
./gradlew assembleStandardRelease assembleGuardRelease
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 真机验证（没有验证前不要说「已完成」）**

在电视上用 release 版，执行：

```bash
adb shell cmd package compile -m speed -f app.orchard.tv
```

逐项确认：

- 小窗右边缘与底部最后一个磁贴卡片右边缘重合；
- 胶囊上沿距屏幕 14dp，小窗高度比改动前多约 14dp；
- 首页大时钟是细体时间，下方依次是图标/温度/条件、城市+湿度胶囊、明日；
- 时钟「左上 / 右上」两种设置都正常，右上时整块右对齐；
- 双排 4/5/6 列时两排磁贴都不被新时钟挡住；
- 长按底部第一排普通 App，菜单出现「替换应用」；第二排、文件夹、信号源没有这一项；
- 选择后该位置换成新 App，原 App 出现在下翻列表里新 App 原来的位置，其他底部磁贴不动；
- 屏保时钟、小时钟、控制中心的时间天气没有变化。

- [ ] **Step 5: 记录状态**

在 `docs/HANDOFF.md` 里只写「已实现、待真机验证」，不要写「已完成」。真机验证通过后再更新。
