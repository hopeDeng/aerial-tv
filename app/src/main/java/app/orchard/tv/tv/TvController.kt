package app.orchard.tv.tv

import android.content.Context
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.orchard.tv.R
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Returns the remembered channel's own current source, or the nearest usable channel. */
internal fun restore(channels: List<TvChannel>, name: String?, sourceUrl: String? = null): Pair<Int, TvSource>? =
    restoreSameChannel(channels, name, sourceUrl) ?: TvPlaylist(channels).first()

/**
 * The channel to keep after an edit: same name first, then its old position onwards. Deleting the
 * channel being watched must not jump to the top of a 162-channel list.
 */
internal fun restoreAfterEdit(
    channels: List<TvChannel>,
    name: String?,
    sourceUrl: String?,
    previousIndex: Int,
): Pair<Int, TvSource>? {
    restoreSameChannel(channels, name, sourceUrl)?.let { return it }
    if (channels.isEmpty()) return null
    // Renaming the watched channel loses its name; its URL still identifies what should stay on.
    if (sourceUrl != null) {
        channels.forEachIndexed { index, channel ->
            channel.sources.firstOrNull { it.enabled && it.url == sourceUrl }?.let { return index to it }
        }
    }
    for (step in 0 until channels.size) {
        val at = (previousIndex.coerceAtLeast(0) + step) % channels.size
        channels[at].playableSources().firstOrNull()?.let { return at to it }
    }
    return null
}

private fun restoreSameChannel(
    channels: List<TvChannel>,
    name: String?,
    sourceUrl: String?,
): Pair<Int, TvSource>? {
    val sameName = channels.indexOfFirst { it.name == name }
    if (sameName < 0) return null
    val channel = channels[sameName]
    // A reload/save keeps the exact source being watched when it still exists. Ordinary starts
    // pass no URL: the file's currentSource is authoritative then, so an automatic failover does
    // not drag the next start onto a later source.
    if (sourceUrl != null) {
        channel.sources.firstOrNull { it.enabled && it.url == sourceUrl }?.let { return sameName to it }
    }
    return channel.playableSources().firstOrNull()?.let { sameName to it }
}

/**
 * The live-TV slot on Home: a tile-sized icon that previews while it has focus and shrinks back to
 * the icon (stopping) when focus leaves, and goes fullscreen on OK. There is no pin: the small
 * window never keeps playing on its own. Channels come from the set-top-box file with the bundled
 * list as fallback; playback is Media3 HLS with one round of source/channel retries.
 */
