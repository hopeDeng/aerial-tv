# TV 源播放检测侧边栏 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 手机页点「播放检测」后由电视切源并全屏播放，电视二维码面板缩成右侧栏，显示当前源信息、实际播放状态、网络和画面质量指标。

**Architecture:** 手机只发 POST 命令，不再播放视频；`TvController` 负责切源、禁止检测期间自动换源、启动一次 `TvSpeedTest`。播放器通过 `TvVideoStats` 上报真实宽高/帧率/码率，侧边栏用纯函数 `TvQuality` 合并画面参数和测速结果。删除网页播放器、HLS 代理和 `hls.js`。

**Tech Stack:** Kotlin、Jetpack Compose、NanoHTTPD、LibVLC 3.7.7、Media3 1.8.1、JUnit 4。

---

### Task 1: 质量模型与格式化

**Files:**
- Create: `app/src/main/java/app/orchard/tv/tv/TvQuality.kt`
- Create: `app/src/test/java/app/orchard/tv/tv/TvQualityTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvQualityTest {
    private val probe = TvSpeedTest.Result(
        url = "http://example/live.m3u8",
        verdict = TvSpeedTest.Verdict.HD,
        mbps = 23.7,
        ms = 900,
        status = 200,
        note = null,
        finalUrl = "http://example/live.m3u8",
        bitrateKbps = 8000,
        width = 1280,
        height = 720,
    )

    @Test fun `player stats win over probe stats`() {
        val video = TvVideoStats(1920, 1080, 50.0, 4500, "ExoPlayer")
        val q = TvQuality.from(video, probe)
        assertEquals(23.7, q.speedMbps!!, 0.001)
        assertEquals(4500, q.bitrateKbps)
        assertEquals(1920, q.width)
        assertEquals(1080, q.height)
        assertEquals(50.0, q.fps!!, 0.001)
    }

    @Test fun `probe fills values the player does not report`() {
        val q = TvQuality.from(null, probe)
        assertEquals(8000, q.bitrateKbps)
        assertEquals(1280, q.width)
        assertEquals(720, q.height)
        assertNull(q.fps)
    }

    @Test fun `each missing player field falls back independently`() {
        val video = TvVideoStats(1280, 720, null, null, "LibVLC")
        val q = TvQuality.from(video, probe)
        assertEquals(8000, q.bitrateKbps)
        assertEquals(1280, q.width)
        assertEquals(720, q.height)
        assertNull(q.fps)
    }

    @Test fun `metrics format for the TV panel`() {
        assertEquals("23.7 Mbps", formatMbps(23.74))
        assertEquals("8.0 Mbps", formatBitrate(8000))
        assertEquals("512 kbps", formatBitrate(512))
        assertEquals("1920×1080", formatResolution(1920, 1080))
        assertEquals("50 fps", formatFps(50.0))
        assertEquals("—", formatMbps(null))
        assertEquals("—", formatBitrate(null))
        assertEquals("—", formatResolution(null, 1080))
        assertEquals("—", formatFps(null))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew testStandardDebugUnitTest --tests 'app.orchard.tv.tv.TvQualityTest'
```

Expected: 编译失败，`TvQuality`、`TvVideoStats`、`formatMbps` 等符号不存在。

- [ ] **Step 3: 写最小实现**

```kotlin
package app.orchard.tv.tv

import java.util.Locale

data class TvVideoStats(
    val width: Int,
    val height: Int,
    val fps: Double?,
    val bitrateKbps: Int?,
    val engine: String,
)

data class TvQuality(
    val speedMbps: Double?,
    val bitrateKbps: Int?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
) {
    companion object {
        fun from(video: TvVideoStats?, test: TvSpeedTest.Result?): TvQuality {
            val width = video?.width?.takeIf { it > 0 } ?: test?.width
            val height = video?.height?.takeIf { it > 0 } ?: test?.height
            return TvQuality(
                speedMbps = test?.mbps,
                bitrateKbps = video?.bitrateKbps?.takeIf { it > 0 } ?: test?.bitrateKbps,
                width = width,
                height = height,
                fps = video?.fps?.takeIf { it > 0 },
            )
        }
    }
}

fun formatMbps(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "—"

fun formatBitrate(kbps: Int?): String = when {
    kbps == null -> "—"
    kbps >= 1000 -> String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
    else -> "$kbps kbps"
}

fun formatResolution(width: Int?, height: Int?): String =
    if (width != null && height != null && width > 0 && height > 0) "${width}×${height}" else "—"

fun formatFps(fps: Double?): String =
    fps?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.0f fps", it) } ?: "—"
```

