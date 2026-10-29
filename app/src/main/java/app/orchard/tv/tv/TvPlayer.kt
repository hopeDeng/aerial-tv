package app.orchard.tv.tv

import android.content.Context
import android.view.TextureView
import app.orchard.tv.system.DiagLog

/**
 * Picks the live-TV engine and lets one back up the other.
 *
 * LibVLC is the default because it is what the reference player uses and what these ZTE/hnyd HLS
 * sources behave best with. If LibVLC cannot load or reports an error on a URL, the same URL is
 * replayed once through ExoPlayer before the controller is told the source failed and moves on.
 */
class TvPlayer(context: Context) {
    var onPlaybackChanged: ((TvPlayback) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null
    var onVideoStats: ((TvVideoStats?) -> Unit)? = null

    private val vlc = VlcTvPlayer(context)
    private val exo: ExoPlayerTvPlayer by lazy { ExoPlayerTvPlayer(context) }
    private var exoCreated = false

    private var view: TextureView? = null
    private var usingExo = false
    private var currentUrl: String? = null
    private var fallbackTried = false

    /** The URL the decoder was last given, or null when it was stopped. */
    val playingUrl: String? get() = currentUrl

    init {
        vlc.onPlaybackChanged = { onPlaybackChanged?.invoke(it) }
        vlc.onVideoStats = { onVideoStats?.invoke(it) }
        vlc.onFailed = { reason ->
            if (!usingExo && !fallbackTried && vlc.available) {
                fallbackTried = true
                onVideoStats?.invoke(null)
                DiagLog.i("tv: vlc failed ($reason) -> exo fallback")
                switchToExo()
                currentUrl?.let { exo.play(it) }
            } else {
                onFailed?.invoke(reason)
            }
        }
    }

    private fun ensureExo(): ExoPlayerTvPlayer {
        if (!exoCreated) {
            exoCreated = true
            exo.onPlaybackChanged = { onPlaybackChanged?.invoke(it) }
            exo.onVideoStats = { onVideoStats?.invoke(it) }
            exo.onFailed = { reason -> onFailed?.invoke(reason) }
        }
        return exo
    }

    private fun switchToExo() {
        if (usingExo) return
        onVideoStats?.invoke(null)
        usingExo = true
        view?.let { vlc.detach(it) }
        view?.let { ensureExo().attach(it) }
    }

    private fun switchToVlc() {
        if (!usingExo) return
        onVideoStats?.invoke(null)
        usingExo = false
        if (exoCreated) view?.let { exo.detach(it) }
        view?.let { vlc.attach(it) }
    }

    fun attach(view: TextureView, replay: Boolean = true) {
        val previous = this.view
        this.view = view
        if (usingExo || !vlc.available) {
            usingExo = true
            ensureExo().attach(view)
        } else {
            vlc.attach(view)
        }
        // Moving the picture between the window and fullscreen replaces the TextureView. The old
        // surface goes away with its view, so restart on the new one; otherwise the decoder loses
        // its output and the screen stays black.
        val url = currentUrl
        if (replay && url != null && previous !== view) {
            DiagLog.i("tv: view swap, restarting playback")
            if (usingExo) ensureExo().play(url) else vlc.play(url)
        }
    }

    fun detach(view: TextureView) {
        if (this.view !== view) return
        if (exoCreated) exo.detach(view)
        vlc.detach(view)
        this.view = null
    }

    fun play(url: String) {
        onVideoStats?.invoke(null)
        if (url != currentUrl) {
            currentUrl = url
            fallbackTried = false
        }
        if (!vlc.available) {
            usingExo = true
            ensureExo().play(url)
            return
        }
        if (fallbackTried) {
            usingExo = true
            ensureExo().play(url)
            return
        }
        // A new URL gets a fresh try on LibVLC even if the previous one needed the fallback.
        switchToVlc()
        vlc.play(url)
    }

    fun pause() {
        if (usingExo) ensureExo().pause() else vlc.pause()
    }

    fun resume() {
        if (usingExo) ensureExo().resume() else vlc.resume()
    }

    fun stop() {
        if (exoCreated) exo.stop()
        vlc.stop()
        currentUrl = null
        fallbackTried = false
        onVideoStats?.invoke(null)
        onPlaybackChanged?.invoke(TvPlayback.Idle)
    }

    fun release() {
        if (exoCreated) exo.release()
        vlc.release()
        view = null
        currentUrl = null
    }
}
