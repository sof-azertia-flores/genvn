# Next window

Ordered. Stop when the window runs out; each item is independently shippable.

---

### 1. Call the real image API once, with a budget, and look at the pictures
Everything is wired and verified against a local stand-in; the live OpenAI Images API has not been
called from this machine. Put an image key in `backend/config/application.yml`, set
`image.arc-budget: 6`, play one session, and *look* at one background and one base portrait plus
one expression variant. Judge consistency with your eyes, not by the prompt. Then tune
`VisualPlanner`'s style/prompt templates against what you saw.

### 2. Play three real-model sessions and fix what the prompts get wrong
Now that the opening proposes `completeBeat` for beat 1 (seen with the real text model), the scene
prompt needs a rule that the opening never closes a beat. Also watch for escalating scale, invented
NPCs and written-for-the-player feelings. Tune `prompt/Prompts.java` only.

### 3. Picture requests that a scene makes should also pull the *next* beat's plan forward
`AssetCoordinator.onSceneCommitted` already queues anything planned for the current and next beat.
Add: when the story leaves a location for good, drop the priority of its remaining variants so a
turning story does not keep paying for rooms it will not revisit.

### 4. Scene-level streaming of prose
Long generations still show a spinner until the whole SceneBundle validates. Stream narration
blocks as they arrive, mark them provisional, and hold choices/commit until validation passes.

### 5. Save/load polish
A "continue last session" button, delete from the UI, and prefetch-on-load for both prose and
pictures (the pipeline already recovers the manifest; the branch cache is still cold after load).

### 6. Player journal
Canon, characters met, places seen, open and closed threads, and the scrollback that
`state.recentScenes` already carries -- shown only for what the player has actually reached.
