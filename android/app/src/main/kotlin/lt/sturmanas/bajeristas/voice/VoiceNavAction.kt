package lt.sturmanas.bajeristas.voice

/**
 * Navigation-level actions emitted by [MainViewModel] after a voice command is parsed.
 *
 * These require composable-level state changes (isNavigating, isMuted, starting a new
 * navigation session) that live in [SturmanasApp]. MainViewModel emits the action via
 * [pendingNavAction]; the composable observes it with LaunchedEffect, executes the
 * correct existing lambda, then calls [MainViewModel.clearPendingNavAction].
 *
 * All non-navigation responses (TTS distance/time/destination announcements) are handled
 * entirely inside MainViewModel and do NOT produce a VoiceNavAction.
 */
sealed class VoiceNavAction {
    /** User said "sustabdyk navigaciją" — stop guidance, return to StartScreen. */
    object StopNavigation : VoiceNavAction()

    /** User said "važiuojam į …" — start navigation to [destination]. */
    data class StartNavigation(val destination: String) : VoiceNavAction()

    /** User said "nutildyk balsą" — enable isMuted. */
    object Mute : VoiceNavAction()

    /** User said "įjunk balsą" — disable isMuted. */
    object Unmute : VoiceNavAction()
}
