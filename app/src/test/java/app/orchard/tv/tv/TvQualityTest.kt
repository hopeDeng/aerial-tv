package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvQualityTest {
    private val probe = TvSpeedTest.Result(
        url = "http://example/live.m3u8",
        verdict = TvSpeedTest.Verdict.HD,
        mbps = 23.7,
        ms = 900,
        status = 200,
        note = null,
        finalUrl = "http://example/live.m3u8",
        bitrateKbps = 8000,
        width = 1280,
        height = 720,
    )

    @Test fun `player stats win over probe stats`() {
        val video = TvVideoStats(1920, 1080, 50.0, 4500, "ExoPlayer")
        val q = TvQuality.from(video, probe)
        assertEquals(23.7, q.speedMbps!!, 0.001)
        assertEquals(4500, q.bitrateKbps)
        assertEquals(1920, q.width)
        assertEquals(1080, q.height)
        assertEquals(50.0, q.fps!!, 0.001)
    }

    @Test fun `probe fills values the player does not report`() {
        val q = TvQuality.from(null, probe)
        assertEquals(8000, q.bitrateKbps)
        assertEquals(1280, q.width)
        assertEquals(720, q.height)
        assertNull(q.fps)
    }

    @Test fun `each missing player field falls back independently`() {
        val video = TvVideoStats(1280, 720, null, null, "LibVLC")
        val q = TvQuality.from(video, probe)
        assertEquals(8000, q.bitrateKbps)
        assertEquals(1280, q.width)
        assertEquals(720, q.height)
        assertNull(q.fps)
    }

    @Test fun `channel label includes the source label`() {
        assertEquals("CCTV1 · hnyd", channelLabel("CCTV1", TvSource("http://example/live.m3u8", "hnyd", true)))
        assertEquals("CCTV1", channelLabel("CCTV1", TvSource("http://example/live.m3u8", " ", true)))
        assertEquals("CCTV1", channelLabel("CCTV1", null))
    }

    @Test fun `metrics format for the TV panel`() {
        assertEquals("23.7 Mbps", formatMbps(23.74))
        assertEquals("8.0 Mbps", formatBitrate(8000))
        assertEquals("512 kbps", formatBitrate(512))
        assertEquals("1920×1080", formatResolution(1920, 1080))
        assertEquals("50 fps", formatFps(50.0))
        assertEquals("—", formatMbps(null))
        assertEquals("—", formatBitrate(null))
        assertEquals("—", formatResolution(null, 1080))
        assertEquals("—", formatFps(null))
    }
}