- [ ] **Step 4: 跑测试确认通过**

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew testStandardDebugUnitTest --tests 'app.orchard.tv.tv.TvQualityTest'
```

Expected: `BUILD SUCCESSFUL`，4 个测试通过。

---

### Task 2: 播放器上报真实画面参数

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/tv/TvVideoPlayer.kt`
- Modify: `app/src/main/java/app/orchard/tv/tv/TvPlayer.kt`
- Modify: `app/src/main/java/app/orchard/tv/tv/VlcTvPlayer.kt`
- Modify: `app/src/main/java/app/orchard/tv/tv/ExoPlayerTvPlayer.kt`

- [ ] **Step 1: 给播放器接口加回调**

在 `TvVideoPlayer` 里加：

```kotlin
var onVideoStats: ((TvVideoStats?) -> Unit)?
```

- [ ] **Step 2: 门面转发两个引擎的回调**

在 `TvPlayer` 里加：

```kotlin
var onVideoStats: ((TvVideoStats?) -> Unit)? = null
```

在 `init` 的 `vlc.onPlaybackChanged` 旁加：

```kotlin
vlc.onVideoStats = { onVideoStats?.invoke(it) }
```

在 `ensureExo()` 创建 Exo 后加：

```kotlin
exo.onVideoStats = { onVideoStats?.invoke(it) }
```

在 `play()` 开头、`stop()` 结尾、`switchToExo()` / `switchToVlc()` 切换前清空旧值：

```kotlin
onVideoStats?.invoke(null)
```

- [ ] **Step 3: LibVLC 读当前视频轨**

在 `VlcTvPlayer` 里加：

```kotlin
override var onVideoStats: ((TvVideoStats?) -> Unit)? = null

private fun publishStats() {
    val track = runCatching { mp?.currentVideoTrack }.getOrNull() ?: return
    if (track.width <= 0 || track.height <= 0) return
    val fps = if (track.frameRateNum > 0 && track.frameRateDen > 0) {
        track.frameRateNum.toDouble() / track.frameRateDen
    } else {
        null
    }
    onVideoStats?.invoke(
        TvVideoStats(
            width = track.width,
            height = track.height,
            fps = fps,
            bitrateKbps = track.bitrate.takeIf { it > 0 }?.div(1000),
            engine = "LibVLC",
        ),
    )
}
```

在 `Playing` 和 `Vout` 事件里调用 `publishStats()`；在 `layoutListener` 最后也调用一次。
在 `play()` 开头和 `stop()` 里调用 `onVideoStats?.invoke(null)`。

- [ ] **Step 4: ExoPlayer 读 VideoSize/Format**

在 `ExoPlayerTvPlayer` 里加：

```kotlin
override var onVideoStats: ((TvVideoStats?) -> Unit)? = null

private fun publishStats(size: VideoSize? = null) {
    val format = player.videoFormat
    val width = size?.width?.takeIf { it > 0 } ?: format?.width?.takeIf { it > 0 } ?: return
    val height = size?.height?.takeIf { it > 0 } ?: format?.height?.takeIf { it > 0 } ?: return
    onVideoStats?.invoke(
        TvVideoStats(
            width = width,
            height = height,
            fps = format?.frameRate?.takeIf { it > 0 },
            bitrateKbps = format?.bitrate?.takeIf { it > 0 }?.div(1000),
            engine = "ExoPlayer",
        ),
    )
}
```

