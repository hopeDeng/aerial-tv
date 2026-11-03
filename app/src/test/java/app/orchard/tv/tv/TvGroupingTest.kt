package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvGroupingTest {
    private fun channel(name: String, group: String? = null, enabled: Boolean = true, source: Boolean = true) =
        TvChannel(name, enabled, 0, if (source) listOf(TvSource("http://$name", "", true)) else emptyList(), group)

    private val channels = listOf(
        channel("CCTV1", "央视频道"),
        channel("湖南卫视", "卫视频道"),
        channel("长沙都市", null),
        channel("旧分组", "已删除"),
        channel("CCTV5", "央视频道", enabled = false),
        channel("空台", null, source = false),
    )

    @Test fun `the group entries are recent, the owner's order, all, then ungrouped`() {
        assertEquals(
            listOf(
                TvGroupFilter.Recent,
                TvGroupFilter.Named("央视频道"),
                TvGroupFilter.Named("卫视频道"),
                TvGroupFilter.All,
                TvGroupFilter.Ungrouped,
            ),
            tvGroupFilters(listOf("央视频道", "卫视频道")),
        )
    }

    @Test fun `a named group keeps the channel order and includes disabled channels`() {
        assertEquals(
            listOf(0, 4),
            tvChannelsInGroup(channels, listOf("央视频道"), TvGroupFilter.Named("央视频道"), emptyMap()),
        )
    }

    @Test fun `all channels keeps the file order`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5),
            tvChannelsInGroup(channels, TvStore.DEFAULT_GROUPS, TvGroupFilter.All, emptyMap()),
        )
    }

    @Test fun `ungrouped also takes channels whose group was deleted`() {
        assertEquals(
            listOf(2, 3, 5),
            tvChannelsInGroup(channels, listOf("央视频道", "卫视频道"), TvGroupFilter.Ungrouped, emptyMap()),
        )
    }

    @Test fun `recent needs five minutes, a playable channel and sorts by last watched`() {
        val stats = mapOf(
            "CCTV1" to WatchStat(totalMs = 300_000, lastWatchedMs = 10),
            "湖南卫视" to WatchStat(totalMs = 299_999, lastWatchedMs = 20),
            "长沙都市" to WatchStat(totalMs = 900_000, lastWatchedMs = 30),
            "CCTV5" to WatchStat(totalMs = 900_000, lastWatchedMs = 40),
            "空台" to WatchStat(totalMs = 900_000, lastWatchedMs = 50),
        )
        assertEquals(
            listOf(2, 0),
            tvChannelsInGroup(channels, TvStore.DEFAULT_GROUPS, TvGroupFilter.Recent, stats),
        )
    }

    @Test fun `the playing channel picks its own group, otherwise recent`() {
        assertEquals(
            TvGroupFilter.Named("央视频道"),
            tvGroupOfChannel(channels[0], listOf("央视频道", "卫视频道")),
        )
        assertEquals(TvGroupFilter.Recent, tvGroupOfChannel(channels[2], listOf("央视频道")))
        assertEquals(TvGroupFilter.Recent, tvGroupOfChannel(channels[3], listOf("央视频道")))
        assertEquals(TvGroupFilter.Recent, tvGroupOfChannel(null, listOf("央视频道")))
    }

    @Test fun `the selected group survives its storage key`() {
        for (filter in listOf(
            TvGroupFilter.Recent,
            TvGroupFilter.Named("央视频道"),
            TvGroupFilter.All,
            TvGroupFilter.Ungrouped,
        )) {
            assertEquals(filter, tvGroupFilterOfKey(filter.storageKey()))
        }
        assertEquals(TvGroupFilter.All, tvGroupFilterOfKey("missing"))
        assertEquals(TvGroupFilter.All, tvGroupFilterOfKey(null))
    }

    @Test fun `no watch history means an empty recent group`() {
        assertEquals(
            emptyList<Int>(),
            tvChannelsInGroup(channels, TvStore.DEFAULT_GROUPS, TvGroupFilter.Recent, emptyMap()),
        )
    }
}
