package app.orchard.tv.tv

import android.content.Context
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.orchard.tv.system.DiagLog

/**
 * Media3 fallback engine.
 *
 * Two fixes the reference player never needed because LibVLC does this for it:
 *  - hnyd/ZTE sources 400 unless the URL carries a query. A bare trailing "?" is what the file has;
 *    Media3 loses or percent-encodes it, so pad it into a harmless "?x=1".
 *  - Falling behind the live window is normal for these streams; re-seek instead of failing over.
 */
@OptIn(UnstableApi::class)
internal class ExoPlayerTvPlayer(context: Context) : TvVideoPlayer {
    override var onPlaybackChanged: ((TvPlayback) -> Unit)? = null
    override var onFailed: ((String) -> Unit)? = null
    override var onVideoStats: ((TvVideoStats?) -> Unit)? = null

    private val http = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(25000)
        .setReadTimeoutMs(35000)
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")

    private val hlsFactory = HlsMediaSource.Factory(http)
    private val mediaSourceFactory = DefaultMediaSourceFactory(http)

    private var surface: Surface? = null
    private var attachedView: TextureView? = null
    private var liveRecoveries = 0
    private var lastLiveRecoveryAt = 0L
    private var failed = false

    private fun publishStats(videoSize: androidx.media3.common.VideoSize? = null) {
        val format = player.videoFormat
        val width = videoSize?.width?.takeIf { it > 0 } ?: format?.width?.takeIf { it > 0 } ?: return
        val height = videoSize?.height?.takeIf { it > 0 } ?: format?.height?.takeIf { it > 0 } ?: return
        val fps = format?.frameRate?.takeIf { it > 0f }?.toDouble()
        val bitrate = format?.bitrate
        val bitrateKbps = bitrate?.takeIf { it > 0 }?.let { it / 1000 }
        onVideoStats?.invoke(
            TvVideoStats(
                width = width,
                height = height,
                fps = fps,
                bitrateKbps = bitrateKbps,
                engine = "ExoPlayer",
            ),
        )
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    DiagLog.i("tv: exo state=$playbackState isPlaying=$isPlaying")
                    // Exo emits STATE_IDLE right after onPlayerError; it must not hide the error the
                    // source test panel (and the fullscreen placeholder) is showing.
                    if (playbackState == Player.STATE_IDLE && failed) return
                    onPlaybackChanged?.invoke(
                        when (playbackState) {
                            Player.STATE_BUFFERING -> TvPlayback.Buffering
                            Player.STATE_READY -> if (isPlaying) TvPlayback.Playing else TvPlayback.Paused
                            else -> TvPlayback.Idle
                        },
                    )
                    if (playbackState == Player.STATE_READY && isPlaying) {
                        publishStats()
                    }
                    if (playbackState == Player.STATE_ENDED) onFailed?.invoke("playback ended")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    when {
                        isPlaying -> {
                            onPlaybackChanged?.invoke(TvPlayback.Playing)
                            publishStats()
                        }
                        playbackState == Player.STATE_READY -> onPlaybackChanged?.invoke(TvPlayback.Paused)
                    }
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        publishStats(videoSize)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("tv: exo error ${error.errorCodeName}: ${error.message} cause=${error.cause}")
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && recoverLive()) return
                    failed = true
                    onVideoStats?.invoke(null)
                    onPlaybackChanged?.invoke(TvPlayback.Error)
                    onFailed?.invoke(error.message ?: "playback failed")
                }
            })
        }

    private fun recoverLive(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastLiveRecoveryAt > 15_000) liveRecoveries = 0
        lastLiveRecoveryAt = now
        if (liveRecoveries >= 3) return false
        liveRecoveries++
        DiagLog.i("tv: exo behind live window, re-seeking to edge ($liveRecoveries)")
        player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = true
        return true
    }

    override fun attach(view: TextureView) {
        val texture = view.surfaceTexture ?: return
        if (attachedView !== null && attachedView !== view) {
            surface?.release()
            surface = null
            onVideoStats?.invoke(null)
        }
        attachedView = view
        val created = Surface(texture)
        surface = created
        player.setVideoSurface(created)
    }

    override fun detach(view: TextureView) {
        if (attachedView !== view) return
        attachedView = null
        player.setVideoSurface(null)
        surface?.release()
        surface = null
        onVideoStats?.invoke(null)
    }

    fun detach() = attachedView?.let { detach(it) }

    override fun play(url: String) {
        // The ZTE/hnyd URLs must keep a query string; a bare "?" gets mangled by Media3 into a 400.
        val fixed = if (url.endsWith("?")) url + "x=1" else url
        DiagLog.i("tv: exo play $fixed")
        failed = false
        onVideoStats?.invoke(null)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(fixed))
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(3_000).build())
            .build()
        val source = try {
            if (fixed.lowercase().contains(".m3u8")) {
                hlsFactory.createMediaSource(mediaItem)
            } else {
                mediaSourceFactory.createMediaSource(mediaItem)
            }
        } catch (e: Exception) {
            DiagLog.i("tv: exo source err ${e.message}")
            mediaSourceFactory.createMediaSource(mediaItem)
        }
        liveRecoveries = 0
        player.stop()
        player.clearMediaItems()
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun resume() {
        player.playWhenReady = true
    }

    override fun stop() {
        failed = false
        player.stop()
        player.clearMediaItems()
        onVideoStats?.invoke(null)
        onPlaybackChanged?.invoke(TvPlayback.Idle)
    }

    override fun release() {
        detach()
        player.release()
    }
}
