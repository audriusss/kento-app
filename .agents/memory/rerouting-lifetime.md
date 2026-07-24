---
name: isRerouting flag lifetime rule
description: isRerouting must only be cleared by the next distance callback, never by syncStateFromNavigator
---

**Rule:** `syncStateFromNavigator` must NOT contain `isRerouting = false`. The re-route branch in `RouteChangedListener` sets `isRerouting=true` and returns early (no `syncState` call). The next `RemainingTimeOrDistanceChangedListener` callback clears it: `if (_state.value.isRerouting) { _state.value = _state.value.copy(isRerouting = false) }`.

**Why:** The original code set `isRerouting=true` then immediately called `syncStateFromNavigator` in the same call stack, which set it back to `false`. The rerouting overlay appeared for zero frames — invisible to the user. The distance callback approach keeps the overlay visible for the natural duration it takes for new route data to arrive.

**How to apply:** Check any future `syncStateFromNavigator` call sites — `isRerouting` must never appear in their `copy()` arguments. The distance listener is the ONLY place that clears it.
