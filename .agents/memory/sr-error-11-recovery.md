---
name: ERROR 11 recovery
description: SpeechRecognizer error code 11 (ERROR_SERVER_DISCONNECTED) must be treated as RECOVERABLE, not fatal.
---

## Rule
`RecoveryPolicy.E_SERVER_DISCONNECTED = 11` is recoverable: `shouldRecreateRecognizer(11) = true`, `isFatal(11) = false`, `delayMs(11) = 1200ms`.

The recognizer must be destroyed and recreated (done inside `SpeechRecognitionManager.makeListener.onError` via `destroyCurrentRecognizer()`), then `scheduleOneRestart(1200ms)` is called.

**Why:** On a real Xiaomi device, ERROR 11 fires when the Google speech service connection drops (transient network or server hiccup). The old code routed ERROR 11 to the `else` branch in `SpeechRecognitionManager.onError`, which called `onFatalError` → `stopContinuousSession()` → session ended permanently. This was the confirmed real-device bug.

**How to apply:** `RecoveryPolicy` (pure Kotlin, no Android imports) is the single source of truth for error classification. Any new error handling must go through `RecoveryPolicy`; never add `when(errorCode)` branches directly in SpeechRecognitionManager or MainViewModel.
