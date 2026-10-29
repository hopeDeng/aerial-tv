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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.tv.TvChannel
import app.orchard.tv.tv.TvController
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.theme.Type

private const val Columns = 5
private val CellHeight = 79.dp
private val Selection = Color(0xFFFF9800)
private val PanelFill = Color.Black.copy(alpha = 0.6f)
private val IconColors = intArrayOf(
    0xFFE53935.toInt(), 0xFF8E24AA.toInt(), 0xFF3949AB.toInt(), 0xFF1E88E5.toInt(),
    0xFF00897B.toInt(), 0xFF43A047.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(),
    0xFF546E7A.toInt(), 0xFFD81B60.toInt(), 0xFF5E35B1.toInt(), 0xFF00ACC1.toInt(),
    0xFF7CB342.toInt(), 0xFFC0CA33.toInt(), 0xFFFB8C00.toInt(),
)

/**
 * The reference player's grid: five columns of colour letter tiles against the left edge, the
 * selected tile outlined in orange. The grid scrolls only when the selected row is not fully on
 * screen; up/down wrap between the first and last rows. MENU goes back to the list; BACK closes
 * either.
 */
@Composable
fun TvChannelGrid(tv: TvController) {
    if (!tv.channelGridOpen) return

    val focus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val count = tv.channels.size
    val rowHeight = with(LocalDensity.current) { CellHeight.roundToPx() }
    var selected by remember {
        mutableIntStateOf((tv.current?.first ?: 0).coerceIn(0, (count - 1).coerceAtLeast(0)))
    }

    fun reveal(index: Int, downward: Boolean) {
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        val rowStart = target / Columns * Columns
        val rowEnd = (rowStart + Columns - 1).coerceAtMost(count - 1)
        val info = gridState.layoutInfo
        val rowItems = info.visibleItemsInfo.filter { it.index in rowStart..rowEnd }
        val top = rowItems.minOfOrNull { it.offset.y }
        val bottom = rowItems.maxOfOrNull { it.offset.y + it.size.height }
        val viewport = info.viewportSize.height
        if (top == null || top < 0 || bottom == null || bottom > viewport) {
            // Up moves align the row to the top; down moves align it to the bottom, so the grid
            // only scrolls when the row would otherwise leave the screen.
            val offset = if (downward) rowHeight - viewport else 0
            gridState.requestScrollToItem(rowStart, offset)
        }
    }

    fun moveTo(index: Int, downward: Boolean) {
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        if (target / Columns != selected / Columns) reveal(target, downward)
        selected = target
    }

    BackHandler { tv.closePanels() }
    LaunchedEffect(Unit) {
        // The list this replaced is torn down in the same frame; asking for focus before the next
        // frame fails and leaves Home's weather line holding the remote (its OK opens the city
        // picker). Wait for the grid's focus node to be laid out first.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        if (count > 0) gridState.requestScrollToItem(selected / Columns * Columns)
    }
    LaunchedEffect(count) {
        selected = selected.coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .width(365.dp)
                .fillMaxHeight()
                .background(PanelFill)
                .focusRequester(focus)
                .focusable()
                .consumeTaps()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (count > 0) {
                                val target = (selected - 1).coerceAtLeast(0)
                                moveTo(target, downward = target / Columns > selected / Columns)
                            }
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (count > 0) {
                                val target = (selected + 1).coerceAtMost(count - 1)
                                moveTo(target, downward = target / Columns > selected / Columns)
                            }
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_UP -> {
                            if (count > 0) moveTo(tvGridVerticalTarget(selected, count, down = false), downward = false)
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (count > 0) moveTo(tvGridVerticalTarget(selected, count, down = true), downward = true)
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER,
                        NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            tv.playChannel(selected)
                            true
                        }
                        NativeKeyEvent.KEYCODE_MENU -> {
                            tv.openChannelList()
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(Columns),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(tv.channels) { index, channel ->
                    GridCell(channel = channel, selected = index == selected)
                }
            }
        }
    }
}

@Composable
private fun GridCell(channel: TvChannel, selected: Boolean) {
    Column(
        Modifier
            .height(CellHeight)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .then(
                    if (selected) {
                        Modifier.border(3.dp, Selection, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(iconColor(channel.name)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    letterOf(channel.name),
                    style = Type.Title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White),
                )
            }
        }
        BasicText(
            channel.name,
            style = Type.Body.copy(fontSize = 15.sp, color = Color.White, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(54.dp)
                .height(16.dp),
        )
    }
}

internal fun tvGridVerticalTarget(index: Int, count: Int, down: Boolean): Int {
    if (count <= 0) return 0
    val rows = (count + Columns - 1) / Columns
    val current = index.coerceIn(0, count - 1)
    val row = (current / Columns + if (down) 1 else rows - 1) % rows
    return (row * Columns + current % Columns).coerceAtMost(count - 1)
}

private fun letterOf(name: String): String =
    name.firstOrNull { !it.isWhitespace() }?.toString()?.uppercase() ?: "?"

private fun iconColor(name: String): Color =
    Color(IconColors[(name.hashCode() and 0x7fffffff) % IconColors.size])
