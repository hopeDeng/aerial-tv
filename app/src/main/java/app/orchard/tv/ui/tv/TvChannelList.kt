package app.orchard.tv.ui.tv

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.tv.TvChannel
import app.orchard.tv.tv.TvController
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.theme.Type

private val Amber = Color(0xFFFFC107)
private val CurrentGreen = Color(0xFF00FF00)
private val RowHeight = 48.dp
private val PanelFill = Color.Black.copy(alpha = 0.6f)

/**
 * The reference player's channel list: a full-height panel against the left edge with a row per
 * channel, the playing one marked by a play glyph, the selection amber. The container runs to the
 * screen edges; only its content is inset.
 */
@Composable
fun TvChannelList(tv: TvController) {
    if (!tv.channelListOpen) return

    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val current = tv.current?.first ?: -1
    var selected by remember {
        mutableIntStateOf(if (current in tv.channels.indices) current else 0)
    }
    BackHandler { tv.closePanels() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val rowPx = with(LocalDensity.current) { RowHeight.roundToPx() }
    LaunchedEffect(selected, tv.channels.size) {
        if (tv.channels.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(selected.coerceIn(0, tv.channels.lastIndex), scrollOffset = -2 * rowPx)
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(280.dp)
                .fillMaxHeight()
                .background(PanelFill)
                .focusRequester(focus)
                .focusable()
                .consumeTaps()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val count = tv.channels.size
                    when (event.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_UP,
                        NativeKeyEvent.KEYCODE_CHANNEL_UP -> {
                            if (count > 0) selected = (selected - 1 + count) % count
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_DOWN,
                        NativeKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                            if (count > 0) selected = (selected + 1) % count
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER,
                        NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (selected in tv.channels.indices) tv.playChannel(selected)
                            true
                        }
                        NativeKeyEvent.KEYCODE_MENU -> {
                            tv.openChannelGrid()
                            true
                        }
                        NativeKeyEvent.KEYCODE_BACK -> {
                            tv.closePanels()
                            true
                        }
                        else -> true
                    }
                },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 18.dp, bottom = 18.dp),
            ) {
                BasicText(
                    stringResource(R.string.tv_channel_list_n, tv.channels.size),
                    style = Type.Title.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White),
                    modifier = Modifier.padding(start = 12.dp, top = 9.dp, end = 12.dp, bottom = 5.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    itemsIndexed(tv.channels) { index, channel ->
                        ChannelRow(
                            index = index,
                            channel = channel,
                            current = index == current,
                            selected = index == selected,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(index: Int, channel: TvChannel, current: Boolean, selected: Boolean) {
    val disabled = stringResource(R.string.tv_channel_disabled)
    val text = buildString {
        append(channel.name)
        val sources = channel.sources
        append("【")
        append(if (channel.currentSource in sources.indices) channel.currentSource + 1 else "-")
        append('/').append(sources.size).append('】')
        if (!channel.enabled) append('[').append(disabled).append(']')
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelBadge(number = index + 1, playing = current, selected = selected)
        Spacer(Modifier.width(8.dp))
        BasicText(
            text,
            style = Type.Body.copy(
                fontSize = 20.sp,
                fontWeight = if (selected || current) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    selected -> Amber
                    current -> CurrentGreen
                    else -> Color.White
                },
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The channel's number in a rounded box; the channel on air gets a boxed play triangle instead. */
@Composable
private fun ChannelBadge(number: Int, playing: Boolean, selected: Boolean) {
    val fill = when {
        selected -> Amber.copy(alpha = 0.22f)
        playing -> CurrentGreen.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    val ink = when {
        selected -> Amber
        playing -> CurrentGreen
        else -> Color.White.copy(alpha = 0.85f)
    }
    Box(
        Modifier
            .width(30.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            Canvas(Modifier.size(10.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.08f)
                    lineTo(size.width * 0.92f, size.height * 0.5f)
                    lineTo(size.width * 0.18f, size.height * 0.92f)
                    close()
                }
                drawPath(path, ink)
            }
        } else {
            BasicText(
                number.toString(),
                style = Type.Body.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink),
            )
        }
    }
}
