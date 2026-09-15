# genvn

A **local-first generative visual novel**: you write a short story, the engine compiles it into a
world, and then you *play* it — as a visual novel with a real TRPG dice runtime underneath.

The core loop it proves:

> your story → AI compiles a world → you play it as a VN → choices and dice change real state →
> the next scenes are generated **while you are still reading**

The one rule the whole design hangs on:

> **The LLM writes the story. The Runtime decides the facts.**
> The model may *propose* a state change. It can never *make* one. It never rolls a die.

Personal project. No accounts, no cloud, no telemetry. Binds to `127.0.0.1` by default.

---

## Requirements

- **Java 21+** (tested on Temurin 21). A jlink-trimmed runtime with no `jdk.random` module
  still starts: the dice fall back to `SecureRandom` and say so once in the log.
- **Node 20+** (tested on Node 26)
- No database. No API key required — it ships with a full offline mock.

## Run it

Two terminals.

**Backend** (port 8080):

```bash
cd genvn && ./run-backend.sh
```

**Frontend** (port 5180):

```bash
cd genvn && ./run-frontend.sh
```

Then open **http://localhost:5180**.

<sub>Equivalent long form: `cd backend && ./gradlew bootRun` and `cd frontend && npm install && npm run dev`.</sub>

## Packaging for another machine

```bash
backend/tools/package-backend.sh
```

Builds the runnable jar and writes `backend/dist/genvn-backend-<version>-<stamp>.tar.gz`: the jar,
the config template, `run.sh` / `run.bat`, the local image stand-in, a deployment note and a
manifest with SHA-256 sums. Your live `config/application.yml` (API keys) and `data/` (saves,
pictures) are left out unless you pass `--with-config` / `--with-data`; `--source` adds the source
tree so the bundle can be rebuilt. Unpack, fill in `config/application.yml`, run `./run.sh`.

## Public deployment: access key, split front end and backend

Locally nothing changes: no key, loopback only. To put the game on the internet you set one key
on the backend, tell it which front-end origin may call it, and bake the backend's address into
the front-end build.

**1. Backend** -- in `backend/config/application.yml`:

```yaml
server:
  address: 127.0.0.1        # keep loopback; a reverse proxy with HTTPS sits in front
  port: 8080
genvn:
  access-key: "a-long-random-string"      # the browser asks for this once, then sends it on every call
  allowed-origins: ["https://vn.example.com"]   # the front end's origin (no path); loopback is always allowed
  language: zh                            # zh Simplified Chinese or en; UI chrome and model prose
```

Generate a key with `openssl rand -base64 32`. Put a reverse proxy in front for TLS and forward
`/api` to `127.0.0.1:8080` with a long read timeout -- a choice can wait several minutes on the
model (nginx: `proxy_read_timeout 600s;`; Caddy: `reverse_proxy 127.0.0.1:8080 { transport http { response_header_timeout 10m } }`).
A CDN or WAF in front of the *backend* that cuts idle connections at ~100 s (Cloudflare's proxy
does) will 504 long generations: point the backend hostname straight at the server (DNS only), or
put only the front end behind it. `backend/tools/package-backend.sh` still bundles everything the
server needs; edit the config there.

**2. Front end** -- build with the backend origin baked in, then upload `frontend/dist/` to the CDN:

```bash
frontend/tools/package-frontend.sh --api https://api.example.com
```

or set `"apiBase": "https://api.example.com"` in `frontend/genvn.config.json` and run
`npm run build`. `GENVN_API_BASE` overrides both for CI. The output is plain static files with no
client-side routing: serve `index.html` uncached (or a short max-age) and `assets/*` cached for a
year. Any static host works (Cloudflare Pages, Vercel, Netlify, S3/OSS + CDN, GitHub Pages). When
one server hosts both halves under one domain, leave `apiBase` empty and let the proxy forward `/api`.

**3. In the browser** -- the first visit asks for the key; once the backend accepts it, the browser
keeps it in `localStorage` and sends it as the `X-Genvn-Key` header on every `/api` call. Changing
the key on the server makes the next call return 401 and the key screen reappears, with the game
still on screen. Picture URLs cannot carry a header, so on a locked server they carry a per-save
token derived from the key (`?t=`) that opens that save's pictures and nothing else; the key never
appears in a URL. `GET /api/access` is the only endpoint reachable without the key.

