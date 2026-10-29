# 电视端源播放检测与二维码侧边栏

日期：2026-10-28

## 目标

手机频道页的「播放检测」不再在浏览器里播放视频，而是让电视切到指定频道/源并全屏播放。
二维码面板从居中弹窗改成右侧窄侧边栏，在检测期间显示当前源的基本信息、播放状态和网络/画面质量指标。

## 非目标

- 不改变批量测速、频道/源增删改、导入导出。
- 不改变 LibVLC 优先、ExoPlayer 回退的播放策略。
- 不在网页里保留第二套视频播放器、HLS 代理或播放统计。

## 交互

1. 用户在手机页点某个源的「播放检测」。
2. 页面调用 `POST /api/source/test-play`，表单字段 `channel`、`source` 是频道和源的下标。
3. 电视切到该源、清空失败标记、开始全屏播放；二维码面板保持打开。
4. 控制器同时启动一次 `TvSpeedTest`，并把播放器上报的真实画面参数更新到侧边栏。
5. 用户按上键/返回关闭侧边栏；播放继续，检测结果保留。
6. 检测期间该源失败时不自动换源，侧边栏显示「播放失败」，避免检测 A 却实际播放 B。
7. 用户之后正常换台/换源时结束检测模式并清除旧的检测结果。

## 侧边栏布局

画布按 960×540dp。面板贴在右侧，宽 360dp，上下留 24dp，右边留 24dp；内部 20dp padding，
圆角 24dp，底色黑色 90% 不透明，保留左侧约 576dp 的画面可见。不使用覆盖全屏的深色遮罩。

从上到下：

1. 标题「用手机管理频道」，22sp。
2. 二维码，150dp 白底，居中。
3. 网页地址，12sp，单行省略。
4. 分隔线。
5. 「当前源」小标题。
6. 频道名 + 源标签，20sp，单行省略。
7. 播放状态，13sp：未播放 / 缓冲中 / 播放中 / 已暂停 / 播放失败。
8. 源地址，11sp，最多两行。
9. 评级徽标：高清流畅 / 流畅 / 较慢 / 太慢 / 不可达 / 加密 / 检测中 / 未检测。
10. 2×2 指标卡：
    - 速度：`23.7 Mbps`
    - 码率：真实播放器值优先，否则用测速声明/估算值
    - 分辨率：真实画面宽高优先，否则用清单声明
    - 帧率：真实播放器帧率，没有时显示 `—`
11. 底部提示「上键 / 返回：关闭」。

二维码启动中、无网络时沿用现有提示。面板高度不足时优先压缩地址和间距，不出现滚动条。

## 数据与组件

### `TvVideoStats`

播放器层新增纯数据：

```kotlin
data class TvVideoStats(
    val width: Int,
    val height: Int,
    val fps: Double?,
    val bitrateKbps: Int?,
    val engine: String,
)
```

- `TvVideoPlayer` 增加 `onVideoStats: ((TvVideoStats?) -> Unit)?`。
- LibVLC 在 `Playing` / `Vout` 后读 `getCurrentVideoTrack()`：宽高、`frameRateNum / frameRateDen`、
  `bitrate / 1000`。读不到时发 `null`。
- ExoPlayer 在 `onVideoSizeChanged` 和 `PLAYING`/`READY` 时读 `VideoSize` 与 `videoFormat`：
  宽高、`frameRate`、`bitrate / 1000`。
- 门面 `TvPlayer` 把两个引擎的回调转给控制器；换引擎、`play`、`stop` 时先清空旧参数。

### `TvSourceTest`

```kotlin
data class TvSourceTest(
    val channelIndex: Int,
    val channelName: String,
    val source: TvSource,
    val running: Boolean,
    val result: TvSpeedTest.Result? = null,
)
```

控制器新增：

- `var sourceTest: TvSourceTest?`：当前检测。
- `var videoStats: TvVideoStats?`：播放器真实画面参数。
- `fun testSource(channelIndex: Int, sourceIndex: Int)`：校验下标，切源、全屏、启动测速。
- `private var testingSource: Boolean`：检测期间禁止自动换源。
- 正常换台、换源、频道列表选台、刷新恢复时调用 `clearSourceTest()`。
- 重新打开二维码面板时，若旧检测的 URL 与当前源不同则丢弃，避免显示过期数据。

网络指标和画面指标分开更新。测速失败只把评级设为「不可达」，不覆盖仍然成功的播放状态；
播放失败只改播放状态，不抹掉已经测到的网络数据。

### HTTP

新增 `POST /api/source/test-play`：

- 成功：`{"ok": true}`。
- 频道或源下标无效：400。
- 由服务器通过 `onMain` 调 `TvController.testSource`，不改现有 `/api/test/start|status` 批量测速协议。

### 手机页

- 单源「播放检测」改为上面的 POST；成功后提示「已在电视上播放，检测结果看电视二维码面板」。
- 删除网页视频弹窗、`hls.js`、`/play` 代理、`/hls.min.js` 和 `TvProxyUrls`。
- 保留批量测速结果表；删除只服务于网页播放的 CSS/JS 文案。

## 文案

新增文案必须在 `values`、`values-zh`、`values-ru`、`values-uk`、`values-kk` 五份文件里保持一致：
当前源、未检测、检测中、速度、码率、分辨率、帧率、未播放、缓冲中、播放中、已暂停、
播放失败、高清流畅、流畅、较慢、太慢、不可达、加密、未知值 `—`。
判定词优先复用 `tv.html` 现有五种语言的翻译。

## 错误处理

- 手机请求的源已不存在或已禁用：400，页面提示失败。
- 网络测速失败：评级「不可达」，速度「—」，播放状态独立显示。
- 播放失败：评级仍可显示网络结果，播放状态「播放失败」；检测模式不自动切到该频道其他源。
- 侧边栏关闭或服务器 5 分钟后停止：检测任务和电视播放不受影响。
- 播放器不提供真实参数：码率/分辨率回退到测速结果，帧率显示 `—`。

## 验证

1. 新增纯函数单测，覆盖「真实画面参数优先、测速结果回退」和指标格式化。
2. `export JAVA_HOME=/usr/local/opt/openjdk@17 && ./gradlew testStandardDebugUnitTest assembleStandardRelease assembleGuardRelease`。
3. `./gradlew lintStandardDebug`：新文件不增加错误，剩余仍为既有 9 个。
4. 真机安装 release，打开电视全屏二维码面板，用 curl 调 `/api/source/test-play`。
5. `uiautomator dump` 确认侧边栏出现频道/源/播放状态/速度/码率/分辨率/帧率；`logcat` 确认播放器统计回调。
6. 手机页实际点「播放检测」，确认电视切源、侧边栏更新、关闭侧边栏后继续播放；测试失败源不自动换源。
