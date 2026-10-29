package app.orchard.tv.tv

enum class TvWindow { Collapsed, Preview, Fullscreen, Stopped }

enum class TvPlayback { Idle, Buffering, Playing, Paused, Error }

data class TvState(
    val window: TvWindow = TvWindow.Collapsed,
    val focused: Boolean = false,
    val playback: TvPlayback = TvPlayback.Idle,
)

sealed interface TvEvent {
    data class WindowGotFocus(val hasChannel: Boolean) : TvEvent
    data object WindowLostFocus : TvEvent
    data object WindowClicked : TvEvent
    data class Idle(val hasChannel: Boolean) : TvEvent
    data object FullscreenExited : TvEvent
    data object AppLaunched : TvEvent
    /** Home's grid scrolled past its shelf: the slot slid away with it, so live TV stops. */
    data object LeftHome : TvEvent
    data object ManualScreensaver : TvEvent
    data object PlaybackFailed : TvEvent
}

sealed interface TvEffect {
    data object None : TvEffect
    data object Play : TvEffect
    data object Stop : TvEffect
    data object EnterFullscreen : TvEffect
}

data class TvTransition(val state: TvState, val effect: TvEffect)

/**
 * The live-window state machine: a tile-sized icon, a preview that plays while it has focus,
 * fullscreen, and the not-playable placeholder. There is no pinned state: the small window is Home's
 * only live picture, and everything else - channel lists, source management, EPG - lives inside
 * fullscreen, which owns its own overlay stack.
 */
object TvStateMachine {
    fun reduce(state: TvState, event: TvEvent): TvTransition = when (event) {
        is TvEvent.WindowGotFocus -> when {
            state.window == TvWindow.Fullscreen -> keep(state)
            state.window == TvWindow.Collapsed && !event.hasChannel ->
                TvTransition(state.copy(window = TvWindow.Stopped, focused = true, playback = TvPlayback.Idle), TvEffect.None)
            state.window == TvWindow.Collapsed ->
                TvTransition(state.copy(window = TvWindow.Preview, focused = true), TvEffect.Play)
            else -> TvTransition(state.copy(focused = true), TvEffect.None)
        }
        // Focus is what makes the preview a preview. Losing it is not a pause: the decoder is Home's
        // wallpaper's again, and the slot shrinks to a tile.
        TvEvent.WindowLostFocus -> when (state.window) {
            TvWindow.Preview -> TvTransition(
                state.copy(window = TvWindow.Collapsed, focused = false, playback = TvPlayback.Idle),
                TvEffect.Stop,
            )
            else -> TvTransition(state.copy(focused = false), TvEffect.None)
        }
        TvEvent.WindowClicked -> when (state.window) {
            TvWindow.Preview -> TvTransition(state.copy(window = TvWindow.Fullscreen), TvEffect.EnterFullscreen)
            TvWindow.Stopped, TvWindow.Collapsed ->
                TvTransition(state.copy(window = TvWindow.Preview, focused = true), TvEffect.Play)
            else -> keep(state)
        }
        /**
         * The screensaver timer reached Home. The caller starts the screensaver unless the window
         * answered by going fullscreen ([TvEffect.EnterFullscreen]).
         */
        is TvEvent.Idle -> when {
            state.window == TvWindow.Fullscreen -> keep(state)
            state.window == TvWindow.Preview && state.focused && event.hasChannel ->
                TvTransition(state.copy(window = TvWindow.Fullscreen), TvEffect.EnterFullscreen)
            state.window == TvWindow.Preview ->
                TvTransition(state.copy(window = TvWindow.Collapsed, focused = false, playback = TvPlayback.Idle), TvEffect.Stop)
            else -> keep(state)
        }
        // Back from fullscreen is the grid, not a preview: the icon waits for focus, so stop cleanly.
        TvEvent.FullscreenExited -> TvTransition(
            state.copy(window = TvWindow.Collapsed, focused = false, playback = TvPlayback.Idle),
            TvEffect.Stop,
        )
        /**
         * Home's shelf scrolled away, something else took the screen, or the user asked for the
         * screensaver while fullscreen: the slot goes up with the grid and is off the canvas by then.
         */
        TvEvent.AppLaunched, TvEvent.LeftHome, TvEvent.ManualScreensaver -> when (state.window) {
            TvWindow.Preview, TvWindow.Fullscreen -> TvTransition(
                state.copy(window = TvWindow.Collapsed, focused = false, playback = TvPlayback.Idle),
                TvEffect.Stop,
            )
            else -> keep(state)
        }
        // Out of the window there is nothing left to show. In fullscreen the layer stays and says so.
        TvEvent.PlaybackFailed -> when (state.window) {
            TvWindow.Fullscreen -> TvTransition(state.copy(playback = TvPlayback.Error), TvEffect.None)
            else -> TvTransition(state.copy(window = TvWindow.Stopped, playback = TvPlayback.Error), TvEffect.Stop)
        }
    }

    private fun keep(state: TvState) = TvTransition(state, TvEffect.None)
}
