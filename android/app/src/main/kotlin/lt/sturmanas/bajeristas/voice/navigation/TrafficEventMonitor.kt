package lt.sturmanas.bajeristas.voice.navigation

import android.util.Log
import lt.sturmanas.bajeristas.navigation.NavigationPhase
import lt.sturmanas.bajeristas.navigation.NavigationState

/**
 * Monitors [NavigationState] for significant ETA worsening and rerouting events,
 * speaking short deterministic Lithuanian comments via [speak].
 *
 * ## Supported (real SDK data only)
 *  - ETA meaningful increase: [NavigationState.remainingDurationSeconds]
 *  - Rerouting start: [NavigationState.isRerouting] false → true
 *
 * ## Deliberately NOT implemented (SDK exposes no data for these)
 *  - Traffic incident details, severity, or cause
 *  - ETA improvements — ETA naturally decreases while driving; commenting on it would
 *    produce constant false-positive "good news" announcements.
 *  - Alternative route candidates or time savings.
 *
 * ## Preventing false positives
 * Under normal driving conditions the remaining duration decreases at roughly 1 s/s.
 * This class tracks [minObservedEtaSec] — a running floor that follows the natural
 * progress decrease — and only fires when ETA rises significantly **above that floor**,
 * which indicates real traffic delay rather than ordinary countdown.
 *
 * ## Speech routing
 * [speak] is wired to [NavigationVoiceController.speakTrafficComment], which uses the
 * nav-TTS pipeline (QUEUE_ADD).  This means:
 *  - Ongoing maneuver guidance is never interrupted.
 *  - The existing AI-response pause/resume mechanism fires correctly.
 *  - Paused AI responses are never discarded by a traffic comment.
 *  - Comments are never sent to KentasChat and never enter conversation history.
 *
 * ## Threading
 * All public methods must be called on the **main thread**.
 */
