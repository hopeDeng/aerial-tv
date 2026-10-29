package app.orchard.tv.ui.tv

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orchard.tv.ui.home.PillHeight
import app.orchard.tv.ui.home.PillTop
import app.orchard.tv.ui.home.PlatePadH
import app.orchard.tv.ui.home.PlatePadV

/**
 * The live slot's two resting places, in the canvas Home lays its grid in. The window's right edge
 * lines up with the glass plate's (with no plate, with the last tile's), its bottom and top sit one
 * screen-edge gap off the plate and the status pill, and its width follows the screen's aspect ratio.
 * The icon is a tile: the last tile of the top row's size and right edge.
 */
data class TvSlotGeometry(
    val screenW: Dp,
    val windowTop: Dp,
    val windowRight: Dp,
    val windowWidth: Dp,
    val windowHeight: Dp,
    val iconTop: Dp,
    val iconRight: Dp,
    val iconWidth: Dp,
    val iconHeight: Dp,
)

/** The window is never flatter than this, whatever layout switch pushed the top row up. */
private val MinSlotHeight = 60.dp

fun tvSlotGeometry(
    screenW: Dp,
    screenH: Dp,
    margin: Dp,
    pitchX: Dp,
    tileW: Dp,
    tileH: Dp,
    columns: Int,
    topRowY: Dp,
    plate: Boolean,
): TvSlotGeometry {
    val tileRight = margin + pitchX * (columns - 1) + tileW
    val windowRight = if (plate) tileRight + PlatePadH else tileRight
    val gap = screenW - windowRight
    val rowTop = if (plate) topRowY - PlatePadV else topRowY
    // Closer to the pill than to the plate: the top margin is two thirds of the screen-edge gap.
    val top = PillTop + PillHeight + gap * (2f / 3f)
    val bottom = (rowTop - gap).coerceAtLeast(top + MinSlotHeight)
    val height = bottom - top
    return TvSlotGeometry(
        screenW = screenW,
        windowTop = top,
        windowRight = windowRight,
        // The aspect ratio first: Dp only divides by Dp to a Float, so the screen's width/height
        // fraction is taken before it scales the height.
        windowWidth = height * (screenW / screenH),
        windowHeight = height,
        iconTop = top,
        iconRight = tileRight,
        iconWidth = tileW,
        iconHeight = tileH,
    )
}

/** Interpolate two dp lengths; the slot morph drives all four edges from one fraction. */
fun lerpDp(from: Dp, to: Dp, fraction: Float): Dp = from + (to - from) * fraction
