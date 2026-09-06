package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.config.GenvnProperties;
import com.genvn.game.DeltaOp;
import com.genvn.game.GameSession;
import com.genvn.game.GameState;
import com.genvn.game.StateDelta;
import com.genvn.game.StateReducer;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.LlmClient;
import com.genvn.llm.LlmException;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.LlmResponse;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.SceneBundle;
import com.genvn.narrative.SceneGenerator;
import com.genvn.narrative.SceneRequest;
import com.genvn.prompt.ContextRenderer;
import com.genvn.story.ArcContinuationService;
import com.genvn.story.ArcOutline;
import com.genvn.story.AuthorCanon;
import com.genvn.story.CompiledStory;
import com.genvn.story.CompilerResponse;
import com.genvn.story.LocationProfile;
import com.genvn.story.StoryBeat;
import com.genvn.story.StoryBible;
import com.genvn.story.StoryCompiler;
import com.genvn.story.StorySpine;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Provider-shaped data exercises the parse, repair and runtime boundaries together. */
class GenerationValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compilerRepairsDuplicateBeatIdsBeforeCreatingState() {
        AtomicInteger calls = new AtomicInteger();
        StructuredLlm llm = scripted(request -> {
            boolean malformed = calls.getAndIncrement() == 0;
            CompiledStory story = story(malformed ? new String[]{"a", "a", "c", "d", "e"} : new String[]{"a", "b", "c", "d", "e"});
            return json(new CompilerResponse(List.of("The player visits a house."), story.bible,
                    story.spine, "loc_interior", List.of()));
        });

        var compiled = new StoryCompiler(llm).compile("test", Engine.OUTLINE, Engine.alex());

