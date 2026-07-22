---
name: NavigationEngine lifecycle split
description: onViewDestroy() vs onDestroy() — why they must be separate and where each is called.
---

## The rule

`onViewDestroy()` — tears down `NavigationView` only. Called from `NavigationScreen.DisposableEffect.onDispose`.  
`onDestroy()` — full teardown (NavigationView + Navigator.cleanup() + null both). Called ONLY from `MainActivity.onDestroy` via `NavigationController.onDestroy`.

Never call `onDestroy()` from a composable or DisposableEffect.

## Why

The original `onDestroy()` was called from `DisposableEffect.onDispose` and nulled the `Navigator`. When an address search failed, the flow was:

```
onError → isNavigating=false → NavigationScreen unmounts →
DisposableEffect.onDispose → onDestroy() → navigator=null
```

Next attempt: `startNavigation()` → `navigator` is null → `onError` fires synchronously → user sees "Navigacija neparuošta" forever until app restart.

## How to apply

- `NavigationEngine` interface: both methods declared.
- `GoogleNavigationEngine.onViewDestroy()`: destroys `NavigationView`, sets `isViewDestroyed=true`. Does NOT touch `navigator`.
- `GoogleNavigationEngine.onDestroy()`: calls `onViewDestroy()` then `navigator?.cleanup()`, `navigator = null`.
- `NavigationController.onViewDestroy()`: delegates to `engine.onViewDestroy()`.
- `NavigationController.onDestroy()`: delegates to `engine.onDestroy()`.
- `NavigationScreen.DisposableEffect.onDispose`: calls `engine.onViewDestroy()`.
- `MainActivity.onDestroy()`: calls `navigationController.onDestroy()`.
- `MockNavigationEngine.onViewDestroy()`: cancels simulation job (no real NavigationView).
- `MockNavigationEngine.onDestroy()`: calls `onViewDestroy()`.
