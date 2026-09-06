package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.asset.AssetRequest;
import com.genvn.asset.AssetSpec;
import com.genvn.asset.VisualPlanner;
import com.genvn.config.ImageProperties;
import com.genvn.game.*;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.*;
import com.genvn.prompt.ContextRenderer;
import com.genvn.story.*;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LongStoryArchiveTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StateReducer reducer = new StateReducer();

    @Test void oldSupportingIdentitiesSurviveRoundTripAndCannotBeReassignedAfterFortyEncounters() throws Exception {
        CompiledStory story = story();
        GameState state = state();
        for (int i = 0; i < 40; i++) commit(story, state, scene(i, "room_0", npc(i)));
        state.characters.get("npc_0").relationship = 7;
        var original = story.visualCharacter("npc_0");
        story = mapper.readValue(mapper.writeValueAsString(story), CompiledStory.class);
        state = mapper.readValue(mapper.writeValueAsString(state), GameState.class);
        assertEquals(40, story.encounteredNpcs.size());
        assertEquals(original, story.visualCharacter("npc_0"));
        assertTrue(story.bible.characters().isEmpty());

        ObjectNode response = (ObjectNode) mapper.readTree(SceneJson.scene("A familiar visitor returns.")
                .at("room_0").choice("next", "Continue", "action").build());
        response.withArray("characters").addObject().put("characterId", "npc_0")
                .put("name", "Unrelated newcomer").put("expression", "neutral");
        var replacement = new NpcProfile("npc_0", "Unrelated newcomer", "different identity", "different personality",
                List.of(), List.of(), "different voice", "stranger", "completely different appearance");
        var introduction = response.putArray("newNpcs").addObject().put("recurring", true);
        introduction.set("profile", mapper.valueToTree(replacement));
        var generator = generator(request -> response.toString());
        var next = generator.generate(new SceneRequest(story, state, null, SceneRequest.NONE, null, 40, false));
        assertEquals(original, next.newNpcs().getFirst().profile(), "normalization must retain the established identity");
        commit(story, state, next);
        assertEquals(original, story.bible.character("npc_0"), "later promotion keeps the original person");
        assertEquals("Person 0", state.characters.get("npc_0").name);
        assertEquals(7, state.characters.get("npc_0").relationship);
        assertEquals("pt.npc_0.base", AssetSpec.portraitId(original.id(), AssetSpec.BASE_VARIANT));
    }

    @Test void locationsBeyondSixtyFourRemainRegisteredAndCanProduceBackgroundsAfterRoundTrip() throws Exception {
        CompiledStory story = story();
        GameState state = state();
        for (int i = 0; i < 70; i++) commit(story, state, scene(i, "room_" + i, null));
        story = mapper.readValue(mapper.writeValueAsString(story), CompiledStory.class);
        state = mapper.readValue(mapper.writeValueAsString(state), GameState.class);
        assertEquals(70, story.bible.locations().size());
        assertEquals("room_69", state.currentLocationId);
        assertNotNull(story.bible.location("room_69"));
        var applied = reducer.apply(state, story, StateDelta.of(
                DeltaOp.of(DeltaOp.CHANGE_LOCATION, "room_65", null, "return to a previously visited room")), "scene_070");
        assertTrue(applied.rejected().isEmpty());
        assertEquals("room_65", state.currentLocationId);
        var background = new VisualPlanner(new ImageProperties()).fromRequest(story,
                new AssetRequest("background", "room_65", "default", "The same room"), "style", "style", "beat");
        assertNotNull(background);
        assertEquals("bg.room_65.default", background.assetId());
        var original = story.bible.location("room_65");
        story.registerLocation(new LocationProfile("room_65", "Another place", "wrong", "wrong"));
        assertEquals(original, story.bible.location("room_65"));
    }

    @Test void promptViewsAreBoundedPrioritizeCurrentAndRecentPeopleAndPlacesAndNeverModifyTheArchive() throws Exception {
        CompiledStory story = story();
        GameState state = state();
        for (int i = 0; i < 70; i++) commit(story, state, scene(i, "room_" + i, i < 40 ? npc(i) : null));
        // Bring an early person and an early room back; neither may be omitted merely for its age.
        commit(story, state, scene(70, "room_1", npc(0)));
        String originalStory = mapper.writeValueAsString(story);
        String originalState = mapper.writeValueAsString(state);
        var context = new ContextRenderer();
        String foundation = context.renderStoryFoundation(story, state);
        String runtime = context.renderGameState(state, story);
        assertEquals(ContextRenderer.MAX_LOCATION_PROFILES,
                foundation.lines().filter(line -> line.startsWith("- [room_")).count());
        assertTrue(foundation.contains("- [room_1]"));
        assertTrue(foundation.contains("- [room_69]"));
        assertTrue(foundation.indexOf("- [room_1]") < foundation.indexOf("- [room_69]"));
        assertEquals(ContextRenderer.MAX_CHARACTER_PROFILES,
                runtime.lines().filter(line -> line.contains("supporting person [")).count());
        assertEquals(ContextRenderer.MAX_CHARACTER_PROFILES,
                runtime.lines().filter(line -> line.startsWith("  - ") && line.contains("relationship")).count());
        assertTrue(runtime.contains("Current supporting person [npc_0] Person 0"));
        assertTrue(runtime.contains("Previously encountered supporting person [npc_39] Person 39"));
        assertFalse(runtime.contains("supporting person [npc_1]"));
        assertEquals(foundation, context.renderStoryFoundation(story, state));
        assertEquals(runtime, context.renderGameState(state, story));
        assertEquals(originalStory, mapper.writeValueAsString(story));
        assertEquals(originalState, mapper.writeValueAsString(state));
    }

    @Test void speculativeCopiesCanGrowBeyondOldLimitsWithoutChangingCanonicalIdentityOrLocationLists() throws Exception {
        CompiledStory story = story();
        GameState state = state();
        for (int i = 0; i < 64; i++) commit(story, state, scene(i, "room_" + i, i < 32 ? npc(i) : null));
        String originalStory = mapper.writeValueAsString(story);
        String originalState = mapper.writeValueAsString(state);
        var forkStory = mapper.readValue(originalStory, CompiledStory.class);
        var forkState = mapper.readValue(originalState, GameState.class);
        commit(forkStory, forkState, scene(64, "room_64", npc(32)));
        assertEquals(33, forkStory.encounteredNpcs.size());
        assertEquals(65, forkStory.bible.locations().size());
        assertNotNull(forkStory.visualCharacter("npc_0"));
        assertEquals(originalStory, mapper.writeValueAsString(story));
        assertEquals(originalState, mapper.writeValueAsString(state));
    }

    @Test void aLongLocationIdIsRepairedWithoutTruncatingItIntoAnExistingRoom() {
        CompiledStory story = story();
        String prefix = "r".repeat(48);
        story.registerLocation(new LocationProfile(prefix, "Original place", "original", "original"));
        AtomicInteger calls = new AtomicInteger();
        var generator = generator(request -> SceneJson.scene("A newly discovered place.")
                .at(calls.getAndIncrement() == 0 ? prefix + "_new_room" : "room_new")
                .choice("next", "Continue", "action").build());
        var result = generator.generate(new SceneRequest(story, state(), null, SceneRequest.NONE, null, 1, false));
        assertEquals(2, calls.get());
        assertEquals("room_new", result.location().id());
        assertEquals("Original place", story.bible.location(prefix).name());
    }

    private SceneGenerator generator(java.util.function.Function<com.genvn.llm.LlmRequest, String> source) {
        return new SceneGenerator(new StructuredLlm(new ScriptedLlmClient(mapper, source), mapper, new LlmCallLog()),
                new ContextRenderer());
    }

    private void commit(CompiledStory story, GameState state, SceneBundle scene) {
        SceneStateProjector.register(story, state, scene);
        SceneStateProjector.apply(reducer, story, state, scene, null, null);
    }

    private static NpcProfile npc(int number) {
        return new NpcProfile("npc_" + number, "Person " + number, "identity " + number, "personality " + number,
                List.of(), List.of(), "voice " + number, "neutral", "appearance " + number);
    }

    private static SceneBundle scene(int index, String location, NpcProfile npc) {
        return new SceneBundle("scene_%03d".formatted(index), "beat",
                new SceneLocation(location, "Place " + location, "Appearance " + location, "Background " + location, null),
                npc == null ? List.of() : List.of(new CharacterPresence(npc.id(), npc.name(), "neutral", "right", npc.visualDescription(), null)),
                List.of(Block.narration("A new encounter.")), List.of(new Choice("next", "Continue", "action", null, List.of())),
                StateDelta.empty(), "", List.of(), npc == null ? List.of() : List.of(new NpcIntroduction(npc, null, false)),
                new SceneMeta("test", false, 0, SceneRequest.NONE, null, 0));
    }

    private static CompiledStory story() {
        return new CompiledStory(new AuthorCanon("house", List.of("house")),
                new StoryBible("house", "mystery", List.of(), List.of(),
                        List.of(new LocationProfile("room_0", "Place room_0", "Appearance room_0", "Appearance room_0")),
                        List.of(), List.of(), List.of(), List.of()),
                new StorySpine("arc", List.of(new StoryBeat("beat", "Explore", "Explore", "Learn", "major"))));
    }

    private static GameState state() {
        GameState state = new GameState();
        state.sessionId = "archive-test";
        state.currentBeatId = "beat";
        state.currentLocationId = "room_0";
        return state;
    }
}
