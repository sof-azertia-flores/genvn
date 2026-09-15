# Implementation notes

Implementation history, current behavior, and validation, including the visual asset pipeline.

---

## Status against the P0 list

| | Requirement | Status |
| --- | --- | --- |
| P0.1 | Backend + frontend start, documented | **done** — `run-backend.sh`, `run-frontend.sh`, README |
| P0.2 | Outline + character creation + Start | **done** |
| P0.3 | Story Compiler produces Bible + Spine | **done** (real LLM or mock, same contract) |
| P0.4 | First SceneBundle rendered as a VN | **done** |
| P0.5 | Choices advance through many scenes | **done** — 14-scene runs verified |
| P0.6 | `d20 + stat vs DC`, server-rolled, shown in UI | **done** |
| P0.7 | Canonical state really changes | **done** — inventory, flags, relations, location, HP, conditions, beats, threads |
| P0.8 | One-step speculative generation | **done** — one candidate per choice; a check's die is cast ahead and sealed |
| P0.9 | Invalid LLM output never crashes the app | **done** — repair loop, local normalisation, clean 502 |
| P0.10 | Actually built, run and tested | **done** — 311 backend tests, 87 frontend tests, plus browser play-throughs |

P1 also landed: **Story Arc Continuation** (a second arc is planned in the background and taken up
when the spine runs out), **file save** after every commit, and a save list on the start screen.

---

## Architecture in one screen

```
POST /api/sessions
  StoryCompiler ──LLM──> {authorCanonFacts, bible, spine, openingLocationId, threads}
       └─> CompiledStory + initial GameState (canonical)
  SceneGenerator ──LLM──> SceneBundle(scene_000)
       └─> commitScene()  ← the ONLY place canonical state changes
       └─> SpeculativeGenerator.prefetch()

POST /api/sessions/{id}/choices/{choiceId}
  1. CheckResolver.resolve()      ← the engine rolls the d20. Not the model. Ever.
  2. BranchCache.takeIfFresh(sceneId, choiceId, SUCCESS|FAILURE|NONE, stateVersion)
       hit  -> use the pre-generated candidate
       miss -> SceneGenerator.generate() right now
  3. BranchCache.discardAll()     ← in-memory unused candidates die; finished ones were already written to the tree
  4. commitScene()                ← StateReducer validates + applies the delta
  5. repository.save()            ← data/sessions/<id>/session.json plus nodes/<sceneId>.json
  6. prefetch() the new frontier + maybe plan the next arc
```

Packages: `story/` (compiler, bible, spine, arcs) · `game/` (state, delta, reducer, session
service) · `narrative/` (scene bundle, generator) · `dice/` · `speculation/` · `llm/` (client,
mock, structured-output loop) · `prompt/` (every prompt string) · `persistence/` · `api/`.

---

## GameState flow

`GameState` is a plain mutable object; the LLM cannot address it. The only vocabulary the model has
for changing the world is a `StateDelta` — a flat list of `DeltaOp{op, target, amount, value, reason}` —
and `StateReducer` is the gate:

- **Allow-list of 12 ops.** Anything else is dropped and written to `rejectedOpsLog`. There is
  deliberately no op that can express "overwrite the player", "edit Author Canon", or "set an
  arbitrary path". Author Canon and the Story Bible are structurally unreachable.
- **Everything numeric is clamped.** `hpDelta` to ±20 then HP to `[0, maxHp]`; `relationshipDelta`
  to ±3 per step and `[-10, 10]` overall.
- **Every reference must resolve.** Unknown beat / thread / character / location ids are refused.
- **Everything is bounded.** 30 inventory items, 60 flags, 8 conditions, 24 threads, and at most
  2 new threads per scene, so the model cannot pile on mysteries.
- **`stateVersion` advances on every committed scene**, including an empty delta, because location,
  text and scene counters still change. Applied deltas also invalidate older speculative snapshots.

The scene's own location is authoritative for `currentLocationId` on commit, so the prose and the
state can never disagree about where you are. A location the compiler never named gets registered
into the bible at commit time — **on the canonical path only**, never from a branch.

## Dice flow

The model proposes `{stat, dc}` on a choice and nothing more. Every checked choice's die is cast
the moment its scene becomes current (`GameSession.sceneDice`) and stays sealed until the player
picks that choice; the prefetch therefore writes only the outcome that will happen. When the
choice is made:

1. `Stat.fromLoose()` maps whatever the model wrote (`"perception"`, `"PERCEPTION"`, `"感知"`,
   `"wis"`) to a real stat; an unresolvable one becomes a checkless choice rather than an error.
2. `dc` is clamped to `[8, 18]`. The pre-clamp value is kept in `CheckResult.requestedDc` for the
   inspector.
3. `DiceService` — the only source of randomness in the system — supplied the `d20` when the
   scene was committed (older saves get theirs on first load).
4. `total = d20 + stat`, `success = total >= dc`.
5. The *outcome* is handed to the narrative layer, which is told in the prompt never to mention
   dice, numbers or DCs, and — on a failure — to apply fail-forward: a cost, a complication,
   partial information, a worse position, or new danger. Never "you can't".

The UI shows `🎲 6 + Perception 4 = 10 · DC 12 · FAILURE` in a reveal overlay, and then keeps that
line pinned above the narration for the whole scene so the result is never just a flash.

### Roll and scene are two calls, on purpose

The die is decided instantly server-side, but a scene can take a minute to generate on a cache
miss with a real model. If both travelled in one response, the dice overlay would spin for the
whole generation -- which is exactly the "dice screen hangs" symptom. So a checked choice is two
steps:

1. `POST .../choices/{id}/roll` reveals the die already cast for that choice, persists it on the
   session as `pendingRoll`, and returns just the roll in milliseconds. The overlay shows the real
   number immediately.
2. `POST .../choices/{id}` generates and commits the scene, *reusing* that persisted die.

The persisted die makes the roll binding and idempotent: a retry after a failed generation, a
refresh, or a backend restart all replay the same number -- nothing ever re-rolls. Casting a die
for one choice locks the scene to it; rolling a different choice returns HTTP 409 `roll_pending`,
and the client reloads and resumes from the die already shown. On the client the reveal overlay
and the "generating the next scene" state are now separate, so the die never stands in for a
generation's worth of waiting.

## Speculative generation

While you read scene *N*, the successors of scene *N+1* are already being written:

- one branch per choice; for a choice with a check, **two** — one written for SUCCESS, one for
  FAILURE. **No dice are rolled at prefetch time.**
- each branch forks `canonicalState.deepCopy()` on the request thread, so the snapshot is
  unambiguously "state at commit". Branch work happens on a 4-thread daemon pool.
- the branch applies its own candidate delta **to its fork only**, purely so the inspector can show
  you what that branch would do (`provisional` column). The canonical state is untouched.
- a branch records the `stateVersion` it forked from. `takeIfFresh` refuses any branch whose base
  version no longer matches canon. That check is why an unchosen branch cannot leak into canon even
  if the bookkeeping were wrong elsewhere.
- on commit, `discardAll` cancels and drops **every** branch for the session before the new scene is
  committed.

Prefetch is never load-bearing: a miss, a still-generating branch, a failed branch or a stale branch
all fall through to live generation. Budget is capped (`genvn.speculation.max-branches`, default 6).

