package app.orchard.tv.tv

import android.view.TextureView

/**
 * The live-TV engine. Two implementations exist: [VlcTvPlayer] mirrors the reference player and is
 * the default, [ExoPlayerTvPlayer] is the fallback when a source confuses LibVLC.
 *
 * Both draw into the same [TextureView]; VLC needs the view itself (IVLCVout), ExoPlayer only a
 * Surface it derives from it, so the interface hands over the view.
 */
internal interface TvVideoPlayer {
    var onPlaybackChanged: ((TvPlayback) -> Unit)?
    var onFailed: ((String) -> Unit)?
    var onVideoStats: ((TvVideoStats?) -> Unit)?

    fun attach(view: TextureView)
    fun detach(view: TextureView)
    fun play(url: String)
    fun pause()
    fun resume()
    fun stop()
    fun release()
}
