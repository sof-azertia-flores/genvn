package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.asset.AssetKind;
import com.genvn.asset.AssetSpec;
import com.genvn.game.GameSession;
import com.genvn.llm.LlmException;
import com.genvn.narrative.SceneBundle;
import com.genvn.narrative.SceneRequest;
import com.genvn.speculation.SpeculativeGenerator;
import com.genvn.story.CompiledStory;
import com.genvn.story.NpcProfile;
import com.genvn.story.PreparedVisual;
import com.genvn.story.SpareDesignResponse;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A spare appearance-only design belongs to exactly one person. When a scene gives it an
 * identity it leaves the pool, its pictures become that person's, and a replacement is sketched.
 */
class SpareDesignTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String DESIGN = "npc_visual_1";
    @TempDir Path imageDirectory;

    @Test
    @DisplayName("adopting a design relabels its pictures to the person and the pool is topped up with a fresh id")
    void adoptingADesignRelabelsItsAssetsAndReplenishesThePool() throws Exception {
        var fake = new FakeImageProvider();
        var props = Engine.imageProperties();
        props.getPlan().setCharacters(0);
        props.getPlan().setLocations(0);
        Engine engine = new Engine(new ScriptedLlmClient(mapper, request ->
                ScriptedLlmClient.choiceOf(request) == null ? opening() : introduction(DESIGN, DESIGN, true)),
                new ScriptedRandom(12), false, false, fake, props, imageDirectory);
        engine.spareDesigns.setTarget(3);
        try {
            GameSession created = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            // The compiler sketched two; the pool is brought up to three before anyone is met.
            await(() -> designs(created).size() == 3);
            Set<String> beforeAdoption = new HashSet<>(designs(created));
            assertTrue(beforeAdoption.contains(DESIGN));
            assertEquals(3, beforeAdoption.size());

            GameSession session = engine.sessions.choose(created.id, "continue", created.currentScene.sceneId(),
                    created.state.stateVersion).session();
            GameSession live = engine.sessions.require(session.id);
            assertNull(live.story.preparedVisual(DESIGN), "an adopted design leaves the pool");
            assertEquals("Mara", live.story.visualCharacter(DESIGN).name(), "the design's id is now the person's id");

            await(() -> designs(live).size() == 3);
            List<String> afterAdoption = designs(live);
            assertFalse(afterAdoption.contains(DESIGN), "the pool never hands the same design out twice");
            String fresh = afterAdoption.stream().filter(id -> !beforeAdoption.contains(id)).findFirst().orElseThrow();
            assertNull(live.story.visualCharacter(fresh), "the replacement is a look, not a person");
            assertTrue(PreparedVisual.validId(fresh));
            synchronized (live) {
                assertTrue(live.story.preparedVisuals.stream().map(PreparedVisual::id).allMatch(PreparedVisual::validId));
                assertEquals(3, new HashSet<>(designs(live)).size(), "ids are unique");
            }

            engine.awaitAssets(session.id);
            var manifest = engine.assets.snapshot(session.id).orElseThrow();
            var base = manifest.get(AssetSpec.portraitId(DESIGN, AssetSpec.BASE_VARIANT));
            var card = manifest.get(AssetSpec.characterCardId(DESIGN));
            assertNotNull(base); assertNotNull(card);
            assertEquals("Mara", base.spec.subjectName(), "the sprite is now labelled as the person who adopted it");
            assertEquals("Mara", card.spec.subjectName());
            assertFalse(base.spec.idlePreparation(), "no longer a spare: it is scheduled like any cast member's picture");
            var freshBase = manifest.get(AssetSpec.portraitId(fresh, AssetSpec.BASE_VARIANT));
            assertNotNull(freshBase, "the replacement design has its pictures planned");
            assertEquals(AssetKind.PORTRAIT, freshBase.spec.kind());
            assertTrue(freshBase.spec.idlePreparation(), "spare pictures only use idle workers");
            assertNotNull(manifest.get(AssetSpec.characterCardId(fresh)));
        } finally {
            engine.spareDesigns.close();
            engine.assets.shutdown();
        }
    }

    @Test
    @DisplayName("a replacement sketch is briefed with the world's canon, themes and places, not just a premise")
    void replacementSketchesAreBriefedWithTheSetting() throws Exception {
        var client = new ScriptedLlmClient(mapper, r -> opening());
        Engine engine = new Engine(client, new ScriptedRandom(12), false, false);
        engine.spareDesigns.setTarget(3);
        try {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            await(() -> !client.requestsOf(com.genvn.llm.LlmPurpose.SPARE_DESIGNS).isEmpty());
            String brief = client.requestsOf(com.genvn.llm.LlmPurpose.SPARE_DESIGNS).get(0).user();
            CompiledStory story = session.story;
            assertTrue(brief.contains(story.authorCanon.facts().get(0)), "the author's fixed facts anchor the era and place");
            assertTrue(brief.contains(story.bible.locations().get(0).name()), "the places people move through set the look");
            for (String canon : story.bible.hardCanon().subList(0, Math.min(3, story.bible.hardCanon().size()))) assertTrue(brief.contains(canon));
            assertTrue(brief.contains("## SETTING"));
            assertTrue(brief.contains(story.preparedVisuals.get(0).visualDescription()), "existing designs are listed so the new one differs");
            assertTrue(client.requestsOf(com.genvn.llm.LlmPurpose.SPARE_DESIGNS).get(0).system().contains("belong to THIS world"));
        } finally {
            engine.spareDesigns.close();
        }
    }

    @Test
    @DisplayName("a new person may not copy an unassigned design's appearance under a different id")
    void aCopiedAppearanceUnderANewIdIsRejected() {
        AtomicInteger calls = new AtomicInteger();
        Engine engine = new Engine(new ScriptedLlmClient(mapper, request -> {
            if (ScriptedLlmClient.choiceOf(request) == null) return opening();
            CompiledStory story = request.mockContext().get("story") instanceof CompiledStory s ? s : null;
            String copied = story.preparedVisual(DESIGN).visualDescription();
            return calls.getAndIncrement() == 0
                    ? introductionWithAppearance("npc_stranger", null, copied)
                    : introduction(DESIGN, DESIGN, false);
        }), new ScriptedRandom(12), false, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());

        SceneBundle scene = engine.sceneGenerator.generate(new SceneRequest(session.story, session.state,
                session.currentScene.choices().get(0), SceneRequest.NONE, null, 2, false));

        assertEquals(2, calls.get(), "the copy was rejected and repaired");
        assertEquals(1, scene.meta().repairAttempts());
        assertEquals(DESIGN, scene.newNpcs().get(0).profile().id());
        assertNull(session.story.visualCharacter("npc_stranger"));
    }

    @Test
    @DisplayName("a sketched batch is checked for taken ids, duplicates and empty looks")
    void replacementDesignsAreValidated() throws Exception {
        Engine engine = new Engine(new ScriptedLlmClient(mapper, r -> opening()), new ScriptedRandom(12), false, false);
        CompiledStory story = engine.sessions.create(Engine.OUTLINE, Engine.alex()).story;
        Method validate = com.genvn.story.SpareDesignService.class.getDeclaredMethod("validate",
                SpareDesignResponse.class, CompiledStory.class, int.class);
        validate.setAccessible(true);
        String takenNpc = story.bible.characters().get(0).id();
        assertNotNull(validate.invoke(null, new SpareDesignResponse(List.of()), story, 1));
        assertNotNull(validate.invoke(null, new SpareDesignResponse(List.of(new PreparedVisual(DESIGN, "a look"))), story, 1), "an existing design id");
        assertNotNull(validate.invoke(null, new SpareDesignResponse(List.of(new PreparedVisual(takenNpc, "a look"))), story, 1), "a person's id");
        assertNotNull(validate.invoke(null, new SpareDesignResponse(List.of(new PreparedVisual("player", "a look"))), story, 1));
        assertNotNull(validate.invoke(null, new SpareDesignResponse(List.of(new PreparedVisual("Bad Id", "a look"))), story, 1));
        assertNotNull(validate.invoke(null, new SpareDesignResponse(List.of(new PreparedVisual("npc_visual_9", " "))), story, 1));
        assertNotNull(validate.invoke(null, new SpareDesignResponse(List.of(new PreparedVisual("npc_visual_9", "a"), new PreparedVisual("npc_visual_9", "b"))), story, 2));
        assertNull(validate.invoke(null, new SpareDesignResponse(List.of(new PreparedVisual("npc_visual_9", "a distinct look"))), story, 1));
    }

    @Test
    @DisplayName("a replacement design arriving in the background does not invalidate prepared successors")
    void promotionIgnoresSpareDesignDifferences() throws Exception {
        Engine engine = new Engine(new ScriptedLlmClient(mapper, r -> opening()), new ScriptedRandom(12), false, false);
        CompiledStory canonical = engine.sessions.create(Engine.OUTLINE, Engine.alex()).story;
        CompiledStory projected = mapper.convertValue(canonical, CompiledStory.class);
        Method same = SpeculativeGenerator.class.getDeclaredMethod("sameStoryIgnoringSpares",
                ObjectMapper.class, CompiledStory.class, CompiledStory.class);
        same.setAccessible(true);
        canonical.preparedVisuals = new java.util.ArrayList<>(canonical.preparedVisuals);
        canonical.preparedVisuals.add(new PreparedVisual("npc_visual_7", "a look sketched after the branch was written"));
        assertTrue((boolean) same.invoke(null, mapper, projected, canonical));
        canonical.artStyle = "changed";
        assertFalse((boolean) same.invoke(null, mapper, projected, canonical), "anything else still has to match");
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> designs(GameSession session) {
        synchronized (session) {
            return session.story.preparedVisuals.stream().map(PreparedVisual::id).toList();
        }
    }

    private static void await(BooleanSupplier ready) throws InterruptedException {
        for (int i = 0; i < 500; i++) { if (ready.getAsBoolean()) return; Thread.sleep(10); }
        fail("Background sketching did not reach the expected state");
    }

    private String opening() {
        return SceneJson.scene("The corridor is empty.").choice("continue", "Continue", "action").build();
    }

    private String introduction(String id, String preparedId, boolean recurring) {
        return introductionWithAppearance(id, preparedId, "a contradictory model-supplied appearance");
    }

    private String introductionWithAppearance(String id, String preparedId, String appearance) {
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(SceneJson.scene("A visitor waits by the window.")
                    .choice("continue", "Ask about the parcel", "social").build());
            node.withArray("characters").addObject().put("characterId", id).put("name", "Mara")
                    .put("expression", "neutral").put("visualDescription", appearance);
            ObjectNode introduction = node.putArray("newNpcs").addObject().put("recurring", true);
            if (preparedId == null) introduction.putNull("preparedVisualId"); else introduction.put("preparedVisualId", preparedId);
            var profile = new NpcProfile(id, "Mara", "A visitor looking for a missing parcel", "Direct and thoughtful",
                    List.of("Find the parcel"), List.of(), "Brief but courteous sentences", "Cautious interest", appearance);
            introduction.set("profile", mapper.valueToTree(profile));
            return node.toString();
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