Only one layer, as specified — the frontier slides forward one scene at a time. No A→A1→A1a tree.

## Structured output and failure handling

Every generation goes through `StructuredLlm`: extract the outermost balanced JSON object (fences
and surrounding prose survive; braces inside strings do not confuse it) → deserialize → domain
validate → on failure, a **repair request** quoting the error, up to 3 attempts total.

Two tiers of repair, deliberately:

- **Local, free:** an out-of-range DC is clamped, a duplicate choice id is made unique, an unknown
  stat drops the check, a dialogue line with no speaker is demoted to narration, a speaker the model
  forgot to put on stage is added. These never cost a round trip.
- **Round trip:** only structural damage — no JSON, no blocks, no location, or no choices while the
  story is unfinished.

If all attempts fail, the request throws, the handler returns a clean `502` with a readable message,
and **canonical state is untouched** — generation always completes before any commit happens.

## Prompt injection

Player story text is wrapped in `<<<USER_STORY_CONTENT>>>` delimiters, the delimiter sequences are
neutralised in the input so story text cannot close its own block, and every system prompt states
that delimited content is fiction to be adapted and never instructions to obey. Structurally it
also cannot matter much: the model's only channel into the runtime is a schema-validated delta
against a 12-op allow-list.

## Choices that came up

- **Spring Boot + Gradle, no database.** `GameSessionRepository` with a file implementation
  (write to temp + atomic move, with an in-memory live map so a session in play is one shared
  object). An `InMemoryGameSessionRepository` in the tests proves the seam is real.
- **`GameState` is a mutable POJO with public fields, deep-copied via a Jackson round trip.**
  Boring on purpose: branch forking is *obviously* correct because a fork physically cannot alias a
  canonical collection, and it costs about six lines instead of a `with`-er per field.
- **`LlmRequest.mockContext` carries live domain objects** so `MockLlmClient` can be state-aware
  without re-parsing its own prompt. Real providers ignore it; it never leaves the process. Slightly
  impure, and it is what makes the offline demo good rather than random.
- **Records for anything parsed from the model, classes for mutable runtime state.**
- **`scenesInCurrentBeat`** is runtime bookkeeping the narrative layer needs to pace beats; a scene
  narrates the beat it is *closing* but offers the choices of the beat it is *opening*.

---

## Tests (311, `./gradlew test`)

The three correctness cases from the brief are tested by name:

- `BranchIsolationTest` — **Case 1.** Branch A picks up a key, branch B does not, the player picks
  B: the key never exists in canon, and the ledger of applied flags proves only B committed. Plus a
  test that a branch forked from an older `stateVersion` is refused.
- `CheckOutcomeTest` — **Case 2.** Both candidates are pre-generated; a scripted `d20` of 4 against
  DC 13 with Perception 4 fails; the **FAILURE** candidate becomes canon, the SUCCESS candidate is
  discarded, and the run reports a cache hit. Mirror test for success. Third test asserts a failure
  still yields progress (fail-forward).
- `StateReducerTest` — **Case 3.** `hpDelta: 999999` clamps; `setAuthorCanon` / `deleteStoryBible` /
  `setPlayer` / `eval` / `__proto__` are all refused and logged; dangling ids are refused;
  relationship swings are bounded; thread spam is capped; a fully-rejected delta does not bump
  `stateVersion`.

Also: `DiceTest` (the documented rule, ties, DC clamping, lenient stat names, 4000 rolls in range),
`InvalidLlmOutputTest` (messy JSON recovery, repair-then-continue, give-up-without-corruption, local
normalisation), `MockPlaythroughTest` (10-scene offline play-through asserting real state change,
save round-trip, hostile outline treated as data), `ArcContinuationTest`.

---

## Known limitations

1. **The mock still repeats itself, but less.** It is a template bank: two or three variants per
   beat / outcome / approach, selected by a hash of scene, beat, outcome, choice and location.
   Measured over 16-scene runs, repeated opening lines dropped from 6/16 to 3-5/16 — a typical
   10-scene play-through rarely shows one. The residue is concentrated in the `cautious` and
   `social` banks, which are shared across every beat rather than written per beat. It exists so
   the loop is demonstrable offline, not to be a good author. With a real key the prose is the
   model's.
2. **Arc 2+ under the mock now has its own material** — a re-read / ask-again / go-back template
   set with its own choices, so a continuation no longer replays the first night. It is still only
   three stages deep; a fourth arc recycles the third.
3. **Live image quality is not covered by the local smoke checks.** Backgrounds and portraits now
   use `ImageAssetProvider`, are saved locally and appear asynchronously. PNG and JPEG are supported;
   unsupported output formats, including WebP, pause image requests before any provider call.
   The game remains playable with placeholder art when image generation is disabled or paused.
4. **Speculation is expensive on a paid API** — up to 5 generations per scene. Tune or disable it
   via `genvn.speculation` in `backend/config/application.yml`.
5. **Provider-side billing is outside local cancellation control.** Discarding a branch now
   interrupts its worker and HTTP/SSE read and prevents retry or repair. A provider may still
   finish and bill work already accepted before the connection closes.
6. **Multiple pages now have conflict protection.** Each choice carries its source scene and state
   version. An outdated page gets HTTP 409 before a roll or mutation and reloads the current scene.
7. **No Load-a-save resume mid-scene.** Saves restore fine and the start screen lists them, but the
   speculative cache is cold after a reload (first choice after loading is a live generation).
8. **Browser checks use mock text and a local image HTTP stand-in.** The 36 frontend tests cover
   client guards plus component behavior for in-progress choice recovery, bounded retries,
   stale pictures and preload selection. The latest browser pass verifies creation, rendered
   pictures, choice/roll, a prefetch hit, and refresh/load. Live-provider latency and visual quality
   still need separate evaluation. Only `session_not_found` sends a missing session to the menu.
9. **HP can reach 0 and nothing happens.** There is no death, defeat or downed state — a combat
   system was an explicit non-goal for this window, and a mystery VN has nowhere obvious to send a
   dead protagonist. HP is clamped to `[0, maxHp]` and shown on the sheet, but it is currently
   pressure without a consequence. Decide what 0 HP *means* before adding one.
10. **Disk failure still permits in-memory play, with an explicit warning.** `saveHealthy=false`
   appears in session responses and the inspector; the UI keeps a warning visible until persistence
   succeeds. The repository retains the last good file, cleans failed temporary writes, and keeps
   memory-only sessions selectable. Restarting while the warning is active still loses unsaved work.
11. **`Stat` map keys serialise as `BODY`/`PERCEPTION`** (Jackson enum default); the UI normalises
   case on read. Worth making explicit in the API contract eventually.

## Streaming and gateway 504s (2026-09-05)

**Symptom.** With a real key, `POST /api/sessions` failed with `504`, while the same endpoint
answered a plain API test tool fine.

**Root cause.** Not Vite, not our timeout. The provider sits behind a gateway (Cloudflare-style
`error code: 504` body) with a roughly 75–100 s idle-origin limit. The story compiler asks for a
few thousand tokens of JSON in one shot; at ~48 tok/s that outlasts the limit, so the gateway
gives up on a request the model was still serving. Short test-tool requests never get near it.
Our retry then succeeded on attempt 2 (159 s total), which is why it sometimes "worked".

