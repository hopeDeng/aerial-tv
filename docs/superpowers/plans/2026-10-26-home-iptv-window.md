# Home IPTV Window Implementation Plan

> **For the agent executing this plan:** implement task by task, in order. Every task ends with a
> build or a test run and a commit. The design source is
> `docs/superpowers/specs/2026-10-26-home-iptv-window-design.md`; where this plan differs in file
> structure (HomeScreen keeps its own All Apps branch instead of a separate screen file), the plan
> wins because it avoids moving the wallpaper/screensaver/overlay stack.

**Goal:** Aerial's Home becomes lock-screen style: big clock and weather top left, IPTV window top
right (default focus), six-slot app dock at the bottom, plus an All Apps page. Channels come from
the CBN Coocaa APK's `/sdcard/iptv_sources.json`, falling back to the bundled `hnyd.m3u`.

**Architecture:** A pure data layer (`TvChannel`, `TvSources`, `TvPlaylist`), a pure state machine
(`TvStateMachine`) and a controller (`TvController`) that owns an ExoPlayer and Compose state.
Compose widgets (`TvWindow`, `TvFullscreen`, `TvChannelList`, `HomeDock`, `CityPicker`) read the
controller. `HomeScreen` gets an All Apps mode; `MainActivity` wires screensaver/idle/app launch.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 1.8.1 (`media3-exoplayer-hls`), Robolectric + JUnit
for pure/Robolectric tests, ADB for on-device checks.

**Testing note:** this project has no Compose UI test harness. Data and state-machine tasks use
TDD with JUnit. Player and Compose tasks are verified by compiling, then on the TV
(`192.168.1.21:5555`, Skyworth Android 8). Never call a device feature done before that check.

**Commit convention:** the repo uses short imperative messages (see `git log`). Commit after each
green step; do not push.

---

## Task 1: Channel model and source parsing

**Files:**
- Create: `app/src/main/java/app/orchard/tv/tv/TvChannel.kt`
- Create: `app/src/main/java/app/orchard/tv/tv/TvSources.kt`
- Create: `app/src/test/java/app/orchard/tv/tv/TvSourcesTest.kt`
- Copy: `my-tv-launcher/work/player/assets/hnyd.m3u` → `app/src/main/assets/hnyd.m3u`

**Step 1: Write the failing test**

```kotlin
package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSourcesTest {
    private val json = """
        {"channels":[
          {"name":"湖南卫视","enabled":true,"currentSource":1,"sources":[
            {"url":"http://a/1.m3u8","label":"A","enabled":true},
            {"url":"http://b/2.m3u8","label":"B","enabled":true},
            {"url":"http://c/3.m3u8","label":"C","enabled":false}]},
          {"name":"隐藏台","enabled":false,"currentSource":0,"sources":[
            {"url":"http://d/4.m3u8","label":"D","enabled":true}]},
          {"name":"空台","enabled":true,"currentSource":0,"sources":[]},
          {"name":"好台","enabled":true,"currentSource":0,"sources":[
            {"url":"http://e/5.m3u8","label":"E","enabled":true}]}
        ]}
    """.trimIndent()

    @Test fun `keeps enabled channels with a usable source`() {
        val channels = TvSources.parse(json)
        assertEquals(listOf("湖南卫视", "好台"), channels.map { it.name })
    }

    @Test fun `the saved source plays first, disabled ones never`() {
        val channel = TvSources.parse(json).first()
        assertEquals(listOf("http://b/2.m3u8", "http://a/1.m3u8"), channel.playableSources().map { it.url })
    }

    @Test fun `a bad currentSource still starts from the first usable source`() {
        val channel = TvChannel(
            "台", true, 99,
            listOf(TvSource("http://x", "X", true), TvSource("http://y", "Y", false)),
        )
        assertEquals(listOf("http://x"), channel.playableSources().map { it.url })
    }

    @Test fun `channel with only disabled sources is not played`() {
        val channel = TvChannel("台", true, 0, listOf(TvSource("http://x", "X", false)))
        assertTrue(channel.playableSources().isEmpty())
    }

    @Test fun `parse rejects malformed json instead of throwing`() {
        assertEquals(emptyList<TvChannel>(), TvSources.parse("not json"))
    }

    @Test fun `m3u fallback reads name and url`() {
        val channels = TvSources.parseM3u("#EXTM3U\n#EXTINF:-1,湖南卫视\nhttp://a/1.m3u8\n#EXTINF:-1,浙江卫视\nhttp://b/2.m3u8\n")
        assertEquals(listOf("湖南卫视", "浙江卫视"), channels.map { it.name })
        assertEquals("http://a/1.m3u8", channels.first().playableSources().first().url)
    }
}
```

**Step 2: Run the test, see it fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testStandardDebugUnitTest --tests 'app.orchard.tv.tv.TvSourcesTest'
```

Expected: compilation failure, `TvChannel` / `TvSources` unresolved.

**Step 3: Implement**

`TvChannel.kt`:

```kotlin
package app.orchard.tv.tv

data class TvSource(val url: String, val label: String, val enabled: Boolean)

