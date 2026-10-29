package app.orchard.tv.tv

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvImportTest {
    private fun channel(name: String, vararg urls: String, label: String = "old") =
        TvChannel(name, true, 0, urls.map { TvSource(it, label, true) })

    @Test fun `reads extinf names, bare urls and the box name comma url form`() {
        val entries = TvImport.parseM3u(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="x",湖南卫视
            http://a/1.m3u8
            #EXTINF:-1,浙江卫视
            浙江二套,http://b/2.m3u8
            #EXTINF:-1,
            # a comment
            http://c/3.m3u8
            ftp://d/4
            """.trimIndent(),
        )
        assertEquals(listOf("湖南卫视", "浙江二套", "频道3"), entries.map { it.name })
        assertEquals(listOf("http://a/1.m3u8", "http://b/2.m3u8", "http://c/3.m3u8"), entries.map { it.url })
    }

    @Test fun `normalizes spacing punctuation and the words TV channels`() {
        assertEquals("湖南卫", TvImport.normalizeName(" 湖南-卫视 高清频道 "))
        assertEquals("cctv1", TvImport.normalizeName("CCTV-1"))
    }

    @Test fun `matches exact names first and then containment`() {
        val channels = listOf(channel("湖南卫视"), channel("CCTV1"), channel("CCTV10"))
        assertEquals(0, TvImport.findByName(channels, "湖南卫视高清"))
        assertEquals(1, TvImport.findByName(channels, "CCTV-1"))
        // A longer name with a different digit tail is another channel, not CCTV1.
        assertEquals(2, TvImport.findByName(channels, "CCTV10"))
        assertNull(TvImport.findByName(channels, "北京卫视"))
    }

    @Test fun `merges into the same normalized channel and skips the same url`() {
        val channels = listOf(channel("湖南卫视", "http://a/1"))
        val result = TvImport.merge(
            channels,
            listOf(
                TvImport.Entry("湖南卫视高清", "http://a/2"),
                TvImport.Entry("湖南卫视", "http://a/1"),
                TvImport.Entry("CCTV1", "http://c/1"),
            ),
            "file",
        )
        assertEquals(listOf("湖南卫视", "CCTV1"), result.channels.map { it.name })
        assertEquals(listOf("http://a/1", "http://a/2"), result.channels[0].sources.map { it.url })
        assertEquals(listOf("old", "file"), result.channels[0].sources.map { it.label })
        assertEquals(1, result.addedChannels)
        assertEquals(1, result.addedSources)
        assertEquals(1, result.duplicates)
    }

    @Test fun `preview shows the target and duplicates`() {
        val channels = listOf(channel("湖南卫视", "http://a/1"))
        val preview = TvImport.preview(
            channels,
            listOf(
                TvImport.Entry("湖南卫视", "http://a/1"),
                TvImport.Entry("湖南卫视", "http://a/2"),
                TvImport.Entry("CCTV1", "http://c/1"),
            ),
            "file",
        )
        assertEquals(listOf("湖南卫视", "湖南卫视", null), preview.items.map { it.target })
        assertEquals(listOf(true, false, false), preview.items.map { it.duplicate })
    }

    @Test fun `hnyd sources move first while the current source is kept by url`() {
        val channels = listOf(
            TvChannel(
                "A", true, 2,
                listOf(
                    TvSource("http://a/1", "file", true),
                    TvSource("http://a/2", "hnyd", true),
                    TvSource("http://a/3", "file", true),
                ),
            ),
        )
        val reordered = TvImport.reorderPreferred(channels, "hnyd")
        assertEquals(listOf("http://a/2", "http://a/1", "http://a/3"), reordered[0].sources.map { it.url })
        assertEquals("http://a/3", reordered[0].playableSources().first().url)
    }

    @Test fun `decodes gbk playlists instead of replacing the Chinese names`() {
        val bytes = "#EXTINF:-1,湖南卫视\nhttp://a/1\n".toByteArray(Charset.forName("GBK"))
        assertTrue(TvImport.decode(bytes).contains("湖南卫视"))
    }

    @Test fun `exports each channel's current source`() {
        val channels = listOf(
            TvChannel("A", true, 1, listOf(TvSource("http://a/1", "x", true), TvSource("http://a/2", "x", true))),
            TvChannel("B", false, 0, listOf(TvSource("http://b/1", "x", true))),
        )
        assertEquals(
            "#EXTM3U\n#EXTINF:-1,A\nhttp://a/2\n#EXTINF:-1,B\nhttp://b/1\n",
            TvImport.exportM3u(channels),
        )
    }

    @Test fun `imports the export format with and without a pool`() {
        val export = TvStore.encodeExport(
            listOf(channel("A", "http://a/1")),
            listOf(TvSource("http://pool/1", "p", true)),
        )
        val imported = TvStore.parseImport(export)!!
        assertEquals(listOf("A"), imported.channels.map { it.name })
        assertEquals(listOf("http://pool/1"), imported.pool.map { it.url })

        val old = TvStore.parseImport("""{"channels":[{"name":"B","sources":[{"url":"http://b/1"}]}]}""")!!
        assertEquals(listOf("http://b/1"), old.pool.map { it.url })
        assertNull(TvStore.parseImport("not json"))
        assertNull(TvStore.parseImport("""{"nope":[]}"""))
    }
}
