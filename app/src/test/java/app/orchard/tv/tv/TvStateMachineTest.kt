package app.orchard.tv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/** The window table from the spec: an icon that previews on focus, goes fullscreen on OK, and stops
 *  the moment focus leaves it. No pin, and no panels outside fullscreen. */
class TvStateMachineTest {
    private fun run(state: TvState, event: TvEvent) = TvStateMachine.reduce(state, event)

    private val playing = TvState(window = TvWindow.Preview, focused = true, playback = TvPlayback.Playing)
    private val full = TvState(window = TvWindow.Fullscreen, focused = true, playback = TvPlayback.Playing)

    @Test fun `the icon previews on focus`() {
        val result = run(TvState(), TvEvent.WindowGotFocus(hasChannel = true))
        assertEquals(TvWindow.Preview, result.state.window)
        assertEquals(TvEffect.Play, result.effect)
    }

    @Test fun `the icon with nothing to play is the placeholder`() {
        val result = run(TvState(), TvEvent.WindowGotFocus(hasChannel = false))
        assertEquals(TvWindow.Stopped, result.state.window)
        assertEquals(TvEffect.None, result.effect)
    }

    @Test fun `a preview that loses focus stops and collapses`() {
        val result = run(playing, TvEvent.WindowLostFocus)
        assertEquals(TvWindow.Collapsed, result.state.window)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `losing focus in fullscreen changes nothing`() {
        val result = run(full, TvEvent.WindowLostFocus)
        assertEquals(TvWindow.Fullscreen, result.state.window)
        assertEquals(TvEffect.None, result.effect)
    }

    @Test fun `OK on a preview goes straight fullscreen`() {
        val result = run(playing, TvEvent.WindowClicked)
        assertEquals(TvWindow.Fullscreen, result.state.window)
        assertEquals(TvEffect.EnterFullscreen, result.effect)
    }

    @Test fun `OK on the placeholder tries again`() {
        val result = run(TvState(window = TvWindow.Stopped, playback = TvPlayback.Error), TvEvent.WindowClicked)
        assertEquals(TvWindow.Preview, result.state.window)
        assertEquals(TvEffect.Play, result.effect)
    }

    @Test fun `back out of fullscreen is the icon, stopped`() {
        val result = run(full, TvEvent.FullscreenExited)
        assertEquals(TvWindow.Collapsed, result.state.window)
        assertEquals(false, result.state.focused)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `the screensaver takes a focused preview fullscreen instead`() {
        val result = run(playing, TvEvent.Idle(hasChannel = true))
        assertEquals(TvWindow.Fullscreen, result.state.window)
        assertEquals(TvEffect.EnterFullscreen, result.effect)
    }

    @Test fun `the screensaver stops an unfocused preview first`() {
        val result = run(TvState(window = TvWindow.Preview, focused = false, playback = TvPlayback.Playing), TvEvent.Idle(hasChannel = true))
        assertEquals(TvWindow.Collapsed, result.state.window)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `the screensaver leaves an idle Home alone`() {
        for (window in listOf(TvWindow.Collapsed, TvWindow.Stopped)) {
            val result = run(TvState(window = window), TvEvent.Idle(hasChannel = true))
            assertEquals(window.toString(), window, result.state.window)
            assertEquals(TvEffect.None, result.effect)
        }
        val fullResult = run(full, TvEvent.Idle(hasChannel = true))
        assertEquals(TvWindow.Fullscreen, fullResult.state.window)
        assertEquals(TvEffect.None, fullResult.effect)
    }

    @Test fun `the screensaver without anything to play does not go fullscreen`() {
        val result = run(playing, TvEvent.Idle(hasChannel = false))
        assertEquals(TvWindow.Collapsed, result.state.window)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `an app, a manual screensaver and scrolling home away all stop the window`() {
        for (event in listOf<TvEvent>(TvEvent.AppLaunched, TvEvent.ManualScreensaver, TvEvent.LeftHome)) {
            for (window in listOf(TvWindow.Preview, TvWindow.Fullscreen)) {
                val result = run(TvState(window = window, focused = true, playback = TvPlayback.Playing), event)
                assertEquals("$event $window", TvWindow.Collapsed, result.state.window)
                assertEquals(TvEffect.Stop, result.effect)
            }
        }
        val idle = run(TvState(), TvEvent.AppLaunched)
        assertEquals(TvWindow.Collapsed, idle.state.window)
        assertEquals(TvEffect.None, idle.effect)
    }

    @Test fun `a failure in the window leaves the placeholder`() {
        val result = run(playing, TvEvent.PlaybackFailed)
        assertEquals(TvWindow.Stopped, result.state.window)
        assertEquals(TvPlayback.Error, result.state.playback)
        assertEquals(TvEffect.Stop, result.effect)
    }

    @Test fun `a failure in fullscreen stays in fullscreen`() {
        val result = run(full, TvEvent.PlaybackFailed)
        assertEquals(TvWindow.Fullscreen, result.state.window)
        assertEquals(TvPlayback.Error, result.state.playback)
        assertEquals(TvEffect.None, result.effect)
    }

    @Test fun `a stopped icon stays an icon when the screensaver takes fullscreen down`() {
        val result = run(TvState(window = TvWindow.Stopped, playback = TvPlayback.Error), TvEvent.Idle(hasChannel = false))
        assertEquals(TvWindow.Stopped, result.state.window)
        assertEquals(TvEffect.None, result.effect)
    }
}