在 `Player.Listener` 里加：

```kotlin
override fun onVideoSizeChanged(videoSize: VideoSize) = publishStats(videoSize)
```

在 `onPlaybackStateChanged` 的 `STATE_READY` 分支和 `onIsPlayingChanged(true)` 里调用 `publishStats()`。
在 `play()` 开头、`stop()` 里调用 `onVideoStats?.invoke(null)`。

- [ ] **Step 5: 编译**

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew compileStandardDebugKotlin
```

Expected: `BUILD SUCCESSFUL`。

---

### Task 3: 控制器检测状态与禁止自动换源

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/tv/TvController.kt`
- Modify: `app/src/main/java/app/orchard/tv/tv/TvQuality.kt`

- [ ] **Step 1: 在 TvQuality.kt 加检测状态**

```kotlin
data class TvSourceTest(
    val channelIndex: Int,
    val channelName: String,
    val source: TvSource,
    val running: Boolean,
    val result: TvSpeedTest.Result? = null,
)
```

- [ ] **Step 2: 控制器新增状态**

在 `playback` 后加：

```kotlin
var sourceTest by mutableStateOf<TvSourceTest?>(null); private set
var videoStats by mutableStateOf<TvVideoStats?>(null); private set
```

在私有字段区加：

```kotlin
private var probeJob: Job? = null
private var testingSource = false
```

在 `init` 的播放器回调旁加：

```kotlin
player.onVideoStats = { videoStats = it }
```

- [ ] **Step 3: 实现 testSource 和清理**

在 `switchSource` 后加：

```kotlin
fun testSource(channelIndex: Int, sourceIndex: Int): Boolean {
    val channel = channels.getOrNull(channelIndex) ?: return false
    val source = channel.sources.getOrNull(sourceIndex)?.takeIf { it.enabled } ?: return false
    probeJob?.cancel()
    testingSource = true
    failed.clear()
    val at = channelIndex to source
    current = at
    sourceTest = TvSourceTest(channelIndex, channel.name, source, running = true)
    play(at)
    if (window != TvWindow.Fullscreen) enterFullscreen()
    probeJob = scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { TvSpeedTest.test(source.url) }.getOrElse {
                TvSpeedTest.Result(
                    url = source.url,
                    verdict = TvSpeedTest.Verdict.FAILED,
                    mbps = null,
                    ms = 0,
                    status = null,
                    note = it.javaClass.simpleName,
                    finalUrl = null,
                )
            }
        }
        if (sourceTest?.source?.url == source.url) {
            sourceTest = sourceTest?.copy(running = false, result = result)
        }
    }
    return true
}

private fun clearSourceTest() {
    probeJob?.cancel()
    probeJob = null
    testingSource = false
    sourceTest = null
}
```

- [ ] **Step 4: 正常换台/换源时清理**

在 `onNext`、`onPrev`、`playChannel`、`playChannelSource`、`switchSource` 的开头调用 `clearSourceTest()`。
在 `reloadIfChanged` 的 `loadJob` 内、`logLoaded(loadedChannels)` 后调用 `clearSourceTest()`。

- [ ] **Step 5: 检测时禁止自动换源**

把 `retry()` 开头改成：

```kotlin
private fun retry() {
    val at = current ?: return
    if (testingSource) {
        apply(TvEvent.PlaybackFailed)
        return
    }
    failed += at.second.url
    retryJob?.cancel()
    retryJob = scope.launch {
        delay(1_500)
        val next = channels.getOrNull(at.first)?.playableSources()
            ?.firstOrNull { it.url !in failed }
            ?.let { at.first to it }
        if (next != null) {
            play(next, remember = false)
        } else {
            if (window == TvWindow.Fullscreen) showOsd(context.getString(R.string.tv_all_sources_failed))
            apply(TvEvent.PlaybackFailed)
        }
    }
}
```

- [ ] **Step 6: 关闭/退出时清理**

