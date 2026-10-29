package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TvSourcesTest {
    private val json = """
        {"channels":[
          {"name":"湖南卫视","enabled":true,"currentSource":1,"sources":[
            {"url":"http://a/1.m3u8","label":"A","enabled":true},
            {"url":"http://b/2.m3u8","label":"B","enabled":true},
            {"url":"http://c/3.m3u8","label":"C","enabled":false}]},
          {"name":"隐藏台","enabled":false,"currentSource":0,"sources":[
            {"url":"http://d/4.m3u8","label":"D","enabled":true}]},
          {"name":"空台","enabled":true,"currentSource":0,"sources":[]},
          {"name":"好台","enabled":true,"currentSource":0,"sources":[
            {"url":"http://e/5.m3u8","label":"E","enabled":true}]}
        ]}
    """.trimIndent()

    @Test fun `keeps every named channel so the page can enable or fix it`() {
        val channels = TvSources.parse(json)
        assertEquals(listOf("湖南卫视", "隐藏台", "空台", "好台"), channels.map { it.name })
    }

    @Test fun `parseOrNull tells an empty list from a damaged file`() {
        assertEquals(emptyList<TvChannel>(), TvSources.parseOrNull("""{"channels":[]}"""))
        assertEquals(null, TvSources.parseOrNull("not json"))
    }

    @Test fun `the saved source plays first, disabled ones never`() {
        val channel = TvSources.parse(json).first()
        assertEquals(listOf("http://b/2.m3u8", "http://a/1.m3u8"), channel.playableSources().map { it.url })
    }

    @Test fun `a bad currentSource still starts from the first usable source`() {
        val channel = TvChannel(
            "台", true, 99,
            listOf(TvSource("http://x", "X", true), TvSource("http://y", "Y", false)),
        )
        assertEquals(listOf("http://x"), channel.playableSources().map { it.url })
    }

    @Test fun `channel with only disabled sources is not played`() {
        val channel = TvChannel("台", true, 0, listOf(TvSource("http://x", "X", false)))
        assertTrue(channel.playableSources().isEmpty())
    }

    @Test fun `parse rejects malformed json instead of throwing`() {
        assertEquals(emptyList<TvChannel>(), TvSources.parse("not json"))
    }

    @Test fun `m3u fallback reads name and url`() {
        val channels = TvSources.parseM3u("#EXTM3U\n#EXTINF:-1,湖南卫视\nhttp://a/1.m3u8\n#EXTINF:-1,浙江卫视\nhttp://b/2.m3u8\n")
        assertEquals(listOf("湖南卫视", "浙江卫视"), channels.map { it.name })
        assertEquals("http://a/1.m3u8", channels.first().playableSources().first().url)
    }
}