**Fix.** `OpenAiCompatibleLlmClient` now sends `stream: true` and reassembles the SSE stream
(`SseAssembler`). Bytes flow continuously, the gateway never sees an idle origin. Verified:
same request, 200 in 115 s, zero transport failures. `llm.stream` (default `true`) turns it off
for a provider that cannot stream; the assembler also copes with a provider that ignores the
flag and returns one JSON body. `llm.timeout-seconds` (now 300) is the *total* budget per
generation including streaming; the HTTP request timeout only bounds time-to-first-byte.

**Logging.** The pipeline now logs at INFO throughout a generation — request sent, first byte,
streaming progress every 5 s, parse accepted/rejected, compile summary, scene ready, commit
summary, prefetch start and each branch completing — so a two-minute compile is never silent.

**Config layout, revised.** `backend/config/application.yml` is the user's live file and is
gitignored (it holds the key). The committed template is `application.example.yml`;
`run-backend.sh` copies it into place on first run. `ConfigFileTest` validates the template's
values and only the *key names* of the live file, never its secrets.

**Observed, not fixed.** With the real model the opening scene proposed `completeBeat` for the
first beat, so the story entered beat 2 immediately. The runtime accepted it because it is
structurally valid; whether the *opening* should be allowed to close a beat is a prompt-tuning
question, noted for the next window.

## Runtime bug fixes (2026-09-05)

- Compiler and continuation reject duplicate beat IDs. Old saves containing duplicate IDs use a
  bounded forward scan, so completing one can no longer spin forever.
- Scene ending validation uses usable normalized choices and a reducer projection on a deep copy.
  Blank choices cannot terminate an unfinished story; completing the final beat can return no choices.
- File saves publish one canonical object per session, serialize under its monitor, use unique
  temporary files and atomic replacement where supported, and expose durability failures. Deleted
  sessions cannot be revived by late background writes.
- HTTP choices require `expectedSceneId` and `expectedStateVersion`. Conflicts return 409 before
  rolling. Responses are detached under the same lock, preventing mixed-scene serialization.
- Full narration/dialogue is retained in history. The last four complete scenes are included in
  subsequent generation and speculative prompts. Existing saves recover their current scene text;
  previously discarded older dialogue cannot be reconstructed. Older scenes remain on disk, while
  lasting facts must still be represented in flags, inventory or the continuity ledger for long runs.
- Speculation and arc planning read detached story/state snapshots. A late successful arc can reopen
  the ending with a continuation choice; the ending UI polls only while planning is still in progress.
- Configured temperature is used by default, including streamed requests; explicit per-request
  overrides survive repair attempts. Existing SSE and gateway-timeout handling is preserved.
- Setup errors are visible and retryable. Creation uses the response directly; create/load actions
  are mutually exclusive; stale responses are ignored. Keyboard handlers respect controls and panels,
  all saves remain reachable, and switching sessions clears the prior dice display.

Validation: 94 backend tests, 10 frontend client tests, production frontend build, and isolated Mock
browser/API verification. Regression HTTP servers use loopback and dummy credentials. User provider
configuration and existing saves are preserved; this fix pass does not re-evaluate real-model quality.


## Visual assets: pictures prepared ahead of the prose (2026-09-05)

**Goal.** Backgrounds and portraits should exist before the player needs them, and the game must
never block on them. Two different look-ahead horizons: prose is prefetched one choice deep;
pictures are planned for the whole current arc and rendered in priority order.

**Shape.** `asset/`:
- `VisualPlanner` derives a plan from the *validated* Bible/Spine -- deterministic, no model call.
  Opening location first, then locations in bible order (capped), a base portrait per main
  character (capped), then expression variants that `dependsOn` the base. Ids are stable story
  identity (`bg.<loc>.<variant>`, `pt.<npc>.<variant>`), so beats, branches and restarts share one
  picture. Style is fixed at first plan and becomes part of every prompt.
- `AssetPipeline` is the scheduler: its own worker threads (`image.concurrency`), a priority queue
  that can be re-ordered when the story turns, one task per asset id (all interested callers
  subscribe to the same future), a per-arc attempt budget persisted in the manifest (restarts cannot
  reset it), bounded backoff on 429/5xx/timeouts, no retry on 400/401/403/404, and late results for a
  forgotten session are dropped. Variants poll for their base rather than blocking a worker, so two
  variants can never starve the base they wait on. A finished picture bumps the manifest's own
  `version`; it never touches `GameState.stateVersion`.
- `AssetStore`: `data/assets/<session>/{manifest.json, *.png}`. Bytes go to a temp file, are decoded
  to prove they are a complete image, then published atomically; only that is READY. Ids are
  charset-restricted and resolved paths are checked to stay inside the library. On restart a READY
  file is re-validated (else MISSING and repaired within budget); a GENERATING task is re-queued only
  if attempts remain.
- `ImageAssetProvider` with `OpenAiImageProvider` (generations JSON + edits multipart; handles
  gpt-image vs dall-e differences in one place) and `DisabledImageProvider`. Tests use
  `FakeImageProvider`, which can be held at the door and counts concurrency.
- `AssetResolver` turns "loc_interior, npc_witness looking worried" into ids. A model hint is
  honoured only for the same subject and an applicable outcome; a picture tied to `OUTCOME:SUCCESS`
  is never handed to the failure branch. The prompt sees a compact manifest (ids, subject, status),
  never bytes or paths, and may add at most a couple of `assetRequests` for established subjects --
  acted on **only when the scene commits**, so a discarded branch buys nothing.
- `AssetCoordinator` is the only thing `SessionService` calls: plan at create (before the opening
  prose), mark-needed / pull-forward / honour-requests at commit, re-plan at arc start, forget at delete.
- Frontend: `useAssets` polls `/assets` (fast while pending, slow when quiet), `Backdrop`/`Sprite`
  decode before they show (no flash, no layout jump), a variant still generating falls back to the
  same character's base portrait and never to another face, and picture arrival never resets the
  dialogue line, replays the die or resubmits a choice. The Inspector's assets tab shows plan,
  queue, budget, attempts, reuse counts and `plannedAt/startedAt/readyAt/firstNeededAt`.

**Evidence from a real run** (production adapter -> local OpenAI-Images stand-in returning PNGs,
mock text, isolated `data-demo/`): 9 pictures requested starting the same second the story
compiled, two at a time (high-water 2 = `image.concurrency`), 5 generations + 4 edits; all READY on
disk with a manifest; served as `200 image/png` with immutable caching; the opening scene referenced
`bg.loc_threshold.default` and the neighbour's `suspicious` variant; the next scene swapped to
`bg.loc_interior.default` with **zero** new requests, and that picture was ready 1m54s before it was
first needed (`readyBeforeNeeded=true`). After a backend restart the same session served the same
files with no provider call.

**Not verified by these checks.** Live OpenAI Images API behavior and generated-art quality.
Character consistency across variants is only as good as the provider's edit endpoint; with a
provider that cannot edit, variants are generated from the base prompt and consistency is weaker.
Do not read the stand-in's timings as real-provider timings.

## The session monitor is no longer held across a model call (2026-09-05)