在 `stopToCollapsed()` 开头、`TvEffect.Stop` 分支里调用 `clearSourceTest()`。
在 `release()` 里调用 `probeJob?.cancel()`。
在 `play()` 开头加 `videoStats = null`。

- [ ] **Step 7: 打开二维码时丢弃过期检测**

在 `openManager()` 设置 `managerOpen = true` 后加：

```kotlin
if (sourceTest?.source?.url != current?.second?.url) clearSourceTest()
```

- [ ] **Step 8: 编译**

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew compileStandardDebugKotlin
```

Expected: `BUILD SUCCESSFUL`。

---

### Task 4: 手机页触发电视播放的 API

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/tv/TvPhoneServer.kt`

- [ ] **Step 1: 加路由**

在 `/api/source/add` 之前加：

```kotlin
session.method == Method.POST && path == "/api/source/test-play" -> testPlay(session)
```

- [ ] **Step 2: 加处理函数**

在 `testStatus` 前加：

```kotlin
private fun testPlay(session: IHTTPSession): Response {
    val f = form(session)
    val channel = f["channel"]?.toIntOrNull()
    val source = f["source"]?.toIntOrNull()
    if (channel == null || source == null) {
        return errorResponse(Response.Status.BAD_REQUEST, "bad source")
    }
    val ok = onMain { controller?.testSource(channel, source) ?: false }
    return if (ok) json(JSONObject().put("ok", true))
    else errorResponse(Response.Status.BAD_REQUEST, "bad source")
}
```

- [ ] **Step 3: 编译**

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew compileStandardDebugKotlin
```

Expected: `BUILD SUCCESSFUL`。

---

### Task 5: 五种语言文案

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values-uk/strings.xml`
- Modify: `app/src/main/res/values-kk/strings.xml`

- [ ] **Step 1: 在五份文件里加同一组 key**

| key | zh | en | ru | uk | kk |
|---|---|---|---|---|---|
| tv_quality_current | 当前源 | Current source | Текущий источник | Поточне джерело | Ағымдағы дереккөз |
| tv_quality_waiting | 未检测 | Not tested | Не проверено | Не перевірено | Тексерілмеген |
| tv_quality_testing | 检测中… | Testing… | Проверка… | Перевірка… | Тексерілуде… |
| tv_quality_speed | 速度 | Speed | Скорость | Швидкість | Жылдамдық |
| tv_quality_bitrate | 码率 | Bitrate | Битрейт | Бітрейт | Битрейт |
| tv_quality_resolution | 分辨率 | Resolution | Разрешение | Роздільна здатність | Ажыратымдылық |
| tv_quality_fps | 帧率 | Frame rate | Частота кадров | Частота кадрів | Кадр жиілігі |
| tv_playback_idle | 未播放 | Idle | Не играет | Не відтворюється | Ойнатылмайды |
| tv_playback_buffering | 缓冲中 | Buffering | Буферизация | Буферизація | Буферлеу |
| tv_playback_playing | 播放中 | Playing | Идёт воспроизведение | Відтворюється | Ойнатылуда |
| tv_playback_paused | 已暂停 | Paused | Пауза | Пауза | Кідіріс |
| tv_playback_error | 播放失败 | Playback failed | Ошибка воспроизведения | Помилка відтворення | Ойнату қатесі |
| tv_verdict_hd | 高清流畅 | HD smooth | HD без буферизации | HD без буферизації | HD тежелусіз |
| tv_verdict_smooth | 流畅 | Smooth | Стабильно | Стабільно | Тұрақты |
| tv_verdict_slow | 较慢 | Slow | Медленно | Повільно | Баяу |
| tv_verdict_unusable | 太慢 | Too slow | Слишком медленно | Занадто повільно | Тым баяу |
| tv_verdict_failed | 不可达 | Unreachable | Недоступен | Недоступний | Қолжетімсіз |
| tv_verdict_encrypted | 加密 | Encrypted | Зашифрован | Зашифрований | Шифрланған |

