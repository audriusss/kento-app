# Šturmanas Bajeristas — Android App

AI driving companion ("Kentas") layered on top of Google Navigation. **Google decides where to go; the AI only decides how to speak.**

---

## Architecture

```
Google Navigation SDK
        ↓
NavigationEngine (interface)
        ↓  GoogleNavigationEngine (real)  /  MockNavigationEngine (dev)
NavigationController  ←  single source of truth for navigation state
        ↓
NavigationState  (immutable snapshot; no AI logic inside)
        ↓
SafetyController         PersonaPrompts / SessionConfig
        ↓                       ↓
               UI (Compose)
```

**Invariant:** `SafetyController` always runs before any AI audio. `BLOCKED` silences Kentas immediately; Google Navigation voice always has priority.

---

## Phase Status

| Phase | What | Status |
|-------|------|--------|
| 1     | Skeleton, mock navigation, SafetyController, backend stub | ✅ Done |
| 1.1   | ConversationMode, TripMode, HumorIntensity, HumorFormat, SessionConfig, Start screen UI | ✅ Done |
| 2     | Google Navigation SDK integration, real maneuver mapping, engine interface | ✅ Done |
| 3     | VoiceSessionController (OpenAI Realtime), AudioController, ephemeral token | ⏳ Pending |
| 4     | SafetyController interrupt wired to real AI audio, live safety tests | ⏳ Pending |

---

## Phase 2 Setup

### 1. Prerequisites

You need a **Google Cloud project** with **billing enabled** and two APIs active:

| API | Console name |
|-----|-------------|
| Maps SDK for Android | `Maps SDK for Android` |
| Navigation SDK for Android | `Navigation SDK for Android` |

