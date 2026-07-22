---
name: Address resolution strategy
description: Multi-attempt geocoding + Google API HTTP fallback + startGuidance() call location.
---

## startGuidance() location

Must be called in `RouteChangedListener`, NOT in `startNavigation()` or right after `setDestination()`.

`setDestination()` returns `ListenableResultFuture<RouteStatus>` — addOnSuccessListener/addOnFailureListener do NOT exist on this type in Navigation SDK 7.8.0. `RouteChangedListener` fires when the route is ready (both initial and reroutes). Use `guidanceStarted: Boolean` flag to call `startGuidance()` only on the first firing.

## Address resolution — 5-attempt strategy in order

1. Raw "lat,lng" — fast path, no network.
2. Android Geocoder — raw input.
3. Android Geocoder — input + ", Lietuva" (country bias).
4. Android Geocoder — Lithuanian abbreviations expanded (pr.→prospektas, g.→gatvė, al.→alėja, etc.).
5. Android Geocoder — expanded + ", Lietuva".
6. Google Geocoding API HTTP — `https://maps.googleapis.com/maps/api/geocode/json?address=...&key=GOOGLE_MAPS_API_KEY&language=lt&region=lt`

## Why the HTTP fallback is required

Xiaomi/MIUI ships without full Google GMS Geocoder support. `Geocoder.getFromLocationName()` returns empty results even for valid Lithuanian addresses with internet connectivity. The Google Geocoding API HTTP call bypasses the device Geocoder entirely and is consistently accurate.

## API 33+ Geocoder

Use callback API on API ≥ 33 (Tiramisu) via `CompletableDeferred<List<Address>>`. Use deprecated synchronous API on older versions (both run on Dispatchers.IO so blocking is fine).

## Stale request guard

`currentRequestId: Int` incremented on every `startNavigation` call. Geocoder callback checks `requestId != currentRequestId` and discards if stale (user entered a second destination before first geocoding completed).
