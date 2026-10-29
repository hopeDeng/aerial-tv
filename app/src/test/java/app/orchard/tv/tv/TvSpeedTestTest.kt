package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSpeedTestTest {
    private fun bytes(size: Int) = ByteArray(size) { 0x47 }

    private class Fake(
        private val responses: Map<String, TvSpeedTest.Fetch>,
    ) : TvSpeedTest.Fetcher {
        val asked = mutableListOf<String>()
        override fun get(url: String, limit: Int, timeoutMs: Int): TvSpeedTest.Fetch {
            asked += url
            return responses[url] ?: TvSpeedTest.Fetch(url, 0, null, ByteArray(0), 10, error = "missing")
        }
    }

    private fun stream(url: String, size: Int, ms: Long, timedOut: Boolean = false) =
        TvSpeedTest.Fetch(url, 200, "video/mp2t", bytes(size), ms, timedOut)

    private fun text(url: String, body: String, type: String = "application/vnd.apple.mpegurl") =
        TvSpeedTest.Fetch(url, 200, type, body.toByteArray(), 5)

    @Test fun `direct streams are graded by measured speed`() {
        fun verdict(size: Int, ms: Long): TvSpeedTest.Verdict =
            TvSpeedTest.test("http://a", Fake(mapOf("http://a" to stream("http://a", size, ms)))).verdict

        assertEquals(TvSpeedTest.Verdict.HD, verdict(2 * 1024 * 1024, 1000))
        assertEquals(TvSpeedTest.Verdict.SMOOTH, verdict(256 * 1024, 1000))
        assertEquals(TvSpeedTest.Verdict.SLOW, verdict(128 * 1024, 1000))
        assertEquals(TvSpeedTest.Verdict.UNUSABLE, verdict(64 * 1024, 1000))
    }

    @Test fun `http errors and html pages are failures, not slow streams`() {
        val notFound = Fake(mapOf("http://a" to TvSpeedTest.Fetch("http://a", 404, null, ByteArray(0), 20)))
        assertEquals(TvSpeedTest.Verdict.FAILED, TvSpeedTest.test("http://a", notFound).verdict)
        assertEquals("http", TvSpeedTest.test("http://a", notFound).note)

        val html = Fake(
            mapOf("http://a" to TvSpeedTest.Fetch("http://a", 200, "text/html", "<html>".toByteArray(), 20)),
        )
        assertEquals("html", TvSpeedTest.test("http://a", html).note)
    }

    @Test fun `hls follows the master variant and measures its first segment`() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080\nlive/index.m3u8\n"
        val media = "#EXTM3U\n#EXTINF:4,\nseg-1.ts\n#EXTINF:4,\nseg-2.ts\n"
        val fake = Fake(
            mapOf(
                "http://a/list.m3u8" to text("http://a/list.m3u8", master),
                "http://a/live/index.m3u8" to text("http://a/live/index.m3u8", media),
                "http://a/live/seg-1.ts" to stream("http://a/live/seg-1.ts", 1024 * 1024, 1000),
            ),
        )
        val result = TvSpeedTest.test("http://a/list.m3u8", fake)
        assertEquals(TvSpeedTest.Verdict.HD, result.verdict)
        assertEquals(8.388, result.mbps!!, 0.001)
        assertEquals(2000, result.bitrateKbps)
        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertEquals(
            listOf("http://a/list.m3u8", "http://a/live/index.m3u8", "http://a/live/seg-1.ts"),
            fake.asked,
        )
    }

    @Test fun `encrypted hls is reported as encrypted, not as playable`() {
        val media = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\nseg.ts\n"
        val fake = Fake(
            mapOf(
                "http://a/list.m3u8" to text("http://a/list.m3u8", media),
                "http://a/seg.ts" to stream("http://a/seg.ts", 1024 * 1024, 1000),
            ),
        )
        val result = TvSpeedTest.test("http://a/list.m3u8", fake)
        assertEquals(TvSpeedTest.Verdict.ENCRYPTED, result.verdict)
        assertTrue(result.note!!.contains("encrypted"))
        assertTrue(result.mbps!! > 8.0)
    }

    @Test fun `a partial read keeps its measured speed and says it timed out`() {
        val fake = Fake(mapOf("http://a" to stream("http://a", 1024 * 1024, 8000, timedOut = true)))
        val result = TvSpeedTest.test("http://a", fake)
        assertEquals(TvSpeedTest.Verdict.SLOW, result.verdict)
        assertEquals("timeout", result.note)
    }

    @Test fun `a media playlist reports the first segment bitrate`() {
        val media = "#EXTM3U\n#EXTINF:4.0,\nseg.ts\n"
        val fake = Fake(
            mapOf(
                "http://a/list.m3u8" to text("http://a/list.m3u8", media),
                "http://a/seg.ts" to stream("http://a/seg.ts", 512 * 1024, 1000),
            ),
        )
        val result = TvSpeedTest.test("http://a/list.m3u8", fake)
        assertEquals(1049, result.bitrateKbps)
    }

    @Test fun `a bare trailing query is padded for ZTE sources`() {
        assertEquals("http://a/list.m3u8?x=1", TvSpeedTest.requestUrl("http://a/list.m3u8?"))
        assertEquals("http://a/list.m3u8?token=1", TvSpeedTest.requestUrl("http://a/list.m3u8?token=1"))
    }

    @Test fun `a playlist without a segment is a failure`() {
        val fake = Fake(mapOf("http://a/list.m3u8" to text("http://a/list.m3u8", "#EXTM3U\n#EXT-X-ENDLIST\n")))
        assertEquals("no-segment", TvSpeedTest.test("http://a/list.m3u8", fake).note)
    }
}
