package app.orchard.tv.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vertical navigation in the five-column grid; rows wrap at both ends. */
class TvChannelGridNavTest {
    @Test fun `up from the first row wraps to the last row`() {
        assertEquals(160, tvGridVerticalTarget(0, 162, down = false))
        assertEquals(161, tvGridVerticalTarget(1, 162, down = false))
    }

    @Test fun `up from a later row keeps the column`() {
        assertEquals(2, tvGridVerticalTarget(7, 162, down = false))
    }

    @Test fun `down from the last row wraps to the first row`() {
        assertEquals(1, tvGridVerticalTarget(161, 162, down = true))
        assertEquals(0, tvGridVerticalTarget(160, 162, down = true))
    }

    @Test fun `a short last row clamps to its last channel`() {
        assertEquals(161, tvGridVerticalTarget(3, 162, down = false))
    }

    @Test fun `a single row stays put`() {
        assertEquals(3, tvGridVerticalTarget(3, 4, down = false))
        assertEquals(3, tvGridVerticalTarget(3, 4, down = true))
    }

    @Test fun `empty grid is safe`() {
        assertEquals(0, tvGridVerticalTarget(0, 0, down = true))
    }
}
