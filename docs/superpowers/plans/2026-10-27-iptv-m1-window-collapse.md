# IPTV M1：小窗收束（去钉住 + 几何 + 缩回动画 + 冷启动焦点）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把首页直播槽收成「图标 ↔ 小窗」两态：焦点进入即预览播放，焦点离开即缩回图标并停播，OK 直接进全屏，小窗不再换台、不再开任何面板。

**Architecture:** 纯 reducer `TvStateMachine` 决定状态与副作用（`Play/Stop/EnterFullscreen`），`TvController` 只执行副作用并持有 Media3 播放器；几何由新的纯函数 `TvSlotGeometry` 从 Home 的网格度量算出，`TvWindow` 用一个 `Animatable<Float>` 在图标矩形和小窗矩形之间做一次 spring 插值（同一个 `Pressable`，不是两个组件）。

**Tech Stack:** Kotlin + Jetpack Compose（960×540dp 固定画布）、Media3 ExoPlayer + HLS、TextureView、JUnit4（JVM 单测）。

**依据：** `docs/superpowers/specs/2026-10-27-iptv-fullscreen-port-design.md` §2 §3（M2–M4 不在本计划内）

**约定：**
- 构建前先 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`（本机 Intel Mac 若不存在该路径，用 `/usr/local/opt/openjdk@17`）。
- 本计划按步提交（分里程碑推进已获用户同意）；只提交本任务改动的文件，绝不 `git add -A`。
- 真机验证一律 release 版 + `cmd package compile -m speed -f app.orchard.tv`；**没有在创维真机上跑过的步骤不得勾选完成**。
- 创维设备号 `192.168.1.21:5555`（IP 来自 `local/tvs.env`，不要写进版本库文件）。

**文件结构（本计划的改动面）：**

| 文件 | 动作 | 职责 |
|---|---|---|
| `app/src/main/java/app/orchard/tv/tv/TvStateMachine.kt` | 重写 | 四态 + 事件表 + 副作用（纯函数，可单测） |
| `app/src/main/java/app/orchard/tv/tv/TvController.kt` | 改 | 去 `pinned`/面板事件；`exitFullscreen()` 无参并把焦点交回网格；全屏内失败不再抢焦点 |
| `app/src/main/java/app/orchard/tv/ui/tv/TvSlotGeometry.kt` | 新建 | 图标/小窗两个矩形（纯函数 + `lerpDp`） |
| `app/src/main/java/app/orchard/tv/ui/home/HomeChrome.kt` | 改 | 抽出 `PillTop`/`PillHeight` 常量（几何要按胶囊定位，不能飘） |
| `app/src/main/java/app/orchard/tv/ui/tv/TvWindow.kt` | 重写 | 矩形插值动画、按键吞掉、图标字形 |
| `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt` | 改 | `screenW`、`plateShown`、传几何、冷启动一次焦点、退出全屏回格子 |
| `app/src/main/java/app/orchard/tv/MainActivity.kt` | 改 | `exitFullscreen()` 调用点、`closePanels()` 调用点 |
| `app/src/main/res/values*/strings.xml` | 改 | `tv_preview_hint` 改写、新增 `tv_all_sources_failed`（五语） |
| `app/src/test/.../TvStateMachineTest.kt`、`app/src/test/java/app/orchard/tv/ui/tv/TvSlotGeometryTest.kt` | 重写/新建 | 状态表与几何单测 |

---

### Task 1: 状态机去钉住（TDD）

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/tv/TvStateMachine.kt`（整文件重写）
- Test: `app/src/test/java/app/orchard/tv/tv/TvStateMachineTest.kt`（整文件重写）

- [ ] **Step 1: 重写单测，先让它红**

把 `app/src/test/java/app/orchard/tv/tv/TvStateMachineTest.kt` 整个替换为：