`choose()` used to hold the session monitor for the whole generation wait, which meant
`copySession()` (and so `GET /sessions/{id}`) could stall for a minute. It is now three phases:
validate + decide/persist the die + snapshot (locked, ms); obtain the scene from cache, in-flight
branch or live generation against the snapshot (unlocked); re-validate scene id and state version,
then commit (locked). A concurrent second click sees `resolvingChoiceId` and gets an immediate 409;
a page that is behind gets a 409 at phase 1 or 3. `ChooseLockTest` proves reads return in
milliseconds mid-generation and that the double click generated nothing. A die cast by `choose()`
itself is now persisted before generation, closing the last path where a failed generation could
have re-rolled on retry.

## Audit repairs and validation (2026-09-05)

The twelve confirmed audit defects have regression coverage and fixes:

- A running choice now returns `choice_resolving` and exposes `resolvingChoiceId` in session
  responses. The frontend waits with bounded polling instead of resubmitting the saved roll.
  Another choice cannot roll while generation owns the scene; commit also checks roll ownership.
- Branches retain their actual executor task. Discard interrupts the worker and HTTP/SSE read;
  cancellation never starts a repair or retry. The chosen queued candidate takes priority over
  siblings. Deletion, commit and prefetch coordinate through the session monitor, and deletion
  cannot trigger a fresh live generation as a fallback.
- A canonical scene registers its validated picture requests and resolves their stable ids before
  it is committed. New rooms and variants therefore appear when their files arrive, without a
  narrative state change. A missing base portrait is registered before its requested variant.
- Image attempt reservations must reach disk before a provider request starts. Persistence
  failures pause new requests and surface in asset status. Late success, failure and retry
  callbacks cannot republish a deleted session. A new arc gets its own first-batch allowance;
  repeat notifications for the same arc cannot replenish that allowance or its attempt budget.
- PNG/JPEG are accepted; unsupported formats such as WebP pause images before a paid request.
  Changing to a supported format allows recovery through normal scheduling.
- Picture loading drops mismatched old art immediately, ignores stale callbacks and retries
  failed downloads a bounded number of times. Explicit retry and reconnect can recover later.
  Preloading marks only successful downloads as warmed, excludes them before filling slots,
  and prioritizes the current and upcoming beats.

Validation ran in a temporary source copy with isolated data and dummy credentials: all **113
backend tests** and **22 frontend tests** passed, followed by TypeScript checking and a production
frontend build. The final strengthened HTTP/SSE cancellation test also passed: the SSE case waits
until the reader has entered `SseAssembler` before interrupting it. New focused suites are
`RuntimeConcurrencyRegressionTest`, `LlmCancellationTest`, `AssetDurabilityTest`,
`AssetBindingRegressionTest`, and the frontend component tests.

A real browser against mock text and the production image adapter connected to a local HTTP
stand-in verified creation, background/portrait display, a failed server dice check, a prefetch
hit, scene transition, refresh and saved-session load. The Inspector showed nine READY pictures,
image worker high-water two, and the next location's background ready before it was needed.
These are integration checks, not evidence of real model latency or generated-art quality.
The user's live configuration and save directories were not used for these tests.

Remaining planning work from the audit: beat-to-subject associations are still inferred from Bible
ordering rather than semantic beat requirements, and continuation pictures are planned when a new
arc begins rather than as soon as its pending outline is available. These are future improvements
to how far ahead assets can be prepared.

## Visual novel interface and character art (2026-09-05)

The stage now follows the supplied layout: a row of permanent character cards at the upper left,
the active character's large transparent sprite above the dialogue box, and a restrained dark
teal/green palette with warm gold details. The player sheet remains available under **我的角色**.
The old `x / y` dialogue counter is gone. Text reveals by grapheme, so emoji and combining marks
stay intact; punctuation gets a short pause. A click or Space completes the line before it can
advance. Choices appear only after the final line is revealed. Picture arrival and task polling
do not reset this reveal; reduced-motion preference displays the full text immediately.

**幕后准备** opens an animated, keyboard-accessible dialog. It separates speculative story tasks
from the image queue, shows real parallel-worker counts and Chinese states, and keeps the
candidate prose hidden. Escape closes it and restores focus. The developer inspector is tucked
under **调试详情**. Background generation status no longer covers the whole stage, allowing the
player to inspect preparation while a choice is resolving.

Character art has two independent display roles. `pt.<npc>.base` and its pose/expression variants
are always transparent PNGs; provider requests explicitly ask for transparent backgrounds, and
the returned file must contain both transparent and visible pixels. `card.<npc>.default`
(`CHARACTER_CARD`) is one opaque character card per NPC, edited from the transparent base with a
frame and a background informed by that character's profile. Every pose shares it. Both card and
pose edits require the base reference; a missing edit/transparency capability pauses the affected
art instead of generating an inconsistent substitute. New games automatically plan these assets
and persist them through the existing budgeted queue. No existing save/asset migration is included,
as requested.

The opening screen now has a Chinese preparation log and progress bar backed by actual milestones
from `SessionService.create`, via `POST /api/session-creations` and `GET` of its job id. The progress
is a stage indicator, not a model-token or elapsed-time estimate. Pictures start once the validated
framework exists and continue independently of opening prose. A UUID is retained in tab-local
session storage before submission and sent as `Idempotency-Key`; polling, refresh and a lost POST
response cannot automatically submit a second story. A brief GET grace period handles refresh
arriving before POST registration. Individual requests have deadlines, repeated read failures
offer retry of the same job, and a missing/expired task has a return path. Only the opaque job id
is stored for this recovery, not story text or credentials. Jobs are process-local with bounded
workers, waiting queue and retained records; terminal jobs expire after an hour.

Final isolated validation: **132 backend tests**, **36 frontend tests**, TypeScript checking and
the production frontend build passed. Browser checks at 1280×720 and 390×844 covered the setup
layout, real Chinese milestone logs, immediately refreshing a submitted opening, transparent
sprite/fixed-card display, character-by-character text, choices, a server dice check, the resulting
scene transition and the preparation dialog. The final browser console had no error entries.
The local image stand-in now emits real alpha PNGs for sprites and opaque reference-edit cards;
its successful image files are saved to an isolated data directory. These checks do not establish
live-provider art quality, likeness consistency or production generation latency. Actual user
configuration files were preserved and existing game data was not used for validation.


## Player art, art direction, history and image retry — 2026-09-05

- Added persistent global art direction and player appearance to synchronous/asynchronous creation and
  idempotency fingerprints; independent player visual profile never enters NPC relations.
- Planned player base/card/talking/action assets early; selected speech/actions have their own readable
  UI turn. History API paginates only canon through the displayed reading boundary.
- Added durable, deduplicated manual image retries and dependent card/pose wakeup. Each manual click
  authorizes one additional attempt; arc budgets survive retries/restarts and write failures.
- GPT Image 2 native PNG transparency request is retained on both endpoints. Appended output constraints
  prevent scene context from taking precedence; invalid alpha output now retries within configured
  limits instead of becoming permanently non-retryable. No model substitution or local fake cutout.
- Verified in an isolated copy: 151 backend tests, 48 frontend tests, TypeScript and production build.
  Provider verification uses a local HTTP double (exact gpt-image-2 generation/edit requests), including
  an opaque first pose response followed by a valid transparent retry, counted against budget.
  Real external-provider output quality is stochastic and was not re-tested through a paid API.
