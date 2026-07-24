---
name: Nav session active guard
description: GoogleNavigationEngine sessionActive flag guards stale RouteChanged/ArrivalListener callbacks after stopNavigation
---

**Rule:** `private var sessionActive = false` in GoogleNavigationEngine. Set to `true` only after successful address resolution (just before `setDestination`). Set to `false` in `stopNavigation()` BEFORE calling `stopGuidance()`, and in `ArrivalListener` after processing. Both `RouteChangedListener` and `ArrivalListener` guard: `if (!sessionActive) return@listener`.

**Why:** On some OEM builds (confirmed pattern target: Xiaomi/MIUI), the SDK fires one final `RouteChangedListener` callback after `stopGuidance()`. Without the flag, the listener sees `guidanceStarted=false` (already reset by `stopNavigation`), calls `startGuidance()` on a dead session, and sets `phase=NAVIGATING` — corrupting phase state for the second trip. The bug does not reproduce reliably in unit tests.

**How to apply:** Any listener registered once at Navigator setup time (not per-trip) must use `sessionActive` to scope callbacks to the current active trip. `guidanceStarted` alone is insufficient because it is reset before the stale callback fires.