```kotlin
package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/** The window table from the spec: an icon that previews on focus, goes fullscreen on OK, and stops
 *  the moment focus leaves it. No pin, and no panels outside fullscreen. */
class TvStateMachineTest {
    private fun run(state: TvState, event: TvEvent) = TvStateMachine.reduce(state, event)

    private val playing = TvState(window = TvWindow.Preview, focused = true, playback = TvPlayback.Playing)
    private val full = TvState(window = TvWindow.Fullscreen, focused = true, playback = TvPlayback.Playing)

    @Test fun `the icon previews on focus`() {
        val result = run(TvState(), TvEvent.WindowGotFocus(hasChannel = true))
        assertEquals(TvWindow.Preview, result.state.window)
        assertEquals(TvEffect.Play, result.effect)
    }

    @Test fun `the icon with nothing to play is the placeholder`() {
        val result = run(TvState(), TvEvent.WindowGotFocus(hasChannel = false))
        assertEquals(TvWindow.Stopped, result.state.window)
        assertEquals(TvEffect.None, result.effect)
    }

    @Test fun `a preview that loses focus stops and collapses`() {
        val result = run(playing, TvEvent.WindowLostFocus)
        assertEquals(TvWindow.Collapsed, result.state.window)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `losing focus in fullscreen changes nothing`() {
        val result = run(full, TvEvent.WindowLostFocus)
        assertEquals(TvWindow.Fullscreen, result.state.window)
        assertEquals(TvEffect.None, result.effect)
    }

    @Test fun `OK on a preview goes straight fullscreen`() {
        val result = run(playing, TvEvent.WindowClicked)
        assertEquals(TvWindow.Fullscreen, result.state.window)
        assertEquals(TvEffect.EnterFullscreen, result.effect)
    }

    @Test fun `OK on the placeholder tries again`() {
        val result = run(TvState(window = TvWindow.Stopped, playback = TvPlayback.Error), TvEvent.WindowClicked)
        assertEquals(TvWindow.Preview, result.state.window)
        assertEquals(TvEffect.Play, result.effect)
    }

    @Test fun `back out of fullscreen is the icon, stopped`() {
        val result = run(full, TvEvent.FullscreenExited)
        assertEquals(TvWindow.Collapsed, result.state.window)
        assertEquals(false, result.state.focused)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `the screensaver takes a focused preview fullscreen instead`() {
        val result = run(playing, TvEvent.Idle(hasChannel = true))
        assertEquals(TvWindow.Fullscreen, result.state.window)
        assertEquals(TvEffect.EnterFullscreen, result.effect)
    }

    @Test fun `the screensaver stops an unfocused preview first`() {
        val result = run(TvState(window = TvWindow.Preview, focused = false, playback = TvPlayback.Playing), TvEvent.Idle(hasChannel = true))
        assertEquals(TvWindow.Collapsed, result.state.window)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `the screensaver leaves an idle home alone`() {
        for (window in listOf(TvWindow.Collapsed, TvWindow.Stopped)) {
            val result = run(TvState(window = window), TvEvent.Idle(hasChannel = true))
            assertEquals(window.toString(), window, result.state.window)
            assertEquals(TvEffect.None, result.effect)
        }
        val fullResult = run(full, TvEvent.Idle(hasChannel = true))
        assertEquals(TvWindow.Fullscreen, fullResult.state.window)
        assertEquals(TvEffect.None, fullResult.effect)
    }

    @Test fun `the screensaver without anything to play does not go fullscreen`() {
        val result = run(playing, TvEvent.Idle(hasChannel = false))
        assertEquals(TvWindow.Collapsed, result.state.window)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `an app, a manual screensaver and scrolling home away all stop the window`() {
        for (event in listOf<TvEvent>(TvEvent.AppLaunched, TvEvent.ManualScreensaver, TvEvent.LeftHome)) {
            for (window in listOf(TvWindow.Preview, TvWindow.Fullscreen)) {
                val result = run(TvState(window = window, focused = true, playback = TvPlayback.Playing), event)
                assertEquals("$event $window", TvWindow.Collapsed, result.state.window)
                assertEquals(TvEffect.Stop, result.effect)
            }
        }
        val idle = run(TvState(), TvEvent.AppLaunched)
        assertEquals(TvWindow.Collapsed, idle.state.window)
        assertEquals(TvEffect.None, idle.effect)
    }

    @Test fun `a failure in the window leaves the placeholder`() {
        val result = run(playing, TvEvent.PlaybackFailed)
        assertEquals(TvWindow.Stopped, result.state.window)
        assertEquals(TvPlayback.Error, result.state.playback)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `a failure in fullscreen stays in fullscreen`() {
        val result = run(full, TvEvent.PlaybackFailed)
        assertEquals(TvWindow.Fullscreen, result.state.window)
        assertEquals(TvPlayback.Error, result.state.playback)
        assertEquals(TvEffect.None, result.effect)
    }

    @Test fun `a stopped icon stays an icon when the screensaver takes fullscreen down`() {
        val result = run(TvState(window = TvWindow.Stopped, playback = TvPlayback.Error), TvEvent.Idle(hasChannel = false))
        assertEquals(TvWindow.Stopped, result.state.window)
        assertEquals(TvEffect.None, result.effect)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:testStandardDebugUnitTest --tests 'app.orchard.tv.tv.TvStateMachineTest' -q
```
Expected: 编译失败（`unresolved: Pinned` 之类），因为旧表还有 `TvWindow.Pinned`，而新测试断言 `FullscreenExited → Stop`。若编译通过则是断言先红：`expected STOP but was NONE`。

- [ ] **Step 3: 重写 reducer**

把 `app/src/main/java/app/orchard/tv/tv/TvStateMachine.kt` 整个替换为：

```kotlin
package app.orchard.tv.tv

enum class TvWindow { Collapsed, Preview, Fullscreen, Stopped }

enum class TvPlayback { Idle, Buffering, Playing, Paused, Error }

data class TvState(
    val window: TvWindow = TvWindow.Collapsed,
    val focused: Boolean = false,
    val playback: TvPlayback = TvPlayback.Idle,
)

sealed interface TvEvent {
    data class WindowGotFocus(val hasChannel: Boolean) : TvEvent
    data object WindowLostFocus : TvEvent
    data object WindowClicked : TvEvent
    data class Idle(val hasChannel: Boolean) : TvEvent
    data object FullscreenExited : TvEvent
    data object AppLaunched : TvEvent
    /** Home's grid scrolled past its shelf: the slot slid away with it, so live TV stops. */
    data object LeftHome : TvEvent
    data object ManualScreensaver : TvEvent
    data object PlaybackFailed : TvEvent
}

sealed interface TvEffect {
    data object None : TvEffect
    data object Play : TvEffect
    data object Stop : TvEffect
    data object EnterFullscreen : TvEffect
}

data class TvTransition(val state: TvState, val effect: TvEffect)

/**
 * The live-window state machine: a tile-sized icon, a preview that plays while it has focus,
 * fullscreen, and the not-playable placeholder. There is no pinned state: the small window is Home's
 * only live picture, and everything else - channel lists, source management, EPG - lives inside
 * fullscreen, which owns its own overlay stack.
 */
object TvStateMachine {
    fun reduce(state: TvState, event: TvEvent): TvTransition = when (event) {
        is TvEvent.WindowGotFocus -> when {
            state.window == TvWindow.Fullscreen -> keep(state)
            state.window == TvWindow.Collapsed && !event.hasChannel ->
                TvTransition(state.copy(window = TvWindow.Stopped, focused = true, playback = TvPlayback.Idle), TvEffect.None)
            state.window == TvWindow.Collapsed ->
                TvTransition(state.copy(window = TvWindow.Preview, focused = true), TvEffect.Play)
            else -> TvTransition(state.copy(focused = true), TvEffect.None)
        }
        // Focus is what makes the preview a preview. Losing it is not a pause: the decoder is Home's
        // wallpaper's again, and the slot shrinks to a tile.
        TvEvent.WindowLostFocus -> when (state.window) {
            TvWindow.Preview -> TvTransition(
                state.copy(window = TvWindow.Collapsed, focused = false, playback = TvPlayback.Idle),
                TvEffect.Stop,
            )
            else -> TvTransition(state.copy(focused = false), TvEffect.None)
        }
        TvEvent.WindowClicked -> when (state.window) {
            TvWindow.Preview -> TvTransition(state.copy(window = TvWindow.Fullscreen), TvEffect.EnterFullscreen)
            TvWindow.Stopped, TvWindow.Collapsed ->
                TvTransition(state.copy(window = TvWindow.Preview, focused = true), TvEffect.Play)
            else -> keep(state)
        }
        /**
         * The screensaver timer reached Home. The caller starts the screensaver unless the window
         * answered by going fullscreen ([TvEffect.EnterFullscreen]).
         */
        is TvEvent.Idle -> when {
            state.window == TvWindow.Fullscreen -> keep(state)
            state.window == TvWindow.Preview && state.focused && event.hasChannel ->
                TvTransition(state.copy(window = TvWindow.Fullscreen), TvEffect.EnterFullscreen)
            state.window == TvWindow.Preview ->
                TvTransition(state.copy(window = TvWindow.Collapsed, focused = false, playback = TvPlayback.Idle), TvEffect.Stop)
            else -> keep(state)
        }
        // Back from fullscreen is the grid, not a preview: the icon waits for focus, so stop cleanly.
        TvEvent.FullscreenExited -> TvTransition(
            state.copy(window = TvWindow.Collapsed, focused = false, playback = TvPlayback.Idle),
            TvEffect.Stop,
        )
        /**
         * Home's shelf scrolled away, something else took the screen, or the user asked for the
         * screensaver while fullscreen: the slot goes up with the grid and is off the canvas by then.
         */
        TvEvent.AppLaunched, TvEvent.LeftHome, TvEvent.ManualScreensaver -> when (state.window) {
            TvWindow.Preview, TvWindow.Fullscreen -> TvTransition(
                state.copy(window = TvWindow.Collapsed, focused = false, playback = TvPlayback.Idle),
                TvEffect.Stop,
            )
            else -> keep(state)
        }
        // Out of the window there is nothing left to show. In fullscreen the layer stays and says so.
        TvEvent.PlaybackFailed -> when (state.window) {
            TvWindow.Fullscreen -> TvTransition(state.copy(playback = TvPlayback.Error), TvEffect.None)
            else -> TvTransition(state.copy(window = TvWindow.Stopped, playback = TvPlayback.Error), TvEffect.Stop)
        }
    }

    private fun keep(state: TvState) = TvTransition(state, TvEffect.None)
}
```