- [ ] **Step 2: 编译资源**

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew processStandardDebugResources
```

Expected: `BUILD SUCCESSFUL`。

---

### Task 6: 二维码右侧栏

**Files:**
- Modify: `app/src/main/java/app/orchard/tv/ui/tv/TvChannelManager.kt`

- [ ] **Step 1: 替换根布局**

保留现有的 `managerOpen`、焦点、`BackHandler`、二维码加载和按键处理。把居中 `Box` 的
`contentAlignment` 改成 `Alignment.CenterEnd`，去掉整屏黑底，只保留透明可聚焦层。

外层侧栏：

```kotlin
Column(
    Modifier
        .padding(end = 24.dp, top = 24.dp, bottom = 24.dp)
        .width(360.dp)
        .fillMaxHeight()
        .clip(RoundedCornerShape(24.dp))
        .background(Color.Black.copy(alpha = 0.90f))
        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
        .padding(20.dp),
) {
    // 内容见下一步
}
```

- [ ] **Step 2: 放入标题、二维码和地址**

```kotlin
BasicText(
    stringResource(R.string.tv_manage_title),
    style = Type.Title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White),
)
Spacer(Modifier.height(8.dp))
Box(
    Modifier
        .align(Alignment.CenterHorizontally)
        .size(140.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(Color.White)
        .padding(10.dp),
    contentAlignment = Alignment.Center,
) {
    when {
        qr != null -> Image(qr!!, null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
        tv.managerLoading -> BasicText(stringResource(R.string.tv_manage_starting), style = Type.Caption.copy(color = Color.Black.copy(alpha = 0.55f)))
        else -> GlyphIcon(Glyph.Offline, Color.Black.copy(alpha = 0.35f), 32.dp)
    }
}
Spacer(Modifier.height(8.dp))
BasicText(
    when {
        tv.managerLoading -> stringResource(R.string.tv_manage_starting)
        url == null -> stringResource(R.string.tv_manage_offline)
        else -> url.removePrefix("http://")
    },
    style = Type.Caption.copy(fontSize = 12.sp, color = if (url != null) Color(0xFFFFC107) else Color.White.copy(alpha = 0.7f)),
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
)
```

- [ ] **Step 3: 显示当前源**

```kotlin
val current = tv.current
val channel = current?.let { tv.channels.getOrNull(it.first) }
val source = current?.second
val test = tv.sourceTest?.takeIf { it.source.url == source?.url }
val quality = TvQuality.from(tv.videoStats, test?.result)
val title = when {
    channel == null || source == null -> stringResource(R.string.tv_no_source)
    source.label.isBlank() -> channel.name
    else -> "${channel.name} · ${source.label}"
}
```

在分隔线后依次显示 `tv_quality_current`、`title`、播放状态、源地址。

- [ ] **Step 4: 评级徽标**

```kotlin
val (verdictText, verdictColor) = when {
    test == null -> stringResource(R.string.tv_quality_waiting) to Color.White.copy(alpha = 0.45f)
    test.running -> stringResource(R.string.tv_quality_testing) to Color(0xFFFFC107)
    else -> when (test.result?.verdict) {
        TvSpeedTest.Verdict.HD -> stringResource(R.string.tv_verdict_hd) to Color(0xFF7ED6A5)
        TvSpeedTest.Verdict.SMOOTH -> stringResource(R.string.tv_verdict_smooth) to Color(0xFF7ED6A5)
        TvSpeedTest.Verdict.SLOW -> stringResource(R.string.tv_verdict_slow) to Color(0xFFFFC107)
        TvSpeedTest.Verdict.ENCRYPTED -> stringResource(R.string.tv_verdict_encrypted) to Color(0xFF9E9E9E)
        TvSpeedTest.Verdict.UNUSABLE -> stringResource(R.string.tv_verdict_unusable) to Color(0xFFFF8A65)
        else -> stringResource(R.string.tv_verdict_failed) to Color(0xFFFF6B6B)
    }
}
```

徽标用 `clip(RoundedCornerShape(50))`、`background(verdictColor.copy(alpha = 0.16f))` 和同色文字。

源地址文本：

```kotlin
BasicText(
    source?.url.orEmpty(),
    style = Type.Caption.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f)),
    maxLines = 2,
    overflow = TextOverflow.Ellipsis,
)
```

- [ ] **Step 5: 2×2 指标卡**

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Metric(stringResource(R.string.tv_quality_speed), formatMbps(quality.speedMbps), Modifier.weight(1f))
    Metric(stringResource(R.string.tv_quality_bitrate), formatBitrate(quality.bitrateKbps), Modifier.weight(1f))
}
Spacer(Modifier.height(8.dp))
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Metric(stringResource(R.string.tv_quality_resolution), formatResolution(quality.width, quality.height), Modifier.weight(1f))
    Metric(stringResource(R.string.tv_quality_fps), formatFps(quality.fps), Modifier.weight(1f))
}
```

