package lt.sturmanas.bajeristas.voice

import lt.sturmanas.bajeristas.navigation.CandidatePlace

/**
 * Holds the pending destination disambiguation state when the voice resolver
 * cannot determine a single unambiguous destination.
 *
 * [MainViewModel] emits this via [MainViewModel.pendingClarification].
 * [SturmanasApp] observes it and presents a dialog so the user can choose.
 * The choice is handled by [MainViewModel.onClarificationAnswer] (button tap)
 * or by a [lt.sturmanas.bajeristas.voice.VoiceCommand.SelectCandidate] voice command.
 *
 * @param originalText  The raw destination string that triggered disambiguation.
 * @param candidates    Up to 3 candidate places (name + address + optional distance).
 */
data class ClarificationState(
    val originalText: String,
    val candidates: List<CandidatePlace>,
)