The key is transported in the clear over plain HTTP, so only ever expose the backend over HTTPS.

## Fastest demo (60 seconds)

1. Open http://localhost:5180 — the old-house story and the character **Alex** are pre-filled.
   Switch **中文 / English** on the masthead if you want the UI and later AI prose in English.
2. Click **让故事开始** / **Begin the story**. Logs and a progress bar follow the actual compilation stages,
   from the world and story framework to the opening scene. Click **步入故事** / **Enter the story** when it is ready.
3. Text appears character by character. Click the text box (or press **Space**) to reveal the whole
   line; the next click advances. Choices appear after the final line finishes typing.
4. Pick a choice with a **🎲 badge** — the *server* rolls a d20, adds your stat, compares to the DC,
   and shows you the arithmetic. A failure never dead-ends; it costs you something and moves on.
5. Open **幕后准备** / **Behind the scenes** to see parallel story and picture tasks. Candidate prose
   stays hidden; **调试详情** / **Debug details** opens the developer inspector when needed.
6. Open **我的角色** for the character sheet, inventory, relations and the continuity ledger.
   Characters present in the scene have fixed cards at the upper left; the active character's
   transparent sprite stands above the dialogue box. The old remaining-line counter is hidden.

Saves live in `backend/data/sessions/<id>/` (session.json plus a scene tree). They appear in
the **继续未完的故事** list on the start screen. Open **剧情回顾** and tap **从这里重新选择**
to return to an earlier scene; that scene's dice stay sealed, so the story changes by picking
a different choice, not by rolling again.

### First run

The first time a browser opens genvn it gets a short guide: a few pages on the start screen (what
this is, what the outline is for, what the talent points do, who rolls the dice, where the art style
comes from), then five bubbles over the stage the first time you enter a story. Skipping counts as
having seen it. The record lives in that browser's `localStorage` and nowhere else, so a different
browser or machine is treated as a genuine first visit, and **设置 → 重新观看新手引导** puts it back.

Six sample stories sit above the outline box, spanning Tang Chang'an, a Ming coastal garrison,
modern China, a medieval European abbey and modern Prague. Clicking one fills the whole form —
outline, name, background, traits, appearance and the fifteen talent points — so a first story is
one click away.

The art direction box ships filled in with a default house style (`demo_graphic.txt`, in English
under the English UI) rather than empty. Backdrops, sprites and character cards all follow it;
rewrite it or clear it whenever you want something else.

### 重塑剧情 — rewriting the plot you did not want

Rewinding lets you pick a different choice. **重塑剧情** changes what the story *is*. Press it in
the stage controls (or **从这里重塑剧情** on any scene in 剧情回顾), write in your own words what
you want instead, and the engine re-plans the framework around it: the author canon, the story
bible and every beat still ahead. Where your request contradicts the canon, **the canon is
rewritten** — your sentence becomes part of the story's law rather than a note bolted onto it. The
scene you refused is then written again, prose and choices, against the new framework, and
everything after it follows from the new beats as you play them.

It runs as a background job with a progress bar, because it is two model calls. While it runs the
save takes no choices.

What it never does: erase what you have already read (the meaning of past scenes may change, the
events do not), delete a character you have met or a place you have been, re-roll a die you have
already seen, or keep your instruction anywhere afterwards. The text is sent to the model and
discarded — the only record is the revised story. The scene you refused stays in the scene tree, so
**从这里重新选择** on it restores the old framework whole if you change your mind.

## Configuration

All settings live in **two config files**. There are no environment variables to remember, and
nothing to pass on the command line.

| File | Controls |
| --- | --- |
| `backend/config/application.yml` | LLM provider, bind address/port, saves, speculation, continuation (your live file, gitignored; created from `application.example.yml` on first run) |
| `frontend/genvn.config.json` | Dev server port/host and the backend URL the `/api` proxy points at |

