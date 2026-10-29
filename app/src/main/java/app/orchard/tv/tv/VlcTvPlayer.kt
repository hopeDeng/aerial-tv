package app.orchard.tv.tv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import app.orchard.tv.system.DiagLog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.AbstractVLCEvent
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * The engine the reference player (`TvInputActivity`) runs on. It passes the HLS URL to VLC
 * untouched and lets VLC deal with redirects, empty query strings and live-window slips, which is
 * why hnyd stays on its first source here.
 */
internal class VlcTvPlayer(context: Context) : TvVideoPlayer {
    override var onPlaybackChanged: ((TvPlayback) -> Unit)? = null
    override var onFailed: ((String) -> Unit)? = null
    override var onVideoStats: ((TvVideoStats?) -> Unit)? = null

    /** False when LibVLC could not be loaded on this device; the facade then uses ExoPlayer only. */
    var available = false
        private set

    private var libVLC: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var view: TextureView? = null
    private var playing = false
    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = Runnable {
        val player = mp ?: return@Runnable
        val progressed = runCatching { player.isPlaying || player.time > 0 }.getOrDefault(false)
        if (!progressed) {
            DiagLog.i("tv: vlc no progress after 8s")
            onFailed?.invoke("vlc no progress")
        } else {
            DiagLog.i("tv: vlc progressing t=${runCatching { player.time }.getOrDefault(-1)}")
        }
    }

    private fun mediaVideoTrack(): IMedia.VideoTrack? {
        val media = runCatching { mp?.media }.getOrNull() ?: return null
        for (index in 0 until runCatching { media.trackCount }.getOrDefault(0)) {
            val track = runCatching { media.getTrack(index) }.getOrNull() ?: continue
            val size = (track as? IMedia.VideoTrack)?.let { "${it.width}x${it.height}" } ?: "-"
            DiagLog.i("tv: vlc media track[$index] ${track.javaClass.simpleName} $size")
            if (track is IMedia.VideoTrack && track.width > 0 && track.height > 0) return track
        }
        return null
    }

    private fun publishStats() {
        val player = mp ?: return
        val current = runCatching { player.currentVideoTrack }.getOrElse {
            DiagLog.i("tv: vlc video track failed ${it.javaClass.simpleName}")
            null
        }
        if (current != null && current.width > 0 && current.height > 0) {
            DiagLog.i("tv: vlc current track ${current.width}x${current.height}")
        }
        // Some streams (hnyd HLS) leave the selected track's size at 0 while the decoder is already
        // showing a picture; the media's own track list still has the real size.
        val track = current?.takeIf { it.width > 0 && it.height > 0 } ?: mediaVideoTrack() ?: return
        val fps = if (track.frameRateNum > 0 && track.frameRateDen > 0) {
            track.frameRateNum.toDouble() / track.frameRateDen
        } else {
            null
        }
        val bitrate = runCatching { track.bitrate }.getOrNull()
        val bitrateKbps = bitrate?.takeIf { it > 0 }?.let { it / 1000 }
        DiagLog.i("tv: vlc stats ${track.width}x${track.height} fps=$fps kbps=$bitrateKbps")
        onVideoStats?.invoke(
            TvVideoStats(
                width = track.width,
                height = track.height,
                fps = fps,
                bitrateKbps = bitrateKbps,
                engine = "LibVLC",
            ),
        )
    }

