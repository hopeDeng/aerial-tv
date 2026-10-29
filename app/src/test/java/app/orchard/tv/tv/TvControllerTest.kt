package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvControllerTest {
    private val channels = listOf(
        TvChannel("A", true, 0, listOf(TvSource("a1", "", true), TvSource("a2", "", true))),
        TvChannel("B", true, 0, listOf(TvSource("b1", "", true))),
    )

    @Test fun `restores the remembered channel and source`() {
        val at = restore(channels, "B", "b1")
        assertEquals(1 to "b1", at?.let { it.first to it.second.url })
    }

    @Test fun `falls back to the same channel's first source`() {
        val at = restore(channels, "A", "gone")
        assertEquals(0 to "a1", at?.let { it.first to it.second.url })
    }

    @Test fun `falls back to the first channel when the name is gone`() {
        val at = restore(channels, "Z", "z1")
        assertEquals(0 to "a1", at?.let { it.first to it.second.url })
    }

    @Test fun `keeps the exact source being watched after a save`() {
        val edited = listOf(
            TvChannel(
                "A", true, 0,
                listOf(TvSource("a1", "", true), TvSource("a2", "", true)),
            ),
        )
        val at = restoreAfterEdit(edited, "A", "a2", 0)
        assertEquals(0 to "a2", at?.let { it.first to it.second.url })
    }

    @Test fun `deleting the watched channel stays at its old position`() {
        val edited = listOf(
            TvChannel("A", true, 0, listOf(TvSource("a1", "", true))),
            TvChannel("C", true, 0, listOf(TvSource("c1", "", true))),
        )
        // B was at index 1; C takes its place.
        val at = restoreAfterEdit(edited, "B", "b1", 1)
        assertEquals(1 to "c1", at?.let { it.first to it.second.url })
    }

    @Test fun `deleting the last channel wraps to the first`() {
        val edited = listOf(TvChannel("A", true, 0, listOf(TvSource("a1", "", true))))
        val at = restoreAfterEdit(edited, "B", "b1", 9)
        assertEquals(0 to "a1", at?.let { it.first to it.second.url })
    }
}
