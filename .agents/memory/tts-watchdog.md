---
name: TTS watchdog pattern
description: Timeout fallback for a frozen TTS engine when onDone never arrives after onStart
---

**Rule:** Arm the watchdog in `ttsManager.onStart` (not in `speak()` return). Cancel it in `ttsManager.onDone`. If the watchdog fires, call `ttsManager.forceComplete()` which resets `isSpeaking=false` and calls `onDone?.invoke()`. Never use arbitrary `delay` loops as the normal completion path.

**Why:** If the Android TTS engine freezes after `onStart` (rare but observed on certain OEM builds), the continuous session loop stalls permanently because `onDone` never arrives. Arbitrary delays fire even on normal completion, creating a double-restart race.

**How to apply:**
```kotlin
ttsManager.onStart = {
    ttsWatchdogJob?.cancel()
    ttsWatchdogJob = viewModelScope.launch {
        delay(TTS_WATCHDOG_MS)
        if (ttsManager.isSpeaking) ttsManager.forceComplete()
    }
}
ttsManager.onDone = {
    ttsWatchdogJob?.cancel(); ttsWatchdogJob = null; ...
}
```
`TTS_WATCHDOG_MS = 12_000L`. `forceComplete()` in TtsManager: `isSpeaking = false; onDone?.invoke()`. Cancel `ttsWatchdogJob` in `onCleared()`.
