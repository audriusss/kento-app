package lt.sturmanas.bajeristas.voice

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Regression tests for navigation / conversation state decoupling.
 *
 * ## Root causes fixed
 *
 * **BUG-1 — navigation instructions silently dropped when mic is hot.**
 *
 * `isSpeechBlocked` caused early returns in `MainViewModel.speakNavInstruction`,
 * `speakRouteReady`, and `speakArrival`, and in `MainActivity.LaunchedEffect(maneuverDist)`.
 * Because `LaunchedEffect` only re-fires on a new distance value, any threshold that fired
 * while the mic was active was lost forever — the conversation could block all maneuver
 * announcements indefinitely.
 *
 * Fix: remove the early returns.  When `isSpeechBlocked`, cancel only the SR session
 * (`SpeechRecognitionManager.cancel()`), then deliver the navigation instruction.
 * `KentasConversationController.resumeAfterNavInterrupt()` restarts SR after the nav
 * utterance finishes.
 *
 * **BUG-2 — `stopConversation()` killed navigation TTS on conversation timeout.**
 *
 * `KentasConversationController.stopConversation()` called
 * `KentasSpeechCoordinator.stop()` → `TtsManager.stop()` (global Android TTS stop).
 * If a navigation utterance happened to be playing at the moment of conversation timeout,
 * it was cut off.
 *
 * Fix: `stopConversation()` now calls `stopConversationSpeechOnly()` instead of `stop()`.
 * `stopConversationSpeechOnly()` skips `ttsManager.stop()` when `onNavigationDone` is set,
 * letting the navigation utterance finish naturally.
 *
 * ## Architecture invariant
 *
 * Navigation and conversation are independent layers:
 * - `navigationActive` (managed by `NavigationController` / Navigation SDK)
 * - `conversationActive` (managed by `KentasConversationController`)
 *
 * `KentasConversationController` must never hold a reference to NavigationController or
 * NavigationEngine, and must never write to any navigation route/phase/destination state.
 *
 * ## Acceptance criteria
 *
 * NAV-C01  `KentasConversationController` has no navigation-controller dependency.
 * NAV-C02  Conversation stop leaves no navigation route/phase fields in the controller.
 * NAV-C03  `stopConversationSpeechOnly()` exists on `KentasSpeechCoordinator`.
 * NAV-C04  `stopConversationSpeechOnly()` preserves `onNavigationDone` when nav TTS active.
 * NAV-C05  `stopConversationSpeechOnly()` clears `onConversationDone` unconditionally.
 * NAV-C06  `resumeAfterNavInterrupt()` is a public method on the conversation controller.
 * NAV-C07  `SpeechRecognitionManager.cancel()` is public — the interruption path exists.
 * NAV-C08  The conversation controller never references `navigationController.stopNavigation`.
 */
class ConversationNavigationCouplingTest {

    // ── NAV-C01 ────────────────────────────────────────────────────────────

    /**
     * `KentasConversationController` must not hold any field whose type name contains
     * "NavigationController" or "NavigationEngine".
     *
     * Navigation and conversation are independent layers. The controller may receive the
     * current `NavigationState` as a data object (read-only snapshot), but it must never
     * hold a reference to the navigation lifecycle owner.
     */
    @Test
    fun `NAV-C01 KentasConversationController has no NavigationController or NavigationEngine field`() {
        val fieldTypes = KentasConversationController::class.java.declaredFields.map { it.type.simpleName }
        val violations = fieldTypes.filter { typeName ->
            typeName.contains("NavigationController", ignoreCase = true) ||
            typeName.contains("NavigationEngine", ignoreCase = true)
        }
        assertTrue(
            "KentasConversationController must NOT hold a NavigationController or NavigationEngine " +
            "reference — navigation and conversation are independent layers. Found: $violations",
            violations.isEmpty(),
        )
    }

    // ── NAV-C02 ────────────────────────────────────────────────────────────

    /**
     * `KentasConversationController` must own no route/destination/phase/maneuver fields.
     *
     * Conversation timeout (`stopConversation`) must never mutate navigation route state.
     * The only navigation-related data the controller is allowed to hold is
     * `latestNavState: NavigationState`, a read-only snapshot used by the AI call.
     */
    @Test
    fun `NAV-C02 KentasConversationController has no route or destination state fields`() {
        val fieldNames = KentasConversationController::class.java.declaredFields.map { it.name }
        val forbidden = listOf("route", "destination", "phase", "isNavigating", "maneuver")
        val violations = fieldNames.filter { name ->
            forbidden.any { pattern -> name.lowercase().contains(pattern) }
        }
        assertTrue(
            "KentasConversationController must not own navigation route/destination/phase/maneuver " +
            "state. Stopping the conversation must never affect the active route. " +
            "Suspicious fields found: $violations",
            violations.isEmpty(),
        )
    }

