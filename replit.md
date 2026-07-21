# Šturmanas Bajeristas

AI driving companion layered on top of Google Navigation. The AI only decides how to speak — Google decides where to go.

## Run & Operate

- `pnpm --filter @workspace/api-server run dev` — run the API server (port 5000)
- `pnpm run typecheck` — full typecheck across all packages
- `pnpm run build` — typecheck + build all packages
- `pnpm --filter @workspace/api-spec run codegen` — regenerate API hooks and Zod schemas from the OpenAPI spec
- `pnpm --filter @workspace/db run push` — push DB schema changes (dev only)
- Required env: `DATABASE_URL` — Postgres connection string

## Stack

- pnpm workspaces, Node.js 24, TypeScript 5.9
- API: Express 5
- DB: PostgreSQL + Drizzle ORM
- Validation: Zod (`zod/v4`), `drizzle-zod`
- API codegen: Orval (from OpenAPI spec)
- Build: esbuild (CJS bundle)

## Where things live

- `android/` — complete Android Studio project (Kotlin + Jetpack Compose)
- `android/app/src/main/kotlin/lt/sturmanas/bajeristas/` — all app source
- `android/app/src/test/` — SafetyController unit tests
- `artifacts/api-server/src/routes/realtimeSession.ts` — POST /api/realtime-session (Phase 3 placeholder)
- `android/README.md` — Android-specific setup, architecture, phase checklist

## Architecture decisions

- **Android project lives in `android/`** — self-contained, open `android/` in Android Studio (not the repo root).
- **SafetyController is deterministic, no AI** — small Kotlin class with clear distance thresholds; must never depend on AI state.
- **Backend issues ephemeral tokens** — OpenAI API key stays server-side; Android client uses short-lived tokens (Phase 3).
- **Navigation is the source of truth** — AI persona prompts explicitly forbid inventing directions; only verified NavigationState values are passed to the AI.
- **Phase gates are strict** — do not begin Phase 2 until Phase 1 is confirmed working on device.

## Product

- **Start screen**: destination input, personality selector (Kentas fully implemented, others placeholder), humor intensity slider.
- **Navigation screen**: map (Phase 2), maneuver card, push-to-talk mic, mute AI, emergency fallback to standard nav voice.
- **SafetyController**: blocks AI audio within 200 m of a maneuver; blocks for all complex maneuvers regardless of distance.
- **Kentas**: Lithuanian-language humorous co-pilot persona — playful, slightly sarcastic, max 12-word replies.

## User preferences

- Build in strict phases; do not expand scope beyond the agreed phase.
- Report format at end of each phase: completed / tested / blocked / next single step.
- No databases, no auth, no analytics, no extra screens.
- Android project must remain openable in Android Studio without additional setup.

## Gotchas

- Open `android/` in Android Studio, **not** the repository root.
- `local.properties` is gitignored — copy from `local.properties.template` and add SDK path + API key.
- The Google Navigation SDK requires the Navigation SDK specifically enabled in Google Cloud (not just Maps SDK).
- `OPENAI_API_KEY` is a Replit secret — it must never be added to the Android project.

## Pointers

- See the `pnpm-workspace` skill for workspace structure, TypeScript setup, and package details
