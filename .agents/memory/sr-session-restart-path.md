---
name: SR session loop single restart path
description: The single pendingRestartJob pattern that prevents overlapping startListening() calls in the continuous session loop.
---

## Rule
`scheduleOneRestart(delayMs: Long)` in MainViewModel is the ONLY function that may call `speechRecognitionManager.startListening()`. It always cancels `pendingRestartJob` before launching a new one.

`notifyCommandDone()` replaces all former `scheduleRestartIfSessionActive()` call sites at command exit points.

Three callers feed into `scheduleOneRestart`:
1. `ttsManager.onDone` → `scheduleOneRestart(500ms)` (TTS-path)
2. `notifyCommandDone()` — when `!ttsManager.isSpeaking` → `scheduleOneRestart(300ms)` (silent-path)
3. `onRecoverableError` → `scheduleOneRestart(RecoveryPolicy.delayMs(code))` (error-path)

**Why:** The previous design had three independent paths (onDone, scheduleRestartIfSessionActive, onRecoverableError direct launch) that could all fire for the same recognition cycle, producing overlapping SR sessions and the "stuck listening" freeze.

**How to apply:** Any new command handler must call `notifyCommandDone()` at every exit point (including coroutine branches). Never call `startListening()` directly from command handlers or error callbacks.