data class TvChannel(
    val name: String,
    val enabled: Boolean,
    val currentSource: Int,
    val sources: List<TvSource>,
) {
    /** The saved source first, then every other enabled source in file order. */
    fun playableSources(): List<TvSource> {
        if (!enabled) return emptyList()
        val saved = sources.getOrNull(currentSource)?.takeIf { it.enabled }
        val rest = sources.filterIndexed { index, source -> source.enabled && index != currentSource }
        return listOfNotNull(saved) + rest
    }
}
```

`TvSources.kt`:

```kotlin
package app.orchard.tv.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object TvSources {
    const val CBN_FILE = "/sdcard/iptv_sources.json"
    const val FALLBACK_ASSET = "hnyd.m3u"

    fun parse(json: String): List<TvChannel> = runCatching {
        val array = JSONObject(json).optJSONArray("channels") ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val sourcesJson = item.optJSONArray("sources") ?: continue
                val sources = buildList {
                    for (j in 0 until sourcesJson.length()) {
                        val source = sourcesJson.optJSONObject(j) ?: continue
                        val url = source.optString("url").trim()
                        if (url.isEmpty()) continue
                        add(TvSource(url, source.optString("label"), source.optBoolean("enabled", true)))
                    }
                }
                val channel = TvChannel(
                    name = item.optString("name").trim(),
                    enabled = item.optBoolean("enabled", true),
                    currentSource = item.optInt("currentSource", 0),
                    sources = sources,
                )
                if (channel.name.isNotEmpty() && channel.playableSources().isNotEmpty()) add(channel)
            }
        }
    }.getOrDefault(emptyList())

    fun parseM3u(text: String): List<TvChannel> {
        val result = mutableListOf<TvChannel>()
        var name: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfter(',', "").trim().ifEmpty { null }
            } else if (line.isNotEmpty() && !line.startsWith("#") && name != null) {
                result += TvChannel(name, true, 0, listOf(TvSource(line, "默认", true)))
                name = null
            }
        }
        return result
    }

    /** Reads the CBN file when readable, otherwise the bundled list. Never throws. */
    suspend fun load(context: Context, path: String = CBN_FILE): List<TvChannel> = withContext(Dispatchers.IO) {
        val file = File(path)
        if (file.canRead()) {
            runCatching { parse(file.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        val text = runCatching {
            context.assets.open(FALLBACK_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        parseM3u(text)
    }

    /** The modification time of the CBN file, or 0 when it cannot be read. */
    fun stamp(path: String = CBN_FILE): Long = runCatching { File(path).lastModified() }.getOrDefault(0L)
}
```

Copy the fallback list:

```bash
mkdir -p app/src/main/assets
cp ../my-tv-launcher/work/player/assets/hnyd.m3u app/src/main/assets/hnyd.m3u
```

**Step 4: Run the test, see it pass**

```bash
./gradlew testStandardDebugUnitTest --tests 'app.orchard.tv.tv.TvSourcesTest'
```

**Step 5: Commit**

```bash
git add app/src/main/java/app/orchard/tv/tv app/src/test/java/app/orchard/tv/tv app/src/main/assets/hnyd.m3u
git commit -m "Add IPTV channel parsing with M3U fallback"
```

---

## Task 2: Preferences and the dock store

**Files:**
- Create: `app/src/main/java/app/orchard/tv/tv/TvPrefs.kt`
- Create: `app/src/main/java/app/orchard/tv/tv/DockStore.kt`
- Create: `app/src/test/java/app/orchard/tv/tv/DockStoreTest.kt`

**Step 1: Write the failing test**

```kotlin
package app.orchard.tv.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DockStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `dock saves and restores six slots in order`() {
        val store = DockStore(context)
        store.save(listOf("a", "b", "", "c", "d", "e"))
        assertEquals(listOf("a", "b", "", "c", "d", "e"), store.load())
    }

    @Test fun `defaults take the first six non-folder apps`() {
        val keys = listOf("folder:1", "v:a", "v:b", "folder:2", "v:c", "v:d", "v:e", "v:f")
        assertEquals(listOf("v:a", "v:b", "v:c", "v:d", "v:e", "v:f"), defaultDockKeys(keys))
    }

    @Test fun `prefs remembers the last channel and source`() {
        val prefs = TvPrefs(context)
        prefs.lastChannel = "湖南卫视"
        prefs.lastSource = "http://a/1.m3u8"
        val again = TvPrefs(context)
        assertEquals("湖南卫视", again.lastChannel)
        assertEquals("http://a/1.m3u8", again.lastSource)
        again.clearLast()
        assertNull(TvPrefs(context).lastChannel)
    }
}
```

`defaultDockKeys` takes keys, not `AppEntry`, so the test does not need tile art. Folder keys are
prefixed `folder:` by `LayoutStore` (check `Folder.key`/`AppEntry.key` before implementing; if the
prefix differs, use the same predicate `HomeViewModel` uses for `folderApps`).

**Step 2: Run, see it fail**

```bash
./gradlew testStandardDebugUnitTest --tests 'app.orchard.tv.tv.DockStoreTest'
```

**Step 3: Implement**

`TvPrefs.kt`:

```kotlin
package app.orchard.tv.tv

import android.content.Context

class TvPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("tv_window", Context.MODE_PRIVATE)

    var lastChannel: String?
        get() = prefs.getString("last_channel", null)
        set(value) = prefs.edit().putString("last_channel", value).apply()

    var lastSource: String?
        get() = prefs.getString("last_source", null)
        set(value) = prefs.edit().putString("last_source", value).apply()

    fun clearLast() = prefs.edit().remove("last_channel").remove("last_source").apply()

    /** Set when an app is opened; consumed when Home comes back in front. */
    @Volatile var resumeOnHome = false
}
```

`DockStore.kt`:

```kotlin
package app.orchard.tv.tv

import android.content.Context

private const val SLOTS = 6
private const val FOLDER_PREFIX = "folder:"

fun defaultDockKeys(keys: List<String>): List<String> =
    keys.filterNot { it.startsWith(FOLDER_PREFIX) }.take(SLOTS)

class DockStore(context: Context) {
    private val prefs = context.getSharedPreferences("dock", Context.MODE_PRIVATE)

    fun load(): List<String> {
        val saved = prefs.getString("slots", null) ?: return emptyList()
        return saved.split('\n').take(SLOTS)
    }

    fun save(keys: List<String>) {
        require(keys.size <= SLOTS)
        prefs.edit().putString("slots", keys.joinToString("\n")).apply()
    }
}
```

**Step 4: Run, see it pass**

**Step 5: Commit**

```bash
git add app/src/main/java/app/orchard/tv/tv app/src/test/java/app/orchard/tv/tv
git commit -m "Add TV window preferences and dock storage"
```

---

## Task 3: Pure state machine

**Files:**
- Create: `app/src/main/java/app/orchard/tv/tv/TvStateMachine.kt`
- Create: `app/src/test/java/app/orchard/tv/tv/TvStateMachineTest.kt`

**Step 1: Write the failing test**

```kotlin
package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvStateMachineTest {
    private fun run(state: TvState, event: TvEvent) = TvStateMachine.reduce(state, event)

    @Test fun `home starts focused on the window`() {
        val result = run(TvState(), TvEvent.HomeResumed(hasChannel = true))
        assertEquals(TvWindow.Window, result.state.window)
        assertEquals(TvEffect.Play, result.effect)
        assertEquals(TvFocusTarget.Window, result.focus)
    }

    @Test fun `an app stops the window and Home resumes it`() {
        var state = run(TvState(), TvEvent.HomeResumed(true)).state
        state = run(state, TvEvent.AppLaunched).state
        assertEquals(TvWindow.Stopped, state.window)
        assertEquals(true, state.resumeOnHome)

        val back = run(state, TvEvent.HomeResumed(true))
        assertEquals(TvWindow.Window, back.state.window)
        assertEquals(TvEffect.Play, back.effect)
    }

    @Test fun `idle stops playback but keeps the window when it is not focused`() {
        val state = TvState(window = TvWindow.Window, focused = false, playback = TvPlayback.Playing)
        val result = run(state, TvEvent.Idle(canPlay = true))
        assertEquals(TvWindow.Window, result.state.window)
        assertEquals(TvWindow.Window, result.state.window)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `idle while focused on the window starts the screensaver`() {
        val state = TvState(window = TvWindow.Window, focused = true, playback = TvPlayback.Playing)
        val result = run(state, TvEvent.Idle(canPlay = true))
        assertEquals(TvEffect.StartScreensaver, result.effect)
        assertEquals(TvPlayback.Playing, result.state.playback)
    }

    @Test fun `waking from a screensaver leaves the icon and focuses it`() {
        val state = TvState(window = TvWindow.Icon, playback = TvPlayback.Idle)
        val result = run(state, TvEvent.ScreensaverWoke)
        assertEquals(TvFocusTarget.Dock, result.focus)
    }

    @Test fun `focusing the icon previews the channel`() {
        val state = TvState(window = TvWindow.Icon, playback = TvPlayback.Idle)
        val result = run(state, TvEvent.WindowGotFocus)
        assertEquals(TvWindow.Preview, result.state.window)
        assertEquals(TvEffect.Play, result.effect)
    }

    @Test fun `leaving a preview immediately stops back to the icon`() {
        val state = TvState(window = TvWindow.Preview, focused = true, playback = TvPlayback.Playing)
        val result = run(state, TvEvent.WindowLostFocus)
        assertEquals(TvWindow.Icon, result.state.window)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `leaving a playing window keeps playing`() {
        val state = TvState(window = TvWindow.Window, focused = true, playback = TvPlayback.Playing)
        val result = run(state, TvEvent.WindowLostFocus)
        assertEquals(TvWindow.Window, result.state.window)
        assertEquals(TvEffect.None, result.effect)
    }

    @Test fun `all apps pauses and closing it resumes`() {
        val state = TvState(window = TvWindow.Window, playback = TvPlayback.Playing)
        val open = run(state, TvEvent.AllAppsOpened)
        assertEquals(TvWindow.AllApps, open.state.window)
        assertEquals(TvEffect.Pause, open.effect)
        val close = run(open.state, TvEvent.AllAppsClosed)
        assertEquals(TvWindow.Window, close.state.window)
        assertEquals(TvEffect.Resume, close.effect)
    }

    @Test fun `a stopped window with no channel stays stopped`() {
        val result = run(TvState(window = TvWindow.Stopped), TvEvent.HomeResumed(hasChannel = false))
        assertEquals(TvWindow.Stopped, result.state.window)
        assertEquals(TvEffect.None, result.effect)
        assertEquals(TvFocusTarget.Dock, result.focus)
    }

    @Test fun `fullscreen exit returns focus to the window`() {
        val state = TvState(window = TvWindow.Fullscreen, playback = TvPlayback.Playing)
        val result = run(state, TvEvent.FullscreenExited)
        assertEquals(TvWindow.Window, result.state.window)
        assertEquals(TvFocusTarget.Window, result.focus)
    }

    @Test fun `manual screensaver pauses a playing window`() {
        val state = TvState(window = TvWindow.Window, playback = TvPlayback.Playing)
        val result = run(state, TvEvent.ManualScreensaver)
        assertEquals(TvEffect.Pause, result.effect)
        assertEquals(true, result.state.wasPlaying)
    }
}
```

**Step 2: Run, see it fail.**

**Step 3: Implement**

```kotlin
package app.orchard.tv.tv

enum class TvWindow { Stopped, Window, Preview, Icon, Fullscreen, AllApps }

enum class TvPlayback { Idle, Buffering, Playing, Error }

enum class TvFocusTarget { None, Window, Dock }

data class TvState(
    val window: TvWindow = TvWindow.Stopped,
    val focused: Boolean = false,
    val playback: TvPlayback = TvPlayback.Idle,
    val wasPlaying: Boolean = false,
    val resumeOnHome: Boolean = false,
)

sealed interface TvEvent {
    data class HomeResumed(val hasChannel: Boolean) : TvEvent
    data object AppLaunched : TvEvent
    data object WindowGotFocus : TvEvent
    data object WindowLostFocus : TvEvent
    data object WindowClicked : TvEvent
    data class Idle(val canPlay: Boolean) : TvEvent
    data object FullscreenExited : TvEvent
    data object AllAppsOpened : TvEvent
    data object AllAppsClosed : TvEvent
    data object ManualScreensaver : TvEvent
    data object ScreensaverWoke : TvEvent
    data object PlaybackFailed : TvEvent
}

sealed interface TvEffect {
    data object None : TvEffect
    data object Play : TvEffect
    data object Stop : TvEffect
    data object Pause : TvEffect
    data object Resume : TvEffect
    data object StartScreensaver : TvEffect
    data object EnterFullscreen : TvEffect
    data object ExitFullscreen : TvEffect
}

data class TvTransition(val state: TvState, val effect: TvEffect, val focus: TvFocusTarget)

object TvStateMachine {
    fun reduce(state: TvState, event: TvEvent): TvTransition = when (event) {
        is TvEvent.HomeResumed -> when {
            state.window == TvWindow.Fullscreen -> keep(state)
            !event.hasChannel -> TvTransition(state.copy(window = TvWindow.Stopped), TvEffect.None, TvFocusTarget.Dock)
            else -> TvTransition(
                state.copy(window = TvWindow.Window, focused = true, resumeOnHome = false),
                TvEffect.Play,
                TvFocusTarget.Window,
            )
        }
        TvEvent.AppLaunched -> if (state.window == TvWindow.Fullscreen) keep(state)
            else TvTransition(state.copy(window = TvWindow.Stopped, focused = false, resumeOnHome = true, playback = TvPlayback.Idle), TvEffect.Stop, TvFocusTarget.None)
        TvEvent.WindowGotFocus -> when (state.window) {
            TvWindow.Icon, TvWindow.Stopped ->
                if (state.playback == TvPlayback.Error) keep(state)
                else TvTransition(state.copy(window = TvWindow.Preview, focused = true), TvEffect.Play, TvFocusTarget.None)
            else -> TvTransition(state.copy(focused = true), TvEffect.None, TvFocusTarget.None)
        }
        TvEvent.WindowLostFocus -> when (state.window) {
            TvWindow.Preview -> TvTransition(state.copy(window = TvWindow.Icon, focused = false, playback = TvPlayback.Idle), TvEffect.Stop, TvFocusTarget.None)
            else -> TvTransition(state.copy(focused = false), TvEffect.None, TvFocusTarget.None)
        }
        TvEvent.WindowClicked -> when {
            state.window == TvWindow.Fullscreen -> keep(state)
            state.playback == TvPlayback.Error -> TvTransition(state, TvEffect.Play, TvFocusTarget.None)
            else -> TvTransition(state.copy(window = TvWindow.Fullscreen), TvEffect.EnterFullscreen, TvFocusTarget.None)
        }
        is TvEvent.Idle -> when {
            !event.canPlay -> keep(state)
            state.focused && state.window == TvWindow.Window ->
                TvTransition(state.copy(wasPlaying = state.playback == TvPlayback.Playing), TvEffect.StartScreensaver, TvFocusTarget.None)
            state.window == TvWindow.Window || state.window == TvWindow.Preview ->
                TvTransition(state.copy(focused = false), TvEffect.Stop, TvFocusTarget.None)
            else -> keep(state)
        }
        TvEvent.FullscreenExited -> TvTransition(state.copy(window = TvWindow.Window, focused = true), TvEffect.None, TvFocusTarget.Window)
        TvEvent.AllAppsOpened -> if (state.window == TvWindow.Window || state.window == TvWindow.Preview)
            TvTransition(state.copy(window = TvWindow.AllApps), TvEffect.Pause, TvFocusTarget.None)
        else TvTransition(state.copy(window = TvWindow.AllApps), TvEffect.None, TvFocusTarget.None)
        TvEvent.AllAppsClosed -> if (state.window == TvWindow.AllApps)
            TvTransition(state.copy(window = TvWindow.Window, focused = true), TvEffect.Resume, TvFocusTarget.Window)
        else keep(state)
        TvEvent.ManualScreensaver -> TvTransition(
            state.copy(wasPlaying = state.window == TvWindow.Window && state.playback == TvPlayback.Playing),
            if (state.window == TvWindow.Window) TvEffect.Pause else TvEffect.None,
            TvFocusTarget.None,
        )
        TvEvent.ScreensaverWoke -> TvTransition(state, TvEffect.None, TvFocusTarget.Dock)
        TvEvent.PlaybackFailed -> TvTransition(state.copy(playback = TvPlayback.Error), TvEffect.None, TvFocusTarget.None)
    }

    private fun keep(state: TvState) = TvTransition(state, TvEffect.None, TvFocusTarget.None)
}
```

**Step 4: Run, see it pass.**

**Step 5: Commit**

```bash
git add app/src/main/java/app/orchard/tv/tv app/src/test/java/app/orchard/tv/tv
git commit -m "Add IPTV window state machine"
```

---

## Task 4: Playlist and retry

**Files:**
- Create: `app/src/main/java/app/orchard/tv/tv/TvPlaylist.kt`
- Create: `app/src/test/java/app/orchard/tv/tv/TvPlaylistTest.kt`

**Step 1: Write the failing test**

```kotlin
package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvPlaylistTest {
    private fun channel(name: String, vararg urls: String) =
        TvChannel(name, true, 0, urls.map { TvSource(it, "s", true) })

    private val playlist = TvPlaylist(listOf(channel("A", "a1", "a2"), channel("B", "b1"), channel("C", "c1")))

    @Test fun `next source stays in the channel first`() {
        assertEquals(0 to "a2", playlist.nextSource(0, "a1")?.let { it.first to it.second.url })
    }

    @Test fun `after the last source it moves to the next channel`() {
        val next = playlist.nextSource(0, "a2")!!
        assertEquals(1, next.first)
        assertEquals("b1", next.second.url)
    }

    @Test fun `wrapping goes from the last channel back to the first`() {
        val next = playlist.nextSource(2, "c1")!!
        assertEquals(0, next.first)
        assertEquals("a1", next.second.url)
    }

    @Test fun `a missing channel starts at the first`() {
        assertEquals(0 to "a1", playlist.first()?.let { it.first to it.second.url })
    }

    @Test fun `empty playlist has no first`() {
        assertNull(TvPlaylist(emptyList()).first())
    }
}
```

**Step 2: Run, see it fail.**

**Step 3: Implement**

```kotlin
package app.orchard.tv.tv

class TvPlaylist(val channels: List<TvChannel>) {
    fun first(): Pair<Int, TvSource>? =
        channels.indices.firstNotNullOfOrNull { i -> channels[i].playableSources().firstOrNull()?.let { i to it } }

    fun nextSource(channelIndex: Int, sourceUrl: String?): Pair<Int, TvSource>? {
        val channel = channels.getOrNull(channelIndex) ?: return first()
        val sources = channel.playableSources()
        val at = sources.indexOfFirst { it.url == sourceUrl }
        sources.getOrNull(at + 1)?.let { return channelIndex to it }
        for (step in 1..channels.size) {
            val next = channels[(channelIndex + step) % channels.size]
            next.playableSources().firstOrNull()?.let { return (channelIndex + step) % channels.size to it }
        }
        return null
    }

    fun channelIndex(url: String?): Int =
        channels.indexOfFirst { it.playableSources().any { source -> source.url == url } }

    fun channelAt(index: Int): TvChannel? = channels.getOrNull(index)
}
```

If `sourceUrl` belongs to a different channel (restored preference), `nextSource` currently starts at
the first source of `channelIndex`; the controller passes the channel index it restored, so this is
correct.

**Step 4: Run, see it pass.**

**Step 5: Commit**

```bash
git commit -am "Add IPTV playlist navigation"
```

---

## Task 5: Player wrapper

**Files:**
- Modify: `app/build.gradle.kts` (add HLS)
- Create: `app/src/main/java/app/orchard/tv/tv/TvPlayer.kt`

**Step 1: Add the dependency**

In the `dependencies` block next to `media3-exoplayer`, add:

```kotlin
implementation("androidx.media3:media3-exoplayer-hls:1.8.1")
```

**Step 2: Implement**

`TvPlayer` owns one `ExoPlayer`. It does not decide what to play; the controller does.

```kotlin
package app.orchard.tv.tv

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource

class TvPlayer(context: Context) {
    var onPlaybackChanged: ((TvPlayback) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    private val http = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(12_000)
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("Aerial/1.0")

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(HlsMediaSource.Factory(http))
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    onPlaybackChanged?.invoke(
                        when (playbackState) {
                            Player.STATE_BUFFERING -> TvPlayback.Buffering
                            Player.STATE_READY -> if (playWhenReady) TvPlayback.Playing else TvPlayback.Paused
                            else -> TvPlayback.Idle
                        },
                    )
                }
                override fun onPlayerError(error: PlaybackException) {
                    onPlaybackChanged?.invoke(TvPlayback.Error)
                    onFailed?.invoke(error.message ?: "playback failed")
                }
            })
        }

    fun attach(surfaceView: SurfaceView) { player.setVideoSurfaceView(surfaceView) }

    fun play(url: String) {
        player.setMediaSource(HlsMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(Uri.parse(url))))
        player.prepare()
        player.playWhenReady = true
    }

    fun pause() { player.playWhenReady = false }

    fun resume() { player.playWhenReady = true }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        onPlaybackChanged?.invoke(TvPlayback.Idle)
    }

    fun release() { player.release() }
}
```

`TvPlayback` must gain `Paused`; extend the enum (and the state-machine test still compiles because
it only names existing values).

**Step 3: Compile**

```bash
./gradlew compileStandardDebugKotlin
```

Expected: success. ExoPlayer cannot run under Robolectric; no unit test here.

**Step 4: Commit**

```bash
git commit -am "Add ExoPlayer wrapper for HLS channels"
```

---

## Task 6: Controller

**Files:**
- Create: `app/src/main/java/app/orchard/tv/tv/TvController.kt`
- Create: `app/src/test/java/app/orchard/tv/tv/TvControllerTest.kt` (only the channel-restore helper)

The controller is the only mutable owner. Compose reads its `mutableStateOf` fields directly.

**Public surface used by UI/MainActivity:**

```kotlin
class TvController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val prefs = TvPrefs(context)
    var channels by mutableStateOf<List<TvChannel>>(emptyList()); private set
    val playlist: TvPlaylist get() = TvPlaylist(channels)
    var current by mutableStateOf<Pair<Int, TvSource>?>(null); private set
    var window by mutableStateOf(TvWindow.Stopped); private set
    var playback by mutableStateOf(TvPlayback.Idle); private set
    var focused by mutableStateOf(false); private set
    var focusTarget by mutableStateOf(TvFocusTarget.None); private set
    var osd by mutableStateOf<String?>(null); private set
    var channelListOpen by mutableStateOf(false); private set
    var cityPickerOpen by mutableStateOf(false); private set
    var sourcePath by mutableStateOf(TvSources.CBN_FILE); private set
    val player = TvPlayer(context)

    fun start()
    fun attach(surface: SurfaceView)
    fun onHomeResumed()
    fun onAppLaunched()
    fun onWindowFocus(gained: Boolean)
    fun onClick()
    fun onNext()
    fun playChannel(index: Int)
    fun enterFullscreen()
    fun exitFullscreen()
    fun openChannelList()
    fun closeChannelList()
    fun openCityPicker()
    fun closeCityPicker()
    fun onIdle(canPlay: Boolean)
    fun onManualScreensaver()
    fun onScreensaverWoke()
    fun pauseForAllApps()
    fun resumeFromAllApps()
    fun consumeFocus()
    fun closePanels()
    fun release()
}
```

**Step 1: Write the failing test for restore**

```kotlin
package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvControllerTest {
    private val channels = listOf(
        TvChannel("A", true, 0, listOf(TvSource("a1", "", true), TvSource("a2", "", true))),
        TvChannel("B", true, 0, listOf(TvSource("b1", "", true))),
    )

    @Test fun `restores the remembered channel and source`() {
        val at = restore(channels, "B", "b1")
        assertEquals(1 to "b1", at?.let { it.first to it.second.url })
    }

    @Test fun `falls back to the same channel's first source`() {
        val at = restore(channels, "A", "gone")
        assertEquals(0 to "a1", at?.let { it.first to it.second.url })
    }

    @Test fun `falls back to the first channel when the name is gone`() {
        val at = restore(channels, "Z", "z1")
        assertEquals(0 to "a1", at?.let { it.first to it.second.url })
    }
}
```

**Step 2: Run, see it fail.**

**Step 3: Implement**

```kotlin
package app.orchard.tv.tv