    private val eventListener = MediaPlayer.EventListener { event: AbstractVLCEvent ->
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                playing = true
                handler.removeCallbacks(watchdog)
                DiagLog.i("tv: vlc Playing t=${runCatching { mp?.time }.getOrDefault(-1)}")
                onPlaybackChanged?.invoke(TvPlayback.Playing)
                publishStats()
            }
            MediaPlayer.Event.Paused -> onPlaybackChanged?.invoke(TvPlayback.Paused)
            MediaPlayer.Event.Buffering -> {
                // LibVLC reports 100% once more after Playing; treating that as buffering leaves the
                // manager panel stuck on "buffering" while the picture is already running.
                val percent = (event as? MediaPlayer.Event)?.buffering
                DiagLog.i("tv: vlc Buffering $percent type=${event.type}")
                if (percent == null || percent < 100f) {
                    onPlaybackChanged?.invoke(TvPlayback.Buffering)
                } else if (mp?.isPlaying == true || (mp?.time ?: 0) > 0) {
                    onPlaybackChanged?.invoke(TvPlayback.Playing)
                }
            }
            MediaPlayer.Event.Opening -> {
                DiagLog.i("tv: vlc Opening type=${event.type}")
                onPlaybackChanged?.invoke(TvPlayback.Buffering)
            }
            MediaPlayer.Event.Vout -> {
                DiagLog.i("tv: vlc Vout")
                publishStats()
            }
            MediaPlayer.Event.Stopped -> {
                playing = false
                onVideoStats?.invoke(null)
                onPlaybackChanged?.invoke(TvPlayback.Idle)
            }
            MediaPlayer.Event.EncounteredError -> {
                playing = false
                onVideoStats?.invoke(null)
                DiagLog.i("tv: vlc EncounteredError")
                onPlaybackChanged?.invoke(TvPlayback.Error)
                onFailed?.invoke("vlc error")
            }
            MediaPlayer.Event.EndReached -> {
                playing = false
                onVideoStats?.invoke(null)
                DiagLog.i("tv: vlc EndReached")
                onFailed?.invoke("vlc end")
            }
        }
    }

    init {
        try {
            // LibVLC appends --aout/--android-display-chroma to this list, so it must be mutable.
            val vlc = LibVLC(context, mutableListOf("--no-stats", "--no-video-title-show"))
            val player = MediaPlayer(vlc)
            player.setEventListener(eventListener)
            libVLC = vlc
            mp = player
            available = true
            DiagLog.i("tv: vlc init ok ${LibVLC.version()}")
        } catch (t: Throwable) {
            DiagLog.i("tv: vlc init failed ${t.javaClass.name}: ${t.message}\n${android.util.Log.getStackTraceString(t)}")
            runCatching { libVLC?.release() }
            runCatching { mp?.release() }
            libVLC = null
            mp = null
            available = false
        }
    }

    private fun layoutListener(vout: IVLCVout, width: Int, height: Int, visibleWidth: Int, visibleHeight: Int, sarNum: Int, sarDen: Int) {
        val v = view ?: return
        if (v.width <= 0 || v.height <= 0) return
        vout.setWindowSize(v.width, v.height)
        mp?.setScale(0f)
        DiagLog.i("tv: vlc layout ${width}x$height vis=${visibleWidth}x$visibleHeight tex=${v.width}x${v.height}")
        publishStats()
    }

    override fun attach(view: TextureView) {
        val player = mp ?: return
        val vout = player.vlcVout
        val replacing = this.view != null && this.view !== view
        if (replacing) {
            // Hand the decoder back before its old surface is destroyed with the old view;
            // otherwise MediaCodec keeps queueing into an abandoned BufferQueue and its error
            // leaves the new picture black (seen on the Xiaomi TV entering fullscreen).
            handler.removeCallbacks(watchdog)
            runCatching { player.stop() }
            playing = false
            onVideoStats?.invoke(null)
        }
        if (this.view != null && this.view !== view && vout.areViewsAttached()) vout.detachViews()
        this.view = view
        vout.setVideoView(view)
        if (view.isAvailable && !vout.areViewsAttached()) {
            vout.attachViews(object : IVLCVout.OnNewVideoLayoutListener {
                override fun onNewVideoLayout(
                    vout: IVLCVout,
                    width: Int,
                    height: Int,
                    visibleWidth: Int,
                    visibleHeight: Int,
                    sarNum: Int,
                    sarDen: Int,
                ) = layoutListener(vout, width, height, visibleWidth, visibleHeight, sarNum, sarDen)
            })
        }
        if (view.width > 0 && view.height > 0) {
            vout.setWindowSize(view.width, view.height)
            player.setScale(0f)
        }
    }

    override fun detach(view: TextureView) {
        if (this.view !== view) return
        handler.removeCallbacks(watchdog)
        val vout = mp?.vlcVout
        if (vout != null && vout.areViewsAttached()) vout.detachViews()
        this.view = null
        playing = false
    }

    private fun detachCurrent() {
        val current = view ?: return
        detach(current)
    }

    override fun play(url: String) {
        val player = mp ?: run {
            onFailed?.invoke("vlc unavailable")
            return
        }
        DiagLog.i("tv: vlc play $url")
        playing = false
        onVideoStats?.invoke(null)
        handler.removeCallbacks(watchdog)
        runCatching {
            player.stop()
            val media = Media(libVLC, Uri.parse(url))
            player.setMedia(media)
            media.release()
            player.play()
            handler.postDelayed(watchdog, 8_000)
        }.onFailure {
            DiagLog.i("tv: vlc play failed ${it.message}")
            onFailed?.invoke(it.message ?: "vlc play failed")
        }
    }

    override fun pause() {
        mp?.pause()
    }

    override fun resume() {
        mp?.play()
    }

    override fun stop() {
        handler.removeCallbacks(watchdog)
        playing = false
        runCatching { mp?.stop() }
        onVideoStats?.invoke(null)
        onPlaybackChanged?.invoke(TvPlayback.Idle)
    }

    override fun release() {
        handler.removeCallbacks(watchdog)
        runCatching { mp?.setEventListener(null) }
        detachCurrent()
        runCatching { mp?.release() }
        runCatching { libVLC?.release() }
        mp = null
        libVLC = null
        available = false
    }
}