- [ ] **Step 4: 跑状态机单测**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'app.orchard.tv.tv.TvStateMachineTest' -q
```
Expected: BUILD SUCCESSFUL（该测试类 15 个用例全绿）。`tv/TvController.kt` 此时会编译失败（仍引用 `Pinned`/`PanelsOpened`），这是 Task 2 的内容 —— 所以本步只在该类单独编译；若 Gradle 因主源码编译失败而跑不起来，直接进 Task 2，两个任务一起转绿。

- [ ] **Step 5: Commit（与 Task 2 一起提交亦可）**

```bash
git add app/src/main/java/app/orchard/tv/tv/TvStateMachine.kt app/src/test/java/app/orchard/tv/tv/TvStateMachineTest.kt
git commit -m "IPTV M1: the window has no pinned state - focus in plays, focus out stops"
```

---

### Task 2: TvController 去掉钉住与面板副作用

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/tv/TvController.kt`
- Modify: `app/src/main/java/app/orchard/tv/MainActivity.kt:484,646`

- [ ] **Step 1: 删掉 `pinned` 属性**（`TvController.kt:50`）

```kotlin
    val pinned get() = window == TvWindow.Pinned
```
整行删除。`val active`（:52）保留不动。

- [ ] **Step 2: 把「焦点交回小窗」改成「焦点交回网格」**（`TvController.kt:46-48`、`:259-263`）

属性注释与名字一起换：

```kotlin
    /** Bumped when the slot let go of the screen, so Home puts focus on the top row's last tile. */
    var releaseFocus by mutableStateOf(0); private set
```
私有方法 `askFocus()` 整体替换为：

```kotlin
    /** Ask Home to take focus back onto the grid: the slot itself never grabs it. */
    private fun releaseFocus() {
        DiagLog.i("tv: handing focus back to Home")
        releaseFocus++
    }
```

- [ ] **Step 3: 面板只置标志位，不再有状态机事件**（`TvController.kt:200-224`）

```kotlin
    /** The channel list is fullscreen's own: the window state does not change while it is up. */
    fun openChannelList() {
        channelListOpen = true
    }

    fun openCityPicker() {
        cityPickerOpen = true
    }

    /**
     * Closing the channel list leaves focus to whoever asked for it (fullscreen re-takes its own), and
     * the city picker to Home's weather line, which watches [cityPickerOpen].
     */
    fun closePanels() {
        channelListOpen = false
        cityPickerOpen = false
    }
```

- [ ] **Step 4: 退出全屏只回图标**（`TvController.kt:194-198`）

```kotlin
    fun exitFullscreen() {
        apply(TvEvent.FullscreenExited)
        player.resume()
        releaseFocus()
    }
```

- [ ] **Step 5: 全屏里的失败不再抢焦点**（`TvController.kt:292-310`）

`retry()` 的尾部 `else` 分支替换为（全屏里留在原地并报文案，小窗里落回占位图标）：

```kotlin
            } else {
                if (window == TvWindow.Fullscreen) showOsd(context.getString(R.string.tv_all_sources_failed))
                // Nothing may suspend after this: the Stop effect cancels this very job.
                apply(TvEvent.PlaybackFailed)
            }
```
并在文件顶部的 import 里加 `import app.orchard.tv.R`。

- [ ] **Step 6: `onManualScreensaver` 去掉 refocus 参数**（`TvController.kt:236-239`）

```kotlin
    fun onManualScreensaver() {
        closePanels()
        apply(TvEvent.ManualScreensaver)
    }
```

- [ ] **Step 7: 全屏频道列表选台不再改窗口状态**（`TvController.kt:170-184`）

`playChannel` 里这一段整块删除（列表只在全屏里可达，窗口状态不该由它决定）：

```kotlin
        if (window == TvWindow.Collapsed || window == TvWindow.Stopped) {
            state = state.copy(window = TvWindow.Preview, focused = true)
            window = TvWindow.Preview
            focused = true
        }
```
同时把末尾 `if (channelListOpen) closePanels()` 保留。

- [ ] **Step 8: 改调用点**

`MainActivity.kt:484`：`tv.closePanels(refocus = false)` → `tv.closePanels()`
`MainActivity.kt:646`：`tv.exitFullscreen(focusWindow = false)` → `tv.exitFullscreen()`

- [ ] **Step 9: 跑单测 + 编译**

