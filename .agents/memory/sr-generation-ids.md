---
name: SR generation IDs
description: Each startListening() call creates a new RecognitionListener capturing its generation; stale callbacks from old sessions are no-ops.
---

## Rule
`SpeechRecognitionManager.generation: Long` is incremented by `startListening()` and by `cancel()`. `makeListener(sessionGeneration)` creates a fresh `RecognitionListener` that captures `sessionGeneration` in a closure. Every callback begins with `if (isStale(eventName)) return`.

`isStale()` checks `sessionGeneration != this@SpeechRecognitionManager.generation` and logs to `KentasSpeechLifecycle` when a stale event is dropped.

**Why:** On Xiaomi/MIUI, after `cancel()+destroy()`, the old recognizer sometimes delivers one more callback. Without generation IDs, that stale `onResults` or `onError` would restart the session (or stop it) unexpectedly. The generation counter makes these harmless.

**How to apply:** The listener object is created fresh per session via `makeListener(myGeneration)` inside `startListening()`. Never reuse a single `object : RecognitionListener` across multiple sessions.