import android.content.Context
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Returns the remembered channel/source, or the nearest usable one. */
internal fun restore(channels: List<TvChannel>, name: String?, url: String?): Pair<Int, TvSource>? {
    if (channels.isEmpty()) return null
    val index = channels.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: 0
    val channel = channels[index]
    val source = channel.playableSources().firstOrNull { it.url == url } ?: channel.playableSources().firstOrNull()
    return source?.let { index to it }
}

class TvController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val prefs = TvPrefs(context)
    var channels by mutableStateOf<List<TvChannel>>(emptyList()); private set
    fun playlist(): TvPlaylist = TvPlaylist(channels)
    var current by mutableStateOf<Pair<Int, TvSource>?>(null); private set
    var window by mutableStateOf(TvWindow.Stopped); private set
    var playback by mutableStateOf(TvPlayback.Idle); private set
    var focused by mutableStateOf(false); private set
    var focusTarget by mutableStateOf(TvFocusTarget.None); private set
    var osd by mutableStateOf<String?>(null); private set
    var channelListOpen by mutableStateOf(false); private set
    var cityPickerOpen by mutableStateOf(false); private set
    var sourcePath by mutableStateOf(TvSources.CBN_FILE); private set
    val player = TvPlayer(context)
    private var state = TvState()
    private var loadJob: Job? = null
    private var osdJob: Job? = null
    private var retryJob: Job? = null
    private var started = false

    init {
        player.onPlaybackChanged = { playback = it }
        player.onFailed = { scope.launch { retry() } }
    }

    fun start() {
        if (started) return
        started = true
        reload(play = true)
    }

    fun attach(surface: SurfaceView) = player.attach(surface)

    fun reload(play: Boolean) {
        val stamp = TvSources.stamp()
        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = TvSources.load(context)
            channels = loaded
            val at = restore(loaded, prefs.lastChannel, prefs.lastSource)
            current = at
            if (play && at != null && prefs.resumeOnHome || play && at != null && window != TvWindow.Stopped) {
                play(at)
            } else if (at != null) {
                state = state.copy(playback = TvPlayback.Idle)
            }
        }
    }

    fun onHomeResumed() {
        reload(play = false)
        if (prefs.resumeOnHome && current != null) {
            prefs.resumeOnHome = false
            play(current!!)
        }
        apply(TvEvent.HomeResumed(hasChannel = current != null || channels.isNotEmpty()))
        if (current == null) {
            current = playlist().first()
            if (current != null) play(current!!)
        }
    }

    fun onAppLaunched() {
        prefs.resumeOnHome = true
        apply(TvEvent.AppLaunched)
    }

    fun onWindowFocus(gained: Boolean) {
        focused = gained
        apply(if (gained) TvEvent.WindowGotFocus else TvEvent.WindowLostFocus)
        if (gained && state.window == TvWindow.Preview && current == null) {
            current = playlist().first()
            current?.let { play(it) }
        }
    }

    fun onClick() {
        apply(TvEvent.WindowClicked)
        if (state.window == TvWindow.Fullscreen) enterFullscreen()
    }

    fun onNext(step: Int = 1) {
        val from = current ?: return
        repeat(step.coerceAtLeast(1)) {
            val next = playlist().nextSource(from.first, it.let { from.second.url }) ?: return@repeat
            current = next
            prefs.lastChannel = channels[next.first].name
            prefs.lastSource = next.second.url
            showOsd(channels[next.first].name)
            play(next)
        }
    }

    fun playChannel(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        val source = channel.playableSources().firstOrNull() ?: return
        current = index to source
        prefs.lastChannel = channel.name
        prefs.lastSource = source.url
        showOsd(channel.name)
        play(index to source)
        if (state.window == TvWindow.Fullscreen) apply(TvEvent.WindowClicked)
    }

    fun enterFullscreen() {
        state = state.copy(window = TvWindow.Fullscreen)
        window = TvWindow.Fullscreen
        player.resume()
    }

    fun exitFullscreen() {
        apply(TvEvent.FullscreenExited)
        player.resume()
    }

    fun openChannelList() { channelListOpen = true; player.pause() }
    fun closeChannelList() { channelListOpen = false; if (state.window != TvWindow.Fullscreen) player.resume() }

    fun openCityPicker() { cityPickerOpen = true; player.pause() }
    fun closeCityPicker() { cityPickerOpen = false; player.resume() }

    fun onIdle(canPlay: Boolean) {
        apply(TvEvent.Idle(canPlay))
    }

    fun onManualScreensaver() {
        apply(TvEvent.ManualScreensaver)
    }

    fun onScreensaverWoke() {
        apply(TvEvent.ScreensaverWoke)
    }

    fun pauseForAllApps() { apply(TvEvent.AllAppsOpened) }

    fun resumeFromAllApps() { apply(TvEvent.AllAppsClosed) }

    fun consumeFocus() { focusTarget = TvFocusTarget.None }

    fun closePanels() {
        channelListOpen = false
        cityPickerOpen = false
    }

    fun release() {
        retryJob?.cancel()
        player.release()
    }

    private fun play(at: Pair<Int, TvSource>) {
        current = at
        val channel = channels.getOrNull(at.first) ?: return
        prefs.lastChannel = channel.name
        prefs.lastSource = at.second.url
        player.play(at.second.url)
        state = state.copy(window = if (state.window == TvWindow.Fullscreen) TvWindow.Fullscreen else TvWindow.Window, playback = TvPlayback.Buffering)
        window = state.window
    }

    private fun retry() {
        val at = current ?: return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(1_500)
            val next = playlist().nextSource(at.first, at.second.url)
            if (next != null) {
                current = next
                play(next)
            } else {
                state = state.copy(playback = TvPlayback.Error)
                window = TvWindow.Icon.takeIf { state.window == TvWindow.Window } ?: state.window
            }
        }
    }

    private fun apply(event: TvEvent) {
        val before = state
        val result = TvStateMachine.reduce(state, event)
        state = result.state
        window = state.window
        if (state.window == TvWindow.Fullscreen && result.effect == TvEffect.EnterFullscreen) {
            window = TvWindow.Fullscreen
        }
        when (result.effect) {
            TvEffect.Play -> { current?.let { player.play(it.second.url) }; state = state.copy(playback = TvPlayback.Buffering) }
            TvEffect.Stop -> player.stop()
            TvEffect.Pause -> player.pause()
            TvEffect.Resume -> player.resume()
            TvEffect.StartScreensaver -> { /* MainActivity owns the screensaver; it calls onIdle after checking */ }
            TvEffect.EnterFullscreen, TvEffect.ExitFullscreen, TvEffect.None -> Unit
        }
        if (result.focus != TvFocusTarget.None) focusTarget = result.focus
        if (before.window != state.window) window = state.window
    }

    private fun showOsd(text: String) {
        osd = text
        osdJob?.cancel()
        osdJob = scope.launch { delay(2_000); osd = null }
    }
}
```

Fix the sloppy parts before compiling: `onNext` currently ignores `step` after the first loop and
`reload`'s play condition is muddled. Use this exact body for `onNext`:

```kotlin
fun onNext() {
    val from = current ?: return
    val next = playlist().nextSource(from.first, from.second.url) ?: return
    current = next
    showOsd(channels[next.first].name)
    play(next)
}
```

And `reload` should be:

```kotlin
fun reload(playNow: Boolean = false) {
    loadJob?.cancel()
    loadJob = scope.launch {
        val loaded = TvSources.load(context)
        channels = loaded
        val at = restore(loaded, prefs.lastChannel, prefs.lastSource)
        current = at
        if (playNow && at != null) play(at)
    }
}
```

`start()` calls `reload()`, then `onHomeResumed()` decides whether to play. `onHomeResumed` must not
call `reload(play = false)` after `start` if that cancels the first load; call `reload()` only when
the file stamp changed, or let `start` be the sole loader and have `onHomeResumed` only restore.

**Step 4: Run the test**

```bash
./gradlew testStandardDebugUnitTest --tests 'app.orchard.tv.tv.TvControllerTest'
```

**Step 5: Commit**

```bash
git commit -am "Add IPTV controller"
```

---

## Task 7: Window widget

**Files:**
- Create: `app/src/main/java/app/orchard/tv/ui/tv/TvWindow.kt`
- Create: `app/src/main/java/app/orchard/tv/ui/tv/TvSurface.kt`

`TvSurface` is a small `AndroidView` wrapper shared by the window and fullscreen. It creates one
`SurfaceView` per composition, calls `tv.attach(view)`, and releases the surface on dispose by
calling `player.attach` on the next host (do not release the player).

```kotlin
@Composable
fun TvSurface(tv: TvController, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                holder.setKeepScreenOn(true)
                tv.attach(this)
            }
        },
        modifier = modifier,
    )
}
```

`TvWindow` states:
- `Window`/`Preview`: 320×180 dp glass panel at top right, `GridMargin` from top/right, rounded
  18 dp, black video surface cropped by the panel, 1 dp white 18% border, channel name OSD pill at
  the bottom left inside the panel.
- `Icon`: same panel size, dark glass, TV glyph + channel name, no video.
- `Stopped`: same panel, `ic_tv_off` glyph + `tv_no_source`.
- Focus: `Pressable` with `focusedScale = 1.03f`; on focus change call `tv.onWindowFocus`.
- Keys via `onKey`: Left/Right `tv.onNext`; Center `tv.onClick`; Down move focus to the dock;
  Menu `tv.openChannelList`.
- Clicking (touch) calls `tv.onClick`.

Skeleton that compiles (fill the children in the same task):

```kotlin
@Composable
fun TvWindow(tv: TvController, focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    val window = tv.window
    val name = tv.current?.let { tv.channels.getOrNull(it.first)?.name }.orEmpty()
    Pressable(
        onClick = { tv.onClick() },
        focusRequester = focusRequester,
        focusedScale = 1.03f,
        modifier = modifier.size(320.dp, 180.dp),
        onKey = { event ->
            if (event.type != KeyEventType.KeyDown) return@Pressable false
            when (event.nativeKeyEvent.keyCode) {
                NativeKeyEvent.KEYCODE_DPAD_LEFT -> { tv.onNext(); true }
                NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { tv.onNext(); true }
                NativeKeyEvent.KEYCODE_MENU -> { tv.openChannelList(); true }
                else -> false
            }
        },
    ) { state ->
        LaunchedEffect(state.focused) { tv.onWindowFocus(state.focused) }
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, Color.White.copy(alpha = if (state.focused) 0.5f else 0.18f), RoundedCornerShape(18.dp)),
        ) {
            when (window) {
                TvWindow.Window, TvWindow.Preview -> TvSurface(tv, Modifier.fillMaxSize())
                else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    GlyphIcon(if (window == TvWindow.Stopped) Glyph.TvOff else Glyph.Tv, Color.White.copy(alpha = 0.85f), 34.dp)
                    if (name.isNotEmpty()) BasicText(name, style = Type.Body.copy(color = Color.White.copy(alpha = 0.8f)))
                }
            }
            tv.osd?.let {
                BasicText(it, style = Type.Callout.copy(color = Color.White), modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
            }
        }
    }
}
```

`Glyph.Tv` / `Glyph.TvOff` may not exist; add them to `ui/theme/Glyph.kt` using the same path
style as neighbours (`Tv`, `TvOff`). If a glyph is too much for this task, use the existing
`Glyph.Switches` and a string label; do not add an icon font dependency.

`TvWindow` does not request focus by itself. `HomeScreen` observes `tv.focusTarget` and requests.

**Verify:** compile. Device check comes after Task 11 wiring.

**Commit:** `Add IPTV window widget`

---

## Task 8: Fullscreen, channel list and root overlays

**Files:**
- Create: `app/src/main/java/app/orchard/tv/ui/tv/TvFullscreen.kt`
- Create: `app/src/main/java/app/orchard/tv/ui/tv/TvChannelList.kt`
- Create: `app/src/main/java/app/orchard/tv/ui/tv/TvOverlays.kt`
- Create: `app/src/main/java/app/orchard/tv/ui/tv/CityPicker.kt`

**TvFullscreen:**
- Renders when `tv.window == TvWindow.Fullscreen`.
- Full-screen black `TvSurface`; channel name + source label in a glass pill top left; a hint
  `tv_fullscreen_hint` bottom center for 3 s after entry.
- Captures keys with `onPreviewKeyEvent`: Left/Right `tv.onNext`; OK `tv.openChannelList`; Back
  `tv.exitFullscreen`; Menu same as OK.
- `BackHandler { tv.exitFullscreen() }`.
- Uses `tv.player`; no second ExoPlayer.

**TvChannelList:**
- Glass sheet ~640×420 dp centered, vertical list of `tv.channels`, highlighted current channel.
- `LazyColumn`; each row is `Pressable`; on click `tv.playChannel(index); tv.closeChannelList()`.
- Back closes only the list.

**TvOverlays:**
```kotlin
@Composable
fun TvOverlays(tv: TvController, onOpenCity: () -> Unit) {
    TvFullscreen(tv)
    if (tv.channelListOpen) TvChannelList(tv)
    if (tv.cityPickerOpen) CityPicker(onChoose = { tv.closeCityPicker(); onOpenCity() }, onDismiss = { tv.closeCityPicker() })
}
```
Order matters: fullscreen below its channel list. `CityPicker` is in Task 9; add the call there.

**Verify:** compile.

**Commit:** `Add fullscreen player and channel list`

---

## Task 9: City picker and weather focus

**Files:**
- Create: `app/src/main/java/app/orchard/tv/ui/tv/CityPicker.kt`
- Modify: `app/src/main/java/app/orchard/tv/ui/home/HomeChrome.kt`
- Modify: `app/src/main/java/app/orchard/tv/weather/Weather.kt` if a province list is missing

**CityPicker:**
- Two levels: province list, then city list. `Cities.all` is grouped by `province`; sort provinces
  by rank then name; cities by `rank` then name.
- Focusable rows via `Pressable`; Back goes from cities to provinces, then dismisses.
- Choosing calls `Weather.choose(city.name)` from a coroutine and dismisses.
- Show current place at the top.

**ShelfClock:**
- Add parameter `onWeatherClick: (() -> Unit)? = null`.
- Wrap the weather `Row` in `Pressable` only when the callback is non-null:
```kotlin
val weatherRow: @Composable () -> Unit = { /* existing Row */ }
if (onWeatherClick != null) {
    Pressable(onClick = onWeatherClick, focusedScale = 1.04f, modifier = Modifier) { weatherRow() }
} else weatherRow()
```
- A focused weather row gets a white 12% rounded plate; otherwise unchanged.
- `HomeScreen` passes `onWeatherClick = { tv.openCityPicker() }`.

**Verify:** compile; on TV, focus weather → city list → choose → weather updates.

**Commit:** `Add city picker and focusable weather`

---

## Task 10: Dock

**Files:**
- Create: `app/src/main/java/app/orchard/tv/ui/tv/HomeDock.kt`
- Modify: `app/src/main/java/app/orchard/tv/HomeViewModel.kt` if the dock needs entry lookup
  (it already has `entry(key)` and `apps`)

**Dock behavior:**
- Six slots, 112×72 dp glass rounded 18 dp, gap 16 dp, centered, bottom `GridMargin - 8.dp`.
- Load `DockStore.load()`; if empty, `defaultDockKeys(vm.apps.map { it.key })`; drop keys that no
  longer exist; pad with empty slots.
- Slot content: icon (`vm.entry(key)?.art`) + label under the slot. Reuse `AppTile`'s icon
  composable if it is callable without the grid; otherwise `IconImage`/`ArtImage` used by `AppTile`.
- Focus: `Pressable`, white plate when focused.
- Center: `onLaunch(entry, bounds)`; if empty slot: open the picker.
- Long press or Menu: open the picker in "replace" mode for that slot.
- Picker: glass panel listing `vm.apps` (non-folder, not already in another slot); choice writes
  `DockStore.save` and returns focus to the slot.
- Up: if column 0–? move focus back to the window when the window is above; the first slot's Up
  goes to the weather; other slots' Up go to the window when `slot index` is near the right edge,
  else stay/clamp. Implement the simple rule from the spec: slot 0 Up → weather, slots 3–5 Up →
  window, slots 1–2 Up → nothing.
- Down: nothing (bottom).
- Keys: Left/Right move slots, Center launches, Menu picker, Up per rule.

**Focus restoration:** expose a `FocusRequester` for slot 0; `HomeScreen` requests it when
`tv.focusTarget == Dock` or after closing All Apps.

**Verify:** on TV with 6 apps, focus path window ↔ dock ↔ weather follows the spec.

**Commit:** `Add home app dock`

---

## Task 11: HomeScreen All Apps mode and new layout

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/HomeViewModel.kt` (add `Overlay.AllApps`)
- Modify: `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/app/orchard/tv/MainActivity.kt` (pass `tv`, wrap overlays)

