package app.orchard.tv.ui.tv

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.tv.TvController
import app.orchard.tv.tv.TvPhoneServer
import app.orchard.tv.tv.TvPlayback
import app.orchard.tv.tv.TvQuality
import app.orchard.tv.tv.TvSpeedTest
import app.orchard.tv.tv.formatBitrate
import app.orchard.tv.tv.formatFps
import app.orchard.tv.tv.formatMbps
import app.orchard.tv.tv.formatResolution
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UP in fullscreen opens this panel instead of the channel grid: the phone page is where channels
 * and sources are edited now. It never times out, because scanning and typing take longer than the
 * other panels' 15 seconds; UP or Back closes it.
 */
@Composable
fun TvChannelManager(tv: TvController) {
    if (!tv.managerOpen) return

    val focus = remember { FocusRequester() }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    val url = tv.managerUrl
    BackHandler { tv.closePanels() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(url) {
        qr = url?.let {
            withContext(Dispatchers.Default) {
                runCatching { TvPhoneServer.qr(it).asImageBitmap() }.getOrNull()
            }
        }
    }

    val current = tv.current
    val channel = current?.let { tv.channels.getOrNull(it.first) }
    val source = current?.second
    val test = tv.sourceTest?.takeIf { it.source.url == source?.url }
    val quality = TvQuality.from(tv.videoStats, test?.result)
    val sourceTitle = when {
        channel == null || source == null -> stringResource(R.string.tv_no_source)
        source.label.isBlank() -> channel.name
        else -> "${channel.name} · ${source.label}"
    }
    val playbackLabel = when (tv.playback) {
        TvPlayback.Idle -> stringResource(R.string.tv_playback_idle)
        TvPlayback.Buffering -> stringResource(R.string.tv_playback_buffering)
        TvPlayback.Playing -> stringResource(R.string.tv_playback_playing)
        TvPlayback.Paused -> stringResource(R.string.tv_playback_paused)
        TvPlayback.Error -> stringResource(R.string.tv_playback_error)
    }
    val playbackColor = when (tv.playback) {
        TvPlayback.Playing -> Color(0xFF7ED6A5)
        TvPlayback.Error -> Color(0xFFFF6B6B)
        else -> Color.White.copy(alpha = 0.66f)
    }
    val (verdictText, verdictColor) = when {
        test == null -> stringResource(R.string.tv_quality_waiting) to Color.White.copy(alpha = 0.45f)
        test.running -> stringResource(R.string.tv_quality_testing) to Color(0xFFFFC107)
        else -> when (test.result?.verdict) {
            TvSpeedTest.Verdict.HD -> stringResource(R.string.tv_verdict_hd) to Color(0xFF7ED6A5)
            TvSpeedTest.Verdict.SMOOTH -> stringResource(R.string.tv_verdict_smooth) to Color(0xFF7ED6A5)
            TvSpeedTest.Verdict.SLOW -> stringResource(R.string.tv_verdict_slow) to Color(0xFFFFC107)
            TvSpeedTest.Verdict.ENCRYPTED -> stringResource(R.string.tv_verdict_encrypted) to Color(0xFF9E9E9E)
            TvSpeedTest.Verdict.UNUSABLE -> stringResource(R.string.tv_verdict_unusable) to Color(0xFFFF8A65)
            else -> stringResource(R.string.tv_verdict_failed) to Color(0xFFFF6B6B)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .consumeTaps()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_BACK,
                    NativeKeyEvent.KEYCODE_DPAD_UP -> tv.closePanels()
                    else -> Unit
                }
                true
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .padding(end = 24.dp, top = 24.dp, bottom = 24.dp)
                .width(360.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.90f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
                .padding(20.dp),
        ) {
            BasicText(
                stringResource(R.string.tv_manage_title),
                style = Type.Title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(140.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    qr != null -> Image(
                        qr!!,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize(),
                    )
                    tv.managerLoading -> BasicText(
                        stringResource(R.string.tv_manage_starting),
                        style = Type.Caption.copy(color = Color.Black.copy(alpha = 0.55f)),
                    )
                    else -> GlyphIcon(Glyph.Offline, Color.Black.copy(alpha = 0.35f), 32.dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                when {
                    tv.managerLoading -> stringResource(R.string.tv_manage_starting)
                    url == null -> stringResource(R.string.tv_manage_offline)
                    else -> url.removePrefix("http://")
                },
                style = Type.Caption.copy(
                    fontSize = 12.sp,
                    color = if (url != null) Color(0xFFFFC107) else Color.White.copy(alpha = 0.7f),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.10f)),
            )
            Spacer(Modifier.height(10.dp))
            BasicText(
                stringResource(R.string.tv_quality_current),
                style = Type.Caption.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.50f)),
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                sourceTitle,
                style = Type.Body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                playbackLabel,
                style = Type.Caption.copy(fontSize = 12.sp, color = playbackColor),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                source?.url ?: "—",
                style = Type.Caption.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.52f)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(verdictColor.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                BasicText(
                    verdictText,
                    style = Type.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = verdictColor),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(stringResource(R.string.tv_quality_speed), formatMbps(quality.speedMbps), Modifier.weight(1f))
                Metric(stringResource(R.string.tv_quality_bitrate), formatBitrate(quality.bitrateKbps), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(
                    stringResource(R.string.tv_quality_resolution),
                    formatResolution(quality.width, quality.height),
                    Modifier.weight(1f),
                )
                Metric(stringResource(R.string.tv_quality_fps), formatFps(quality.fps), Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            BasicText(
                stringResource(R.string.tv_manage_close),
                style = Type.Caption.copy(fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f)),
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText(
            label,
            style = Type.Caption.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            value,
            style = Type.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
