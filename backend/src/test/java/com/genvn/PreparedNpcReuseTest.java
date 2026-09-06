package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.game.GameSession;
import com.genvn.asset.AssetStatus;
import com.genvn.llm.LlmException;
import com.genvn.narrative.SceneBundle;
import com.genvn.narrative.SceneCast;
import com.genvn.narrative.SceneRequest;
import com.genvn.prompt.ContextRenderer;
import com.genvn.story.CompiledStory;
import com.genvn.story.NpcProfile;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import com.genvn.support.FakeImageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PreparedNpcReuseTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String DESIGN = "npc_visual_1";
    @TempDir Path imageDirectory;

    private Engine engine(boolean recurring) {
        return new Engine(new ScriptedLlmClient(mapper, request ->
                ScriptedLlmClient.choiceOf(request) == null ? opening() : introduction(DESIGN, DESIGN, recurring, true)),
                new ScriptedRandom(12), false, false);
    }

    @Test void precompiledVisualsContainOnlyAppearanceAndCreateNoCharactersOrRelationships() throws Exception {
        Engine engine = engine(false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        assertEquals(2, session.story.preparedVisuals.size());
        for (var visual : session.story.preparedVisuals) {
            var json = mapper.valueToTree(visual);
            Set<String> fields = new HashSet<>();
            json.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("id", "visualDescription"), fields);
            assertNull(session.story.bible.character(visual.id()));
            assertNull(session.story.visualCharacter(visual.id()), "an illustration design is not an NPC");
            assertFalse(session.state.characters.containsKey(visual.id()));
        }
        String prompt = new ContextRenderer().renderStoryFoundation(session.story);
        assertTrue(prompt.contains("UNASSIGNED APPEARANCE DESIGNS"));
        assertTrue(prompt.contains("No name, role, personality, relationship or encounter"));
    }

    @Test void speculativeIntroductionKeepsThePreparedAppearanceWithoutMutatingCanon() throws Exception {
        Engine engine = engine(true);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        String before = mapper.writeValueAsString(session);
        String visual = session.story.preparedVisual(DESIGN).visualDescription();
        SceneBundle candidate = engine.sceneGenerator.generate(new SceneRequest(session.story, session.state,
                session.currentScene.choices().get(0), SceneRequest.NONE, null, 2, true));
        assertEquals(before, mapper.writeValueAsString(session));
        assertEquals(visual, candidate.newNpcs().get(0).profile().visualDescription());
        assertEquals(visual, candidate.characters().stream().filter(c -> DESIGN.equals(c.characterId())).findFirst().orElseThrow().visualDescription());
        assertEquals("Mara", candidate.characters().stream().filter(c -> DESIGN.equals(c.characterId())).findFirst().orElseThrow().name());
        assertEquals(candidate.newNpcs(), candidate.withSceneId("renamed").withMeta(candidate.meta()).withDelta(candidate.proposedStateDelta()).newNpcs());

        CompiledStory forkStory = mapper.convertValue(session.story, CompiledStory.class);
        var forkState = session.state.deepCopy(mapper);
        SceneCast.apply(forkStory, forkState, candidate);
        assertNotNull(forkStory.bible.character(DESIGN));
        assertTrue(forkState.characters.get(DESIGN).met);
        assertNull(forkStory.preparedVisual(DESIGN));
        assertEquals(before, mapper.writeValueAsString(session), "fork cast transitions cannot leak into canonical state");
    }

    @Test void canonicalRecurringIntroductionRegistersTheFullProfileBeforeStateDeltas() {
        Engine engine = engine(true);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        String appearance = session.story.preparedVisual(DESIGN).visualDescription();
        session = engine.sessions.choose(session.id, "continue", session.currentScene.sceneId(), session.state.stateVersion).session();
        NpcProfile npc = session.story.bible.character(DESIGN);
        assertNotNull(npc);
        assertEquals("Mara", npc.name());
        assertEquals("A visitor looking for a missing parcel", npc.description());
        assertEquals("Direct and thoughtful", npc.personality());
        assertEquals(List.of("Find the parcel"), npc.goals());
        assertEquals(List.of("Has already checked the cellar"), npc.secrets());
        assertEquals(appearance, npc.visualDescription());
        assertEquals(2, session.state.characters.get(DESIGN).relationship,
                "the relation op must see the newly registered NPC in this same canonical commit");
        assertNull(session.story.preparedVisual(DESIGN));
        assertTrue(session.story.encounteredNpcs.isEmpty());
        assertEquals(1, session.currentScene.newNpcs().size());
    }

    @Test void nonrecurringPersonKeepsAStableIdentityWithoutEnteringBibleAndCanBePromotedLater() {
        Engine engine = engine(false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        session = engine.sessions.choose(session.id, "continue", session.currentScene.sceneId(), session.state.stateVersion).session();
        assertNull(session.story.bible.character(DESIGN));
        NpcProfile original = session.story.visualCharacter(DESIGN);
        assertNotNull(original);
        assertEquals(1, session.story.encounteredNpcs.size());
        assertNull(session.story.preparedVisual(DESIGN), "a used appearance must not remain available for another person's identity");
        assertTrue(new ContextRenderer().renderGameState(session.state, session.story).contains("Current supporting person [" + DESIGN + "] Mara"));
        session.story.encounterCharacter(new NpcProfile(DESIGN, "A different person", "wrong", "wrong",
                List.of(), List.of(), "wrong", "wrong", "wrong appearance"), true);
        assertEquals(original, session.story.bible.character(DESIGN));
        assertTrue(session.story.encounteredNpcs.isEmpty());
    }

    @Test void aPreviouslyAssignedDesignCannotBeGivenToAnotherIdentity() {
        Engine engine = engine(false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        session = engine.sessions.choose(session.id, "continue", session.currentScene.sceneId(), session.state.stateVersion).session();
        GameSession current = session;
        assertThrows(LlmException.class, () -> engine.sceneGenerator.generate(new SceneRequest(current.story,
                current.state, current.currentScene.choices().get(0), SceneRequest.NONE, null, 3, true)));
        assertEquals("Mara", session.story.visualCharacter(DESIGN).name());
    }

    @Test void repairRequiresTheNpcIdToMatchThePreparedImageSlot() {
        AtomicInteger calls = new AtomicInteger();
        var engine = new Engine(new ScriptedLlmClient(mapper, request -> {
            if (ScriptedLlmClient.choiceOf(request) == null) return opening();
            return calls.getAndIncrement() == 0 ? introduction("npc_other", DESIGN, true, true)
                    : introduction(DESIGN, DESIGN, true, true);
        }), new ScriptedRandom(12), false, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        session = engine.sessions.choose(session.id, "continue", session.currentScene.sceneId(), session.state.stateVersion).session();
        assertEquals(2, calls.get());
        assertNull(session.story.visualCharacter("npc_other"));
        assertNotNull(session.story.bible.character(DESIGN));
    }

    @Test void offstageDeclarationsCannotConsumePreparedImagesOrEnterBible() {
        var engine = new Engine(new ScriptedLlmClient(mapper, request -> ScriptedLlmClient.choiceOf(request) == null
                ? opening() : introduction(DESIGN, DESIGN, true, false)), new ScriptedRandom(12), false, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        session = engine.sessions.choose(session.id, "continue", session.currentScene.sceneId(), session.state.stateVersion).session();
        assertTrue(session.currentScene.newNpcs().isEmpty());
        assertNotNull(session.story.preparedVisual(DESIGN));
        assertNull(session.story.bible.character(DESIGN));
        assertFalse(session.state.characters.containsKey(DESIGN));
    }

    @Test void adoptingAReadyVisualReusesBothLocalImagesWithoutAnotherProviderCall() throws Exception {
        var props = Engine.imageProperties();
        props.getPlan().setCharacters(0);
        props.getPlan().setLocations(0);
        props.setFirstBatchBudget(4);
        var images = new FakeImageProvider();
        var engine = new Engine(new ScriptedLlmClient(mapper, request -> ScriptedLlmClient.choiceOf(request) == null
                ? opening() : introduction(DESIGN, DESIGN, true, true)), new ScriptedRandom(12), false, false,
                images, props, imageDirectory);
        try {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            for (int i = 0; i < 400; i++) {
                var manifest = engine.assets.snapshot(session.id).orElseThrow();
                if (manifest.records.values().stream().allMatch(r -> r.status == AssetStatus.READY)) break;
                Thread.sleep(25);
            }
            String base = "pt." + DESIGN + ".base";
            String card = "card." + DESIGN + ".default";
            assertEquals(AssetStatus.READY, engine.assets.snapshot(session.id).orElseThrow().get(base).status);
            assertEquals(AssetStatus.READY, engine.assets.snapshot(session.id).orElseThrow().get(card).status);
            byte[] baseBefore = Files.readAllBytes(engine.assets.readyFile(session.id, base).orElseThrow());
            byte[] cardBefore = Files.readAllBytes(engine.assets.readyFile(session.id, card).orElseThrow());
            int callsBefore = images.calls.get();
            session = engine.sessions.choose(session.id, "continue", session.currentScene.sceneId(), session.state.stateVersion).session();
            engine.awaitAssets(session.id);
            assertEquals(base, session.currentScene.characters().stream().filter(c -> DESIGN.equals(c.characterId())).findFirst().orElseThrow().assetId());
            assertArrayEquals(baseBefore, Files.readAllBytes(engine.assets.readyFile(session.id, base).orElseThrow()));
            assertArrayEquals(cardBefore, Files.readAllBytes(engine.assets.readyFile(session.id, card).orElseThrow()));
            assertEquals(callsBefore, images.calls.get(), "assigning a name and role does not redraw an already prepared appearance or card");
        } finally { engine.assets.shutdown(); }
    }

    private String opening() {
        return SceneJson.scene("The corridor is empty.").choice("continue", "Continue", "action").build();
    }

    private String introduction(String id, String preparedId, boolean recurring, boolean present) {
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(SceneJson.scene("A visitor waits by the window.")
                    .choice("continue", "Ask about the parcel", "social").build());
            if (present) {
                node.withArray("characters").addObject().put("characterId", id).put("name", "Untrusted replacement name")
                        .put("expression", "neutral").put("visualDescription", "a contradictory appearance");
                node.withObject("proposedStateDelta").withArray("ops").addObject()
                        .put("op", "relationshipDelta").put("target", id).put("amount", 2).put("reason", "a friendly introduction");
            }
            ObjectNode introduction = node.putArray("newNpcs").addObject().put("recurring", recurring);
            if (preparedId == null) introduction.putNull("preparedVisualId"); else introduction.put("preparedVisualId", preparedId);
            var profile = new NpcProfile(id, "Mara", "A visitor looking for a missing parcel", "Direct and thoughtful",
                    List.of("Find the parcel"), List.of("Has already checked the cellar"), "Brief but courteous sentences",
                    "Cautious interest", "a contradictory model-supplied appearance");
            introduction.set("profile", mapper.valueToTree(profile));
            return node.toString();
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