**Step 1: Add the overlay**

```kotlin
data object AllApps : Overlay
```

**Step 2: HomeScreen parameters**

Add `tv: TvController` after `vm`. `MainActivity` creates `tv` before `setContent` and passes it.

**Step 3: Mode switch**

At the top of the composable body:

```kotlin
val allApps = overlay is Overlay.AllApps
```

Rules:
- When `allApps`, render the existing grid, shelf clock, status pill, tiles, folder, menus.
- When not `allApps`, render the launcher home: shelf clock (left), `TvWindow` (right),
  `HomeDock` (bottom), status pill (top right). Do not render the grid, `AppTile`s, folder, or
  move hint.
- Shared, always: background/wallpaper/film/screensaver apparatus, and all overlays except the
  grid.
- `BackHandler` when not `allApps` and `overlay == Overlay.None`: open the screensaver (respecting
  `AerialSettings.backStartsScreensaver`); when the city picker/channel list is open, those handle
  Back first.
- Entering All Apps: `tv.pauseForAllApps()`, set `Overlay.AllApps`, focus the first app. Exiting:
  `tv.resumeFromAllApps()`, focus the window (or dock if the window is an icon).
- The status pill on launcher Home focuses the window first (not the first app): change
  `StatusPill.onFocused` to request `windowRequester` when not `allApps`.
