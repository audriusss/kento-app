package lt.sturmanas.bajeristas.voice

/**
 * Pure-Kotlin decision table for SpeechRecognizer error recovery.
 *
 * No Android imports — deliberately keeps JVM unit tests dependency-free.
 * All numeric values match the stable Android platform constants.
 */
object RecoveryPolicy {

    // ── SpeechRecognizer error constants (stable since API 23 / 28) ────────

    const val E_NETWORK_TIMEOUT         = 1   // ERROR_NETWORK_TIMEOUT
    const val E_NETWORK                 = 2   // ERROR_NETWORK
    const val E_AUDIO                   = 3   // ERROR_AUDIO
    const val E_SERVER                  = 4   // ERROR_SERVER
    const val E_CLIENT                  = 5   // ERROR_CLIENT
    const val E_SPEECH_TIMEOUT          = 6   // ERROR_SPEECH_TIMEOUT
    const val E_NO_MATCH                = 7   // ERROR_NO_MATCH
    const val E_RECOGNIZER_BUSY         = 8   // ERROR_RECOGNIZER_BUSY
    const val E_INSUFFICIENT_PERMISSIONS = 9  // ERROR_INSUFFICIENT_PERMISSIONS
    const val E_TOO_MANY_REQUESTS       = 10  // ERROR_TOO_MANY_REQUESTS   (API 28+)
    const val E_SERVER_DISCONNECTED     = 11  // ERROR_SERVER_DISCONNECTED  (API 28+) ← real-device bug
    const val E_LANGUAGE_NOT_SUPPORTED  = 12  // ERROR_LANGUAGE_NOT_SUPPORTED (API 31+)
    const val E_LANGUAGE_UNAVAILABLE    = 13  // ERROR_LANGUAGE_UNAVAILABLE   (API 31+)

    // ── Decision functions ─────────────────────────────────────────────────

    /**
     * True when the error is a hard, permanent failure that must stop the session.
     * These errors are never retried.
     */
    fun isFatal(errorCode: Int): Boolean = errorCode in setOf(
        E_INSUFFICIENT_PERMISSIONS,
        E_LANGUAGE_NOT_SUPPORTED,
        E_LANGUAGE_UNAVAILABLE,
    )

    /**
     * True when the error is recoverable via destroy + recreate of the recognizer
     * instance rather than a plain retry.
     *
     * These error codes indicate the existing recognizer object is in a bad state
     * and must not be reused.
     */
    fun shouldRecreateRecognizer(errorCode: Int): Boolean = errorCode in setOf(
        E_RECOGNIZER_BUSY,
        E_SERVER_DISCONNECTED,   // ← ERROR 11, the confirmed real-device failure
        E_CLIENT,
    )

    /**
     * Delay in milliseconds before the next recovery attempt.
     * Longer delays reduce hammering the speech service; shorter delays keep UX responsive.
     */
    fun delayMs(errorCode: Int): Long = when (errorCode) {
        E_NO_MATCH            ->  500L
        E_SPEECH_TIMEOUT      ->  500L
        E_RECOGNIZER_BUSY     ->  800L
        E_CLIENT              -> 1000L
        E_SERVER_DISCONNECTED -> 1200L   // recreate + wait; Google servers may be briefly unavailable
        E_TOO_MANY_REQUESTS   -> 5000L   // back off significantly
        E_NETWORK             -> 1500L
        E_NETWORK_TIMEOUT     -> 2000L
        E_AUDIO               ->  800L
        E_SERVER              -> 1200L
        else                  -> 1000L
    }

    /**
     * True for codes where the recognizer error is expected during normal use
     * (user did not speak, background noise). These should NOT trigger TTS error messages.
     */
    fun isSilentRecovery(errorCode: Int): Boolean = errorCode in setOf(
        E_NO_MATCH,
        E_SPEECH_TIMEOUT,
    )

    /** Lithuanian user-facing status text for each error code. */
    fun statusText(errorCode: Int): String = when (errorCode) {
        E_NO_MATCH            -> "Nieko neišgirdau. Bandau dar kartą…"
        E_SPEECH_TIMEOUT      -> "Nieko neišgirdau. Bandau dar kartą…"
        E_RECOGNIZER_BUSY     -> "Atkuriamas balso atpažinimas…"
        E_CLIENT              -> "Atkuriamas balso atpažinimas…"
        E_SERVER_DISCONNECTED -> "Atkuriamas balso atpažinimas…"
        E_TOO_MANY_REQUESTS   -> "Per daug užklausų. Laukiu…"
        E_NETWORK             -> "Tinklo klaida. Bandau dar kartą…"
        E_NETWORK_TIMEOUT     -> "Tinklas neatsako. Bandau dar kartą…"
        E_AUDIO               -> "Mikrofono klaida. Bandau dar kartą…"
        E_SERVER              -> "Serverio klaida. Bandau dar kartą…"
        else                  -> "Atkuriamas balso atpažinimas…"
    }

    /**
     * Symbolic name for [errorCode] — used in [KentasSpeechLifecycle] logs.
     * Numeric value is always included separately so unknown codes are traceable.
     */
    fun errorName(errorCode: Int): String = when (errorCode) {
        E_NETWORK_TIMEOUT          -> "ERROR_NETWORK_TIMEOUT"
        E_NETWORK                  -> "ERROR_NETWORK"
        E_AUDIO                    -> "ERROR_AUDIO"
        E_SERVER                   -> "ERROR_SERVER"
        E_CLIENT                   -> "ERROR_CLIENT"
        E_SPEECH_TIMEOUT           -> "ERROR_SPEECH_TIMEOUT"
        E_NO_MATCH                 -> "ERROR_NO_MATCH"
        E_RECOGNIZER_BUSY          -> "ERROR_RECOGNIZER_BUSY"
        E_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        E_TOO_MANY_REQUESTS        -> "ERROR_TOO_MANY_REQUESTS"
        E_SERVER_DISCONNECTED      -> "ERROR_SERVER_DISCONNECTED"   // code 11
        E_LANGUAGE_NOT_SUPPORTED   -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        E_LANGUAGE_UNAVAILABLE     -> "ERROR_LANGUAGE_UNAVAILABLE"
        else                       -> "UNKNOWN_ERROR"
    }

    /** Lithuanian TTS message for fatal errors where speaking the error is appropriate. */
    fun fatalTtsMessage(errorCode: Int): String = when (errorCode) {
        E_INSUFFICIENT_PERMISSIONS -> "Reikia mikrofono leidimo."
        else                       -> "Balso atpažinimas neprieinamas."
    }
}