class TvController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val prefs = TvPrefs(context)
    var channels by mutableStateOf<List<TvChannel>>(emptyList()); private set
    var current by mutableStateOf<Pair<Int, TvSource>?>(null); private set
    var window by mutableStateOf(TvWindow.Collapsed); private set
    var playback by mutableStateOf(TvPlayback.Idle); private set
    var focused by mutableStateOf(false); private set
    var osd by mutableStateOf<String?>(null); private set
    var channelListOpen by mutableStateOf(false); private set
    var channelGridOpen by mutableStateOf(false); private set
    var epgOpen by mutableStateOf(false); private set
    var sourceListOpen by mutableStateOf(false); private set
    var cityPickerOpen by mutableStateOf(false); private set
    var managerOpen by mutableStateOf(false); private set
    var managerUrl by mutableStateOf<String?>(null); private set
    var managerLoading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var sourceTest by mutableStateOf<TvSourceTest?>(null); private set
    var videoStats by mutableStateOf<TvVideoStats?>(null); private set

    /** Bumped when the slot let go of the screen, so Home puts focus on the top row's last tile. */
    var releaseFocus by mutableStateOf(0); private set

    /** True while live video holds the decoder: Home pauses moving wallpapers. */
    val active get() = window == TvWindow.Preview || window == TvWindow.Fullscreen

    val player = TvPlayer(context)

    private var state = TvState()
    private var loadJob: Job? = null
    private var osdJob: Job? = null
    private var retryJob: Job? = null
    private var panelIdleJob: Job? = null
    private var managerJob: Job? = null
    private var probeJob: Job? = null
    private var testingSource = false
    private var started = false
    private var scrolledAway = false
    private var loadedStamp = 0L
    private val failed = mutableSetOf<String>()

    init {
        player.onPlaybackChanged = { value ->
            playback = value
            state = state.copy(playback = value)
        }
        player.onFailed = { retry() }
        player.onVideoStats = { videoStats = it }
    }

    fun start() {
        if (started) return
        started = true
        // Note the stamp before the read, not after: onResume can arrive while the file is still
        // being read, and an unrecorded stamp makes that reload cancel this read and never report
        // the list as loaded.
        loadedStamp = TvSources.stamp(context)
        loadJob = scope.launch {
            val loadedChannels = TvSources.load(context)
            channels = loadedChannels
            logLoaded(loadedChannels)
            current = restore(loadedChannels, prefs.lastChannel)
            loaded = true
            if (current == null) {
                state = state.copy(window = TvWindow.Stopped)
                window = TvWindow.Stopped
            }
        }
    }

    fun attach(view: TextureView) = player.attach(view, replay = active)
    fun detach(view: TextureView) = player.detach(view)

    fun reloadIfChanged(force: Boolean = false) {
        val stamp = TvSources.stamp(context)
        if (!force && stamp == loadedStamp) return
        loadedStamp = stamp
        loadJob?.cancel()
        loadJob = scope.launch {
            clearSourceTest()
            val loadedChannels = TvSources.load(context)
            val oldName = current?.let { channels.getOrNull(it.first)?.name } ?: prefs.lastChannel
            channels = loadedChannels
            failed.clear()
            logLoaded(loadedChannels)
            // A reload can win the race with start()'s own read (onResume arrives before the file
            // has been read once), and this is the job that survives: the list is in either way.
            loaded = true
            val next = restore(loadedChannels, oldName)
            current = next
            if (next == null) {
                stopToCollapsed()
            } else if (active) {
                play(next)
            }
        }
    }

    /**
     * The phone page saved a new list. Keep the source being watched when it still exists, otherwise
     * move to the same channel's next source; if that channel is gone, stay near its old position.
     */
    fun applyChannels(newChannels: List<TvChannel>) {
        val old = current
        val oldName = old?.let { channels.getOrNull(it.first)?.name } ?: prefs.lastChannel
        val oldUrl = old?.second?.url
        val oldIndex = old?.first ?: 0
        val wasActive = active
        channels = newChannels
        loaded = true
        loadedStamp = TvSources.stamp(context)
        failed.clear()
        clearSourceTest()
        logLoaded(newChannels)
        val next = restoreAfterEdit(newChannels, oldName, oldUrl, oldIndex)
        current = next
        if (next == null) {
            stopToCollapsed()
            return
        }
        if (wasActive) {
            val sameUrl = oldUrl != null && oldUrl == next.second.url && player.playingUrl == oldUrl
            if (sameUrl) {
                prefs.lastChannel = newChannels[next.first].name
                prefs.lastSource = next.second.url
            } else {
                play(next)
            }
        } else if (window == TvWindow.Stopped) {
            state = state.copy(window = TvWindow.Collapsed)
            window = TvWindow.Collapsed
        }
    }

    /** Home came to the front: refresh the list. Nothing auto-plays; the icon waits for focus. */
    fun onHomeResumed() {
        reloadIfChanged()
        if (!loaded) return
        val rememberedName = current?.let { channels.getOrNull(it.first)?.name } ?: prefs.lastChannel
        current = restore(channels, rememberedName)
        if (current == null && state.window == TvWindow.Collapsed) {
            state = state.copy(window = TvWindow.Stopped)
            window = TvWindow.Stopped
        }
    }

    /** Something else took the screen: an app we launched, the TV's input, Home paused. Back to the icon. */
    fun onAppLaunched() {
        apply(TvEvent.AppLaunched)
        focused = false
    }

    /**
     * Home's grid scrolled past its shelf and took the slot off the canvas with it. Live TV is part
     * of the top of Home, so it stops rather than playing from off screen.
     */
    fun onHomeScrolled(away: Boolean) {
        if (scrolledAway == away) return
        scrolledAway = away
        if (away) apply(TvEvent.LeftHome)
    }

    fun onWindowFocus(gained: Boolean) {
        focused = gained
        apply(if (gained) TvEvent.WindowGotFocus(hasChannel = current != null) else TvEvent.WindowLostFocus)
    }

    fun onClick() {
        if (window == TvWindow.Stopped) {
            current = restore(channels, prefs.lastChannel) ?: return
            failed.clear()
            state = state.copy(playback = TvPlayback.Idle)
        }
        if (current == null) return
        apply(TvEvent.WindowClicked)
    }

    fun onNext() {
        clearSourceTest()
        if (window == TvWindow.Collapsed || window == TvWindow.Stopped) return
        val from = current ?: return
        val next = playlist().nextChannel(from.first) ?: return
        failed.clear()
        showOsd(channelLabel(channels[next.first].name, next.second))
        current = next
        play(next)
    }

    fun onPrev() {
        clearSourceTest()
        if (window == TvWindow.Collapsed || window == TvWindow.Stopped) return
        val from = current ?: return
        val prev = playlist().prevChannel(from.first) ?: return
        failed.clear()
        showOsd(channelLabel(channels[prev.first].name, prev.second))
        current = prev
        play(prev)
    }

    fun playChannel(index: Int) {
        clearSourceTest()
        val channel = channels.getOrNull(index) ?: return
        val source = channel.playableSources().firstOrNull() ?: return
        failed.clear()
        val at = index to source
        current = at
        showOsd(channelLabel(channel.name, source))
        play(at)
        closePanels()
        if (window == TvWindow.Collapsed || window == TvWindow.Stopped) {
            enterFullscreen()
        }
    }

    fun playChannelSource(channelIndex: Int, source: TvSource) {
        clearSourceTest()
        if (channels.getOrNull(channelIndex) == null) return
        failed.clear()
        val at = channelIndex to source
        current = at
        showOsd(channelLabel(channels[channelIndex].name, source))
        play(at)
        closePanels()
        if (window == TvWindow.Collapsed || window == TvWindow.Stopped) {
            enterFullscreen()
        }
    }

    fun switchSource(offset: Int = 1) {
        clearSourceTest()
        val cur = current ?: return
        val channel = channels.getOrNull(cur.first) ?: return
        val sources = channel.sources.filter { it.enabled }
        if (sources.size <= 1) {
            showOsd(context.getString(R.string.tv_no_source))
            return
        }
        val idx = sources.indexOfFirst { it.url == cur.second.url }
        val nextIdx = if (idx < 0) 0 else (idx + offset) % sources.size
        val next = channelIndexToPair(cur.first, sources[nextIdx]) ?: return
        failed.clear()
        showOsd(channelLabel(channel.name, sources[nextIdx]))
        current = next
        play(next)
    }

    private fun channelIndexToPair(index: Int, source: TvSource): Pair<Int, TvSource>? {
        val channel = channels.getOrNull(index) ?: return null
        if (!source.enabled) return null
        return index to source
    }

    fun enterFullscreen() {
        if (current == null || window == TvWindow.Collapsed || window == TvWindow.Stopped) return
        state = state.copy(window = TvWindow.Fullscreen, focused = true)
        window = TvWindow.Fullscreen
        focused = true
        player.resume()
    }

    fun exitFullscreen() {
        closePanels()
        apply(TvEvent.FullscreenExited)
        releaseFocus()
    }

    /** The channel list is fullscreen's own: the window state does not change while it is up. */
    fun openChannelList() {
        closePanels()
        channelListOpen = true
        armPanelIdle()
    }

    fun openChannelGrid() {
        closePanels()
        channelGridOpen = true
        armPanelIdle()
    }

    fun openEpg() {
        if (window != TvWindow.Fullscreen) return
        closePanels()
        epgOpen = true
        armPanelIdle()
    }

    /** The sources of the channel now playing, as a strip like the guide. */
    fun openSourceList() {
        if (window != TvWindow.Fullscreen || current == null) return
        closePanels()
        sourceListOpen = true
        armPanelIdle()
    }

    /**
     * The phone page's QR panel. It is left out of the idle close on purpose: reading the code and
     * typing on a phone takes longer than 15 seconds, and the TV must keep serving meanwhile.
     */
    fun openManager() {
        if (window != TvWindow.Fullscreen) return
        closePanels()
        managerOpen = true
        if (sourceTest?.source?.url != current?.second?.url) clearSourceTest()
        managerLoading = true
        managerUrl = null
        managerJob?.cancel()
        managerJob = scope.launch {
            val url = withContext(Dispatchers.IO) { TvPhoneServer.start(context, this@TvController) }
            managerUrl = url
            managerLoading = false
            // The panel was closed while the server was starting. Keep the usual grace period so a
            // request already on its way can finish, then let it stop.
            if (!managerOpen) TvPhoneServer.stopAfterDelay()
        }
    }

    fun toggleEpg() {
        if (epgOpen) {
            closePanels()
        } else {
            openEpg()
        }
    }

    fun openCityPicker() {
        cityPickerOpen = true
        armPanelIdle()
    }

    /**
     * Closing the channel list leaves focus to whoever asked for it (fullscreen re-takes its own), and
     * the city picker to Home's weather line, which watches [cityPickerOpen].
     */
    fun closePanels() {
        val managerWasOpen = managerOpen
        channelListOpen = false
        channelGridOpen = false
        epgOpen = false
        sourceListOpen = false
        cityPickerOpen = false
        managerOpen = false
        panelIdleJob?.cancel()
        panelIdleJob = null
        if (managerWasOpen) {
            managerJob?.cancel()
            managerJob = null
            managerLoading = false
            TvPhoneServer.stopAfterDelay()
        }
    }

    private fun anyPanelOpen() =
        channelListOpen || channelGridOpen || epgOpen || sourceListOpen || cityPickerOpen

    /** Every key or touch while a panel is up restarts its 15-second idle close. */
    fun noteInteraction() {
        if (anyPanelOpen()) armPanelIdle()
    }

    private fun armPanelIdle() {
        panelIdleJob?.cancel()
        if (!anyPanelOpen()) return
        panelIdleJob = scope.launch {
            delay(15_000)
            if (anyPanelOpen()) closePanels()
        }
    }

    /**
     * The screensaver timer fired. Returns true when the window went fullscreen instead, so the
     * caller reschedules rather than starting a screensaver; otherwise the caller's stopped icon
     * state is screensaver-ready.
     */
    fun onIdle(): Boolean {
        apply(TvEvent.Idle(hasChannel = current != null))
        return window == TvWindow.Fullscreen
    }

    fun onManualScreensaver() {
        closePanels()
        apply(TvEvent.ManualScreensaver)
    }

    /** Stops playback and leaves the icon, or the placeholder when there is nothing to play. */
    private fun stopToCollapsed() {
        clearSourceTest()
        retryJob?.cancel()
        player.stop()
        val next = if (current == null) TvWindow.Stopped else TvWindow.Collapsed
        state = state.copy(window = next, focused = false, playback = TvPlayback.Idle)
        window = next
        focused = false
        playback = TvPlayback.Idle
    }

    fun release() {
        loadJob?.cancel()
        osdJob?.cancel()
        retryJob?.cancel()
        managerJob?.cancel()
        probeJob?.cancel()
        TvPhoneServer.stop()
        player.release()
    }

    /** Ask Home to take focus back onto the grid: the slot itself never grabs it. */
    fun testSource(channelIndex: Int, sourceIndex: Int): Boolean {
        val channel = channels.getOrNull(channelIndex) ?: return false
        val source = channel.sources.getOrNull(sourceIndex)?.takeIf { it.enabled } ?: return false
        // The QR panel is the one exception: it must stay up to show the result.
        if (!managerOpen) closePanels()
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

    private fun releaseFocus() {
        DiagLog.i("tv: handing focus back to Home")
        releaseFocus++
    }

    private fun playlist() = TvPlaylist(channels)

    /** Which list the slot read, and whether the provider's file was readable: the first thing to
     *  check when a channel name and the URL that then plays do not match. */
    private fun logLoaded(list: List<TvChannel>) {
        val head = list.take(3).joinToString(", ") { it.name }
        val file = TvStore.file(context)
        DiagLog.i("tv: ${list.size} channels, ${file.absolutePath} readable=${file.canRead()} mtime=${file.lastModified()} [$head…]")
    }

    private fun play(at: Pair<Int, TvSource>, remember: Boolean = true) {
        videoStats = null
        retryJob?.cancel()
        current = at
        val channel = channels.getOrNull(at.first) ?: return
        DiagLog.i("tv: channel[${at.first}] ${channel.name}")
        prefs.lastChannel = channel.name
        if (remember) prefs.lastSource = at.second.url
        state = state.copy(playback = TvPlayback.Buffering)
        playback = TvPlayback.Buffering
        player.play(at.second.url)
    }

    /**
     * A source of the channel now playing failed: try the channel's own next source, and only once
     * they are all spent leave the placeholder. Never wanders to another channel by itself, which on
     * a 162-channel list reads as the picture changing on its own.
     */
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
                // Nothing may suspend after this: the Stop effect cancels this very job.
                apply(TvEvent.PlaybackFailed)
            }
        }
    }

    private fun apply(event: TvEvent) {
        val reduced = TvStateMachine.reduce(state, event)
        // Back at the icon with nothing that could ever play: the placeholder, not a play glyph.
        val result = if (reduced.state.window == TvWindow.Collapsed && current == null)
            reduced.copy(state = reduced.state.copy(window = TvWindow.Stopped)) else reduced
        state = result.state
        window = state.window
        focused = state.focused
        DiagLog.i("tv: $event → ${state.window}${if (state.focused) " focused" else ""}, effect=${result.effect}")
        when (result.effect) {
            TvEffect.Play -> current?.let { play(it) }
            TvEffect.Stop -> {
                clearSourceTest()
                retryJob?.cancel()
                player.stop()
                playback = TvPlayback.Idle
                state = state.copy(playback = TvPlayback.Idle)
                // The playback listener also reports Idle; keep both copies in step.
            }
            TvEffect.EnterFullscreen -> player.resume()
            TvEffect.None -> Unit
        }
    }

    private fun showOsd(text: String) {
        osd = text
        osdJob?.cancel()
        osdJob = scope.launch {
            delay(2_000)
            osd = null
        }
    }
}
