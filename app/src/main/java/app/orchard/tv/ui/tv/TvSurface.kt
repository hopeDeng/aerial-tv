package app.orchard.tv.ui.tv

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.orchard.tv.tv.TvController

@Composable
fun TvSurface(
    tv: TvController,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    keepScreenOn: Boolean = false,
) {
    val density = LocalDensity.current.density
    val radius = cornerRadius.value * density
    AndroidView(
        // The factory runs exactly once per surface: this view's identity IS the player's surface,
        // and a new view makes TvPlayer restart the stream. Everything the slot morph changes is
        // re-applied in update, below.
        factory = { context -> TvTextureView(context, tv) },
        update = { view ->
            view.keepScreenOn = keepScreenOn
            if (radius > 0f) {
                view.clipToOutline = true
                view.outlineProvider = RoundedOutline(radius)
            } else {
                view.clipToOutline = false
                view.outlineProvider = ViewOutlineProvider.BACKGROUND
            }
        },
        // When the view really is disposed (the screensaver or the setting took the slot away), the
        // surface must be cleared first, or the player takes a runtime error and the retry burns
        // the channel's good sources (Xiaomi).
        onRelease = { it.release() },
        modifier = modifier,
    )
}

/**
 * A TextureView the player binds to once its texture exists.
 *
 * Not a SurfaceView: on Skyworth 8N07_J9000 (Novatek nt72671, Android 8) the decoder reports a
 * first frame on the surface it was given, yet the SurfaceView's queue never gets a video buffer —
 * SurfaceFlinger keeps compositing its 1x1 placeholder, so the sound plays over a black picture.
 * Frames through the app's own compositor also reach the rounded corners and the channel name and
 * hints drawn over them, which a SurfaceView's hole in the window would swallow.
 */
private class TvTextureView(context: Context, private val tv: TvController) :
    TextureView(context), TextureView.SurfaceTextureListener {

    init {
        isOpaque = false
        surfaceTextureListener = this
        // The slot's morph resizes this view while the stream keeps running, and neither engine
        // re-fits its output on its own: LibVLC goes on drawing into the window size it was last
        // given, so the picture stayed pinned to the lower-left corner after a fullscreen toggle
        // (Skyworth 8N07_J9000). Layout is the one callback we always get — VLC takes over the
        // SurfaceTextureListener when it attaches its views, so onSurfaceTextureSizeChanged never
        // reaches us.
        var laidW = 0
        var laidH = 0
        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w != laidW || h != laidH) {
                laidW = w
                laidH = h
                if (w > 0 && h > 0) tv.resize(this)
            }
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        tv.attach(this)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    /** True: the texture is released with the view, so nothing keeps a stale surface. */
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        tv.detach(this)
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun release() = tv.detach(this)
}

private class RoundedOutline(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}
