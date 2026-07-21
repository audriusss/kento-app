package lt.sturmanas.bajeristas.personality

/**
 * Describes who is in the car and adjusts Kentas's social behaviour accordingly.
 *
 * [SOLO]  — Kentas behaves as a personal travel companion. May initiate occasional
 *           conversation and ask natural follow-up questions.
 *
 * [DUO]   — Kentas is a third friend in the car. Does not assume the two occupants
 *           are a couple. May react to both driver and passenger. Speaks less often
 *           than in SOLO mode.
 *
 * [GROUP] — Kentas is an occasional entertaining passenger. Does not react to every
 *           overheard sentence. Responds only when directly addressed via push-to-talk,
 *           or when an entertainment activity is active. May tell jokes, short stories,
 *           ask group questions, or start simple verbal games.
 */
enum class TripMode {
    SOLO,
    DUO,
    GROUP,
}
