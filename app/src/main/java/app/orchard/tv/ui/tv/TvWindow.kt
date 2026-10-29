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
 * The live slot on Home: a tile-sized icon at rest, the screen-shaped window while it previews. One
 * control morphing between the two rectangles, not two controls swapping.
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
    if (tv.window == TvWindow.Fullscreen) return

    val expanded = tv.window == TvWindow.Preview
    val reduced = LocalReducedMotion.current
    var focusSeen by remember { mutableStateOf(false) }

    val shape = remember { Animatable(0f) }
    LaunchedEffect(expanded, reduced) {
        val target = if (expanded) 1f else 0f
        if (reduced) shape.snapTo(target)
        else shape.animateTo(target, spring(dampingRatio = 1f, stiffness = 520f))
    }
    val t = shape.value
    val top = lerpDp(geometry.iconTop, geometry.windowTop, t)
    val right = lerpDp(geometry.iconRight, geometry.windowRight, t)
    val width = lerpDp(geometry.iconWidth, geometry.windowWidth, t)
    val height = lerpDp(geometry.iconHeight, geometry.windowHeight, t)

    Pressable(
        onClick = { tv.onClick() },
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = if (expanded) 1.01f else 1.06f,
        // The window sits against the top and right screen edges: grow away from them so the focus
        // stroke never eats the margin. The icon keeps the tile's usual growth from its centre.
        transformOrigin = if (expanded) TransformOrigin(1f, 0f) else TransformOrigin.Center,
        modifier = modifier
            .padding(top = top, end = geometry.screenW - right)
            .size(width, height),
        onKey = { event ->
            if (event.type != KeyEventType.KeyDown) return@Pressable false
            when (event.nativeKeyEvent.keyCode) {
                // The slot has nothing inside it to reach: Left hands over to the weather line and
                // every other edge is swallowed, so no key retunes the channel or opens a panel behind
                // the user's back. That is all fullscreen's job now.
                NativeKeyEvent.KEYCODE_DPAD_LEFT -> { onLeft?.invoke(); true }
                NativeKeyEvent.KEYCODE_DPAD_RIGHT -> true
                NativeKeyEvent.KEYCODE_MENU -> true
                NativeKeyEvent.KEYCODE_DPAD_UP -> { onUp(); true }
                NativeKeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                else -> false
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
        // not keep a window it can no longer see as the focused one.
        DisposableEffect(Unit) {
            onDispose { if (focusSeen) tv.onWindowFocus(false) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.35f + 0.2f * t))
                .border(
                    if (state.focused) 2.dp else 1.dp,
                    Color.White.copy(alpha = if (state.focused) 0.9f else 0.18f),
                    RoundedCornerShape(20.dp),
                ),
        ) {
            // The picture rides the morph; the glyph comes up as the rectangle lands on a tile.
            if (t > 0.001f) {
                TvSurface(tv, Modifier.fillMaxSize(), cornerRadius = 20.dp)
            }
            GlyphIcon(
                if (tv.window == TvWindow.Stopped || tv.playback == TvPlayback.Error) Glyph.Offline else Glyph.Play,
                Color.White.copy(alpha = 0.85f),
                34.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = 1f - t },
            )

            if (t > 0.01f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = t }) {
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
