# Šturmanas Bajeristas — Android Project

AI driving companion layered on top of Google Navigation.

**Core principle:** Google decides where to go. The AI only decides how to speak.

---

## Phase 1 Status (current)

- ✅ Start screen — destination input, personality selector, humor intensity
- ✅ Navigation screen — map placeholder, maneuver card, mic button, mute, fallback
- ✅ `SafetyController` — deterministic distance + maneuver rules, no AI logic
- ✅ `NavigationController` — mock navigation data (real SDK in Phase 2)
- ✅ `PersonaPrompts` — Kentas system prompt in Lithuanian
- ✅ `VoiceSessionController` — stub (Phase 3)
- ✅ `AudioController` — stub (Phase 3)
- ✅ Backend — `GET /api/healthz`, `POST /api/realtime-session` placeholder

---

## Opening in Android Studio

1. **Clone / download** this repository.
2. Open **Android Studio** → *File → Open* → select the `android/` folder.
3. Wait for Gradle sync to complete.
4. Run on an emulator (API 26+) or physical device.

> The `android/` directory is a self-contained Android Studio project.
> Do **not** open the repository root — open `android/` specifically.

---

## Required API Keys (not needed for Phase 1)

| Phase | Key | Where to add |
|-------|-----|--------------|
| 2 | `GOOGLE_MAPS_API_KEY` | `android/local.properties` as `GOOGLE_MAPS_API_KEY=<your_key>` |
| 3 | `OPENAI_API_KEY` | Replit secret (never in the APK) |

### Getting a Google Maps API key (Phase 2)

1. Go to [Google Cloud Console](https://console.cloud.google.com/).
2. Create or select a project. Enable billing.
3. Enable **Navigation SDK for Android** (APIs & Services → Library).
4. Create an API key (APIs & Services → Credentials).
5. Restrict the key to your app's package name: `lt.sturmanas.bajeristas`.
6. Add to `android/local.properties`:
   ```
   GOOGLE_MAPS_API_KEY=your_key_here
   ```
7. In `AndroidManifest.xml`, uncomment the `<meta-data>` block.
8. In `app/build.gradle.kts`, uncomment the Navigation SDK dependency.

---

## Architecture

```
android/app/src/main/kotlin/lt/sturmanas/bajeristas/
├── MainActivity.kt              Root activity + app-level state
├── navigation/
│   ├── NavigationState.kt       Immutable state snapshot (ManeuverType + fields)
│   └── NavigationController.kt  Owns NavState; Phase 2 wraps Google Nav SDK
├── safety/
│   └── SafetyController.kt      Deterministic audio rules (no AI, no network)
├── personality/
│   └── PersonaPrompts.kt        System prompts + nav context builder
├── ui/
│   ├── StartScreen.kt           Destination input, personality, humor slider
│   ├── NavigationScreen.kt      Map, maneuver card, mic button, fallback
│   └── theme/SturmanasTheme.kt  Material3 color scheme
└── voice/
    ├── VoiceSessionController.kt  Phase 3: ephemeral token + WebSocket
    └── AudioController.kt         Phase 3: playback priority logic
```

Backend (runs in Replit):
```
artifacts/api-server/src/routes/
├── health.ts          GET /api/healthz
└── realtimeSession.ts POST /api/realtime-session  (Phase 3 placeholder)
```

---

## SafetyController Rules

| Condition | Permission |
|-----------|-----------|
| Not navigating | ALLOWED |
| Complex maneuver (any distance) | BLOCKED |
| Distance > 500 m | ALLOWED |
| 200 m < distance ≤ 500 m | SHORT_ONLY |
| Distance ≤ 200 m | BLOCKED |

Complex maneuvers: `ROUNDABOUT`, `MOTORWAY_EXIT`, `LANE_CHANGE`, `COMPLEX_JUNCTION`, `UTURN`

---

## Running the Tests

From Android Studio: right-click `SafetyControllerTest` → *Run*.

From the command line (requires Android SDK installed):
```bash
cd android
./gradlew :app:testDebugUnitTest
```

Test report: `app/build/reports/tests/testDebugUnitTest/index.html`

---

## Development Phases

| Phase | Focus | Status |
|-------|-------|--------|
| 1 | Skeleton — screens, SafetyController, mock nav | ✅ Complete |
| 2 | Google Navigation SDK integration | ⬜ Next |
| 3 | OpenAI Realtime API voice (Kentas) | ⬜ Planned |
| 4 | Safety wiring + audio interruption tests | ⬜ Planned |

---

## Future Ideas

> Not implemented in V1. Listed here only.

- Android Auto support
- Continuous wake-word listening (hands-free)
- Offline TTS fallback (on-device model)
- Trip-aware personality adaptation (long motorway vs. city)
- Multi-language support (add languages without changing architecture)
