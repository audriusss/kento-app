---
name: Continuous mode IDLE gate
description: Why _voiceListeningState must never be written to IDLE while continuousModeEnabled=true, and the supporting fields that enforce this.
---

## The rule

Every write of `_voiceListeningState.value = VoiceListeningState.IDLE` in MainViewModel.kt
must be gated with `if (!_continuousModeEnabled.value)`.

In continuous mode the mic button must stay visually active (red+pulsing = LISTENING)
through all restart cycles. Writing IDLE between cycles causes a visible flicker because
MicButton renders IDLE+sessionActive as a green ring (toggling off then on every ~300 ms).

**Why:** There were 14+ unconditional IDLE writes scattered across command handlers,
SR callbacks, and destination resolution branches. Each write caused a green-ring flash
every recognizer cycle, visible on-device on Xiaomi (fast enough to be jarring).

**How to apply:** Any new code path that writes `_voiceListeningState.value = VoiceListeningState.IDLE`
must include `if (!_continuousModeEnabled.value)` before the write. The only intentional
unconditional IDLE write is inside `stopContinuousSessionInternal()` itself (where
`_continuousModeEnabled.value` is being set to false in the same call).

## Supporting fields

- `_continuousModeEnabled: MutableStateFlow<Boolean>` — the stable "session is on" flag.
  Stays true through restarts, TTS, timeouts, and recovery. Only goes false via
  `stopContinuousSessionInternal()` (user toggle, fatal error, MAX_SESSION_RETRIES).
  Renamed from `_continuousSessionActive` — update any reference you find to the old name.

- `recoveringTextJob: Job?` — 400 ms debounce before showing "Atkuriamas…" text.
  `scheduleOneRestart()` cancels it immediately on fast recovery so brief glitches
  are invisible. Must also be cancelled in `startContinuousSession()`, `stopContinuousSessionInternal()`,
  and `onCleared()`.

- `STABILITY_TAG = "KentasVoiceStability"` — companion log tag used on all writes
  to `_voiceListeningState` and `_continuousModeEnabled` for logcat filtering.

## What startContinuousSession() now does on enable

Sets `_voiceListeningState.value = VoiceListeningState.LISTENING` *immediately* (before
`startListening()` is called) so the mic button goes red+active before the recognizer
finishes initialising. Prevents an IDLE flash at the very start of each session.

## cancelClarification() fix

`cancelClarification()` now calls `notifyCommandDone()` after clearing state so the
continuous loop resumes after the user dismisses the disambiguation dialog. Previously
the loop was stuck waiting after a cancel.
