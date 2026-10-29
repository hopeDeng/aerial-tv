package app.orchard.tv.ui.tv

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The slot's two resting places, lined up with Home's glass plate, its tiles and the status pill. */
class TvSlotGeometryTest {
    /** The 960x540dp canvas at the layout Home actually uses: five tiles, a plate, two rows. */
    private fun canvas(plate: Boolean = true, topRowY: Float = 299.7f) = tvSlotGeometry(
        screenW = 960.dp,
        screenH = 540.dp,
        margin = 56.dp,
        pitchX = 175.2.dp,
        tileW = 147.2.dp,
        tileH = 82.8.dp,
        columns = 5,
        topRowY = topRowY.dp,
        plate = plate,
    )

    @Test fun `the window lines up with the plate and keeps the screen aspect`() {
        val g = canvas()
        assertEquals(930f, g.windowRight.value, 0.05f)
        assertEquals(82f, g.windowTop.value, 0.05f)
        assertEquals(167.7f, g.windowHeight.value, 0.05f)
        assertEquals(298.13f, g.windowWidth.value, 0.05f)
    }

    @Test fun `the icon is the last tile`() {
        val g = canvas()
        assertEquals(904f, g.iconRight.value, 0.05f)
        assertEquals(82f, g.iconTop.value, 0.05f)
        assertEquals(147.2f, g.iconWidth.value, 0.05f)
        assertEquals(82.8f, g.iconHeight.value, 0.05f)
    }

    @Test fun `a taller row gives a taller window`() {
        // One-row layout: the row sits low, so the window grows between the pill and the plate.
        val g = canvas(topRowY = 407.2f)
        assertEquals(275.2f, g.windowHeight.value, 0.05f)
        assertEquals(g.windowHeight.value * 960f / 540f, g.windowWidth.value, 0.05f)
    }

    @Test fun `without a plate the window lines up with the tiles instead`() {
        val g = canvas(plate = false)
        assertEquals(904f, g.windowRight.value, 0.05f)
        assertEquals(56f, (960.dp - g.windowRight).value, 0.05f)
    }

    @Test fun `a row under the pill floors the height rather than inverting`() {
        val g = canvas(topRowY = 120f)
        assertEquals(60f, g.windowHeight.value, 0.05f)
        assertEquals(106.67f, g.windowWidth.value, 0.05f)
    }
}
