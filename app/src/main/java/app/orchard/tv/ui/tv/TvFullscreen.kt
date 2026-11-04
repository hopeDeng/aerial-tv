package app.orchard.tv.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.tv.TvController
import app.orchard.tv.tv.TvWindow
import app.orchard.tv.tv.channelLabel
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * Fullscreen's chrome. The picture is the slot's own surface (see TvWindow), which morphs to fill
 * the canvas without being disposed; keys, focus and the re-focus after panels all live in the
 * slot too, so this Box must swallow neither keys nor touches.
 */
@Composable
fun TvFullscreen(tv: TvController) {
    if (tv.window != TvWindow.Fullscreen) return

    val channel = tv.current?.let { tv.channels.getOrNull(it.first) }
    val name = channel?.name.orEmpty()
    val source = tv.current?.second

    var overlayVisible by remember { mutableStateOf(true) }
    // Must stay activity-scoped: without it Home's own BackHandler fires on Back instead.
    BackHandler { tv.exitFullscreen() }
    LaunchedEffect(Unit) {
        delay(3_000)
        overlayVisible = false
    }

    Box(Modifier.fillMaxSize()) {
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