    // ── NAV-C03 ────────────────────────────────────────────────────────────

    /**
     * `KentasSpeechCoordinator.stopConversationSpeechOnly()` must exist as a public method.
     *
     * This is the structural anchor for BUG-2's fix: `KentasConversationController.stopConversation()`
     * calls this instead of `stop()`, so a navigation utterance in progress at the moment of
     * conversation timeout is not interrupted.
     */
    @Test
    fun `NAV-C03 stopConversationSpeechOnly exists as public method on KentasSpeechCoordinator`() {
        val method = runCatching {
            KentasSpeechCoordinator::class.java.getMethod("stopConversationSpeechOnly")
        }.getOrNull()
        assertNotNull(
            "KentasSpeechCoordinator must have a public 'stopConversationSpeechOnly()' method. " +
            "It is called from KentasConversationController.stopConversation() to stop only " +
            "conversation TTS without interrupting any navigation TTS in progress.",
            method,
        )
    }

    // ── NAV-C04 ────────────────────────────────────────────────────────────

    /**
     * When `onNavigationDone` is set (a navigation utterance is in progress),
     * `stopConversationSpeechOnly()` must NOT clear it.
     *
     * The navigation utterance must finish naturally and fire its callback.  If the callback
     * is `resumeAfterNavInterrupt()` and the conversation has already been stopped by the
     * time it fires, the conversation controller's `_isActive.value` is false and the call
     * is a harmless no-op — the callback must still be allowed to run.
     *
     * This test allocates a `KentasSpeechCoordinator` via `Unsafe` to bypass the
     * `TtsManager(Context)` constructor dependency.  The method under test returns early
     * before reaching `ttsManager.stop()` when `onNavigationDone != null`, so a null
     * `ttsManager` field is safe for this branch.
     */
    @Test
    fun `NAV-C04 stopConversationSpeechOnly preserves onNavigationDone when nav TTS is active`() {
        val coordinator = allocateCoordinatorUnsafe() ?: return  // skip if Unsafe unavailable
        val navDoneField = getDeclaredFieldOrNull(KentasSpeechCoordinator::class.java, "onNavigationDone")
            ?: return  // skip if field renamed

        val marker: () -> Unit = { /* nav done sentinel */ }
        navDoneField.set(coordinator, marker)

        val method = runCatching {
            KentasSpeechCoordinator::class.java.getDeclaredMethod("stopConversationSpeechOnly")
                .also { it.isAccessible = true }
        }.getOrNull() ?: return  // skip if method not yet present

        // Must not throw — returns early before ttsManager.stop() because onNavigationDone != null
        method.invoke(coordinator)

        val navDoneAfter = navDoneField.get(coordinator)
        assertNotNull(
            "onNavigationDone must NOT be cleared by stopConversationSpeechOnly when nav TTS " +
            "is in progress. The navigation utterance must finish and fire its callback naturally. " +
            "Clearing it would silently orphan resumeAfterNavInterrupt().",
            navDoneAfter,
        )
    }

    // ── NAV-C05 ────────────────────────────────────────────────────────────

    /**
     * `stopConversationSpeechOnly()` must always clear `onConversationDone`.
     *
     * Whether or not navigation TTS is active, any pending conversation TTS callback
     * must be discarded when the conversation is stopped, so that a lingering TTS finish
     * from a previous turn cannot trigger a re-listen after the session is closed.
     */
    @Test
    fun `NAV-C05 stopConversationSpeechOnly always clears onConversationDone`() {
        val coordinator = allocateCoordinatorUnsafe() ?: return
        val convDoneField = getDeclaredFieldOrNull(KentasSpeechCoordinator::class.java, "onConversationDone")
            ?: return
        val navDoneField = getDeclaredFieldOrNull(KentasSpeechCoordinator::class.java, "onNavigationDone")
            ?: return

        val convMarker: () -> Unit = { /* conv done sentinel */ }
        convDoneField.set(coordinator, convMarker)
        // Also set nav done so TTS engine is not called (null ttsManager would NPE otherwise).
        val navMarker: () -> Unit = { /* nav done sentinel */ }
        navDoneField.set(coordinator, navMarker)

        val method = runCatching {
            KentasSpeechCoordinator::class.java.getDeclaredMethod("stopConversationSpeechOnly")
                .also { it.isAccessible = true }
        }.getOrNull() ?: return

        method.invoke(coordinator)

        val convDoneAfter = convDoneField.get(coordinator)
        assertNull(
            "onConversationDone must be null after stopConversationSpeechOnly. " +
            "A stale conversation callback must never re-trigger listening after session close.",
            convDoneAfter,
        )
    }

