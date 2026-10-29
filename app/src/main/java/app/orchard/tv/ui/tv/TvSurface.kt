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
        factory = { context ->
            TvTextureView(context, tv).apply {
                this.keepScreenOn = keepScreenOn
                if (radius > 0f) {
                    clipToOutline = true
                    outlineProvider = RoundedOutline(radius)
                }
            }
        },
        // The window↔fullscreen swap disposes one view while the player is on it: clear the surface
        // first, or the player takes a runtime error and the retry burns the channel's good sources.
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
