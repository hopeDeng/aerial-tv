package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvPlaylistTest {
    private fun channel(name: String, vararg urls: String) =
        TvChannel(name, true, 0, urls.map { TvSource(it, "s", true) })

    private val playlist = TvPlaylist(listOf(channel("A", "a1", "a2"), channel("B", "b1"), channel("C", "c1")))

    @Test fun `next gives the following channel's first source`() {
        val next = playlist.nextChannel(0)!!
        assertEquals(1, next.first)
        assertEquals("b1", next.second.url)
    }

    @Test fun `wrapping goes from the last channel back to the first`() {
        val next = playlist.nextChannel(2)!!
        assertEquals(0, next.first)
        assertEquals("a1", next.second.url)
    }

    @Test fun `a channel with nothing to play is stepped over`() {
        val list = TvPlaylist(listOf(channel("A", "a1"), TvChannel("B", true, 0, emptyList()), channel("C", "c1")))
        assertEquals(2 to "c1", list.nextChannel(0)?.let { it.first to it.second.url })
    }

    @Test fun `a missing channel starts at the first`() {
        assertEquals(0 to "a1", playlist.first()?.let { it.first to it.second.url })
    }

    @Test fun `empty playlist has no first`() {
        assertNull(TvPlaylist(emptyList()).first())
    }
}
