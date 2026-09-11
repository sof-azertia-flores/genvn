package com.genvn.asset;

import com.genvn.config.ImageProperties;
import com.genvn.game.GameSession;
import com.genvn.narrative.CharacterPresence;
import com.genvn.narrative.SceneBundle;
import com.genvn.narrative.SceneLocation;
import com.genvn.story.StoryBeat;
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
            var plan = planner.plan(session.story, session.state, null);
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
            Optional<AssetManifest> before = pipeline.snapshot(session.id);
            String style = before.map(m -> m.style).orElse(VisualPlanner.styleFor(session.story));
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
                if (!com.genvn.game.PlayerCharacter.ID.equals(character.characterId())) continue;
                String pose = "neutral".equals(character.expression()) ? AssetSpec.BASE_VARIANT : character.expression();
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
            // A newly present NPC needs both display tracks even when the text model made no
            // asset request, or the compiler's initial pre-render limit excluded this person.
            Set<String> characterIds = new LinkedHashSet<>();
            for (CharacterPresence character : scene.characters()) characterIds.add(character.characterId());
            // Player card stays visible even during narration and NPC dialogue.
            if (session.story.playerVisual != null) characterIds.add(com.genvn.game.PlayerCharacter.ID);
            for (AssetSpec spec : requested.values()) {
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
            pipeline.adoptSpecs(session.id, new ArrayList<>(additions.values()));
            AssetManifest manifest = pipeline.snapshot(session.id).orElse(null);
            if (manifest == null) return scene;
            AssetResolver resolver = AssetResolver.none();
            String outcome = scene.meta() == null ? "NONE" : scene.meta().outcomeContext();
            SceneLocation location = scene.location();
            if (location != null) {
                AssetSpec request = requested.get("background/" + location.id());
                if (request != null) {
                    location = location.withBackgroundAssetId(resolver.resolveBackground(manifest, location.id(),
                            request.assetId(), outcome));
                }
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

    /**
     * Rewind restored a different cast. Drop character pictures that no longer belong to this
     * story, or whose appearance no longer matches, so a reused NPC id cannot keep the old face.
     */
    public void reconcileAfterRestore(GameSession session) {
        if (!active() || session == null || session.story == null) return;
        try {
            Map<String, String> appearances = new LinkedHashMap<>();
            if (session.story.playerVisual != null) {
                appearances.put(com.genvn.game.PlayerCharacter.ID,
                        nz(session.story.playerVisual.visualDescription()));
            }
            if (session.story.bible != null && session.story.bible.characters() != null) {
                for (var npc : session.story.bible.characters()) {
                    if (npc != null && npc.id() != null) appearances.put(npc.id(), nz(npc.visualDescription()));
                }
            }
            if (session.story.encounteredNpcs != null) {
                for (var npc : session.story.encounteredNpcs) {
                    if (npc != null && npc.id() != null) appearances.put(npc.id(), nz(npc.visualDescription()));
                }
            }
            pipeline.dropDivergedCharacterArt(session.id, appearances);
        } catch (RuntimeException e) {
            log.warn("Assets {}: could not reconcile pictures after rewind: {}", session.id, e.toString());
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    public ImageProperties properties() {
        return props;
    }
}