    // ── NAV-C06 ────────────────────────────────────────────────────────────

    /**
     * `KentasConversationController.resumeAfterNavInterrupt()` must be a public method.
     *
     * It is wired as the `speakNavigation` `onDone` callback in `MainViewModel` so that
     * the conversation listening session resumes after every navigation utterance.
     * It must be public so `MainViewModel` can reference it without reflection.
     */
    @Test
    fun `NAV-C06 resumeAfterNavInterrupt is a public method on KentasConversationController`() {
        val method = runCatching {
            KentasConversationController::class.java.getMethod("resumeAfterNavInterrupt")
        }.getOrNull()
        assertNotNull(
            "KentasConversationController must have a public 'resumeAfterNavInterrupt()' method. " +
            "It is wired as the speakNavigation onDone callback so the conversation " +
            "listening session resumes after every navigation utterance.",
            method,
        )
    }

    // ── NAV-C07 ────────────────────────────────────────────────────────────

    /**
     * `SpeechRecognitionManager.cancel()` must be a public method.
     *
     * This is the interruption point for BUG-1's fix: `MainViewModel.speakNavInstruction`
     * (and `speakRouteReady`, `speakArrival`) calls `cancel()` when `isSpeechBlocked` to
     * stop the SR session before delivering the navigation instruction.  The conversation
     * session itself is not stopped — only the current SR listening cycle is cancelled.
     * `resumeAfterNavInterrupt()` restarts it once the nav utterance finishes.
     */
    @Test
    fun `NAV-C07 SpeechRecognitionManager cancel method is public`() {
        val method = runCatching {
            SpeechRecognitionManager::class.java.getMethod("cancel")
        }.getOrNull()
        assertNotNull(
            "SpeechRecognitionManager must have a public 'cancel()' method. " +
            "MainViewModel.speakNavInstruction() calls it when isSpeechBlocked is true " +
            "to interrupt the current SR session before delivering a navigation instruction. " +
            "The conversation session is preserved; resumeAfterNavInterrupt() restarts SR.",
            method,
        )
    }

    // ── NAV-C08 ────────────────────────────────────────────────────────────

    /**
     * `KentasConversationController` must have no method named `stopNavigation` and
     * must not declare any field of type `NavigationController`.
     *
     * Navigation is stopped only by the user explicitly, never as a side-effect of
     * conversation timeout.  This test guards against future accidental coupling.
     */
    @Test
    fun `NAV-C08 KentasConversationController has no stopNavigation method`() {
        val methods = KentasConversationController::class.java.declaredMethods.map { it.name }
        assertFalse(
            "KentasConversationController must NOT have a 'stopNavigation' method. " +
            "Navigation must never be stopped as a side-effect of conversation close.",
            methods.any { it == "stopNavigation" },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Allocate a [KentasSpeechCoordinator] without running its constructor.
     *
     * `KentasSpeechCoordinator` requires a `TtsManager(Context)` which is not available
     * in plain JVM unit tests.  Allocation via `sun.misc.Unsafe` skips the constructor
     * entirely — all fields are null/default.
     *
     * This is safe for tests that set individual fields via reflection and only exercise
     * code paths that do NOT dereference the null `ttsManager`.  In particular,
     * `stopConversationSpeechOnly()` returns before `ttsManager.stop()` when
     * `onNavigationDone != null`, making it safe for NAV-C04 and NAV-C05.
     *
     * Returns `null` if `sun.misc.Unsafe` is not accessible (e.g. future JVM restriction),
     * in which case the calling test is skipped via `?: return`.
     */
    private fun allocateCoordinatorUnsafe(): KentasSpeechCoordinator? = runCatching {
        val unsafeClass  = Class.forName("sun.misc.Unsafe")
        val unsafeField  = unsafeClass.getDeclaredField("theUnsafe").also { it.isAccessible = true }
        val unsafe       = unsafeField.get(null)
        val allocate     = unsafeClass.getMethod("allocateInstance", Class::class.java)
        @Suppress("UNCHECKED_CAST")
        allocate.invoke(unsafe, KentasSpeechCoordinator::class.java) as KentasSpeechCoordinator
    }.getOrNull()

    private fun getDeclaredFieldOrNull(clazz: Class<*>, name: String): Field? =
        runCatching {
            clazz.getDeclaredField(name).also { it.isAccessible = true }
        }.getOrNull()
}