class TrafficEventMonitor(
    /** Routes text to the nav-TTS pipeline. Must be called on the main thread. */
    private val speak: (String) -> Unit,
) {

    companion object {
        private const val TAG = "TrafficMonitor"

        /**
         * ETA increase above [minObservedEtaSec] (seconds) required to fire a comment.
         * Changes below this are below-threshold fluctuations or minor congestion.
         */
        const val ETA_SIGNIFICANT_CHANGE_SECONDS = 3 * 60        // 3 min

        /**
         * Additional ETA increase (seconds) above the last reported value that allows a
         * new comment to bypass the [COMMENT_COOLDOWN_MS] gate (critical update).
         */
        const val ETA_CRITICAL_WORSENING_SECONDS = 5 * 60        // 5 min

        /** Minimum quiet time between ordinary consecutive traffic comments. */
        const val COMMENT_COOLDOWN_MS = 5 * 60 * 1000L           // 5 min

        /**
         * ETA changes below this (seconds) are treated as measurement jitter
         * and logged at DEBUG rather than at INFO to reduce noise.
         */
        private const val JITTER_FILTER_SECONDS = 5
    }

    // ── Tracking state ────────────────────────────────────────────────────

    /**
     * Minimum remaining duration (seconds) observed since the last baseline was set.
     * Decreases with normal driving progress.  -1 until the first NAVIGATING update.
     */
    private var minObservedEtaSec: Int = -1

    /** Remaining duration (seconds) at the time of the last spoken comment; -1 = never. */
    private var lastReportedEtaSec: Int = -1

    /** Epoch time (ms) of the last spoken ordinary traffic comment; 0 = never. */
    private var lastCommentTimeMs: Long = 0L

    /**
     * True while [NavigationState.isRerouting] has been continuously true.
     * Guards against repeating the rerouting phrase for the same rerouting event.
     */
    private var wasRerouting: Boolean = false

    /**
     * Previous phase seen in [onStateUpdate].
     * Detects NAVIGATING → IDLE/ARRIVED transitions so state is automatically cleared
     * when a navigation session ends, preventing stale baselines from leaking into
     * the next trip.
     */
    private var previousPhase: NavigationPhase = NavigationPhase.IDLE

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Feed every [NavigationState] update here from [MainViewModel.onNavigationStateChanged].
     *
     * No-ops when [NavigationState.phase] is outside [NavigationPhase.NAVIGATING].
     * Automatically resets state on a NAVIGATING → IDLE/ARRIVED phase transition.
     */
    fun onStateUpdate(state: NavigationState) {
        val phase = state.phase

        // ── Auto-reset on session end ─────────────────────────────────────
        if (previousPhase == NavigationPhase.NAVIGATING &&
            (phase == NavigationPhase.IDLE || phase == NavigationPhase.ARRIVED)
        ) {
            resetState("phase_${phase.name.lowercase()}")
        }
        previousPhase = phase

        if (phase != NavigationPhase.NAVIGATING) return

        // ── Rerouting detection ───────────────────────────────────────────
        // Speak once per rerouting event.  Neutral wording only — the SDK does
        // not expose whether the reroute was caused by traffic, a road closure,
        // a wrong turn, or any other reason.
        if (state.isRerouting) {
            if (!wasRerouting) {
                wasRerouting = true
                Log.i(TAG, "REROUTING_STARTED")
                speak("Nieko tokio, randam kitą kelią.")
                Log.i(TAG, "REROUTING_COMMENT_SPOKEN")
            }
            return
        }
        if (wasRerouting) {
            wasRerouting = false
            // Reset ETA baseline to the post-reroute ETA so the comparison
            // is meaningful from the start of the new route, not the old one.
            val postRerouteEta = state.remainingDurationSeconds
            minObservedEtaSec = postRerouteEta
            lastReportedEtaSec = postRerouteEta
            Log.i(TAG, "TRAFFIC_ETA_BASELINE_SET reason=post_reroute etaSec=$postRerouteEta")
            return
        }

        val eta = state.remainingDurationSeconds
        if (eta <= 0) return

        // ── First NAVIGATING update: establish baseline ───────────────────
        if (minObservedEtaSec < 0) {
            minObservedEtaSec = eta
            lastReportedEtaSec = eta
            Log.i(TAG, "TRAFFIC_ETA_BASELINE_SET etaSec=$eta")
            return
        }

        // ── ETA went down or held: normal driving progress, update floor ──
        // No comment on ETA decreases — this is expected behavior, not good news.
        if (eta <= minObservedEtaSec) {
            minObservedEtaSec = eta
            return
        }

        // ── ETA went up: check significance ───────────────────────────────
        val worsening = eta - minObservedEtaSec   // seconds above natural-progress floor

        if (worsening < JITTER_FILTER_SECONDS) return   // silent sub-jitter

        if (worsening < ETA_SIGNIFICANT_CHANGE_SECONDS) {
            Log.d(
                TAG,
                "TRAFFIC_ETA_CHANGE_IGNORED reason=below_threshold " +
                "worseningSec=$worsening thresholdSec=$ETA_SIGNIFICANT_CHANGE_SECONDS",
            )
            return
        }

        Log.i(
            TAG,
            "TRAFFIC_ETA_CHANGE_DETECTED worseningSec=$worsening " +
            "etaSec=$eta floorSec=$minObservedEtaSec",
        )

        // ── Deduplication: skip if this worsening level was already reported ──
        // Check how much ETA has changed relative to the last comment's ETA.
        // This prevents re-announcing the same jam when the SDK emits multiple
        // updates that individually cross the threshold in the same event.
        if (lastReportedEtaSec > 0) {
            val sinceLastReport = eta - lastReportedEtaSec
            if (sinceLastReport < ETA_SIGNIFICANT_CHANGE_SECONDS) {
                Log.d(
                    TAG,
                    "TRAFFIC_EVENT_DUPLICATE sinceLastReportSec=$sinceLastReport " +
                    "lastReportedEtaSec=$lastReportedEtaSec",
                )
                return
            }
        }

        // ── Cooldown ──────────────────────────────────────────────────────
        // A critical additional worsening (≥ ETA_CRITICAL_WORSENING_SECONDS above the
        // last reported ETA) bypasses the cooldown so severe jams are not silenced.
        val now = System.currentTimeMillis()
        val sinceLastComment = now - lastCommentTimeMs
        val additionalWorsening = if (lastReportedEtaSec > 0) eta - lastReportedEtaSec else worsening
        val isCritical = additionalWorsening >= ETA_CRITICAL_WORSENING_SECONDS

        if (lastCommentTimeMs > 0 && sinceLastComment < COMMENT_COOLDOWN_MS && !isCritical) {
            Log.d(
                TAG,
                "TRAFFIC_COMMENT_COOLDOWN remainingMs=${COMMENT_COOLDOWN_MS - sinceLastComment} " +
                "isCritical=$isCritical",
            )
            return
        }

        // ── Speak ─────────────────────────────────────────────────────────
        // Phrase choice: long delays (≥ 10 min) get a more prominent opener.
        // "minutėmis" = instrumental plural (3–9 min).
        // "minučių"   = genitive plural (≥ 10 min).
        val deltaMin = ((worsening / 60.0) + 0.5).toInt().coerceAtLeast(1)
        val phrase = if (deltaMin >= 10) {
            "Priekyje situacija pablogėjo. Kelionė pailgėjo maždaug $deltaMin minučių."
        } else {
            "Kelionė pailgėjo maždaug $deltaMin minutėmis."
        }

        Log.i(
            TAG,
            "TRAFFIC_COMMENT_SPOKEN etaSec=$eta worseningSec=$worsening " +
            "deltaMin=$deltaMin phrase='$phrase'",
        )

        // Update baseline: use current traffic-delayed ETA as the new floor.
        lastReportedEtaSec = eta
        minObservedEtaSec = eta
        lastCommentTimeMs = now
        speak(phrase)
    }

    /**
     * Resets all tracking state.  Call when navigation is manually stopped or cancelled.
     * The phase-transition guard in [onStateUpdate] calls this automatically on
     * NAVIGATING → IDLE/ARRIVED, so explicit calls are only needed for manual stops
     * that do not go through the normal phase transition (e.g. force-stop in [MainViewModel]).
     */
    fun onNavigationStopped() = resetState("manual_stop")

    // ── Internal ──────────────────────────────────────────────────────────

    private fun resetState(reason: String) {
        // Idempotent: skip logging when there is nothing to clear.
        if (minObservedEtaSec < 0 && !wasRerouting && lastCommentTimeMs == 0L) return
        minObservedEtaSec = -1
        lastReportedEtaSec = -1
        lastCommentTimeMs = 0L
        wasRerouting = false
        Log.i(TAG, "TRAFFIC_STATE_CLEARED reason=$reason")
    }
}
