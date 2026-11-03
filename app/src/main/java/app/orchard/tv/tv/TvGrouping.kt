package app.orchard.tv.tv

/** How long a channel must have played in fullscreen before it joins "recently watched". */
const val TV_RECENT_MIN_MS = 5 * 60 * 1000L

data class WatchStat(val totalMs: Long, val lastWatchedMs: Long)

/** The left-hand picker: special lists around the owner's own groups. */
sealed interface TvGroupFilter {
    data object Recent : TvGroupFilter
    data object All : TvGroupFilter
    data object Ungrouped : TvGroupFilter
    data class Named(val name: String) : TvGroupFilter
}

fun TvGroupFilter.storageKey(): String = when (this) {
    TvGroupFilter.Recent -> "recent"
    TvGroupFilter.All -> "all"
    TvGroupFilter.Ungrouped -> "ungrouped"
    is TvGroupFilter.Named -> "named:$name"
}

fun tvGroupFilterOfKey(key: String?): TvGroupFilter = when {
    key == null || key == "all" -> TvGroupFilter.All
    key == "recent" -> TvGroupFilter.Recent
    key == "ungrouped" -> TvGroupFilter.Ungrouped
    key.startsWith("named:") -> TvGroupFilter.Named(key.removePrefix("named:"))
    else -> TvGroupFilter.All
}

fun tvGroupFilters(groups: List<String>): List<TvGroupFilter> =
    listOf(TvGroupFilter.Recent) +
        groups.map { TvGroupFilter.Named(it) } +
        listOf(TvGroupFilter.All, TvGroupFilter.Ungrouped)

/**
 * The group that marks the playing channel when the list opens: its own custom group, or "recent"
 * when it has none (or that group was deleted). Data currently stores one group per channel.
 */
fun tvGroupOfChannel(channel: TvChannel?, groups: List<String>): TvGroupFilter {
    val name = channel?.group
    return if (name != null && name in groups) TvGroupFilter.Named(name) else TvGroupFilter.Recent
}

/** Original indices, so playing and the channel numbers stay those of the whole list. */
fun tvChannelsInGroup(
    channels: List<TvChannel>,
    groups: List<String>,
    filter: TvGroupFilter,
    stats: Map<String, WatchStat>,
): List<Int> = when (filter) {
    TvGroupFilter.All -> channels.indices.toList()
    TvGroupFilter.Recent -> channels.indices
        .filter { index ->
            val channel = channels[index]
            channel.enabled &&
                channel.playableSources().isNotEmpty() &&
                (stats[channel.name]?.totalMs ?: 0L) >= TV_RECENT_MIN_MS
        }
        .sortedByDescending { stats[channels[it].name]?.lastWatchedMs ?: 0L }
    TvGroupFilter.Ungrouped -> channels.indices.filter { index ->
        val group = channels[index].group
        group.isNullOrBlank() || group !in groups
    }
    is TvGroupFilter.Named -> channels.indices.filter { channels[it].group == filter.name }
}

/**
 * Counts fullscreen viewing by channel name. Time is added when the channel is left, so a crash
 * loses at most the current stretch; the map is handed back to its owner after every update.
 */
class TvWatchHistory(
    initial: Map<String, WatchStat> = emptyMap(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChange: (Map<String, WatchStat>) -> Unit = {},
) {
    var stats: Map<String, WatchStat> = initial
        private set

    private var name: String? = null
    private var since = 0L

    fun start(channel: String?) {
        flush()
        name = channel
        since = if (channel == null) 0L else clock()
    }

    fun stop() = start(null)

    fun flush() {
        val channel = name ?: return
        val now = clock()
        val elapsed = (now - since).coerceAtLeast(0L)
        val old = stats[channel] ?: WatchStat(0L, 0L)
        stats = stats + (channel to WatchStat(old.totalMs + elapsed, now))
        since = now
        onChange(stats)
    }
}
