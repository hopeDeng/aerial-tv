package app.orchard.tv.tv

import java.util.Locale

data class TvVideoStats(
    val width: Int,
    val height: Int,
    val fps: Double?,
    val bitrateKbps: Int?,
    val engine: String,
)

data class TvSourceTest(
    val channelIndex: Int,
    val channelName: String,
    val source: TvSource,
    val running: Boolean,
    val result: TvSpeedTest.Result? = null,
)

data class TvQuality(
    val speedMbps: Double?,
    val bitrateKbps: Int?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
) {
    companion object {
        fun from(video: TvVideoStats?, test: TvSpeedTest.Result?): TvQuality {
            val width = video?.width?.takeIf { it > 0 } ?: test?.width
            val height = video?.height?.takeIf { it > 0 } ?: test?.height
            return TvQuality(
                speedMbps = test?.mbps,
                bitrateKbps = video?.bitrateKbps?.takeIf { it > 0 } ?: test?.bitrateKbps,
                width = width,
                height = height,
                fps = video?.fps?.takeIf { it > 0 },
            )
        }
    }
}

fun formatMbps(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f Mbps", it) } ?: "—"

fun formatBitrate(kbps: Int?): String = when {
    kbps == null -> "—"
    kbps >= 1000 -> String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
    else -> "$kbps kbps"
}

fun formatResolution(width: Int?, height: Int?): String =
    if (width != null && height != null && width > 0 && height > 0) "${width}×${height}" else "—"

fun formatFps(fps: Double?): String =
    fps?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.0f fps", it) } ?: "—"

fun channelLabel(name: String, source: TvSource?): String =
    if (source?.label.isNullOrBlank()) name else "$name · ${source.label}"
