package com.genvn.asset;

import com.genvn.config.ImageProperties;
import com.genvn.game.GameSession;
import com.genvn.narrative.CharacterPresence;
import com.genvn.narrative.SceneBundle;
import com.genvn.narrative.SceneLocation;
import com.genvn.story.StoryBeat;
import com.genvn.story.CompiledStory;
import com.genvn.story.NpcProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The four moments the story loop talks to the picture pipeline. SessionService calls these
 * and knows nothing else about images. Every call is cheap and non-blocking; none of them can
 * fail the game, and none of them runs inside a speculative branch.
 */
@Service
public class AssetCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AssetCoordinator.class);
    private static final int MAX_REQUESTS_PER_SCENE = 2;

    private final AssetPipeline pipeline;
    private final VisualPlanner planner;
    private final ImageProperties props;

    public AssetCoordinator(AssetPipeline pipeline, VisualPlanner planner, ImageProperties props) {
        this.pipeline = pipeline;
        this.planner = planner;
        this.props = props;
    }

    /** For callers wired without a picture pipeline (older tests): every hook is a no-op. */
    public static AssetCoordinator disabled() {
        return new AssetCoordinator(null, null, null);
    }

    public boolean active() {
        return pipeline != null;
    }

    /** Frame is ready: plan the whole arc's pictures and start the first batch NOW, before any prose. */
    public void planForSession(GameSession session) {
        if (!active()) return;
        try {
            reconcileCharacterVersions(session, List::of);
            var plan = planner.plan(session.story, session.state,
                    pipeline.snapshot(session.id).map(m -> m.style).orElse(null));
            pipeline.adopt(session.id, plan);
        } catch (RuntimeException e) {
            log.warn("Assets {}: planning failed, play continues with placeholders: {}", session.id, e.toString());
        }
    }

    /**
     * Called after the chosen scene's location is registered, before storing the canonical scene.
     * A new request cannot resolve against the pre-generation manifest: register its stable id
     * now and put that id on this scene even while the picture is still queued. No image is awaited.
     * Speculative candidates never call this hook and cannot enqueue pictures.
     */
    public SceneBundle prepareSceneAssets(GameSession session, SceneBundle scene) {
        if (!active()) return scene;
        try {
            reconcileCharacterVersions(session, List::of);
            Optional<AssetManifest> before = pipeline.snapshot(session.id);
            String style = before.map(m -> m.style).filter(v -> !v.isBlank()).orElse(VisualPlanner.styleFor(session.story));
            String styleKey = before.map(m -> m.styleKey).orElse(VisualPlanner.styleKey(style));
            Map<String, AssetSpec> requested = new LinkedHashMap<>();
            for (AssetRequest request : scene.assetRequests()) {
                if (requested.size() >= MAX_REQUESTS_PER_SCENE) break;
                AssetSpec spec = planner.fromRequest(session.story, request, style, styleKey, scene.beatId());
                if (spec != null) {
                    // A subject has one visible variant in a scene; ignore contradictory duplicates.
                    String key = (spec.kind() == AssetKind.BACKGROUND ? "background/" : "portrait/") + spec.subjectId();
                    requested.putIfAbsent(key, spec);
                }
            }
            for (CharacterPresence character : scene.characters()) {
                String pose = poseVariant(character.expression());
                AssetSpec spec = planner.fromRequest(session.story,
                        new AssetRequest("portrait", character.characterId(), pose, null),
                        style, styleKey, scene.beatId());
                if (spec != null) requested.putIfAbsent("portrait/" + character.characterId(), spec);
            }
            Map<String, AssetSpec> additions = new LinkedHashMap<>();
            for (AssetSpec spec : requested.values()) {
                // A character outside the initial plan still needs its reference before an edit.
                if (spec.dependsOn() != null && before.map(m -> m.get(spec.dependsOn())).isEmpty()) {
                    AssetSpec base = planner.fromRequest(session.story,
                            new AssetRequest("portrait", spec.subjectId(), AssetSpec.BASE_VARIANT, null),
                            style, styleKey, scene.beatId());
                    if (base != null) additions.putIfAbsent(base.assetId(), base);
                }
                additions.putIfAbsent(spec.assetId(), spec);
            }
            // Dialogue can use several expressions in one scene, including a speaker absent
            // from the opening cast. All of those pictures share that person's base identity.
            for (var block : scene.blocks()) {
                if (block.speakerId() == null) continue;
                AssetSpec pose = planner.fromRequest(session.story,
                        new AssetRequest("portrait", block.speakerId(), poseVariant(block.expression()), null),
                        style, styleKey, scene.beatId());
                if (pose != null) additions.putIfAbsent(pose.assetId(), pose);
            }
            // The player's immediate response to a choice uses these two poses.
            if (session.story.playerVisual != null) {
                for (String pose : List.of("talking", "action")) {
                    AssetSpec spec = planner.fromRequest(session.story,
                            new AssetRequest("portrait", com.genvn.game.PlayerCharacter.ID, pose, null),
                            style, styleKey, scene.beatId());
                    if (spec != null) additions.putIfAbsent(spec.assetId(), spec);
                }
            }
            if (scene.location() != null) {
                String locationId = scene.location().id();
                AssetSpec background = requested.get("background/" + locationId);
                String hint = scene.location().backgroundAssetId();
                AssetRecord existing = before.map(m -> m.get(hint)).orElse(null);
                String outcome = scene.meta() == null ? "NONE" : scene.meta().outcomeContext();
                if (background == null && existing != null && existing.spec.kind() == AssetKind.BACKGROUND
                        && locationId.equals(existing.spec.subjectId()) && existing.spec.appliesTo(outcome)) {
                    background = existing.spec;
                }
                if (background == null) {
                    String prefix = "bg." + AssetSpec.slug(locationId) + ".";
                    String variant = hint != null && hint.startsWith(prefix)
                            ? hint.substring(prefix.length()) : AssetSpec.DEFAULT_VARIANT;
                    background = planner.fromRequest(session.story,
                            new AssetRequest("background", locationId, variant, null), style, styleKey, scene.beatId());
                }
                if (background != null) additions.putIfAbsent(background.assetId(), background);
            }
            // A newly present NPC needs both display tracks even when the text model made no
            // asset request, or the compiler's initial pre-render limit excluded this person.
            Set<String> characterIds = new LinkedHashSet<>();
            for (CharacterPresence character : scene.characters()) characterIds.add(character.characterId());
            // Player card stays visible even during narration and NPC dialogue.
            if (session.story.playerVisual != null) characterIds.add(com.genvn.game.PlayerCharacter.ID);
            for (AssetSpec spec : additions.values()) {
                if (spec.kind().transparentSprite()) characterIds.add(spec.subjectId());
            }
            for (String characterId : characterIds) {
                var npc = session.story.visualCharacter(characterId);
                if (npc == null) continue;
                AssetSpec base = planner.fromRequest(session.story,
                        new AssetRequest("portrait", characterId, AssetSpec.BASE_VARIANT, null),
                        style, styleKey, scene.beatId());
                additions.putIfAbsent(base.assetId(), base);
                AssetSpec card = planner.characterCard(npc, style, styleKey, scene.beatId());
                additions.putIfAbsent(card.assetId(), card);
            }
            if (additions.isEmpty()) return scene;
            // A missing manifest still needs the save's fixed style before its first adoption.
            if (before.isEmpty()) pipeline.adopt(session.id, new VisualPlanner.Plan(style, styleKey, List.of()));
            pipeline.adoptSpecs(session.id, new ArrayList<>(additions.values()));
            pipeline.ensureQueued(session.id, additions.keySet(), true);
            AssetManifest manifest = pipeline.snapshot(session.id).orElse(null);
            if (manifest == null) return scene;
            AssetResolver resolver = AssetResolver.none();
            String outcome = scene.meta() == null ? "NONE" : scene.meta().outcomeContext();
            SceneLocation location = scene.location();
            if (location != null) {
                AssetSpec request = requested.get("background/" + location.id());
                location = location.withBackgroundAssetId(resolver.resolveBackground(manifest, location.id(),
                        request == null ? location.backgroundAssetId() : request.assetId(), outcome));
            }
            List<CharacterPresence> characters = new ArrayList<>();
            for (CharacterPresence character : scene.characters()) {
                AssetSpec request = requested.get("portrait/" + character.characterId());
                characters.add(character.withAssetId(resolver.resolvePortrait(manifest, character.characterId(),
                        character.expression(), request == null ? character.assetId() : request.assetId(), outcome)));
            }
            return new SceneBundle(scene.sceneId(), scene.beatId(), location, characters, scene.blocks(),
                    scene.choices(), scene.proposedStateDelta(), scene.storyProgressNote(), scene.assetRequests(), scene.newNpcs(), scene.meta());
        } catch (RuntimeException e) {
            log.warn("Assets {}: preparing scene assets failed, play continues: {}", session.id, e.toString());
            return scene;
        }
    }

    /** A scene became canon: note what it used and pull what it needs forward. */
    public void onSceneCommitted(GameSession session, SceneBundle scene) {
        if (!active()) return;
        try {
            Set<String> used = new LinkedHashSet<>();
            if (scene.location() != null && scene.location().backgroundAssetId() != null) {
                used.add(scene.location().backgroundAssetId());
            }
            for (CharacterPresence c : scene.characters()) {
                if (c.assetId() != null) used.add(c.assetId());
                used.add(AssetSpec.characterCardId(c.characterId()));
            }
            if (session.story.playerVisual != null) {
                used.add(AssetSpec.characterCardId(com.genvn.game.PlayerCharacter.ID));
                used.add(AssetSpec.portraitId(com.genvn.game.PlayerCharacter.ID, AssetSpec.BASE_VARIANT));
            }
            if (!used.isEmpty()) {
                pipeline.markNeeded(session.id, used, scene.sceneId());
                pipeline.ensureQueued(session.id, used, true);
            }

            Optional<AssetManifest> snap = pipeline.snapshot(session.id);

            // Look one beat ahead: anything planned for the current or next beat should be moving.
            Set<String> upcoming = new LinkedHashSet<>();
            String current = session.state.currentBeatId;
            StoryBeat next = current == null ? null : session.story.spine.nextAfter(current);
            snap.ifPresent(m -> {
                for (AssetRecord r : m.records.values()) {
                    String b = r.spec.beatId();
                    if (b != null && (b.equals(current) || (next != null && b.equals(next.id())))) {
                        upcoming.add(r.spec.assetId());
                    }
                }
            });
            if (!upcoming.isEmpty()) pipeline.ensureQueued(session.id, upcoming, false);
        } catch (RuntimeException e) {
            log.warn("Assets {}: post-commit asset step failed, play continues: {}", session.id, e.toString());
        }
    }

    /** A new arc: fresh budget, and plan pictures for anything the story has added since. */
    public void onArcBegan(GameSession session) {
        if (!active()) return;
        try {
            pipeline.beginArc(session.id, session.state.storyProgress.arcNumber);
            Optional<AssetManifest> snap = pipeline.snapshot(session.id);
            var plan = planner.plan(session.story, session.state, snap.map(m -> m.style).orElse(null));
            pipeline.adopt(session.id, plan);
        } catch (RuntimeException e) {
            log.warn("Assets {}: arc planning failed, play continues: {}", session.id, e.toString());
        }
    }

    /** New spare designs get their idle-priority base sprite and card planned; nothing is queued now. */
    public void onSpareDesignsAdded(GameSession session, List<com.genvn.story.PreparedVisual> added) {
        if (!active() || added == null || added.isEmpty()) return;
        try {
            Optional<AssetManifest> snap = pipeline.snapshot(session.id);
            var plan = planner.plan(session.story, session.state, snap.map(m -> m.style).orElse(null));
            Set<String> ids = new LinkedHashSet<>();
            for (var design : added) ids.add(design.id());
            List<AssetSpec> specs = plan.specs().stream().filter(spec -> ids.contains(spec.subjectId())).toList();
            pipeline.adopt(session.id, new VisualPlanner.Plan(plan.style(), plan.styleKey(), specs));
        } catch (RuntimeException e) {
            log.warn("Assets {}: planning spare designs failed, play continues: {}", session.id, e.toString());
        }
    }

    /**
     * Re-derive every pose/expression prompt from the current planner and hand the new wording to
     * records that have not produced a file. Called before a manual retry so a picture that failed
     * under an older prompt is retried with the current one, not the text stored when it failed.
     */
    public int refreshVariantPrompts(GameSession session) {
        if (!active()) return 0;
        try {
            Optional<AssetManifest> snap = pipeline.snapshot(session.id);
            if (snap.isEmpty()) return 0;
            AssetManifest m = snap.get();
            String style = m.style == null || m.style.isBlank() ? VisualPlanner.styleFor(session.story) : m.style;
            String styleKey = m.styleKey == null ? VisualPlanner.styleKey(style) : m.styleKey;
            List<AssetSpec> specs = new ArrayList<>();
            for (AssetRecord r : m.records.values()) {
                if (r.spec == null || r.spec.kind() != AssetKind.PORTRAIT_VARIANT) continue;
                AssetSpec fresh = planner.fromRequest(session.story,
                        new AssetRequest("portrait", r.spec.subjectId(), r.spec.variant(), null), style, styleKey, r.spec.beatId());
                if (fresh != null) specs.add(fresh);
            }
            return pipeline.refreshPrompts(session.id, specs);
        } catch (RuntimeException e) {
            log.warn("Assets {}: prompt refresh failed, retrying with stored wording: {}", session.id, e.toString());
            return 0;
        }
    }

    /** True when a picture library already exists for this id; used to keep new session ids unique. */
    public boolean hasPictures(String sessionId) {
        if (!active()) return false;
        try {
            return pipeline.snapshot(sessionId).isPresent();
        } catch (RuntimeException e) {
            return true; // unreadable still means "something is there"
        }
    }

    public void onSessionDeleted(String sessionId) {
        if (!active()) return;
        pipeline.forget(sessionId);
    }

    /** Entering or restoring a scene reuses matching versions and admits only missing pictures. */
    public void ensureCurrentSceneAssets(GameSession session) {
        ensureCurrentSceneAssets(session, List::of);
    }

    public void ensureCurrentSceneAssets(GameSession session, Supplier<List<CompiledStory>> historicalStories) {
        if (!active() || session == null || session.story == null) return;
        try {
            reconcileCharacterVersions(session, historicalStories);
            if (session.currentScene != null) {
                session.currentScene = prepareSceneAssets(session, session.currentScene);
            }
        } catch (RuntimeException e) {
            log.warn("Assets {}: could not restore current scene pictures: {}", session.id, e.toString());
        }
    }

    public void reconcileAfterRestore(GameSession session) {
        ensureCurrentSceneAssets(session);
    }

    public void reconcileAfterRestore(GameSession session, Supplier<List<CompiledStory>> historicalStories) {
        ensureCurrentSceneAssets(session, historicalStories);
    }

    private void reconcileCharacterVersions(GameSession session, Supplier<List<CompiledStory>> historicalStories) {
        AssetManifest manifest = pipeline.snapshot(session.id).orElse(null);
        if (manifest == null) return;
        String style = manifest.style == null || manifest.style.isBlank()
                ? VisualPlanner.styleFor(session.story) : manifest.style;
        Map<String, String> desired = appearanceKeys(session.story, style);
        Map<String, String> legacyBindings = new LinkedHashMap<>();
        List<AssetRecord> unknownBases = manifest.records.values().stream()
                .filter(r -> r.spec != null && r.spec.kind() == AssetKind.PORTRAIT
                        && r.spec.appearanceKey() == null).toList();
        bindLegacyBases(unknownBases, session.story, style, legacyBindings);
        if (legacyBindings.size() < unknownBases.size() && historicalStories != null) {
            for (CompiledStory historical : historicalStories.get()) {
                bindLegacyBases(unknownBases, historical, style, legacyBindings);
            }
        }
        pipeline.reconcileCharacterVersions(session.id, desired, legacyBindings);
    }

    private static Map<String, String> appearanceKeys(CompiledStory story, String style) {
        Map<String, String> result = new LinkedHashMap<>();
        if (story.playerVisual != null) result.put(story.playerVisual.id(), AppearanceIdentity.key(story.playerVisual, style));
        if (story.bible != null) {
            for (NpcProfile npc : story.bible.characters()) result.put(npc.id(), AppearanceIdentity.key(npc, style));
        }
        for (NpcProfile npc : story.encounteredNpcs) result.putIfAbsent(npc.id(), AppearanceIdentity.key(npc, style));
        for (var visual : story.preparedVisuals) {
            result.putIfAbsent(visual.id(), AppearanceIdentity.key(visual.id(), visual.visualDescription(), style));
        }
        return result;
    }

    /** Legacy prompts are evidence only for a base's origin, never an ongoing cache identity. */
    private static void bindLegacyBases(List<AssetRecord> records, CompiledStory story, String style,
                                        Map<String, String> bindings) {
        if (story == null || story.bible == null) return;
        for (AssetRecord record : records) {
            AssetSpec spec = record.spec;
            if (bindings.containsKey(record.recordVersionId)) continue;
            NpcProfile npc = story.visualCharacter(spec.subjectId());
            if (npc != null && (VisualPlanner.portraitPrompt(style, npc, "neutral").equals(spec.prompt())
                    || VisualPlanner.preparedPortraitPrompt(style, AppearanceIdentity.effectiveAppearance(npc))
                    .equals(spec.prompt()))) {
                bindings.put(record.recordVersionId, AppearanceIdentity.key(npc, style));
                continue;
            }
            var prepared = story.preparedVisual(spec.subjectId());
            if (prepared != null && VisualPlanner.preparedPortraitPrompt(style, prepared.visualDescription()).equals(spec.prompt())) {
                bindings.put(record.recordVersionId, AppearanceIdentity.key(prepared.id(), prepared.visualDescription(), style));
            }
        }
    }

    private static String poseVariant(String expression) {
        return expression == null || expression.isBlank() || "neutral".equalsIgnoreCase(expression)
                ? AssetSpec.BASE_VARIANT : expression;
    }

    public ImageProperties properties() {
        return props;
    }
}
