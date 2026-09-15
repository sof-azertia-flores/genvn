# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

genvn is a local-first, single-player generative visual novel / TRPG: a Java 21 + Spring Boot 3.5
backend (`backend/`, Gradle) that owns all game state and talks to an OpenAI-compatible chat
endpoint, and a React 19 + TypeScript + Vite front end (`frontend/`). No database: saves are JSON
files under `backend/data/sessions/<id>/`, pictures under `backend/data/assets/<sessionId>/`. UI text is
Chinese; code, prompts and docs are English.

## Commands

Backend (run from `backend/`):

```bash
./gradlew bootRun --console=plain          # or ../run-backend.sh (copies the config template first)
./gradlew test                             # whole suite, JUnit 5 + MockMvc, no Spring context
./gradlew test --tests 'com.genvn.NarrativeDriveTest'          # one class
./gradlew test --tests 'com.genvn.api.AccessGateTest.tokens'   # one method
./gradlew bootJar                          # build/libs/genvn-backend-<version>.jar
tools/package-backend.sh [--with-config --with-data --source]  # deployable tar.gz in dist/
```

Frontend (run from `frontend/`):

```bash
npm run dev                                # Vite on 127.0.0.1:5180, proxies /api to the backend
npx tsc -b                                 # type check (npm run build = tsc -b && vite build)
node --test tests/*.test.mjs               # all regression tests
node --test tests/access.test.mjs          # one file
tools/package-frontend.sh --api https://api.example.com   # CDN build with the backend origin baked in
```

There is no linter. Test results are the check: after a change run both suites and `npx tsc -b`.
A test's JVM warning "Sharing is only supported for boot loader classes" is noise.

## Configuration rules

- Every setting lives in a config file, never an environment variable or CLI flag.
  `backend/config/application.example.yml` is the committed, commented template;
  `backend/config/application.yml` is the user's live copy (gitignored, holds real API keys —
  never print its contents). `frontend/genvn.config.json` holds dev-server and `apiBase` settings.
- Adding a backend key means touching three places or `ConfigFileTest` fails: the `*Properties`
  class default, the template with a comment, and `ConfigFileTest.KNOWN_KEYS`. Code defaults and
  template values must agree.
- No `llm.api-key` means mock mode: `MockLlmClient` plays the whole game offline through the same
  JSON contract. Tests never need a key or network.

## Architecture

**The model never owns state.** `GameState` is a mutable POJO; the LLM's only way to change it is
a `StateDelta` of allow-listed `DeltaOp`s that `game/StateReducer` validates, clamps and applies.
`SessionService.commitScene` is the only place canonical state changes, and it happens only after a
generation fully succeeded, so a failed model call leaves the save untouched (surfaces as HTTP 502
`llm_failed`). Anything parsed from the model is a record; runtime state is a class.

**Request path** (`api/SessionController` → `game/SessionService`):

1. `POST /api/session-creations` runs `story/StoryCompiler` (bible + spine of `StoryBeat`s with a
   required `turn`, ≥5 beats, mandatory opposition), then `narrative/SceneGenerator` for scene 0.
2. Committing a scene casts a d20 for every checked choice up front (`GameSession.sceneDice`,
   `dice/CheckResolver`). `POST .../choices/{id}/roll` only *reveals* that die and pins it as
   `pendingRoll`; `POST .../choices/{id}` commits using it. Dice are the engine's, never the model's;
   the prompt forbids mentioning numbers and requires fail-forward on failure.
3. `speculation/SpeculativeGenerator` prefetches exactly one branch per choice (the outcome that
   will happen) into `BranchCache`, keyed by scene id, choice and `stateVersion`; a stale, missing
   or failed branch falls through to live generation. `/tasks` exposes only `check: boolean`, never
   the outcome or prose.
4. Clients send `expectedSceneId`/`expectedStateVersion`; mismatches are 409 `scene_conflict`,
   `roll_pending` or `choice_resolving`. The session monitor is never held across a model call.
5. `story/ArcContinuationService` plans the next arc in the background at 60% of the spine;
   `story/SpareDesignService` keeps a pool of unassigned character designs (one design → one NPC).

**LLM layer** (`llm/`): `StructuredLlm` wraps every call — extract the outermost JSON object,
deserialize, domain-validate, repair round-trip up to 3 attempts, fail fast on 4xx, back off on
retryable errors. `OpenAiCompatibleLlmClient` streams SSE with an `IdleWatchdog`; `LlmPurpose`
selects per-purpose `reasoning_effort`. `LlmRequest.mockContext` carries live domain objects so
the mock can be state-aware.

**Prompts** live only in `prompt/Prompts.java` and `prompt/ContextRenderer.java`. Do not open or
edit `prompt/InjectionGuard.java`; reference `InjectionGuard.TEXT` where a prompt needs it.

**Pictures** (`asset/`): `VisualPlanner` derives a plan from the compiled story with no model call;
`AssetPipeline` runs bounded workers against an `ImageAssetProvider` (`OpenAiImageProvider` or
`DisabledImageProvider`), with one request per picture, retries, per-arc budget (0 = unlimited) and
a manifest per session. Sprites must be real-alpha PNG; pose variants are *edits* of the base sprite
and send their short prompt verbatim (long prompts make gpt-image return opaque RGB). Picture status
is lock-free and polled by the UI separately from scene state.

**Access** (`api/AccessGate`, `AccessKeyFilter`, `config/AccessConfig`): with `genvn.access-key`
set, every `/api` call needs the `X-Genvn-Key` header except `GET /api/access` and CORS preflights;
picture URLs carry a per-save HMAC token `?t=` instead. Empty key = open, the local default.
`genvn.allowed-origins` adds a CDN origin; loopback is always allowed.

**Frontend**: `src/App.tsx` owns all game state and the reading/dice/choice flow; `src/api.ts` is
the only fetch site (adds the key header, `API_BASE` baked in via Vite `define`); `src/assets.ts`
polls picture status and resolves URLs; `src/assetView.ts` holds pure picture-resolution rules.
Components under `src/components/` are presentational. `tests/componentHarness.mjs` renders the
real TSX with a fake React (hooks, effects, timers, `Image`) inside `node:vm`; api tests transpile
`api.ts` and import it as a data URL. Stubs for `./api` default to "no key required".

## Conventions worth knowing

- Tests wire real services by hand through `backend/src/test/java/com/genvn/support/Engine`
  (`ScriptedLlmClient`, `ScriptedRandom`, `FakeImageProvider`, `InMemoryGameSessionRepository`);
  controller tests use `MockMvcBuilders.standaloneSetup` with `ApiExceptionHandler`.
- Every behavioural change gets a dated section in `IMPLEMENTATION_NOTES.md` and, when user-facing,
  a README section; both quote the test counts (`## Tests (N, ...)`, "N backend tests",
  "N frontend regression tests") — update them.
- Saves are migrated in place at boot (`persistence/SaveDiceMigration`) and lazily in
  `SessionService.require`; keep old saves loadable when changing `GameSession`.
- A running backend must be restarted for any backend change; the user's own instance usually
  runs on 8080 with real keys — do not kill it or point tests at its `data/`.
- `NEXT_WINDOW.md` and `WINDOW_2_AGENT_PROMPT.md` are agent handoff notes from earlier phases, not
  current instructions.