- `ShelfClock` weather gets `onWeatherClick` only on launcher Home.
- `HomeScreen` handles Up from the window to the pill (`tv`), and the pill's Left goes to weather
  (the existing pill has no Left handling; add an `onKey` in `StatusPill` or request weather
  directly from `ShelfClock`). Keep the spec path: window Up → pill; pill Left → weather; weather
  Right → window; dock slot 0 Up → weather.

Concretely, add to the existing `StatusPill` call an `onKey` hook: expose `onLeft: (() -> Unit)?`
and handle `KEYCODE_DPAD_LEFT` there. `StatusPill` is in `HomeChrome.kt`.

**Step 4: Layout**

Use `BoxWithConstraints`; positions:
- Clock: `Modifier.align(Alignment.TopStart).padding(start = GridMargin, top = 74.dp)`.
- Window: `Modifier.align(Alignment.TopEnd).padding(end = GridMargin, top = 74.dp)`.
- Dock: `Modifier.align(Alignment.BottomCenter).padding(bottom = GridMargin - 8.dp)`.
- All Apps: existing metrics and grid.

Do not change `GridMetrics.clockY`; reuse it.

**Step 5: Focus on first frame**

```kotlin
val windowRequester = remember { FocusRequester() }
val dockRequester = remember { FocusRequester() }
LaunchedEffect(tv.focusTarget, tv.window, vm.loaded) {
    if (!vm.loaded || allApps) return@LaunchedEffect
    withFrameNanos { }
    when (tv.focusTarget) {
        TvFocusTarget.Window -> runCatching { windowRequester.requestFocus() }
        TvFocusTarget.Dock -> runCatching { dockRequester.requestFocus() }
        TvFocusTarget.None -> if (tv.current != null && !tv.focused) runCatching { windowRequester.requestFocus() }
    }
    tv.consumeFocus()
}
```

