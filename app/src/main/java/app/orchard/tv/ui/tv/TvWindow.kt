package app.orchard.tv.ui.tv

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.tv.TvController
import app.orchard.tv.tv.TvPlayback
import app.orchard.tv.tv.TvWindow
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Type

/**
 * The live slot on Home: a tile-sized icon at rest, the screen-shaped window while it previews,
 * the whole canvas in fullscreen. One control morphing between the three rectangles, not controls
 * swapping: the surface inside survives every mode change, so going fullscreen (or Back out of
 * it) is pure geometry and the stream never re-buffers.
 */
@Composable
fun TvWindow(
    tv: TvController,
    geometry: TvSlotGeometry,
    focusRequester: FocusRequester,
    enabled: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val expanded = tv.window == TvWindow.Preview
    val fullscreen = tv.window == TvWindow.Fullscreen
    val reduced = LocalReducedMotion.current
    var focusSeen by remember { mutableStateOf(false) }

    val shape = remember { Animatable(0f) }
    LaunchedEffect(tv.window, reduced) {
        val target = when (tv.window) {
            TvWindow.Fullscreen -> 2f
            TvWindow.Preview -> 1f
            else -> 0f
        }
        if (reduced) shape.snapTo(target)
        else shape.animateTo(target, spring(dampingRatio = 1f, stiffness = 520f))
    }
    val t = shape.value
    // Second stage: u drives the window rectangle onto the full canvas. The parent aligns TopEnd
    // and the box takes padding(top, end = screenW - right) + size, so the canvas is padding 0
    // with size(screenW, screenH).
    val u = (t - 1f).coerceIn(0f, 1f)
    val top = if (t <= 1f) lerpDp(geometry.iconTop, geometry.windowTop, t) else lerpDp(geometry.windowTop, 0.dp, u)
    val right = if (t <= 1f) lerpDp(geometry.iconRight, geometry.windowRight, t) else lerpDp(geometry.windowRight, geometry.screenW, u)
    val width = if (t <= 1f) lerpDp(geometry.iconWidth, geometry.windowWidth, t) else lerpDp(geometry.windowWidth, geometry.screenW, u)
    val height = if (t <= 1f) lerpDp(geometry.iconHeight, geometry.windowHeight, t) else lerpDp(geometry.windowHeight, geometry.screenH, u)
    // The window's edge lands on the screen's edge in fullscreen: rounding there only clips the
    // picture, so the radius animates away with the second stage.
    val radius = lerpDp(20.dp, 0.dp, u)

    // Fullscreen's panels take focus into the overlay layer; once every one of them is closed the
    // slot takes it back, a frame late so the panel has left composition first (same shape as
    // Home's city-picker hand-back). Entering fullscreen rides this effect too.
    //
    // [tv.focused] is a key of the effect, not just a guard: Home takes focus onto the grid by
    // itself when an app disappears under it or when the activity comes back (see HomeScreen's
    // LaunchedEffect(apps) and homeResetPending), and nothing else would hand the remote back to the
    // picture. Without that the stream keeps playing fullscreen while the arrows move tiles under it.
    LaunchedEffect(
        tv.window, tv.focused, tv.channelListOpen, tv.channelGridOpen, tv.epgOpen,
        tv.sourceListOpen, tv.managerOpen, tv.cityPickerOpen,
    ) {
        if (tv.window != TvWindow.Fullscreen) return@LaunchedEffect
        if (tv.focused) return@LaunchedEffect
        if (tv.channelListOpen || tv.channelGridOpen || tv.epgOpen ||
            tv.sourceListOpen || tv.managerOpen || tv.cityPickerOpen
        ) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Pressable(
        onClick = { tv.onClick() },
        enabled = enabled,
        focusRequester = focusRequester,
        // <= 1f tells Pressable not to scale at all: the picture owns the whole canvas in
        // fullscreen, and even the press dip would show black gaps at the screen edges.
        focusedScale = if (fullscreen) 1f else if (expanded) 1.01f else 1.06f,
        // The window sits against the top and right screen edges: grow away from them so the focus
        // stroke never eats the margin. The icon keeps the tile's usual growth from its centre.
        transformOrigin = if (expanded) TransformOrigin(1f, 0f) else TransformOrigin.Center,
        modifier = modifier
            .padding(top = top, end = geometry.screenW - right)
            .size(width, height),
        onKey = { event ->
            if (event.type != KeyEventType.KeyDown) return@Pressable false
            if (fullscreen) {
                // Fullscreen owns the screen: the edges change channels and open the panels
                // instead of moving focus. Back is left to fall through to the activity's
                // BackHandler, which morphs the slot back to the window.
                //
                // A held key repeats every frame, so only the press that opened it acts. The repeats
                // are still consumed: unhandled, they would reach Home under the picture.
                val first = event.nativeKeyEvent.repeatCount == 0
                when (event.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT,
                    NativeKeyEvent.KEYCODE_CHANNEL_DOWN -> { if (first) tv.onPrev(); true }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT,
                    NativeKeyEvent.KEYCODE_CHANNEL_UP -> { if (first) tv.onNext(); true }
                    NativeKeyEvent.KEYCODE_DPAD_UP -> { if (first) tv.openManager(); true }
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> { if (first) tv.openSourceList(); true }
                    NativeKeyEvent.KEYCODE_DPAD_CENTER,
                    NativeKeyEvent.KEYCODE_ENTER,
                    NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> { if (first) tv.openChannelList(); true }
                    NativeKeyEvent.KEYCODE_MENU,
                    NativeKeyEvent.KEYCODE_INFO,
                    NativeKeyEvent.KEYCODE_GUIDE -> { if (first) tv.toggleEpg(); true }
                    else -> false
                }
            } else {
                when (event.nativeKeyEvent.keyCode) {
                    // The window has nothing inside it to reach: Left hands over to the weather line
                    // and every other edge is swallowed, so no key retunes the channel or opens a
                    // panel behind the user's back.
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> { onLeft?.invoke(); true }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> true
                    NativeKeyEvent.KEYCODE_MENU -> true
                    NativeKeyEvent.KEYCODE_DPAD_UP -> { onUp(); true }
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                    else -> false
                }
            }
        },
    ) { state ->
        LaunchedEffect(state.focused) {
            if (state.focused) {
                focusSeen = true
                tv.onWindowFocus(true)
            } else if (focusSeen) {
                tv.onWindowFocus(false)
            }
        }
        // Going away with focus still on it (the screensaver took the screen): the controller must
        // not keep a window it can no longer see as the focused one. Leaving the composition also
        // takes the surface with it, so a stream that is still live must stop here.
        DisposableEffect(Unit) {
            onDispose {
                if (tv.active) tv.onSlotGone()
                if (focusSeen) tv.onWindowFocus(false)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(radius))
                // The TextureView is not opaque and libvlc aspect-fits inside the view, so the
                // letterbox bands are transparent and this colour is what shows through them: it
                // must reach fully black by the time the rectangle reaches the canvas, and stay
                // continuous with the preview's 0.55 at t = 1.
                .background(Color.Black.copy(alpha = if (t <= 1f) 0.35f + 0.2f * t else 0.55f + 0.45f * u))
                .border(
                    // A white stroke along the screen edge would be wrong: it fades out with the
                    // second stage.
                    (if (state.focused) 2.dp else 1.dp) * (1f - u),
                    Color.White.copy(alpha = (if (state.focused) 0.9f else 0.18f) * (1f - u)),
                    RoundedCornerShape(radius),
                ),
        ) {
            // The picture rides the morph; the glyph comes up as the rectangle lands on a tile.
            // Never move this behind a gate that can toggle between Preview and Fullscreen: the
            // surface dying is exactly what restarts the stream.
            if (t > 0.001f) {
                TvSurface(tv, Modifier.fillMaxSize(), cornerRadius = radius, keepScreenOn = fullscreen)
            }
            GlyphIcon(
                if (tv.window == TvWindow.Stopped || tv.playback == TvPlayback.Error) Glyph.Offline else Glyph.Play,
                Color.White.copy(alpha = 0.85f),
                34.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = (1f - t).coerceIn(0f, 1f) },
            )

            // The badge belongs to the window stage: fullscreen draws its own, larger one (see
            // TvFullscreen), and both read tv.osd, so drawing this here too would double-print it.
            if (t > 0.01f && !fullscreen) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = t.coerceIn(0f, 1f) }) {
                    val badge = tv.osd ?: if (tv.window == TvWindow.Preview && state.focused) {
                        stringResource(R.string.tv_preview_hint)
                    } else {
                        null
                    }
                    if (badge != null) {
                        TvInfoBadge(
                            badge,
                            style = Type.Callout,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp),
                        )
                    }
                }
            }

            // The placeholder says so under the icon, where it fits: it used to draw at the window's
            // bottom edge even when collapsed, so the words hung out of the tile.
            if (t < 0.5f && tv.window == TvWindow.Stopped && tv.current == null) {
                BasicText(
                    stringResource(R.string.tv_no_source),
                    style = Type.Callout.copy(color = Color.White.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .graphicsLayer { alpha = 1f - t * 2f },
                )
            }
        }
    }
}
