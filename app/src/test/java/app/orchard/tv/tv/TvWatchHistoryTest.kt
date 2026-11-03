package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvWatchHistoryTest {
    private var now = 1_000L
    private val history = TvWatchHistory(clock = { now })

    @Test fun `switching channels adds the time to the one left`() {
        history.start("CCTV1")
        now += 120_000
        history.start("湖南卫视")
        now += 30_000
        history.stop()

        assertEquals(120_000, history.stats.getValue("CCTV1").totalMs)
        assertEquals(30_000, history.stats.getValue("湖南卫视").totalMs)
        assertEquals(121_000, history.stats.getValue("CCTV1").lastWatchedMs)
        assertEquals(151_000, history.stats.getValue("湖南卫视").lastWatchedMs)
    }

    @Test fun `watching the same channel again accumulates`() {
        history.start("CCTV1")
        now += 180_000
        history.stop()
        now += 60_000
        history.start("CCTV1")
        now += 120_000
        history.stop()

        assertEquals(300_000, history.stats.getValue("CCTV1").totalMs)
    }

    @Test fun `time does not go backwards when the clock does`() {
        history.start("CCTV1")
        now -= 5_000
        history.stop()
        assertEquals(0, history.stats.getValue("CCTV1").totalMs)
    }

    @Test fun `stopping twice does not add time twice`() {
        history.start("CCTV1")
        now += 60_000
        history.stop()
        now += 60_000
        history.stop()
        assertEquals(60_000, history.stats.getValue("CCTV1").totalMs)
    }
}
