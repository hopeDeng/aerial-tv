package app.orchard.tv.ui.tv

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.tv.TvController
import app.orchard.tv.tv.TvSource
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.theme.Type

private val Amber = Color(0xFFFFC107)
private val AmberDim = Color(0xFF8A6D1F)
private val SourceUrl = Color(0xFFBDBDBD)
private val PanelFill = Color.Black.copy(alpha = 0.6f)

/**
 * The sources of the channel on air, as a strip like the guide. DOWN in fullscreen opens it;
 * LEFT/RIGHT walk the sources, OK switches to the selected one at once.
 */
@Composable
fun TvSourceList(tv: TvController) {
    if (!tv.sourceListOpen) return
    val channelIndex = tv.current?.first ?: return
    val channel = tv.channels.getOrNull(channelIndex) ?: return
    val playingUrl = tv.current?.second?.url
    val sources = channel.sources.filter { it.enabled }

    BackHandler { tv.closePanels() }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var selected by remember(sources) {
        mutableIntStateOf(sources.indexOfFirst { it.url == playingUrl }.coerceAtLeast(0))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(selected, sources.size) {
        if (sources.isNotEmpty()) listState.scrollToItem(selected.coerceIn(0, sources.lastIndex))
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(118.dp)
                .background(PanelFill)
                .focusRequester(focus)
                .focusable()
                .consumeTaps()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (sources.isNotEmpty()) selected = (selected - 1 + sources.size) % sources.size
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (sources.isNotEmpty()) selected = (selected + 1) % sources.size
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER,
                        NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            sources.getOrNull(selected)?.let { tv.playChannelSource(channelIndex, it) }
                            true
                        }
                        NativeKeyEvent.KEYCODE_BACK,
                        NativeKeyEvent.KEYCODE_DPAD_UP,
                        NativeKeyEvent.KEYCODE_DPAD_DOWN,
                        NativeKeyEvent.KEYCODE_MENU,
                        NativeKeyEvent.KEYCODE_INFO,
                        NativeKeyEvent.KEYCODE_GUIDE -> {
                            tv.closePanels()
                            true
                        }
                        else -> true
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
            ) {
                BasicText(
                    stringResource(R.string.tv_source_list_n, channel.name, sources.size),
                    style = Type.Headline.copy(fontSize = 15.sp, color = Amber),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 8.dp, end = 14.dp),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .height(82.dp),
                ) {
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(sources) { index, source ->
                            SourceCard(
                                source = source,
                                number = index + 1,
                                count = sources.size,
                                current = source.url == playingUrl,
                                selected = index == selected,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(source: TvSource, number: Int, count: Int, current: Boolean, selected: Boolean) {
    val currentLabel = stringResource(R.string.tv_source_current)
    val tint = when {
        selected -> Amber.copy(alpha = 0.22f)
        current -> Amber.copy(alpha = 0.10f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .width(240.dp)
            .height(74.dp)
            .clip(shape)
            .background(tint)
            .then(
                when {
                    selected -> Modifier.border(2.dp, Amber, shape)
                    current -> Modifier.border(2.dp, AmberDim, shape)
                    else -> Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                BasicText(
                    source.label.ifBlank { "#$number" },
                    style = Type.Body.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Amber else Color.White,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    "$number/$count" + if (current) " · $currentLabel" else "",
                    style = Type.Body.copy(
                        fontSize = 13.sp,
                        color = if (current) Amber else SourceUrl,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicText(
                source.url,
                style = Type.Body.copy(fontSize = 11.sp, color = SourceUrl),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
