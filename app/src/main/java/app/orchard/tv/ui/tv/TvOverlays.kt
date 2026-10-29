package app.orchard.tv.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import app.orchard.tv.tv.TvController

@Composable
fun TvOverlays(tv: TvController) {
    // Any remote key or touch over a panel restarts its 15-second idle close. Preview so the
    // focused control still receives the same event; nothing is consumed here.
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent {
                tv.noteInteraction()
                false
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        tv.noteInteraction()
                    }
                }
            },
    ) {
        TvFullscreen(tv)
        TvChannelList(tv)
        TvChannelGrid(tv)
        TvEpgPanel(tv)
        TvSourceList(tv)
        TvChannelManager(tv)
        if (tv.cityPickerOpen) CityPicker { tv.closePanels() }
    }
}