`Metric` 是文件内的私有 Composable：

```kotlin
@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicText(label, style = Type.Caption.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f)))
        Spacer(Modifier.height(2.dp))
        BasicText(value, style = Type.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White), maxLines = 1)
    }
}
```

- [ ] **Step 6: 播放状态和底部提示**

```kotlin
val status = when (tv.playback) {
    TvPlayback.Idle -> stringResource(R.string.tv_playback_idle)
    TvPlayback.Buffering -> stringResource(R.string.tv_playback_buffering)
    TvPlayback.Playing -> stringResource(R.string.tv_playback_playing)
    TvPlayback.Paused -> stringResource(R.string.tv_playback_paused)
    TvPlayback.Error -> stringResource(R.string.tv_playback_error)
}
```

在内容底部放 `Spacer(Modifier.weight(1f))`，再显示 `tv_manage_close`。
源地址用 `maxLines = 2`、`overflow = TextOverflow.Ellipsis`。

- [ ] **Step 7: 编译**

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew compileStandardDebugKotlin
```

Expected: `BUILD SUCCESSFUL`。

---

### Task 7: 手机页改为电视播放，删除网页播放器和代理

**Files:**
- Modify: `app/src/main/assets/web/tv.html`
- Delete: `app/src/main/assets/web/hls.min.js`
- Delete: `app/src/main/java/app/orchard/tv/tv/TvProxyUrls.kt`
- Delete: `app/src/test/java/app/orchard/tv/tv/TvProxyUrlsTest.kt`
- Modify: `app/src/main/java/app/orchard/tv/tv/TvPhoneServer.kt`

- [ ] **Step 1: 网页点击改为 POST**

把 `test-source` 分支改成：

```javascript
} else if (actName === 'test-source') {
  playOnTv(ci, si);
}
```

在 `closeSheet` 前加：

```javascript
async function playOnTv(ci, si) {
  try {
    await post('api/source/test-play', { channel: ci, source: si });
    toast(t('test_on_tv'));
  } catch (e) { toast(errText(e.message)); }
}
```

在五种语言的 `TEXTS` 里加：

```javascript
// zh
test_on_tv:'已在电视上播放，检测结果看电视二维码面板',
// en
test_on_tv:'Playing on the TV. Check the QR panel for results.',
// ru
test_on_tv:'Воспроизведение на телевизоре. Результаты — на панели с QR-кодом.',
// uk
test_on_tv:'Відтворення на телевізорі. Результати — на панелі з QR‑кодом.',
// kk
test_on_tv:'Теледидарда ойнатылуда. Нәтижелер QR панелінде.',
```

- [ ] **Step 2: 删除网页播放器**

删除 `<script src="hls.min.js"></script>`。

删除 CSS 中的 `.pvlive`、`.video`、`.qstats`、`.qstat`、`.qstatus` 规则。
删除 TEXTS 五种语言里的 `q_speed`、`q_bitrate`、`q_resolution`、`q_status`、
`q_playing`、`q_buffering`、`q_error`、`q_waiting`、`q_unsupported`。

删除 `let player`、`let playerSeq`、`stopPlayer()`、`statBox()`、`setText()`、
`setQualityStatus()`、`renderQuality()`、`updateLevel()`、`pollQuality()`、
`isDirectUrl()`、`startPlayer()`、`previewSheet()`。
删除 `closeSheet()` 和 `openSheet()` 中的 `stopPlayer()` 调用。

- [ ] **Step 3: 删除服务器代理**

删除路由：

```kotlin
path == "/hls.min.js" -> asset("web/hls.min.js", "application/javascript; charset=utf-8")
path == "/play" -> if (session.method == Method.OPTIONS) {
    cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
} else {
    proxySource(session)
}
```

删除 `proxySource()`、`proxyFetch()`、`isPlaylist()`、`readLimited()`、`cors()`，
以及只被它们使用的 import 和 `MAX_PLAYLIST`。

- [ ] **Step 4: 删除文件**

```bash
rm app/src/main/assets/web/hls.min.js
rm app/src/main/java/app/orchard/tv/tv/TvProxyUrls.kt
rm app/src/test/java/app/orchard/tv/tv/TvProxyUrlsTest.kt
```

- [ ] **Step 5: JS 语法和编译**

```bash
python3 - <<'PY'
from pathlib import Path
import re
s=Path('app/src/main/assets/web/tv.html').read_text()
Path('/tmp/tv-inline.js').write_text(re.findall(r'<script>(.*?)</script>', s, re.S)[-1])
PY
node --check /tmp/tv-inline.js
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew compileStandardDebugKotlin
```

Expected: `node --check` 无输出；Gradle `BUILD SUCCESSFUL`。

---

### Task 8: 文档和真机验证

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/HANDOFF.md`