`dockRequester` must be the slot-0 requester passed into `HomeDock`.

**Step 6: Home reset**

`LaunchedEffect(windowFocused, vm.homeResetPending, vm.loaded)` currently focuses app 0. On launcher
Home request the window instead; on All Apps keep app 0.

**Step 7: Compile and commit**

```bash
./gradlew compileStandardDebugKotlin
git commit -am "Add launcher home layout with IPTV window and dock"
```

---

## Task 12: MainActivity screensaver and idle wiring

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/MainActivity.kt`

**Step 1: Own the controller**

```kotlin
private lateinit var tv: TvController
```

In `onCreate`, before `setContent`:

```kotlin
tv = TvController(this, lifecycleScope)
tv.start()
```

In `onDestroy`: `tv.release()`.

**Step 2: Home resets**

- `resumeWork()`: `tv.onHomeResumed()` and `tv.reload()` only if `TvSources.stamp() != lastStamp`
  (store `lastStamp`).
- `launch(app, bounds)`: `tv.onAppLaunched()` before `startActivity`.
- `openBootSource`: also `tv.onAppLaunched()` before `open`, so returning to Home resumes.

**Step 3: Idle**

`restartIdleTimer` currently posts `showScreensaver`. Change `showScreensaver`:

```kotlin
private val showScreensaver = Runnable {
    if (vm.screensaverVisible || !vm.resumed || vm.overlay !is Overlay.None) return@Runnable
    if (tv.window == TvWindow.Fullscreen) { restartIdleTimer(); return@Runnable }
    tv.onIdle(canPlay = tv.current != null)
    if (tv.window == TvWindow.Window && tv.focused) {
        startScreensaver(asked = false)
    } else {
        vm.screensaverVisible = false
        restartIdleTimer()
    }
}
```

The state machine's `StartScreensaver` effect is not used by `MainActivity` directly; the condition
above is the source of truth. Keep `tv.focused` fresh from `TvWindow.onWindowFocus`.

**Step 4: Screensaver start/stop**

In `startScreensaver`:
- `tv.onManualScreensaver()` when called from Back / Control Center; for idle use `tv.onIdle`
  (already called).
- `tv.closePanels()`.
- `vm.screensaverVisible = true`.

In `hideScreensaver()`:
- `tv.onScreensaverWoke()`.
- If `tv.window == TvWindow.Icon` or `Stopped`, leave it; `focusTarget = Dock` from the state
  machine.
- If `tv.window == TvWindow.Preview` (auto fullscreen while focused), exit fullscreen and stop to
  icon: call `tv.exitFullscreen()` then `tv.onWindowFocus(false)`.

Add the focused-window screensaver auto-fullscreen rule:

```kotlin
if (start && tv.focused && tv.window == TvWindow.Window && tv.playback == TvPlayback.Playing) {
    tv.enterFullscreen()
}
```

Keep it in `showScreensaver` after `startScreensaver(asked = false)`.

**Step 5: Overlays and Back**

Wrap the `home` composable content:

```kotlin
Box {
    HomeScreen(...)
    TvOverlays(tv, onOpenCity = { })
}
```

`TvOverlays` needs no city callback if `CityPicker` calls `Weather.choose` itself; remove the
parameter from Task 8's skeleton.

**Step 6: Permissions**

In `onCreate` or `startWork`, if `Build.VERSION.SDK_INT in 23..32` and
`checkSelfPermission(READ_EXTERNAL_STORAGE) != GRANTED`, `requestPermissions` once. `TvSources.load`
already falls back to assets, so permission is an enhancement, not a crash path.

**Step 7: Compile and device check**

```bash
./gradlew assembleStandardRelease
source local/tvs.env && BUILD=1 KEEP_ADB=1 TV=$XIAOMI_TV tools/install.sh
```

If signing blocks install (no `local/signing.properties`), do not uninstall the user's Aerial;
report the blocker and verify on the debug build on a spare device.

**Step 8: Commit**

```bash
git commit -am "Wire IPTV window into Home idle and screensaver"
```

---

## Task 13: Strings, manifest, install script

**Files:**
- `app/src/main/res/values/strings.xml`, `values-zh`, `values-ru`, `values-uk`, `values-kk`
- `app/src/main/AndroidManifest.xml`
- `tools/install.sh`

Add the same keys to all five locales (placeholders identical):

| key | en | zh |
|---|---|---|
| `tv_no_source` | No channels | 无频道 |
| `tv_loading` | Loading… | 加载中… |
| `tv_channel_list` | Channels | 频道 |
| `tv_fullscreen_hint` | OK: channel list · Back: exit | OK：频道列表 · 返回：退出 |
| `tv_city_pick` | Choose city | 选择城市 |
| `tv_city_province` | Province | 省份 |
| `tv_city_current` | Current: %1$s | 当前：%1$s |
| `tv_dock_empty` | Add app | 添加应用 |
| `tv_dock_replace` | Replace | 替换 |
| `tv_all_apps` | All apps | 全部应用 |
| `tv_source_file` | CBN channel file | 广电频道文件 |

Russian/Ukrainian/Kazakh: translate directly; keep `%1$s` unchanged.

Manifest, inside `<manifest>`:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
```