```bash
./gradlew :app:testStandardDebugUnitTest :app:assembleStandardDebug -q
```
Expected: BUILD SUCCESSFUL。此时 `TvWindow.kt` 仍引用 `tv.pinned` 会失败 —— 那是 Step 10 的最小修补（Task 4 才做完整重写）。

- [ ] **Step 10: 先让旧 `TvWindow` 编译通过**

`TvWindow.kt` 里三处引用按下面替换（只删钉住相关，其余下一任务重写）：
- `:54` → `val expanded = tv.window == TvWindow.Preview`
- `:110` → `Color.White.copy(alpha = if (state.focused) 0.9f else 0.18f),`
- `:125-134`（`if (expanded && tv.pinned) { ... }` 整个 Box 块）删除

```bash
./gradlew :app:testStandardDebugUnitTest :app:assembleStandardDebug -q
```
Expected: BUILD SUCCESSFUL；`TvStateMachineTest` 15 个用例全绿。

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/app/orchard/tv/tv/TvController.kt app/src/main/java/app/orchard/tv/MainActivity.kt app/src/main/java/app/orchard/tv/ui/tv/TvWindow.kt app/src/test/java/app/orchard/tv/tv/TvStateMachineTest.kt app/src/main/java/app/orchard/tv/tv/TvStateMachine.kt
git commit -m "IPTV M1: drop pin and panel effects from the controller; fullscreen exits to the icon"
```

---

### Task 3: 槽几何纯函数

**Files:**
- Create: `app/src/main/java/app/orchard/tv/ui/tv/TvSlotGeometry.kt`
- Modify: `app/src/main/java/app/orchard/tv/ui/home/HomeChrome.kt:670` 附近
- Test: `app/src/test/java/app/orchard/tv/ui/tv/TvSlotGeometryTest.kt`

- [ ] **Step 1: 写失败的几何单测**

```kotlin
package app.orchard.tv.ui.tv

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The slot's two resting places, lined up with Home's glass plate, its tiles and the status pill. */
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

    @Test fun `the window lines up with the plate and keeps the screen aspect`() {
        val g = canvas()
        assertEquals(930f, g.windowRight.value, 0.05f)
        assertEquals(92f, g.windowTop.value, 0.05f)
        assertEquals(157.7f, g.windowHeight.value, 0.05f)
        assertEquals(280.35f, g.windowWidth.value, 0.05f)
    }

    @Test fun `the icon is the last tile`() {
        val g = canvas()
        assertEquals(904f, g.iconRight.value, 0.05f)
        assertEquals(92f, g.iconTop.value, 0.05f)
        assertEquals(147.2f, g.iconWidth.value, 0.05f)
        assertEquals(82.8f, g.iconHeight.value, 0.05f)
    }

    @Test fun `a taller row gives a taller window`() {
        // One-row layout: the row sits low, so the window grows between the pill and the plate.
        val g = canvas(topRowY = 407.2f)
        assertEquals(265.2f, g.windowHeight.value, 0.05f)
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

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'app.orchard.tv.ui.tv.TvSlotGeometryTest' -q
```
Expected: 编译失败 `unresolved reference: tvSlotGeometry`。

- [ ] **Step 3: 抽出胶囊尺寸常量**（`HomeChrome.kt`）

`HomeChrome.kt:340` 的 `PlatePadH`/`PlatePadV` 旁边加：

```kotlin
/** The status pill's place: Home hangs it here, and the live window's top gap is measured from it. */
val PillTop = 28.dp
val PillHeight = 34.dp
```
把 `HomeChrome.kt` 里 `StatusPill` 的 `.height(34.dp)` 改成 `.height(PillHeight)`；把 `HomeScreen.kt:746` 的 `.padding(top = 28.dp, end = metrics.margin - 14.dp)` 改成 `.padding(top = PillTop, end = metrics.margin - 14.dp)`（`HomeScreen.kt` 已 `import app.orchard.tv.ui.home.*` 同包，无需新增 import）。

- [ ] **Step 4: 写几何函数**

```kotlin
package app.orchard.tv.ui.tv

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orchard.tv.ui.home.PillHeight
import app.orchard.tv.ui.home.PillTop
import app.orchard.tv.ui.home.PlatePadH
import app.orchard.tv.ui.home.PlatePadV

/**
 * The live slot's two resting places, in the canvas Home lays its grid in. The window's right edge
 * lines up with the glass plate's (with no plate, with the last tile's), its bottom and top sit one
 * screen-edge gap off the plate and the status pill, and its width follows the screen's aspect ratio.
 * The icon is a tile: the last tile of the top row's size and right edge.
 */
data class TvSlotGeometry(
    val screenW: Dp,
    val windowTop: Dp,
    val windowRight: Dp,
    val windowWidth: Dp,
    val windowHeight: Dp,
    val iconTop: Dp,
    val iconRight: Dp,
    val iconWidth: Dp,
    val iconHeight: Dp,
)

/** The window is never flatter than this, whatever layout switch pushed the top row up. */
private val MinSlotHeight = 60.dp

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
    val windowRight = if (plate) tileRight + PlatePadH else tileRight
    val gap = screenW - windowRight
    val rowTop = if (plate) topRowY - PlatePadV else topRowY
    val top = PillTop + PillHeight + gap
    val bottom = (rowTop - gap).coerceAtLeast(top + MinSlotHeight)
    val height = bottom - top
    return TvSlotGeometry(
        screenW = screenW,
        windowTop = top,
        windowRight = windowRight,
        windowWidth = height * screenW / screenH,
        windowHeight = height,
        iconTop = top,
        iconRight = tileRight,
        iconWidth = tileW,
        iconHeight = tileH,
    )
}

/** Interpolate two dp lengths; the slot morph drives all four edges from one fraction. */
fun lerpDp(from: Dp, to: Dp, fraction: Float): Dp = from + (to - from) * fraction
```

- [ ] **Step 5: 跑测试确认通过**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'app.orchard.tv.ui.tv.TvSlotGeometryTest' -q
```
Expected: BUILD SUCCESSFUL，5 个用例全绿。若 `106.67` 因浮点差失败，把断言 delta 放到 `0.1f`（不要改公式）。

- [ ] **Step 6: 全量单测 + 编译**