- Browser QA at 1440×1000 and 390×844 confirmed the art/appearance form, real Chinese compilation
  milestones, fixed player card, immediate player dialogue, and history excluding the queued scene's
  unseen dialogue. A local GPT Image 2 HTTP stand-in returned three opaque action images; the UI
  exposed manual retry, which produced a real RGBA PNG on attempt 4 and changed the task to READY.
  The browser console recorded no errors during these checks. Original runtime/config/data were not restarted or edited.

## Second audit round: hangs, damaged files and unbounded growth (2026-09-05)

A read-only audit of the whole tree found no defect in lock ordering, path handling, the reducer
allowlist, branch promotion or file publishing. It did find ways for a session to wedge or for
data to grow without bound. Fixed, each with a regression test (`StreamIdleTimeoutTest`,
`ResilienceRegressionTest`, `PlayerInputLimitsTest`):

- **A quiet connection no longer hangs a game thread.** The JDK request timeout ends when the
  response headers arrive; a gateway that then held the connection open without sending bytes
  blocked `readLine()` indefinitely, left `resolvingChoiceId` set, and refused every later choice
  on that save with `choice_resolving` until a restart. `IdleWatchdog` now closes the body when
  no bytes arrive for `llm.idle-timeout-seconds` (120) or the total deadline passes, on both the
  SSE and the plain path; the failure is a retryable `LlmException`. The image adapter uses the
  same watchdog with `image.idle-timeout-seconds` (60). The choice marker is also cleared on an
  `Error`, not only on a `RuntimeException`.
- **A damaged `manifest.json` no longer stops the prose.** `AssetPipeline.snapshot` reports the
  file unreadable once, returns "no pictures", and the status payload carries `manifestError`
  (shown in 幕后准备). The budget is still never silently replaced with zero: nothing is written
  until the file is repaired or removed.
- **`image.arc-budget` defaults to 0, meaning no per-arc cap.** The default plan (player set,
  three backgrounds, three characters with card and two poses, spare designs) plus retries
  exceeded the old cap of 24 in ordinary play; the user's own save showed 24/24 with twelve
  pictures paused. Concurrency, one request per picture and `max-attempts` still bound the spend;
  a positive value restores the cap and its reserve of four attempts for later scenes.
- **Model calls that cannot succeed are not retried.** A 400/401/403/404 from the text endpoint
  now fails at once; 408/429/5xx/transport failures are resent unchanged after a short pause, no
  longer wrapped in a "your previous response was rejected" repair prompt.
- **Ids from the model are bounded and checked before adoption.** Invalid or colliding subject
  ids must be repaired rather than silently merged into another picture key. Character and
  location records remain canonical for the whole play-through; only the prompt's detailed
  briefing is limited, prioritizing the current location and recently present people. Each
  player trait is capped at 80 characters.
- **Session ids are checked against existing saves and picture libraries** before use.
- **Image workers survive an `Error`** from one picture; images are rejected by declared
  dimensions and byte size before the decoder allocates for them.
- **Bad query parameters are 400s**, the catch-all 500 keeps its details in the log, and the
  shared die generator is synchronized.

Left alone on purpose: session and choice responses still carry the whole compiled story
(NPC secrets, hard canon, beat conditions). This is a single-player local game and the owner
decided that hiding it from the browser is not worth the payload split.

## Follow-up audit fixes — 2026-09-05

- Updated the actual local image configuration to `arc-budget: 0`, matching the unlimited
  default. Automatic retry bounds and the four image workers remain in effect.
- Dialogue sprites select the current speaker's ready expression, falling back only to that
  person's applicable sprite. Private beat titles and purposes no longer appear in the ordinary
  HUD or character sheet. Late continuation metadata preserves the current line and typewriter
  progress; a changed scene body still starts a fresh reading pass.
- Initial character/location IDs are validated before adoption, including uniqueness and lossy
  asset-key collisions. Asset resolution checks subject, kind and outcome on every fallback.
- Image retries remain durably queued when admission is full, including recovery after a restart.
  Capacity waits spend no provider attempts, and deletion or shutdown stops delayed work.
- The full canonical character and location archives are retained. Prompt detail is bounded and
  prioritizes the current location and current/recent characters, without pruning save data or
  allowing a returning character's identity to be overwritten.
- Isolated validation: 208 backend tests and 60 frontend tests passed; backend bootJar, TypeScript
  and Vite production builds passed. Browser smoke checks exercised compilation, picture loading,
  character-sheet spoiler removal, NPC/player sprites, a dice choice and read-only story history.
  Text used the offline model and images used a local HTTP stand-in; no paid API was called.
  Existing save directories and the original running services were not used for these checks.

## Opaque pose edits on a gpt-image-2 route — 2026-09-05

Every transparent pose/expression edit in a real session failed with "返回图像没有 Alpha 透明通道"
while base sprites (generations) and character cards (opaque edits) succeeded. The request body
was captured byte for byte from `OpenAiImageProvider.edit` and replayed with curl against the
user's endpoint, then bisected with the owner's permission (sixteen paid edit calls in total). The
request body was proven identical to curl's field by field; field order, upload filename and the
boundary string were each changed alone and changed nothing. What decided the alpha channel was
the prompt text:

| Prompt sent to `/images/edits` with `background=transparent` | Result |
| --- | --- |
| "same character, worried expression, transparent background" (twice, once with `n`/`quality`) | RGBA, RGBA |
| "Same character as the reference; keep the face, outfit, hairstyle and art style; talking expression and a natural talking pose; transparent background." (curl, and inside the exact Java body) | RGBA, RGBA |
| a 362-character sentence with negations ("no scenery, no background, no border, no painted checkerboard") | RGBA once, RGB six times |
| any prompt carrying the art-direction text or the appended "FINAL OUTPUT REQUIREMENT" block | RGB every time (in-game 10+, replays 4) |

So on this route `background=transparent` is not enforced; the model decides from the prompt, and
long prompts with negated mentions of backdrops tip it into painting one. Fixes:
`VisualPlanner.variantPrompt` is now the minimal positive sentence above (the reference image
carries the art direction and the appearance), and `OpenAiImageProvider.edit` sends the prompt
verbatim without the appended block. Generation prompts are unchanged: bases with the long prompt
and the block succeeded five out of five in the same session. `max-attempts` still covers the
residual randomness. Existing saves keep the prompts already stored in their manifests; a new game
gets the new ones.

## Dice are cast ahead; the reveal is a ceremony — 2026-09-05

Proposed by the owner: cast every checked choice's die the moment its scene becomes current,
prefetch only the outcome that will happen, and keep the queue silent about which outcome that is.

- `GameSession.sceneDice` holds one `CheckResult` per checked choice of the current scene, cast
  in `commitScene` (or adopted from the committed branch, see below) and persisted with the save.
  `roll` and `choose` read that die; `pendingRoll` still marks the die the player has seen and
  keeps it binding. Nothing is revealed before the player picks the choice.