`tools/install.sh` line 190: add `READ_EXTERNAL_STORAGE` to the existing `pm grant` line.

**Verify:** `./gradlew assembleStandardRelease assembleGuardRelease`.

**Commit:** `Add IPTV strings and storage permission`

---

## Task 14: Final verification

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew testStandardDebugUnitTest
./gradlew assembleStandardRelease assembleGuardRelease
```

On the TV, in order:
1. Cold boot: Home shows clock/weather left, window top right with the last channel, focus on the
   window, dock at the bottom.
2. Left/Right changes channels; OSD shows the name; OK enters fullscreen; fullscreen Left/Right,
   OK list, Back exit.
3. Down goes to the dock; slot 0 Up goes to weather; weather OK opens provinces → cities →
   selection updates the weather.
4. Window Up → pill, pill Left → weather; weather Right returns to the window.
5. All Apps page opens (dock action/Back path), pauses the channel, Back resumes.
6. Wait for the screensaver with focus off the window: playback stops, the icon stays.
   With focus on the window: fullscreen; Back returns to the icon; focusing the icon previews;
   leaving the icon immediately stops and shrinks.
7. Opening any app and pressing Home resumes the channel.
8. Unplug `/sdcard/iptv_sources.json` (rename it): fallback `hnyd.m3u` loads.
9. `dumpsys media.player`/ExoPlayer logs: no leaked surfaces; switching window ↔ fullscreen does
   not leave two playing.

Record results in the PR/commit message. Do not claim success on any step not run on the TV.