```bash
./gradlew :app:testStandardDebugUnitTest :app:assembleStandardDebug -q
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/orchard/tv/ui/tv/TvSlotGeometry.kt app/src/test/java/app/orchard/tv/ui/tv/TvSlotGeometryTest.kt app/src/main/java/app/orchard/tv/ui/home/HomeChrome.kt app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt
git commit -m "IPTV M1: slot geometry from Home's own metrics - plate-aligned window at screen aspect"
```

---

### Task 4: `TvWindow` 重写（一次矩形插值）

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/ui/tv/TvWindow.kt`（整文件重写）

- [ ] **Step 1: 整文件替换**

```kotlin
package app.orchard.tv.ui.tv

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.tv.TvController
import app.orchard.tv.tv.TvPlayback
import app.orchard.tv.tv.TvWindow
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

/**
 * The live slot on Home: a tile-sized icon at rest, the screen-shaped window while it previews. One
 * control morphing between the two rectangles, not two controls swapping.
 */
@Composable
fun TvWindow(
    tv: TvController,
    geometry: TvSlotGeometry,
    focusRequester: FocusRequester,
    enabled: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (tv.window == TvWindow.Fullscreen) return

    val channel = tv.current?.let { tv.channels.getOrNull(it.first) }
    val name = channel?.name.orEmpty()
    val source = tv.current?.second
    val expanded = tv.window == TvWindow.Preview
    val reduced = LocalReducedMotion.current
    var focusSeen by remember { mutableStateOf(false) }

    val shape = remember { Animatable(0f) }
    LaunchedEffect(expanded, reduced) {
        val target = if (expanded) 1f else 0f
        if (reduced) shape.snapTo(target)
        else shape.animateTo(target, spring(dampingRatio = 1f, stiffness = 520f))
    }
    val t = shape.value
    val top = lerpDp(geometry.iconTop, geometry.windowTop, t)
    val right = lerpDp(geometry.iconRight, geometry.windowRight, t)
    val width = lerpDp(geometry.iconWidth, geometry.windowWidth, t)
    val height = lerpDp(geometry.iconHeight, geometry.windowHeight, t)

    Pressable(
        onClick = { tv.onClick() },
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = if (expanded) 1.01f else 1.06f,
        modifier = modifier
            .padding(top = top, end = geometry.screenW - right)
            .size(width, height),
        onKey = { event ->
            if (event.type != KeyEventType.KeyDown) return@Pressable false
            when (event.nativeKeyEvent.keyCode) {
                // The slot has nothing inside it to reach: Left hands over to the weather line and
                // every other edge is swallowed, so no key retunes the channel or opens a panel behind
                // the user's back. That is all fullscreen's job now.
                NativeKeyEvent.KEYCODE_DPAD_LEFT -> { onLeft?.invoke(); true }
                NativeKeyEvent.KEYCODE_DPAD_RIGHT -> true
                NativeKeyEvent.KEYCODE_MENU -> true
                NativeKeyEvent.KEYCODE_DPAD_UP -> { onUp(); true }
                NativeKeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                else -> false
            }
        },
    ) { state ->
        LaunchedEffect(state.focused) {
            if (state.focused) {
                focusSeen = true
                tv.onWindowFocus(true)
            } else if (focusSeen) {
                tv.onWindowFocus(false)
            }
        }
        // Going away with focus still on it (the screensaver took the screen): the controller must
        // not keep a window it can no longer see as the focused one.
        DisposableEffect(Unit) {
            onDispose { if (focusSeen) tv.onWindowFocus(false) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.35f + 0.2f * t))
                .border(
                    if (state.focused) 2.dp else 1.dp,
                    Color.White.copy(alpha = if (state.focused) 0.9f else 0.18f),
                    RoundedCornerShape(20.dp),
                ),
        ) {
            // The picture rides the morph; the glyph comes up as the rectangle lands on a tile.
            if (t > 0.001f) {
                TvSurface(tv, Modifier.fillMaxSize(), cornerRadius = 20.dp)
            }
            GlyphIcon(
                if (tv.window == TvWindow.Stopped || tv.playback == TvPlayback.Error) Glyph.Offline else Glyph.Play,
                Color.White.copy(alpha = 0.85f),
                34.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = 1f - t },
            )

            if (t > 0.01f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = t }) {
                    if (tv.playback == TvPlayback.Buffering) {
                        BasicText(
                            stringResource(R.string.tv_loading),
                            style = Type.Callout.copy(color = Color.White.copy(alpha = 0.85f)),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    if (name.isNotEmpty()) {
                        BasicText(
                            name,
                            style = Type.Callout.copy(color = Color.White),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    tv.osd?.let { text ->
                        BasicText(
                            if (source?.label.isNullOrBlank()) text else "$text · ${source.label}",
                            style = Type.Callout.copy(color = Color.White),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Palette.Focus.copy(alpha = 0.9f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    if (tv.window == TvWindow.Preview && state.focused && tv.osd == null) {
                        BasicText(
                            stringResource(R.string.tv_preview_hint),
                            style = Type.Callout.copy(color = Color.White.copy(alpha = 0.8f)),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.4f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // The placeholder says so under the icon, where it fits: it used to draw at the window's
            // bottom edge even when collapsed, so the words hung out of the tile.
            if (t < 0.5f && tv.window == TvWindow.Stopped) {
                BasicText(
                    stringResource(R.string.tv_no_source),
                    style = Type.Callout.copy(color = Color.White.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .graphicsLayer { alpha = 1f - t * 2f },
                )
            }
        }
    }
}
```

- [ ] **Step 2: 编译（此时 `HomeScreen` 调用点会报缺参数）**

```bash
./gradlew :app:compileStandardDebugKotlin -q
```
Expected: 失败在 `HomeScreen.kt:794`（`TvWindow` 少了 `geometry` 实参）—— Task 5 修。

- [ ] **Step 3: Commit（与 Task 5 合并提交亦可）**

```bash
git add app/src/main/java/app/orchard/tv/ui/tv/TvWindow.kt
git commit -m "IPTV M1: the slot morphs between a tile and the window; keys leave tuning to fullscreen"
```

---

### Task 5: `HomeScreen` 接线（几何、冷启动一次焦点、回桌面焦点）

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt:123-158`（GridMetrics）、`:226-231`（plateShown）、`:333-345`（首次焦点）、`:375-382`（焦点归还）、`:544`（plate）、`:793-809`（槽）

- [ ] **Step 1: 让 `GridMetrics` 交出画布宽度**（`HomeScreen.kt:162` 旁）

```kotlin
    val screenW = screenW
    val screenH = screenH
```

- [ ] **Step 2: 抽出 `plateShown`**（`HomeScreen.kt:226-231` 之后，紧跟 `slotShown`）

```kotlin
    // The glass plate under the top row decides which edge the live window lines its right side up with.
    val plateShown = apps.isNotEmpty() && AerialSettings.homePlate
```
并把 `HomeScreen.kt:544` 的 `val plate = if (apps.isEmpty() || !AerialSettings.homePlate) null else with(density) {` 改成 `val plate = if (!plateShown) null else with(density) {`。

- [ ] **Step 3: 冷启动只把焦点给小窗一次**（`HomeScreen.kt:333-345` 整块替换）

```kotlin
    // First focus once the apps are in; afterwards, put focus back wherever it was when an overlay
    // closes. The very first time Home is whole, the live slot takes that focus instead, once: the
    // day's TV comes up by itself. Coming back from an app, waking the screensaver or scrolling back
    // to the top row all hand focus to a tile, as before.
    var bootFocusGiven by remember { mutableStateOf(false) }
    LaunchedEffect(vm.loaded, overlay, tv.loaded) {
        if (!vm.loaded || overlay != Overlay.None) return@LaunchedEffect
        // TvSources.load never throws, so this wait is only the file and asset read.
        if (!bootFocusGiven && slotShown && !tv.loaded) return@LaunchedEffect
        withFrameNanos { }
        val wanted = focusAfterOverlay
        focusAfterOverlay = null
        if (wanted != null) { focusedKey = wanted; pillFocused = false }
        if (!bootFocusGiven) {
            bootFocusGiven = true
            if (wanted == null && slotShown && tv.current != null) {
                runCatching { windowRequester.requestFocus() }
                return@LaunchedEffect
            }
        }
        val index = apps.indexOfFirst { it.key == focusedKey }
        // A tile that went while the overlay was up (hidden, put in a folder, a folder ungrouped)
        // leaves focus where it was.
        if (pillFocused && focusedKey != null) runCatching { pillRequester.requestFocus() }
        else focusApp(if (index >= 0) index else if (wanted != null) indexAfterOverlay else lastFocusedIndex)
    }
```

- [ ] **Step 4: 归还焦点给格子**（`HomeScreen.kt:375-382` 整块替换）

```kotlin
    // The slot let go of the screen: Back out of fullscreen. Home takes the focus onto the tile the
    // slot's own Up/Down leads to, so the remote never lands on nothing.
    LaunchedEffect(tv.releaseFocus) {
        if (tv.releaseFocus == 0 || !onHome) return@LaunchedEffect
        withFrameNanos { }
        focusApp(topRowEnd)
    }
```

- [ ] **Step 5: 槽改用几何**（`HomeScreen.kt:792-809` 整块替换）

```kotlin
        // The live slot, under the pill and in line with the plate: an icon until focus opens it.
        if (slotShown) {
            val geometry = tvSlotGeometry(
                screenW = metrics.screenW,
                screenH = metrics.screenH,
                margin = metrics.margin,
                pitchX = metrics.pitchX,
                tileW = metrics.tileW,
                tileH = metrics.tileH,
                columns = COLUMNS,
                topRowY = metrics.topRowY,
                plate = plateShown,
            )
            TvWindow(
                tv = tv,
                geometry = geometry,
                focusRequester = windowRequester,
                enabled = onHome,
                onUp = { runCatching { pillRequester.requestFocus() } },
                onDown = { focusApp(topRowEnd) },
                onLeft = if (weatherControls != null) { { runCatching { weatherRequester.requestFocus() } } } else null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Part of Home, not a layer over it: it goes up with the grid (see the offset the
                    // grid itself takes), so scrolling down takes the icon and the window away.
                    .offset { IntOffset(0, -scroll.value.roundToInt()) }
                    .graphicsLayer { alpha = if (overlay is Overlay.ControlCenter) 0f else 1f },
            )
        }
```
（原来的 `.padding(top = metrics.clockY, end = metrics.margin)` 由 `TvWindow` 内部按几何加，必须删掉。）

- [ ] **Step 6: 天气行的 RIGHT 仍指向小窗**（`HomeScreen.kt:325-331` 不动）；`TvWindow` 的 LEFT 已改回天气行，`onLeft` 传法不变。

- [ ] **Step 7: 编译 + 全量单测**

```bash
./gradlew :app:compileStandardDebugKotlin :app:testStandardDebugUnitTest -q
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt app/src/main/java/app/orchard/tv/ui/tv/TvWindow.kt
git commit -m "IPTV M1: Home wires the slot's geometry, gives it the cold-start focus once"
```

---

### Task 6: 五语文案

**Files:**
- Modify: `app/src/main/res/values/strings.xml:253`、`values-zh/strings.xml:252`、`values-ru/strings.xml:245`、`values-uk/strings.xml:245`、`values-kk/strings.xml:245`

- [ ] **Step 1: 改写 `tv_preview_hint`（不再提「钉住」）**

| 文件 | 新值 |
|---|---|
| `values/strings.xml` | `OK: fullscreen` |
| `values-zh/strings.xml` | `按 OK 全屏播放` |
| `values-ru/strings.xml` | `OK: полный экран` |
| `values-uk/strings.xml` | `OK: на весь екран` |
| `values-kk/strings.xml` | `OK: толық экран` |

- [ ] **Step 2: 新增全屏里「所有源都失败」的 OSD 文案**

五个文件都在 `tv_fullscreen_hint` 那行的下面插入同一个键（无占位符，五语各一份）：

| 文件 | 行内容 |
|---|---|
| `values/strings.xml` | `<string name="tv_all_sources_failed">All sources failed</string>` |
| `values-zh/strings.xml` | `<string name="tv_all_sources_failed">所有源均失败</string>` |
| `values-ru/strings.xml` | `<string name="tv_all_sources_failed">Все источники не сработали</string>` |
| `values-uk/strings.xml` | `<string name="tv_all_sources_failed">Усі джерела не спрацювали</string>` |
| `values-kk/strings.xml` | `<string name="tv_all_sources_failed">Барлық дереккөздер сәтсіз</string>` |

- [ ] **Step 3: `tv_fullscreen_hint` 本阶段不改**

M1 的全屏键位仍是「OK／MENU 开频道列表 · 返回退出」，现有文案对得上。M2 重写全屏时按 spec §4 键位摘要一起改，避免此处先撒谎。

- [ ] **Step 4: 编译校验 XML 与五语齐全**

```bash
grep -c "tv_all_sources_failed" app/src/main/res/values*/strings.xml
./gradlew :app:assembleStandardDebug -q
```
Expected: 五个文件各输出 `1`；BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ru/strings.xml app/src/main/res/values-uk/strings.xml app/src/main/res/values-kk/strings.xml
git commit -m "IPTV M1: the window's hint says fullscreen; add the all-sources-failed OSD"
```

---

### Task 7: 真机验证（创维 8N07_J9000，必须）

**Files:** 无代码改动；结论写进 Task 8。

- [ ] **Step 1: 出 release 包并装机**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleGuardRelease :app:testStandardDebugUnitTest -q
adb connect 192.168.1.21:5555
adb -s 192.168.1.21:5555 install -r app/build/outputs/apk/guard/release/app-guard-release.apk
adb -s 192.168.1.21:5555 shell "cmd package compile -m speed -f app.orchard.tv"
adb -s 192.168.1.21:5555 shell am force-stop app.orchard.tv
adb -s 192.168.1.21:5555 shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez diag true
```
Expected: `Success`，`cmd package compile` 无输出即成功。

- [ ] **Step 2: 冷启动自动预览**

重启 launcher（`am force-stop` 后按 Home 或 `monkey -p app.orchard.tv 1`），30 秒内截屏：
```bash
adb -s 192.168.1.21:5555 shell input keyevent 3
adb -s 192.168.1.21:5555 exec-out screencap -p > /tmp/m1-boot.png
adb -s 192.168.1.21:5555 shell tail -40 /sdcard/Android/data/app.orchard.tv/files/aerial-diag.log
```
Expected 图上：右上区域是 280×158dp 的窗口（右边缘和毛玻璃卡片右边缘齐、上边距胶囊一个屏边距），里面有画面。日志里依次有 `WindowGotFocus → Preview, effect=Play`、`tv: channel[N] <频道名>`。

- [ ] **Step 3: 离焦缩回 + 停播**

按一次 DOWN（20）进网格，等 1 秒截屏，再读日志。
```bash
adb -s 192.168.1.21:5555 shell input keyevent 20
adb -s 192.168.1.21:5555 exec-out screencap -p > /tmp/m1-icon.png
adb -s 192.168.1.21:5555 shell tail -20 /sdcard/Android/data/app.orchard.tv/files/aerial-diag.log
```
Expected 日志：`tv: WindowLostFocus → Collapsed, effect=Stop`。Expected 图：图标尺寸 147×83dp、右边缘与最后一个磁贴对齐、里面是播放字形，**没有**「按 OK 全屏播放」或频道名残留。肉眼确认缩回是连续动画而不是硬切。

- [ ] **Step 4: 小窗按键不换台、不开面板**

焦点回小窗（先 UP 19 再 RIGHT 22 或直接从天气行 RIGHT），依次按 LEFT(21)/RIGHT(22)/MENU(82) 各两次，再按 UP(19)、DOWN(20)。
Expected 日志：**没有**任何 `WindowClicked`/`channel[` 变化（频道号不变），LEFT 之后焦点到天气行、UP 到胶囊、DOWN 回网格末位；MENU 无任何反应。

- [ ] **Step 5: OK 进全屏，BACK 回图标并落到格子**

```bash
adb -s 192.168.1.21:5555 shell input keyevent 23   # OK
adb -s 192.168.1.21:5555 exec-out screencap -p > /tmp/m1-full.png
adb -s 192.168.1.21:5555 shell input keyevent 4    # BACK
adb -s 192.168.1.21:5555 exec-out screencap -p > /tmp/m1-back.png
adb -s 192.168.1.21:5555 shell tail -30 /sdcard/Android/data/app.orchard.tv/files/aerial-diag.log
```
Expected：全屏有画面（TextureView 出图，不是黑屏有声）；日志 `WindowClicked → Fullscreen, effect=EnterFullscreen` → `tv: handing focus back to Home` → `FullscreenExited → Collapsed, effect=Stop`；回到桌面后焦点在顶行最后一个磁贴（截图可见放大/描边），槽是图标。

- [ ] **Step 6: 从全屏里开频道列表、选台、退出**

全屏内按 OK(23) 开列表 → 方向键选一个频道 → OK 播出 → BACK 退出全屏。
Expected：列表浮在全屏上，选台后画面切过去；退出后仍是图标 + 焦点在格子（日志无 `WindowGotFocus`）。

- [ ] **Step 7: 下翻离开首页停播**

焦点在顶行按 DOWN，再 DOWN 直到过架（`pastShelf`）。
Expected 日志：`tv: LeftHome → Collapsed, effect=Stop`，截图里图标已随网格滚出画布上沿。回到第 1 排（连按 UP）只出图标，**不起播**，除非焦点进小窗。

- [ ] **Step 8: 起 App 后返回不自动起播**

从格子进任一 App（OK），等 5 秒，BACK 回桌面。
Expected：日志 `AppLaunched → Collapsed, effect=Stop`；回桌面后焦点在磁贴、小窗是图标且无画面。反复两次不出现自动 `Play`。

- [ ] **Step 9: 天气行开城市选择会停播（预期行为，确认一次）**

焦点在小窗时 LEFT 到天气行 → OK 开城市选择 → 关闭。
Expected 日志：开表时 `WindowLostFocus → Collapsed, effect=Stop`（若焦点本来不在小窗则无此行）；关表后焦点回天气行，不自动回播。确认这条与用户「离焦即停播」的决定一致，把结论写进 Task 8。

- [ ] **Step 10: 五语走查**

```bash
adb -s 192.168.1.21:5555 shell settings put system locale zh-CN   # 再依次 en-US / ru-RU / uk-UA / kk-KZ
```
每次重启 launcher 看 `按 OK 全屏播放` 一类提示在小窗里不溢出（280dp 宽足够）、频道名和 OSD 都不越界；测完恢复原来的 locale（`local/tvs.env` 或询问户主，默认 `zh-CN`）。

- [ ] **Step 11: 若真机表现与预期不符 —— 记录，不要口头结论**

任何一条不符：把日志片段 + 截图路径贴进 Task 8 的记录里，标为未通过，先修再进 M2。

---

### Task 8: 文档收口

**Files:**
- Modify: `docs/superpowers/specs/2026-10-27-iptv-fullscreen-port-design.md`
- Modify: `docs/HANDOFF.md`

- [ ] **Step 1: spec 里补 M1 落地的三条副作用**

§2 表格后追加（措辞按实测结论调整，不要写没发生的）：

```markdown
- 天气行开城市选择 → 焦点离开小窗 → 停播（用户已定「离焦即停」）；关表后焦点回天气行。
- 全屏按 BACK → 图标 + 焦点落顶行末磁贴；`exitFullscreen()` 不再要求把焦点还给小窗。
- 全屏里所有源失败 → 留在全屏，OSD 文案 `tv_all_sources_failed`；小窗里失败 → 占位图标。
```

§3 的算例数字（含 `homePlate` 关闭时的分支）在写计划时已订正，Task 8 只补上面三条实测结论；若实测与 §3 公式不符（比如 280×158 的小窗在创维上看着偏小），把差异记进 `docs/devices/skyworth-coocaa.md` 并回来改公式，不要在 UI 里写死数字。

- [ ] **Step 2: HANDOFF 记录 M1 状态**

在 `docs/HANDOFF.md` 的现状段里写：M1（状态机四态、几何、缩回动画、冷启动焦点）已在创维真机验证到第几步、哪几条未过；`tv_fullscreen_hint` 与全屏键位重写归 M2。

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-10-27-iptv-fullscreen-port-design.md docs/HANDOFF.md
git commit -m "IPTV M1: spec corrections from the device run, handoff notes"
```

---

## Self-Review

**1. Spec coverage（§2/§3/§9-M1 逐条）**
- §2 状态表 → Task 1（reducer + 15 用例）✅
- §2「去 `panelOpen`/`PanelsOpened`/`PanelsClosed`」→ Task 2 Step 3 ✅
- §2 小窗按键 LEFT/RIGHT/MENU 不再换台开表 → Task 4 Step 1 `onKey` ✅
- §2 冷启动默认焦点一次 → Task 5 Step 3 ✅
- §2 文案改动（`tv_preview_hint` 改写、`tv_no_source` 溢出修补）→ Task 6、Task 4 Step 1 末尾分支 ✅；`tv_fullscreen_hint` 明确留给 M2（Task 6 Step 3）✅
- §3 几何公式、`gap`、宽度按屏幕比、`max(60.dp)` 兜底、无磁贴玻璃卡片的分支 → Task 3 ✅
- §3 跟随网格滚动不动 → Task 5 Step 5 保留 `.offset` ✅
- §3 spring 插值 + 被打断用 spring → Task 4 `Animatable` + `spring(dampingRatio = 1f, stiffness = 520f)`（与 `Pressable` 同曲线族）✅
- §2 `PlaybackFailed` 在全屏留全屏 → Task 1 reducer + Task 2 Step 5 OSD ✅
- §7 屏保不在本里程碑（spec §9 明写并行插队、独立验证）→ 本计划不含 ✅

**2. Placeholder scan**：无 TBD/TODO/「参照上文」；每个改码步骤都给了整文件或带行号的旧片段。Task 6 Step 3 是唯一「本阶段不做」的条目，它明确指向 M2 并说明原因（现在改会写出与键位不符的文案），不是留白。

**3. 类型一致性**：`TvSlotGeometry` 字段名（`windowTop/windowRight/windowWidth/windowHeight/iconTop/iconRight/iconWidth/iconHeight/screenW`）在 Task 3 定义、Task 4 使用、Task 5 构造，三处一致；`tvSlotGeometry(...)` 参数表 Task 3 测试与 Task 5 调用一致（含 `columns`）；`lerpDp` 定义于 Task 3、仅 Task 4 使用；`releaseFocus`（Task 2 定义）与 `TvController` 的 `exitFullscreen()`（无参）在 Task 5 Step 4 与 `MainActivity` Step 8 一致；`closePanels()` 无参改动覆盖 `MainActivity.kt:484` 与 `onManualScreensaver`。

---

## 执行记录（Task 1–3 已完成，与本文档的偏离）

- **Task 2 漏列的引用点**（已由执行代理一并修掉）：
  - `TvController.kt:52` 的 `val active` 原写「保留不动」，但它引用被删的 `TvWindow.Pinned`，编译不过 → 现为 `window == Preview || window == Fullscreen`。
  - `ui/tv/TvFullscreen.kt:46` 还有一个 `tv.exitFullscreen(focusWindow = true)` 调用点 → 改成无参。
  - `ui/home/HomeScreen.kt:378-379` 读 `tv.focusHandoff` → 先做标识符改名 `tv.releaseFocus`（函数体未动），所以**在 Task 5 Step 4 落地之前**，退出全屏仍会把焦点要回小窗；这只是中间态。
- **Task 2 提前塞进了 `tv_all_sources_failed`**：`retry()` 里 `context.getString(R.string.tv_all_sources_failed)` 在编译期就需要这条串，所以五语已在 Task 2 加好 → **Task 6 只剩 Step 1 的 `tv_preview_hint` 改写**，Step 2 已完成。
- **Task 3 Step 4 的算式改了一处括号**：`height * screenW / screenH` 在 Compose 里编译不过（`Dp * Dp` 无运算符），已写成 `height * (screenW / screenH)`（`Dp / Dp` 先出比例 Float，数值等价），源码里有注释说明。
- **待清理**：`TvController` 的类级 KDoc 还写着「pins to keep playing」，`exitFullscreen()` 里 Stop 之后那句 `player.resume()` 是旧签名遗留（今天无害的空操作）—— Task 4/5 的执行代理一并处理。
- **真机尚未验证**：Task 1–3 只过了 JVM 与编译；Task 7 之前 M1 一律不得称「已完成」。