- [ ] **Step 1: 更新架构文档**

把「手机管理」段落改为：手机页只发 `POST /api/source/test-play`，电视切源并播放；
右侧二维码栏显示源信息、播放状态、测速和播放器真实参数；网页不播放视频。
删除 `/hls.min.js`、`/play`、`hls.js`、`TvProxyUrls` 的描述。
在 API 列表加 `POST /api/source/test-play`。

- [ ] **Step 2: 更新交接文档**

记录本次改动、五语言文案、检测期间不自动换源，以及真机验证结果。
如果还没在电视上验证，明确写「未真机验证」。

- [ ] **Step 3: 完整构建和 lint**

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew testStandardDebugUnitTest assembleStandardRelease assembleGuardRelease
./gradlew lintStandardDebug
```

Expected: 构建成功；lint 只剩既有 9 个错误。

- [ ] **Step 4: 安装**

```bash
adb -s 192.168.1.21:5555 install -r app/build/outputs/apk/standard/release/app-standard-release.apk
adb -s 192.168.1.21:5555 shell cmd package compile -m speed -f app.orchard.tv
adb -s 192.168.1.21:5555 shell am force-stop app.orchard.tv
adb -s 192.168.1.21:5555 shell am start -W -n app.orchard.tv/.MainActivity
```

- [ ] **Step 5: 真机调用接口**

电视全屏后按上键打开二维码面板，从 `uiautomator dump` 取当前 `/token/`，
然后用 curl：

```bash
curl -sS -X POST -d 'channel=0' -d 'source=0' \
  http://192.168.1.21:8791/<token>/api/source/test-play
```

Expected: `{"ok":true}`；电视切到 CCTV1/hnyd 并开始播放。

- [ ] **Step 6: 检查侧边栏**

```bash
adb -s 192.168.1.21:5555 shell uiautomator dump /sdcard/window.xml
adb -s 192.168.1.21:5555 shell cat /sdcard/window.xml
```

Expected: XML 里能找到当前源、播放中、速度、码率、分辨率、帧率；数值不是全部 `—`。
读 logcat 确认测速完成；`TvPlayback.Error` 时检查是否没有自动跳到别的源。

- [ ] **Step 7: 走手机页**

在手机/电脑打开同一个 `/token/`，点 CCTV1/hnyd 的「播放检测」，确认：
电视切源、二维码侧栏出现指标、关闭侧栏后播放继续、没有浏览器视频弹窗。