- `SpeculativeGenerator` writes one branch per choice: `outcomes(choice, dice)` yields the single
  outcome the die produces. Every branch casts the dice for the scene it wrote (`Branch.nextDice`,
  same `CheckResolver`), so the second round can also write one job per checked child; when a
  branch is committed, `choose` hands its dice to `commitScene`, which is what keeps the
  promoted second-round candidates valid. A checked choice whose die is unknown gets no branch.
- Player-facing views hide the outcome: `/tasks` rows carry `check: true/false` and an id without
  the outcome; second-round candidates likewise. The developer inspector still shows everything,
  including `sceneDice`.
- Older saves: `SessionService.require` casts missing dice once and writes the save back, keeping
  a revealed `pendingRoll` as that choice's die; `SaveDiceMigration` does this for every save at
  startup so the files on disk are updated without waiting for a request.
- Cost: a scene with four checked choices used to prefetch eight branches (capped at six); it now
  prefetches four. `max-branches` keeps its default of 6.
- Tests: `SaveDiceMigrationTest` (on-disk migration keeps the revealed die, casts the rest, is
  idempotent); `CheckOutcomeTest`, `PendingRollTest`, `TextBranchConcurrencyTest`,
  `SessionProgressApiTest` and `SecondRoundSpeculationTest` were adjusted to one branch per choice
  and to the sealed queue view. `ScriptedRandom` now settles on 10 once its script runs out,
  because commit-time casting consumes one scripted value per checked choice.

## Reasoning effort in the config — 2026-09-05