        assertEquals(2, calls.get());
        assertEquals(1, compiled.repairAttempts());
        assertEquals(List.of("a", "b", "c", "d", "e"), compiled.story().spine.beats().stream().map(StoryBeat::id).toList());
    }

    @Test
    void continuationRepairsDuplicateBeatIdsBeforeReturningAnArc() {
        AtomicInteger calls = new AtomicInteger();
        StructuredLlm llm = scripted(request -> {
            boolean malformed = calls.getAndIncrement() == 0;
            List<StoryBeat> beats = story(malformed ? new String[]{"a", "a", "c", "d"} : new String[]{"a", "b", "c", "d"}).spine.beats();
            return json(new ArcOutline("Next arc", "A new mystery", "Find the answer", beats,
                    List.of(), List.of(), List.of()));
        });
        GameSession session = new GameSession();
        session.id = "test";
        session.story = story("old");
        session.state = state("old");

        ArcOutline arc = new ArcContinuationService(llm, new ContextRenderer(), new GenvnProperties()).plan(session);

        assertEquals(2, calls.get());
        assertEquals(List.of("a", "b", "c", "d"), arc.beats().stream().map(StoryBeat::id).toList());
    }

    @Test
    void oldSavesWithRepeatedBeatIdsAdvanceWithoutCycling() {
        CompiledStory story = story("a", "b", "a", "b", "c", "c");
        GameState state = state("a");
        StateReducer reducer = new StateReducer();

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertEquals("b", story.spine.nextAfter("a").id());
            assertEquals("c", story.spine.nextAfter("b").id());
            assertNull(story.spine.nextAfter("c"));
            for (String beat : List.of("a", "b", "c")) {
                assertEquals(beat, state.currentBeatId);
                state.scenesInCurrentBeat = 1; // a scene has already played inside this beat
                var applied = reducer.apply(state, story,
                        StateDelta.of(DeltaOp.of(DeltaOp.COMPLETE_BEAT, beat)), "scene_test");
                assertEquals(1, applied.applied().size());
            }
        });
        assertNull(state.currentBeatId);
        assertEquals(List.of("a", "b", "c"), state.completedBeats);
    }

    @Test
    void completingTheLastBeatAllowsAnEndingWithoutChoices() {
        CompiledStory story = story("last");
        GameState state = state("last");
        state.scenesInCurrentBeat = 1; // an ending cannot arrive in the final beat's first scene
        SceneGenerator generator = generator(request -> SceneJson.scene("The mystery is solved.")
                .op("{\"op\":\"completeBeat\",\"target\":\"last\"}").build());

        SceneBundle scene = generator.generate(request(story, state));

        assertTrue(scene.choices().isEmpty());
        assertEquals(0, scene.meta().repairAttempts());
        assertEquals("last", state.currentBeatId, "validation must not commit the projected ending");
        assertTrue(state.completedBeats.isEmpty());
        new StateReducer().apply(state, story, scene.proposedStateDelta(), scene.sceneId());
        assertNull(state.currentBeatId, "the actual commit agrees with the validator's ending");
    }

    @Test
    void blankChoicesMustBeRepairedInsteadOfSilentlyEndingAnUnfinishedStory() {
        AtomicInteger calls = new AtomicInteger();
        SceneGenerator generator = generator(request -> calls.getAndIncrement() == 0
                ? SceneJson.scene("There is more to discover.").choice("c1", "   ", "action").build()
                : SceneJson.scene("There is more to discover.").choice("c1", "Keep searching", "action").build());

        SceneBundle scene = generator.generate(request(story("a"), state("a")));

        assertEquals(2, calls.get());
        assertEquals(1, scene.meta().repairAttempts());
        assertEquals("Keep searching", scene.choices().get(0).text());
    }

    @Test
    void choiceIdsAreStableUniqueAndSafeAsUrlPathSegments() {
        SceneGenerator generator = generator(request -> SceneJson.scene("Choose a path.")
                .choice("中文/?#", "调查房间", "action")
                .choice("c1", "Ask the witness", "social")
                .choice("foo/bar", "Open the door", "action")
                .choice("c1", "Wait here", "cautious").build());
        SceneRequest request = request(story("a"), state("a"));

        SceneBundle first = generator.generate(request);
        SceneBundle repeated = generator.generate(request);

        assertEquals(List.of("c1", "c2", "c3", "c4"),
                first.choices().stream().map(com.genvn.narrative.Choice::id).toList());
        assertEquals(first.choices(), repeated.choices(), "normalization is deterministic across retries");
        assertEquals(List.of("调查房间", "Ask the witness", "Open the door", "Wait here"),
                first.choices().stream().map(com.genvn.narrative.Choice::text).toList());
        assertTrue(first.choices().stream().allMatch(choice -> choice.id().matches("[A-Za-z0-9_-]{1,64}")));
    }

    @Test
    void rejectedCompletionCannotAuthorizeAnEnding() {
        GameState state = state("a");
        AtomicInteger calls = new AtomicInteger();
        SceneGenerator generator = generator(request -> {
            calls.incrementAndGet();
            return SceneJson.scene("A premature ending.")
                    .op("{\"op\":\"completeBeat\",\"target\":\"missing\"}").build();
        });

        assertThrows(LlmException.class, () -> generator.generate(request(story("a"), state)));

        assertEquals(3, calls.get());
        assertEquals("a", state.currentBeatId);
        assertTrue(state.completedBeats.isEmpty());
        assertTrue(state.rejectedOpsLog.isEmpty(), "failed previews cannot add canonical rejection logs");
    }

    @Test
    void completingOneBeatStillRequiresChoicesWhenAnotherBeatRemains() {
        GameState state = state("a");
        SceneGenerator generator = generator(request -> SceneJson.scene("The first question is answered.")
                .op("{\"op\":\"completeBeat\",\"target\":\"a\"}").build());

        assertThrows(LlmException.class, () -> generator.generate(request(story("a", "b"), state)));

        assertEquals("a", state.currentBeatId);
        assertTrue(state.completedBeats.isEmpty());
    }

    private SceneGenerator generator(Function<LlmRequest, String> responses) {
        return new SceneGenerator(scripted(responses), new ContextRenderer(), new StateReducer(), mapper);
    }

    private StructuredLlm scripted(Function<LlmRequest, String> responses) {
        LlmClient client = new LlmClient() {
            @Override public LlmResponse complete(LlmRequest request) { return new LlmResponse(responses.apply(request), "test", 0); }
            @Override public String describe() { return "test"; }
            @Override public boolean isMock() { return true; }
        };
        return new StructuredLlm(client, mapper, new LlmCallLog());
    }

    private static SceneRequest request(CompiledStory story, GameState state) {
        return new SceneRequest(story, state, null, SceneRequest.NONE, null, 1, false);
    }

    private static GameState state(String beatId) {
        GameState state = new GameState();
        state.sessionId = "test";
        state.player = Engine.alex();
        state.currentBeatId = beatId;
        state.currentArcTitle = "A mystery";
        state.currentLocationId = "loc_interior";
        return state;
    }

    private static CompiledStory story(String... beatIds) {
        List<StoryBeat> beats = java.util.Arrays.stream(beatIds)
                .map(id -> new StoryBeat(id, "Beat " + id, "Investigate", "Find something", "major", "Something changes")).toList();
        StoryBible bible = new StoryBible("A mystery in a house", "mysterious", List.of(), List.of(),
                List.of(new LocationProfile("loc_interior", "Hall", "A room", "A dim room")),
                List.of(), List.of(), List.of(), List.of());
        return new CompiledStory(new AuthorCanon(Engine.OUTLINE, List.of("There is a house.")),
                bible, new StorySpine("A mystery", beats));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
