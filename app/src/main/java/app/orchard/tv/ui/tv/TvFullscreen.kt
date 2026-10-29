package app.orchard.tv.ui.tv

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.tv.TvController
import app.orchard.tv.tv.TvWindow
import app.orchard.tv.tv.channelLabel
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.delay

@Composable
fun TvFullscreen(tv: TvController) {
    if (tv.window != TvWindow.Fullscreen) return

    val focus = remember { FocusRequester() }
    val channel = tv.current?.let { tv.channels.getOrNull(it.first) }
    val name = channel?.name.orEmpty()
    val source = tv.current?.second

    var overlayVisible by remember { mutableStateOf(true) }
    BackHandler { tv.exitFullscreen() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(Unit) {
        delay(3_000)
        overlayVisible = false
    }
    LaunchedEffect(tv.channelListOpen) {
        if (!tv.channelListOpen) runCatching { focus.requestFocus() }
    }
    LaunchedEffect(tv.epgOpen) {
        if (!tv.epgOpen) runCatching { focus.requestFocus() }
    }
    LaunchedEffect(tv.sourceListOpen) {
        if (!tv.sourceListOpen) runCatching { focus.requestFocus() }
    }
    LaunchedEffect(tv.managerOpen) {
        if (!tv.managerOpen) runCatching { focus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT,
                    NativeKeyEvent.KEYCODE_CHANNEL_DOWN -> { tv.onPrev(); true }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT,
                    NativeKeyEvent.KEYCODE_CHANNEL_UP -> { tv.onNext(); true }
                    NativeKeyEvent.KEYCODE_DPAD_UP -> { tv.openManager(); true }
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> { tv.openSourceList(); true }
                    NativeKeyEvent.KEYCODE_DPAD_CENTER,
                    NativeKeyEvent.KEYCODE_ENTER,
                    NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> { tv.openChannelList(); true }
                    NativeKeyEvent.KEYCODE_MENU -> { tv.toggleEpg(); true }
                    NativeKeyEvent.KEYCODE_INFO,
                    NativeKeyEvent.KEYCODE_GUIDE -> { tv.toggleEpg(); true }
                    else -> false
                }
            },
    ) {
        TvSurface(tv, Modifier.fillMaxSize(), keepScreenOn = true)
        val label = channelLabel(name, source)
        val badge = tv.osd ?: label.takeIf { overlayVisible && it.isNotEmpty() }
        if (badge != null) {
            TvInfoBadge(
                badge,
                style = Type.Headline,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(32.dp),
            )
        }
        if (overlayVisible) {
            BasicText(
                stringResource(R.string.tv_fullscreen_hint),
                style = Type.Callout.copy(color = Color.White.copy(alpha = 0.7f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}