`llm.reasoning.effort` (plus per-purpose `story-compile`, `scene-generate`, `arc-continue`,
`choice-probabilities`) is sent as `reasoning_effort` on `/chat/completions` when non-empty;
values are trimmed and lower-cased, nothing else is assumed about them, so whatever the provider
accepts (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`) passes through. Unset sends no field
at all, which keeps providers that reject it working. A provider that rejects a value answers
400, which the client treats as non-retryable. `LlmReasoningEffortTest` checks absence by default,
the normalised default, and the override precedence against a local HTTP double.

## One design, one person; the spare pool refills itself — 2026-09-05

- Adoption already bound a design to a person by id (the design id becomes the NPC id and the
  design leaves `preparedVisuals`). What was missing: a scene could give a *new* id the same
  appearance text. `SceneGenerator.validateIntroductions` now rejects a new person whose
  `visualDescription` equals (normalised) an unassigned design's or an established person's look,
  asking for the design's id or a genuinely different appearance.
- Pictures follow the id: `pt.<id>.base` and `card.<id>.default` planned for the spare become the
  person's on the commit that introduces them (`adoptSpecs` relabels subject name and priority,
  keeps the file and prompt). Nothing is redrawn.
- `SpareDesignService` (new, `LlmPurpose.SPARE_DESIGNS`) runs after every commit: when the pool is
  below `genvn.spare-designs` (default 3, capped by `PreparedVisual.MAX_PREPARED`) it sketches the
  missing designs in the background with a dedicated appearance-only prompt, validates ids
  against every id in use (player, Bible, encountered people, existing designs), appends them
  under the session monitor, saves, and plans their idle-priority pictures. Failures only leave
  the pool smaller until the next commit. The mock client sketches offline designs too.
- `SpeculativeGenerator` compares stories without `preparedVisuals` when promoting second-round
  candidates, so a design arriving in the background cannot discard prepared successors.
- Tests: `SpareDesignTest` (relabelling and refill end to end with the fake image provider,
  rejection of a copied look, batch validation, promotion tolerance). Engine tests keep the
  target at 0 unless they opt in.

## History dialog: no jumps, opens at the latest text — 2026-09-05

Three causes of the "picture jumps while reading 剧情回顾" report, all in `HistoryDialog`:

- The dialog box had only min/max heights, so it grew, shrank and re-centred whenever its
  content changed (placeholder to page, an earlier page arriving). `.history-dialog` now has a
  fixed height; only the text inside scrolls.
- Loading an earlier page prepended entries without adjusting the scroll position, so the line
  under the reader's eyes slid down by the height of the new page. The dialog now records the
  scroll height and offset before the request and restores the same text position in a layout
  effect, and `.history-body` sets `overflow-anchor: none` so the browser's own anchoring cannot
  adjust a second time.
- A bound change while the dialog was open (a committed scene arriving) cleared the page to the
  loading placeholder and repainted it. The old page now stays on screen until the replacement
  arrives; a reader at the end stays at the end, a reader mid-page keeps their place.

On open, the first page renders already scrolled to the newest text (before paint). Tests in
`player-history.test.mjs` drive a stand-in scrolling element through all three cases; the test
harness gained `useLayoutEffect`.

## Spare designs are briefed with the whole setting — 2026-09-05

Replacement sketches drifted from the story's world because their prompt carried only premise,
tone and art direction. The brief now includes the author's fixed facts, themes, hard and soft
canon, and the places people move through (capped per list), and the system prompt requires
clothing, fabrics, grooming, technology level and social register to fit that period, region and
tone. `SpareDesignTest` checks the brief carries canon, places and existing designs.

## Image call budget covers the whole generation — 2026-09-05

A real session logged pose edits and cards failing with "image response exceeded the total read
deadline" after "RST_STREAM received" retries. The proxy answers such requests with headers at
once and trickles keep-alive bytes while the model works, so the whole generation happens in the
body phase; the watchdog added in the second audit round capped that phase at
`image.timeout-seconds` (180 s) counted from the start of the body, which is shorter than a
sprite-sized reference edit routinely takes. Before the watchdog the body phase had no limit at
all, so this was a regression. `image.timeout-seconds` is now the budget for the call as a whole,
counted from the request, and its default is 600 s; `idle-timeout-seconds` (60 s) still catches a
connection that goes silent. `ImageBodyDeadlineTest` drives a trickling stand-in through both
outcomes. A live config that pins `timeout-seconds: 180` keeps failing until it is raised or the
line removed. The `RST_STREAM` failures are the proxy (nginx/h2) resetting the stream when its
own upstream gave up; they stay retryable.

## Longer, harder stories: turns, opposition, momentum — 2026-09-05

A full play-through (save 15fec75d) showed the two failure modes the owner described: arc 1 was
four beats, two of them closed in a single scene, and the continuation re-ran arc 1's ending as
procedure -- seventeen scenes of "confirm, file, notify" inside one beat, with one NPC repeating
"其实不想让事情变大" scene after scene. The generation logic changed on four fronts:

- **Beats are events with a turn.** `StoryBeat.turn` (nullable for older saves) says what is
  irreversibly different once a beat lands; the compiler and the continuation must supply it,
  and both are validated for a minimum count (`StoryCompiler.MIN_BEATS` 5,
  `ArcContinuationService.MIN_BEATS` 4; the prompts ask for 8-12 and 7-10). The compiler prompt
  now treats the outline as a seed: mandatory opposition with its own plan, invented allies and
  places, a plot shape (misleading discovery, escalation, midpoint reversal, false victory or
  loss, darkest point, climax decided by the player), 3-6 NPCs each with a reason to stand in the
  way. Chores (verify, file, collect, notify) are not beats.
- **Beats take at least two scenes and are pushed to land.** `StateReducer` refuses to complete
  the current beat in its own first scene. The scene brief carries `Prompts.beatPacing`: first
  scene (open, do not complete), developing (propose `completeBeat` when the turn lands), or
  overdue after three scenes (reach the turn now or set up the obstacle that forces it).
- **Momentum and no repeated lines.** The scene prompt requires every scene to change something
  the player can feel and to escalate; recording or re-checking known facts is not a scene.
  `SceneGenerator.repeatedLine` compares each NPC line with that speaker's lines in the recent
  committed scenes and sends back a scene where a long run (11 CJK or 40 Latin characters) is
  shared -- a repeated sentence, not a repeated word.
- **A continuation is a bigger chapter.** The arc prompt now asks for a new inciting event that
  breaks the last arc's status quo, escalation, up to three new named characters and places, two
  reversals, a hard loss and a player-decided climax, and it receives `renderPlayedBeats`, the
  full list of beats from every arc so far, marked as finished. The mock continuation grew to
  four beats with turns.

`NarrativeDriveTest` covers the reducer rule, the repetition check (and its non-triggers), the
pacing brief, the continuation brief and the minimum-beat validation.

## Access key and split deployment — 2026-09-06

The game was loopback-only by design. To run it on the internet it needed a door with a key,
and a front end that can live on a different origin (a CDN) from the backend.

- **One key, one header.** `genvn.access-key` (empty = open, the local default). `AccessGate`
  holds it; `AccessKeyFilter` checks the `X-Genvn-Key` header on every `/api` request and answers
  401 with `access_key_required` / `access_key_invalid` as JSON. Comparisons are constant-time.
  `GET /api/access` is the one endpoint outside the gate: it reports `required` and whether the
  presented key is `granted`, so the front end can show the key screen before any other call.
- **Pictures without headers.** An `<img>` cannot send the header. `AssetController.status`
  appends `&t=<token>` to picture URLs on a locked server, where the token is HMAC-SHA256 of the
  session id under the key (32 hex chars). The filter accepts it on `GET /api/assets/{sid}/{aid}`
  only, for that session only. The key itself never appears in a URL; a leaked token opens one
  save's pictures and nothing else.
- **CORS before the key.** Both are servlet filters (`AccessConfig`), CORS first, so a refused
  cross-origin call still carries the headers the page needs to read the 401 instead of a blank
  network error, and preflights (which carry no custom headers by design) are answered without
  the key. Loopback origins are always allowed; `genvn.allowed-origins` adds the CDN origin
  (patterns such as `https://*.pages.dev` work). Preflight answers are cached for an hour, since
  the key header makes every call preflighted.
- **Front end.** `API_BASE` is baked in at build time from `genvn.config.json` `apiBase` (or
  `GENVN_API_BASE`), through a Vite `define`; empty keeps the same-origin `/api` behaviour, so the
  dev proxy is unchanged. `api.ts` adds the header from `localStorage`, resolves picture URLs
  against the base, and wakes `onAccessDenied` listeners on a 401 for want of a key. `App` asks
  `/api/access` before anything else and shows `AccessGate` (a key screen on the setup scene) when
  required, or again when a later call is refused -- the game stays mounted underneath.
  `frontend/tools/package-frontend.sh --api https://api.example.com` builds and archives `dist/`.
- **Why not cookies.** A cookie set by the backend from a CDN page is a third-party cookie, which
  Safari drops and other browsers partition; header + signed picture URLs work everywhere and keep
  the browser's HTTP cache for pictures.

`AccessGateTest` runs the real filters in front of the real controllers: open gate unchanged,
refusals with CORS headers, the key-free probe and preflight, per-save tokens, origin list
normalisation. `access.test.mjs` covers the baked-in origin, the header on every request, the
listener on 401 and the key screen's keep/drop/unreachable behaviour. App tests now flush once
after the first render, since the setup screen appears only after the access probe answers.

## Branching saves: rewind to a former choice (2026-09-12)

Each save is a directory `data/sessions/<id>/session.json` with an append-only scene tree beside
it (`nodes/<nodeId>.json`, content-addressed `stories/<hash>.json`). A node is one scene; an
edge is a choice. Node ids are scene ids, minted from a counter that never goes backwards, so
they are never reused.

On every commit the engine writes a visited node (scene, state snapshot, sealed dice). Finished
unused speculative branches are kept as unvisited children keyed `parent__choice__outcome`
instead of being dropped; in-flight ones are still cancelled. `POST .../nodes/{id}/rewind`
moves the head to a visited node without re-rolling. Re-taking the same choice restores that
visited child; taking a different one adopts the retained candidate through `commitScene`.
`stateVersion` still advances so a stale page 409s.

Legacy single-file saves still load; the next save migrates them. A migrated save can rewind
to the scene it was on when upgraded, then grows the tree from there. Dice stay sealed per
node: the story changes by choosing differently.

`SaveTreeRewindTest` covers restore-without-regeneration and unused-branch adoption.
History entries expose `restorable`; the 剧情回顾 dialog offers **从这里重新选择**.

## Scene-tree audit fixes, settings, player card (2026-09-12)

A review of the rewind save format found several ways a crash or a branch change could
corrupt the tree or leak art across routes. Those are closed; the menu also grew a live
settings editor.

- **Node ids are never reused.** Loading a save raises `sceneCounter` past every `scene_NNN`
  already on disk. A commit refuses to overwrite a visited node and mints the next free id
  instead, so a crash between the node write and `session.json` cannot parent a scene to itself.
- **Late continuation updates the tree node**, not only `session.json`, so rewinding to a
  chapter ending still offers 「继续下一章」.
- **`pendingArc` is stored on the node** and restored on rewind. In-flight planners key by a
  process-local epoch (bumped on rewind), so a plan from route A cannot land on route B or block
  B from planning. Walking forward on the same path still accepts a late outline.
- **Character pictures follow appearance.** Rewind drops NPC art that no longer belongs to the
  restored story. Adopting a named role keeps a READY spare's prompt and file; a later route
  whose appearance text no longer matches replaces that file instead of showing the old face.
- **A missing rewind point stays unhealthy.** `repository.save` can no longer flip `saveHealthy`
  to true when the current node file is absent; `require()` retries the write.
- **Delete leftover-first.** When a directory save and a leftover `<id>.json` coexist, the
  leftover is removed before the directory is renamed. A leftover that cannot go leaves the
  latest progress in place.
- **Rewind does not regenerate saved children.** Prefetch skips a choice that already has a
  tree child. Nested prepared ids that would exceed 160 characters are hashed (`p_` + 16 hex).
- **剧情回顾** disables rewind and explains why while a submitted choice is still on screen.
- **设置** (setup masthead and in-game menu) edits every key in `application.yml`. Secrets are
  never returned. Bind address, port and data-dir still need a restart; LLM, image, speculation,
  continuation, access key and CORS take effect immediately via live property beans and
  reloadable clients.
- **我的角色** shows the player card under the name.

`SaveTreeIntegrityTest`, `RuntimeSettingsTest`, `SceneTreeStoreTest` nested-id coverage and
the mixed-layout delete case lock these in.

## UI and generation language (2026-09-12)

`genvn.language` (`zh` default, or `en`) is a live setting. The start-screen masthead, access
key screen and settings dialog switch UI chrome immediately; `PUT /api/settings` writes the
value so later story compilation, scenes, continuation and spare designs prompt the model
to write player-visible prose in that language (JSON keys stay English). Mock replies follow
the same switch. Compile-log milestones are bilingual. Already-written scenes keep the language
they were generated in.

`GET /api/access` (no key) and `GET /api/config` both expose `language`. `UiLanguageTest` and
the settings hot-reload path cover normalisation (`English` → `en`).

---

## Rewind-safe character artwork (2026-09-12)

Character assets now carry a SHA-256 appearance key built from the subject ID, normalized effective
visual description, and the save's fixed art style. Cards and poses inherit the base's identity;
changed names, personality text, prompt phrasing, and assigning a prepared design do not redraw it.
Logical asset IDs remain stable. The manifest retains every appearance lifecycle, selects the
current route's records, and keeps immutable publication entries for previously issued image URLs.
PNG files use lifecycle and publication sequence names; rewinding never deletes or overwrites them.

Queue admission, backoff, subscriptions, reference dependencies and callbacks bind to the lifecycle
version. Unsent work is deactivated on route changes, while sent calls may finish only into their
original archive. Publication sequences are reserved with the durable attempt before sending a
request, so a crash between writing a PNG and saving its manifest cannot overwrite that file.

Opening one save, restoring a scene, or committing a new scene checks the player, present NPCs,
selected background, and dialogue/action poses. Missing required art enters the existing queue only
when generation is enabled. Repeated checks reuse READY files and pending work, preserve failed
attempt limits, and leave the existing manual retry control available. Startup save-dice migration
does not trigger artwork for every save. Legacy base prompts are matched to current or historical
story snapshots once; dependent art inherits the verified reference identity. Unknown old records
and files stay archived without bulk migration.

Focused regressions cover unchanged-image reuse, A/B/A with restart, prepared-design adoption,
legacy attribution, current-only missing repair, disabled generation, late callbacks, exact
reference bytes, immutable URL resolution, bounded retries, full queues, and the public save-tree
rewind path. Validation uses temporary data and mock image/text providers.

## 重塑剧情: rewriting the story from a scene the player refused (2026-09-15)

Rewinding changes which path is current. It cannot change what the story is *about* — the compiled
framework is fixed at creation and only ever replaced wholesale when a new arc begins. A player who
dislikes where the plot is going had no way to say so.

`POST /api/sessions/{id}/nodes/{nodeId}/restructure` takes free text and folds it into the framework
itself, from that scene onward. It answers 202 with a job (`GET /api/session-restructures/{jobId}`)
because it is two model calls; the UI shows the same progress bar story creation uses.

- **The request outranks the canon.** `STORY_RESTRUCTURE` re-emits `authorCanonFacts` and the whole
  bible corrected, and the beats still to come. Where the request contradicts author canon or hard
  canon, the canon is what gives way — that is the feature, not a side effect.
- **What has been played is kept.** The model returns only the remaining beats; the engine splices
  `completed beats (in spine order) ++ the new tail`, so `completedBeats` keeps resolving for
  `StateReducer` and `StoryProgress`. `currentBeatId` becomes the first new beat. Every character id
  with a `CharacterState` and every visited location id must still be in the revised bible — the
  validator sends a revision back for dropping one, and `apply` merges the old profile in if one
  slips through anyway rather than breaking the save over it.
- **Art identity is not plot.** `playerVisual`, `artStyle`, `preparedVisuals` and `encounteredNpcs`
  are carried over untouched, and `authorCanon.originalOutline` stays as provenance.
- **The instruction is never stored.** It reaches the two model calls and nothing else: not
  `session.json`, not a node, not the story blob, not the job view, not the idempotency fingerprint
  (which is `(sessionId, nodeId, expectedStateVersion)`). The only trace a rewrite leaves is the
  revised framework. `StoryRestructurePrivacyTest` greps the whole save to prove it, and
  `MockLlmClient` paraphrases rather than pasting the player's words into canon.
- **Same three phases as `choose`.** The monitor is never held across a model call. Phase 3 restores
  the anchor's parent — which is also what erases the refused scene from `recentScenes` — then
  assigns the revised story (after the restore, since `restoreNode` reloads the story from the
  node's hash) and commits through `commitScene`. `GameSession.restructuringJobId` makes a
  concurrent choose/roll/rewind 409 `restructure_in_progress`; one save rewrites one story at a time.
- **The refused take survives.** It stays a visited sibling under the same parent with the *old*
  story hash, so rewinding to it restores the old framework whole. Re-choosing from the parent lands
  on the rewrite, which sorts later. The die the player already saw is reused, so a rewrite never
  flips success into failure.
- **The opening too.** `SceneNode.preState` stores the state the opening was written against — only
  on the root, since every other node reads its parent's. Saves written before this refuse the
  opening with a message and can still rewrite any later scene.
- `CreationMilestones.model` now takes an explicit `[floor, ceiling]` window; the two existing call
  sites keep their ladders. A restructure uses 10→55 for the framework and 60→92 for the scene.
- Config: `llm.reasoning.story-restructure` (properties class, template, `ConfigFileTest.KNOWN_KEYS`,
  `RuntimeSettings`, settings labels).
- UI: 重塑剧情 in the stage controls and 从这里重塑剧情 on every restorable 剧情回顾 entry; a dialog
  that becomes its own progress panel and cannot be closed mid-rewrite.

`StoryRestructureTest`, `StoryRestructureConflictTest`, `StoryRestructurePrivacyTest`,
`StoryRestructureApiTest` and `story/StoryRestructurePlannerTest` cover the splice, the guards, the
privacy rule and the HTTP surface; `tests/restructure.test.mjs` covers the dialog and the job flow.
Verified end to end in mock mode on a scratch data directory: framework revised, refused take still
rewindable, and no trace of the instruction anywhere under `data/`.


## Rewrite continuity and image restart recovery (2026-09-16)

A repeated rewrite now takes its framework from the selected scene's story snapshot. The parent
still supplies the pre-scene gameplay state and read history, and the original die stays binding.
This retains earlier revisions without admitting the rejected take as past dialogue or importing
later sibling revisions. Whole-bible rewrites have a dedicated complete briefing containing every
registered and encountered NPC and location, including visual descriptions and NPC goals; ordinary
scene briefings retain their existing profile limits.

Interrupted progress reading now exposes a GET-only recovery action independently of the server's
running state. It also covers READY jobs whose revised save could not be loaded, and concurrent retry
clicks share one reader. The dialog's Escape handler checks the current running state, preventing an
editable dialog from retaining permission to close after submission.

A cold image pipeline retains previously queued current-version character work across status reads
and restarts. Once the restored story verifies appearance identities, that work resumes exactly once
through normal bounded admission, dependency handling and counted retries. Archived versions,
withdrawn routes and plans that were never admitted do not acquire a generation grant.

Validation: 311 backend tests and 87 frontend regression tests, backend bootJar, and the TypeScript /
Vite production build passed. New regressions cover repeated opening and non-opening rewrites,
older sibling selection, 45 NPCs / 70 locations, queued future NPCs, a full image queue, reference
dependencies, archived routes, exhausted attempts and disabled image generation. An isolated browser
fixture with the real rewrite hook and dialog verified Escape protection and three failed progress
reads followed by successful recovery: one POST, four GETs and one save load. Model responses were
simulated and saves were temporary; no real provider or running game service was used.
