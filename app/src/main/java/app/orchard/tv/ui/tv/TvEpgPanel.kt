package app.orchard.tv.ui.tv

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.tv.EpgData
import app.orchard.tv.tv.EpgProgram
import app.orchard.tv.tv.TvController
import app.orchard.tv.tv.TvWindow
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

private val Amber = Color(0xFFFFC107)
private val AmberDim = Color(0xFF8A6D1F)
private val Track = Color(0xFF55503F)
private val TimeLabel = Color(0xFFBDBDBD)
private val PastTitle = Color(0xFF8A8A8A)
private val PanelFill = Color.Black.copy(alpha = 0.6f)

/**
 * Programme guide laid out like the reference player: one strip of horizontal cards over the
 * bottom of the picture, current programme selected and scrolled into view, a status line above.
 * Cards are flat semi-transparent tints, the panel a plain semi-transparent black.
 */
@Composable
fun TvEpgPanel(tv: TvController) {
    if (!tv.epgOpen) return
    if (tv.window != TvWindow.Fullscreen) return

    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val channelIndex = tv.current?.first ?: -1
    val channel = if (channelIndex >= 0) tv.channels.getOrNull(channelIndex) else null
    BackHandler { tv.closePanels() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    var programs by remember { mutableStateOf<List<EpgProgram>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var selected by remember { mutableIntStateOf(0) }

    LaunchedEffect(channel?.name) {
        programs = emptyList()
        loading = false
        failed = false
        currentIndex = -1
        selected = 0
        now = System.currentTimeMillis()
        if (channel == null) return@LaunchedEffect
        loading = true
        val list = withContext(Dispatchers.IO) { EpgData.load(channel.name) }
        now = System.currentTimeMillis()
        programs = list
        failed = list.isEmpty() && EpgData.lastError != null
        currentIndex = list.indexOfFirst { now >= it.start && now < it.end }
        selected = if (currentIndex >= 0) currentIndex else 0
        loading = false
    }

    // Ticker from the reference: keep the progress bars and status live, and follow the current
    // programme only while the selection was still on the previous one.
    LaunchedEffect(programs) {
        if (programs.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
            val cur = programs.indexOfFirst { now >= it.start && now < it.end }
            if (cur != currentIndex) {
                val follow = selected == currentIndex
                currentIndex = cur
                if (follow && cur >= 0) selected = cur
            }
        }
    }

    val inset = with(LocalDensity.current) { 30.dp.roundToPx() }
    LaunchedEffect(selected, programs) {
        if (programs.isEmpty()) return@LaunchedEffect
        listState.animateScrollToItem(selected.coerceIn(0, programs.lastIndex), scrollOffset = -inset)
    }

    val showLine = programs.isNotEmpty()
    val status = when {
        channel == null -> ""
        loading && programs.isEmpty() -> stringResource(R.string.tv_epg_loading, channel.name)
        programs.isEmpty() ->
            if (failed) stringResource(R.string.tv_epg_failed, channel.name)
            else stringResource(R.string.tv_epg_none, channel.name)
        currentIndex in programs.indices -> {
            val p = programs[currentIndex]
            val left = ((p.end - now) / 60_000L).coerceAtLeast(0L)
            stringResource(R.string.tv_epg_on_air, channel.name, p.title, left)
        }
        else -> stringResource(R.string.tv_epg_off_air, channel.name)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (showLine) 118.dp else 44.dp)
                .background(PanelFill)
                .focusRequester(focus)
                .focusable()
                .consumeTaps()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (selected > 0) selected--
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (selected < programs.lastIndex) selected++
                            true
                        }
                        NativeKeyEvent.KEYCODE_MENU,
                        NativeKeyEvent.KEYCODE_BACK,
                        NativeKeyEvent.KEYCODE_DPAD_UP,
                        NativeKeyEvent.KEYCODE_DPAD_DOWN,
                        NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER,
                        NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            tv.closePanels()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
            ) {
                BasicText(
                    status,
                    style = Type.Headline.copy(fontSize = 15.sp, color = Amber),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 8.dp, end = 14.dp),
                )
                if (showLine) {
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
                            itemsIndexed(programs) { index, program ->
                                EpgCard(
                                    program = program,
                                    now = now,
                                    selected = index == selected,
                                    current = index == currentIndex,
                                    past = index < currentIndex,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpgCard(
    program: EpgProgram,
    now: Long,
    selected: Boolean,
    current: Boolean,
    past: Boolean,
) {
    val tint = when {
        selected -> Amber.copy(alpha = 0.22f)
        current -> Amber.copy(alpha = 0.10f)
        past -> Color.Black.copy(alpha = 0.35f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .width(300.dp)
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
        Column(Modifier.fillMaxSize()) {
            BasicText(
                program.title,
                style = Type.Body.copy(
                    fontSize = 16.sp,
                    color = if (past) PastTitle else Color.White,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    clock(program.start),
                    style = Type.Body.copy(fontSize = 13.sp, color = TimeLabel),
                    modifier = Modifier.width(46.dp),
                )
                EpgProgress(
                    progress = progress(program, now),
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                )
                BasicText(
                    clock(program.end),
                    style = Type.Body.copy(fontSize = 13.sp, color = TimeLabel),
                    modifier = Modifier.width(46.dp),
                )
            }
        }
    }
}

@Composable
private fun EpgProgress(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.height / 2f
        val corner = CornerRadius(r, r)
        drawRoundRect(Track, cornerRadius = corner)
        if (progress <= 0f) return@Canvas
        drawRoundRect(
            Amber,
            size = Size(max(size.height, size.width * progress), size.height),
            cornerRadius = corner,
        )
    }
}

private fun progress(program: EpgProgram, now: Long): Float {
    if (program.end <= program.start) return 0f
    if (now <= program.start) return 0f
    if (now >= program.end) return 1f
    return (now - program.start).toFloat() / (program.end - program.start).toFloat()
}

private fun clock(millis: Long): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    fmt.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return fmt.format(java.util.Date(millis))
}