> **Billing note:** The Navigation SDK for Android requires a billable Cloud project. Standard Maps SDK pricing applies to Navigation SDK usage. See [Google Maps Platform pricing](https://mapsplatform.google.com/pricing/).

### 2. Create an API key

1. Go to **APIs & Services → Credentials → Create Credentials → API key**.
2. Click **Restrict key → Android apps**.
3. Add a restriction:
   - **Package name:** `lt.sturmanas.bajeristas`
   - **SHA-1 certificate fingerprint:** run `./gradlew signingReport` from the `android/` directory.
4. Under **API restrictions**, select only **Maps SDK for Android** and **Navigation SDK for Android**.
5. Copy the key.

### 3. Add the key to local.properties

```text
android/local.properties          ← already in .gitignore; create if missing
```

```properties
sdk.dir=/path/to/your/Android/sdk
GOOGLE_MAPS_API_KEY=AIza...your-key-here
```

A template is at `android/local.properties.template`.

**Never commit `local.properties` to source control.** The key is read by Gradle and injected into the manifest via `manifestPlaceholders`. It is never hardcoded in Kotlin files.

### 4. Without an API key (Mock mode)

If `local.properties` has no `GOOGLE_MAPS_API_KEY` (or the value is empty), the app compiles and runs normally using **MockNavigationEngine**. The map area shows a green placeholder with the text `[MOCK]`. Navigation state ticks down automatically so you can test all UI states without a key.

No code change is required to switch between real and mock — just add or remove the key and rebuild.

---

## Open and run in Android Studio

1. Open **`android/`** (not the repo root) as a project in Android Studio Hedgehog or newer.
2. Let Gradle sync. It downloads Navigation SDK 7.8.0 from Google Maven.
3. Connect a physical Android device (API 26+) or start an emulator.
4. Run the **`app`** configuration.

> **Physical device recommended.** The Navigation SDK's turn-by-turn UI and GPS integration work best on hardware. Emulators can simulate GPS but lack real maps performance.

---

## Run tests

From the `android/` directory:

```bash
# All unit tests (JVM, no device required)
./gradlew :app:testDebugUnitTest

# With a connected device or emulator
./gradlew :app:connectedDebugAndroidTest
```

Test results: `android/app/build/reports/tests/testDebugUnitTest/index.html`

### Test categories

| Test class | Executable without device | Notes |
|------------|--------------------------|-------|
| `SafetyControllerTest` (28 tests) | ✅ JVM | Pure Kotlin; no Android deps |
| `PersonaPromptsTest` (18 tests) | ✅ JVM | Pure Kotlin; no Android deps |
| `NavigationStateTest` (18 tests) | ✅ JVM | Pure Kotlin; MockNavigationEngine state (no View created) |
| `ManeuverMapperTest` (25 tests) | ✅ JVM (requires SDK jar) | Imports `com.google.android.libraries.navigation.Maneuver`; runs on JVM because `Maneuver` is a plain enum with no Android-runtime dependency |

> If `ManeuverMapperTest` fails with `ClassNotFoundException`, the Navigation SDK jar is not resolvable. Run via Android Studio's instrumented test runner instead.

---

## Known Phase 2 limitations

| Limitation | Phase |
|-----------|-------|
| Microphone button is a placeholder — no audio captured | 3 |
| Kentas never actually speaks (VoiceSessionController is a stub) | 3 |
| AI interruption by SafetyController is not wired to real audio yet | 4 |
| No autocomplete on the destination field — accepts address strings and `lat,lng` | Future |
| No search history, favourites, or recent destinations | Future |
| HumorFormat is modelled but not sent to AI | 3 |
| Background navigation (app minimised) not tested | Future |

---

## Package layout

```
lt.sturmanas.bajeristas/
├── navigation/
│   ├── ManeuverMapper.kt          Google SDK Maneuver → internal ManeuverType
│   ├── ManeuverType.kt            Internal enum (14 values)
│   ├── NavigationController.kt    Single access point for the rest of the app
│   ├── NavigationEngine.kt        Interface — real vs mock boundary
│   ├── NavigationState.kt         Immutable snapshot; no AI logic
│   ├── GoogleNavigationEngine.kt  Real SDK; all SDK objects private here
│   ├── MockNavigationEngine.kt    Dev fallback; simulates state with coroutine timer
│   └── LocationPermissionHelper.kt
├── safety/
│   ├── SafetyController.kt        Deterministic; no AI; no network
│   └── ConversationPermission.kt  ALLOWED / SHORT_ONLY / BLOCKED
├── personality/
│   ├── ConversationMode.kt        SOFT / HARD
│   ├── TripMode.kt                SOLO / DUO / GROUP
│   ├── HumorIntensity.kt          LIGHT / NORMAL / STRONG
│   ├── HumorFormat.kt             SITUATIONAL / SHORT_JOKE / …
│   ├── SessionConfig.kt           Immutable config from Start screen
│   └── PersonaPrompts.kt          All AI prompt text lives here only
├── voice/
│   ├── VoiceSessionController.kt  Phase 3 stub — OpenAI Realtime WebSocket
│   └── AudioController.kt         Phase 3 stub — AudioTrack playback
└── ui/
    ├── StartScreen.kt             Destination + personality pickers
    ├── NavigationScreen.kt        SDK map (AndroidView) + controls
    └── theme/SturmanasTheme.kt
```

---

## API key security summary

| Location | API key present? |
|----------|-----------------|
| `local.properties` | ✅ (your machine only; gitignored) |
| `AndroidManifest.xml` | Via `${GOOGLE_MAPS_API_KEY}` placeholder — value injected by Gradle, not stored in source |
| `BuildConfig.GOOGLE_MAPS_API_KEY` | Injected at build time; not in source |
| Kotlin source files | ❌ Never |
| Git history | ❌ Never (file gitignored) |

---

## Future ideas

- Places Autocomplete on the destination field (Phase 3+ dependency)
- Waypoints / multi-stop routing
- Speed camera and hazard alerts via Navigation SDK
- Wake word detection ("Ei, Kentai") to trigger voice without button press
- Car mode (Android Auto integration)