Both are plain, fully commented files. Edit and restart the relevant process — every key is
optional, and deleting one restores the built-in default. `backend/config/application.yml` is
loaded by Spring Boot from `./config/` at startup and overrides the packaged fallbacks in
`src/main/resources/application.properties`, which you should not need to touch.

### Using a real LLM

Mock mode is the default and needs no network. To use a real model, put a key in
`backend/config/application.yml` — any OpenAI-compatible `/chat/completions` endpoint works
(OpenAI, Groq, Together, llama.cpp server, LM Studio, Ollama's OpenAI shim, …):

```yaml
llm:
  api-key: "sk-..."            # empty  =>  mock mode
  base-url: https://api.openai.com/v1   # endpoint root, no /chat/completions
  model: gpt-4o-mini
  temperature: 0.85
  json-mode: true              # false for providers that reject response_format
  stream: true                 # SSE; prevents gateway 504s on long generations
  force-mock: false            # true to force the mock even with a key present
  timeout-seconds: 300         # total budget per generation, streaming included
  idle-timeout-seconds: 120    # give up (and retry) when the response goes silent this long
  reasoning:                   # reasoning_effort for gpt-5 / o-series models; empty = not sent
    effort: ""                 # default for every call: none|minimal|low|medium|high|xhigh
    story-compile: ""          # per-purpose overrides; also scene-generate, arc-continue,
    choice-probabilities: ""   # choice-probabilities (ranking) and spare-designs (sketching)
```

> **Seeing `504` from your provider?** Story compilation writes a few thousand tokens in one
> go, which can outlast the idle timeout of a gateway (Cloudflare and friends) sitting in front
> of the model even though the model is fine. `stream: true` (the default) is the fix — a plain
> API test tool never hits this because it sends short requests.

Then restart the backend. The start screen and `GET /api/config` always tell you which client is
live — and, in mock mode, exactly which setting put you there.

> Your key lives in `backend/config/application.yml`, which is **gitignored**. The committed
> template is `backend/config/application.example.yml` (empty key — a test asserts it stays that
> way); `run-backend.sh` copies it into place on first run, so a fresh clone starts in mock mode.

### Everything else

```yaml
server:
  address: 127.0.0.1     # 0.0.0.0 to reach it from your LAN. Opt-in only: there is no auth.
  port: 8080             # if you change this, update frontend/genvn.config.json too

genvn:
  data-dir: data         # saves go to <data-dir>/sessions/<id>/
  speculation:
    enabled: true        # false to save tokens on a metered API
    max-branches: 6      # prefetch budget per scene
    threads: 4
  continuation:
    enabled: true        # plan a follow-up arc when the spine runs low
    threshold: 0.6
```

```jsonc
// frontend/genvn.config.json
{
  "devServerPort": 5180,
  "devServerHost": "127.0.0.1",
  "backend": "http://127.0.0.1:8080"   // must match server.address/port above
}
```

> **Cost note:** with speculation on, a scene with three choices (two of them checks) costs up to
> five generations instead of one. That is the point of the feature, but on a paid API set
> `max-branches: 2` or `enabled: false`.

### Pictures: real backgrounds and portraits

Off by default -- a fresh checkout never spends money on art. Turn on in `backend/config/application.yml`:

```yaml
image:
  enabled: true
  provider: openai              # OpenAI Images API (generations + edits)
  api-key: "sk-..."             # its own key; the text provider's key is never reused
  base-url: https://api.openai.com/v1
  model: gpt-image-1            # needs transparent PNG and reference-image edits for character art
  timeout-seconds: 600          # whole call, headers and body; sprite edits can take minutes
  concurrency: 4                # image workers, separate from the text pool
  first-batch-budget: 12        # automatic first batch per story arc
  arc-budget: 0                 # optional cap per story arc; 0 = none (workers, dedup, max-attempts still apply)
  plan:
    locations: 3                # backgrounds pre-rendered at compile time, opening location first
    characters: 3               # transparent base sprite + one permanent card per character
    expressions: worried,suspicious   # transparent pose/expression variants edited from the base
```

What you get: the moment the Story Bible / Spine validates, a visual plan is derived from it (no extra
model call, so the opening never waits) and the first batch starts rendering **while the opening text
is still being written**. Each character has a transparent base sprite and transparent pose/expression
variants. One permanent character card, with a frame and a background informed by the character's
profile, is edited from that same base. All poses share the card; a new pose never buys a new card.
Sprites are always PNG with a real alpha channel; opaque or empty results are rejected. Models that
cannot provide transparency or reference-image edits pause the affected character tasks while the
story continues. Scenes reference pictures by
validated id -- the runtime resolves them, the model only hints. Files land in
`data/assets/<session>/` beside a `manifest.json`, are served from `/api/assets/...`, and are reused
across scenes, branches and restarts. **Text and dice never wait for a picture**: placeholders show
until a file is ready, then it fades in without resetting the dialogue. If you configure an
`arc-budget` and it is spent, the game keeps playing on placeholders; **幕后准备 → 画面素材** shows
status and any pause reason. A `manifest.json` that cannot be read is reported there too, and the
story continues without new pictures rather than failing.

Pictures requested by a newly committed scene are attached to that scene immediately, even while
queued. A missing base portrait is planned before its pose edit or permanent card. PNG and JPEG work with the
bundled decoder; an unsupported output format is paused before a model request is sent. If the
asset manifest cannot be saved, new paid requests pause until storage recovers, preserving the
attempt budget across restarts. Opening a save, restoring a visited scene, and committing a scene
check its required artwork: existing files are reused, missing pictures are queued when image
generation is enabled, and failed pictures retain the manual retry entry.

Character pictures use an explicit appearance identity derived from character ID, normalized
visual description and the save's fixed art direction. Name, personality, prose and prompt wording
do not force a redraw. A rewind selects the matching archived version of each base, card and pose;
other routes' records and PNGs remain on disk. Publication URLs include an immutable version that
continues to serve the original bytes, while unversioned URLs alias the current image without
immutable caching. Older manifests are attributed lazily using base-generation information and
story snapshots; unverifiable files remain archived and only needed current-scene art is rebuilt.

> A `/chat/completions` endpoint does not imply an `/images/*` endpoint -- check your provider. The
> adapter has been verified against a local stand-in that speaks the OpenAI Images API (real PNGs,
> both response shapes, multipart edits, 429/400 classification). These checks do not establish
> live-provider image quality or character consistency; assess those with your configured provider.

#### Try the picture pipeline with no key at all

`backend/tools/image_stub.py` is a tiny local server that speaks the OpenAI Images API and returns
real (plain, gradient) PNGs. Point the production adapter at it to watch the whole chain -- plan,
queue, two workers, edits for expression variants, files on disk, reuse, restart -- without spending
anything:

```bash
python3 backend/tools/image_stub.py
```

```bash
cd backend && ./gradlew bootRun --args='--llm.force-mock=true --genvn.data-dir=data-demo --image.enabled=true --image.api-key=stub --image.base-url=http://127.0.0.1:9911/v1 --image.model=gpt-image-1'
```

Then play from the frontend as usual and open **幕后准备 → 画面素材**. The stand-in returns transparent
PNGs for sprites and opaque cards for reference edits. Saves and pictures go to
`backend/data-demo/`, never near your real saves.

## Mock mode

`MockLlmClient` is a deterministic template narrator that goes through **exactly the same JSON
contract** as a real provider — same schema, same parsing, same validation, same repair loop. It
reads the canonical state, the beat, the choice you took and the outcome the engine decided, and it
writes failures fail-forward. It is not clever, and it will repeat phrasings over a long session,
but it makes the entire product demonstrable with no key and no network.

## API

The browser never owns game state. It posts a choice and is told what became true.

| Method | Path | |
| --- | --- | --- |
| `GET` | `/api/access` | Whether a key is required, whether the `X-Genvn-Key` header sent is right, and the current UI/generation language (`zh` or `en`); the one call that needs no key |
| `GET` | `/api/config` | Which LLM client is live, plus `language` |
| `GET` | `/api/settings` | Live `application.yml` fields (secrets are never returned) |
| `PUT` | `/api/settings` | Write selected keys; most take effect immediately, bind address/port/data-dir still need a restart |
| `POST` | `/api/sessions` | Compile a story, build state, return the first scene |
| `POST` | `/api/session-creations` | Start an asynchronous opening job; optional UUID `Idempotency-Key` prevents duplicate compilation |
| `GET` | `/api/session-creations/{id}` | Milestone logs in the configured language, progress, status and the completed session id |
| `GET` | `/api/sessions` | List local saves |
| `GET` | `/api/sessions/{id}` | Current story + state + scene |
| `DELETE` | `/api/sessions/{id}` | Delete a save |
| `POST` | `/api/sessions/{id}/choices/{choiceId}/roll` | **Reveals the server's die**, cast the moment the scene became current; returns it at once, persisted and idempotent |
| `POST` | `/api/sessions/{id}/choices/{choiceId}` | Commits the choice (using the die already cast), returns roll + state + next scene |
| `POST` | `/api/sessions/{id}/nodes/{nodeId}/rewind` | Restore a visited scene as the head. Dice on that scene stay sealed; pick a different choice to change the story |
| `POST` | `/api/sessions/{id}/nodes/{nodeId}/restructure` | Rewrite the framework and this scene from your own `instruction`; 202 + a job id. Optional UUID `Idempotency-Key` prevents a duplicate rewrite |
| `GET` | `/api/session-restructures/{jobId}` | Rewrite progress, milestone logs and status. Never echoes the instruction |
| `GET` | `/api/sessions/{id}/assets` | Picture status: plan, queue, budget, timings. Lock-free; safe to poll |
| `POST` | `/api/sessions/{id}/assets/{assetId}/retry` | Retry one failed image; deduplicated, budgeted, and persisted |
| `GET` | `/api/sessions/{id}/history` | Read canon through `throughSceneId` and `throughBlockIndex`; optional `beforeSceneId`/`limit` pagination |
| `GET` | `/api/sessions/{id}/tasks` | Current scene's speculative task states and parallel slots, without candidate prose |
| `GET` | `/api/assets/{sessionId}/{assetId}` | Current picture alias; `?v=` selects an immutable publication, including archived art. On a locked server `t=` carries that save's token |
| `GET` | `/api/sessions/{id}/debug` | Everything the dev inspector shows |

## Tests

```bash
cd backend && ./gradlew test
```

302 backend tests cover branch isolation, dice routing, invalid generation, scene/version conflicts,
concurrent file persistence, save failure recovery, retained dialogue, late arc continuation, the
picture pipeline (planning, one-request-per-picture, bounded concurrency, budget, retries, restart
reuse, forget), image/text overlap, real branch concurrency, the OpenAI Images adapter against a
local HTTP double, and that a model call never holds the session monitor. Regression coverage also
includes HTTP/SSE cancellation, delete/commit races, durable image budgets, new-arc scheduling, and
binding newly requested pictures to the scene that requested them, plus idle-connection timeouts,
unreadable manifests, uncapped budgets, retry policy, bounded model-supplied ids, the access
key gate (refusals with CORS headers, the key-free probe and preflight, per-save picture tokens),
scene-tree crash recovery, rewind prefetch skip, and live settings reload. Story restructuring is
covered for the beat splice, kept cast and places, the reused die, the refused take surviving as a
rewindable sibling, the opening's stored pre-state, every concurrent mutation conflicting, and a
whole-save scan proving the player's instruction is never persisted.
98 frontend regression tests run with `cd frontend && node --test tests/*.test.mjs`, including
the first-run guide (per-browser records, version and damaged-value handling, skip-counts-as-seen,
the settings reset), the sample stories and their legal talent spreads, the default art brief in
both languages, and full zh/en key parity, plus
bounded choice/compilation recovery, stale-image protection, retries, preload selection, grapheme
typing, task-dialog behavior, the baked-in backend origin, the key header and the key screen. The backend also verifies transparency, one card per character,
reference dependencies, true compilation milestones and idempotent concurrent opening requests.
See `IMPLEMENTATION_NOTES.md` for verification and remaining limitations.

Choice requests must include the scene and state version the player saw:

```json
{"expectedSceneId":"scene_003","expectedStateVersion":4}
```

An outdated choice returns HTTP 409 (`scene_conflict`) before any dice are rolled. The UI reloads
the current scene so the player can confirm their next action. An accepted choice that is still
being generated instead returns `choice_resolving`; session responses expose `resolvingChoiceId`,
and the UI waits for that operation with bounded polling. It does not keep re-submitting the die
or choice. This in-progress marker is not persisted: after a restart, a saved pending die can resume.
Session responses also expose
`saveHealthy`: a false value keeps a visible warning until a later save succeeds. Restart the
backend and refresh the frontend together after upgrading, so both use this request contract.

### Art direction, player pictures and history

With images enabled, the opening form accepts a free-text art direction (up to 6000 characters),
applied consistently to backgrounds, character cards, transparent poses and later arcs. The player's
appearance field (up to 2000 characters) defines their visual identity independently from NPCs.
The player has one permanent card, a transparent base and reusable talking/action poses. Their card
stays first in the roster. Selected dialogue appears immediately and remains readable before the next
scene; actions appear after the choice resolves. History is limited to the reader's completed text.

### Retrying transparent images

GPT Image 2 supports transparent output in preview; see the [official image guide](https://developers.openai.com/api/docs/guides/image-generation#customize-image-output).
The adapter requests `background=transparent` and PNG for stage sprites on both generation and edit
endpoints. A generation prompt carries the art direction and ends with the cutout requirement; a
pose/expression **edit** sends one short, positive sentence ("Same character as the reference; keep
the face, outfit, hairstyle and art style; worried expression and a natural worried pose; transparent
background."), with no art-direction text and no appended output block. That shape was bisected
against a live gpt-image-2 route where the alpha channel is evidently decided from the prompt rather
than enforced by the parameter: minimal prompts came back RGBA four times out of four, a longer
sentence with negations was a coin flip, and anything carrying the art direction or the appended
block came back opaque every time. Opaque or empty output is still rejected and retried through the
bounded scheduler; nothing converts a painted background into transparency.

In **幕后准备 → 画面素材**, failed images have **重新生成**. Each click grants one further attempt,
including failures previously marked non-retryable. Successful cards/images are reused; in-flight
requests are deduplicated. Restoring a failed base automatically resumes its waiting card/poses,
subject to the remaining budget. Browser download retries do not call the image model.


### Reading ahead and spare character art

First-round scene branches still run in parallel on isolated story/state snapshots. After that
round finishes, a separate structured text call estimates choice preferences from the player's
last twelve actual choices. The runtime normalizes each choice group and ranks
`P(current choice) × P(next choice | first-round branch)`. At most **four second-round scene
requests** run; a checked choice is one job, for the outcome its sealed die will produce. This is a scheduling estimate, never a die roll or a change to gameplay. Invalid estimates
skip the second round, leaving ordinary generation available. Choosing a path cancels its
siblings. A matching second-round snapshot is promoted into the next first-round cache; changed
state, deleted sessions and uncommitted future arcs invalidate it. The task window shows these
phases and probabilities without exposing future prose.

Images use four workers independently of the text pool. Once foreground work drains, spare
workers may render the compiler's small pool of **appearance-only** NPC designs: a transparent
base sprite and one permanent card each. Designs contain no name, role, personality, relationships
or plot. When an `arc-budget` is configured, four of its attempts stay reserved for later scene
needs. A scene normally adopts a suitable prepared appearance, creates its identity at that time,
and may nominate it for the Bible as a recurring NPC. The assignment becomes real only when that
scene is committed, and the locally saved images are reused. A design belongs to exactly one
person: once adopted it leaves the pool, its sprite and card are relabelled as that person's, a
new person may not copy its appearance under another id (the scene is sent back for repair), and a
background call sketches a replacement so `genvn.spare-designs` (default 3) stay on hand.

The opening window reports individual transport, structure, world, cast, beat, state and scene
validation milestones in the configured language (`genvn.language`: `zh` or `en`). Its display eases toward reported progress and never fabricates
completion while waiting for a model. Liquid Glass inspired controls, cards and dialogs support
small screens and reduced motion/transparency preferences.
