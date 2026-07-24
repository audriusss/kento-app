---
name: Destination resolution job tracking
description: resolveAndNavigate must be tracked as a Job? and cancelled before re-launching
---

**Rule:** `private var resolveJob: Job?` in MainViewModel. In `executeVoiceCommand` `StartNavigation` and `AddWaypoint` branches: `resolveJob?.cancel(); resolveJob = viewModelScope.launch { resolveAndNavigate/resolveAndAddWaypoint(...) }`. Cancel in `onNavigationStopped()` and `onCleared()`. Reset `_isSolvingDestination.value = false` in `onNavigationStopped()`.

**Why:** Without a job reference, two rapid voice commands launch two concurrent coroutines. Both can emit `_pendingNavAction = StartNavigation` and call `ttsManager.speak()` for the stale destination. The engine's `currentRequestId` handles stale geocoder results but cannot prevent double-emit at the ViewModel layer.

**How to apply:** Every suspend function that terminates by emitting a `pendingNavAction` or calling `speak()` must be tracked as a `Job?` if it can be re-invoked before completion.
