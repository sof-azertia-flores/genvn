package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.genvn.narrative.Block;
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
import com.genvn.story.NpcProfile;
import com.genvn.story.StoryBeat;
import com.genvn.story.StoryBible;
import com.genvn.story.StoryCompiler;
import com.genvn.story.StorySpine;
import com.genvn.support.Engine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stories must move: beats are phases with turns, not checkboxes; people say new things; a
 * continuation is a bigger chapter, not a re-run of the last one.
 */
class NarrativeDriveTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("the current beat cannot open and close in the same scene; a later scene may close it")
    void aBeatCannotEndInItsOwnFirstScene() {
        CompiledStory story = story("a", "b", "c", "d", "e");
        GameState state = state("a");
        StateReducer reducer = new StateReducer();

        var first = reducer.apply(state, story, StateDelta.of(DeltaOp.of(DeltaOp.COMPLETE_BEAT, "a")), "scene_001");
        assertTrue(first.applied().isEmpty());
        assertTrue(first.rejected().get(0).contains("first scene"), first.rejected().toString());
        assertEquals("a", state.currentBeatId);

        state.scenesInCurrentBeat = 1; // one scene has already played inside this beat
        var second = reducer.apply(state, story, StateDelta.of(DeltaOp.of(DeltaOp.COMPLETE_BEAT, "a")), "scene_002");
        assertEquals(1, second.applied().size());
        assertEquals("b", state.currentBeatId);

        // Closing a beat that is not the current one was never subject to the rule.
        state.scenesInCurrentBeat = 0;
        var other = reducer.apply(state, story, StateDelta.of(DeltaOp.of(DeltaOp.COMPLETE_BEAT, "d")), "scene_003");
        assertEquals(1, other.applied().size());
    }

    @Test
    @DisplayName("an NPC who repeats a recent line is sent back for repair; a new line is accepted")
    void anNpcRepeatingARecentLineIsRepaired() {
        AtomicInteger calls = new AtomicInteger();
        SceneGenerator generator = generator(request -> calls.getAndIncrement() == 0
                ? sceneWithLine("你知道吗，其实不想让事情变大……但这次我会把纸收回来。")
                : sceneWithLine("我把那三个人的名字写下来了，明天一早交给你。"));
        GameState state = state("a");
        state.rememberScene("scene_001", List.of(Block.dialogue("npc_a", "周辰",
                "你知道吗，其实不想让事情变大……那天在文印室附近的人，确实只有我。", "worried")));

        SceneBundle scene = generator.generate(new SceneRequest(story("a", "b", "c", "d", "e"), state, null, SceneRequest.NONE, null, 2, false));

        assertEquals(2, calls.get(), "the repeated line was rejected once");
        assertEquals(1, scene.meta().repairAttempts());
        assertTrue(scene.blocks().get(0).text().startsWith("我把那三个人"));
    }

    @Test
    @DisplayName("a short phrase or a different speaker never counts as repetition")
    void shortOverlapsAndOtherSpeakersPass() {
        AtomicInteger calls = new AtomicInteger();
        SceneGenerator generator = generator(request -> { calls.incrementAndGet(); return sceneWithLine("你知道吗，登记册上少了一页。"); });
        GameState state = state("a");
        state.rememberScene("scene_001", List.of(
                Block.dialogue("npc_a", "周辰", "你知道吗，其实不想让事情变大。", "worried"),
                Block.dialogue("npc_b", "刘老师", "你知道吗，登记册上少了一页。", "neutral")));
        generator.generate(new SceneRequest(story("a", "b", "c", "d", "e"), state, null, SceneRequest.NONE, null, 2, false));
        assertEquals(1, calls.get(), "a four-character opener and another person's line are not a repeat");
    }

    @Test
    @DisplayName("the writer is told how far the current beat has run and what its turn is")
    void thePromptCarriesBeatPacing() {
        AtomicReference<String> prompt = new AtomicReference<>();
        SceneGenerator generator = generator(request -> { prompt.set(request.user()); return sceneWithLine("新的一句。"); });
        CompiledStory story = story("a", "b", "c", "d", "e");
        GameState state = state("a");

        generator.generate(new SceneRequest(story, state, null, SceneRequest.NONE, null, 1, false));
        assertTrue(prompt.get().contains("FIRST scene"), prompt.get());
        assertTrue(prompt.get().contains("turn: Something changes in a"), "the beat's turn is in the briefing");
        assertTrue(prompt.get().contains("Scenes played inside this beat so far: 0"));

        state.scenesInCurrentBeat = 4;
        generator.generate(new SceneRequest(story, state, null, SceneRequest.NONE, null, 2, false));
        assertTrue(prompt.get().contains("OVERDUE"), prompt.get());
    }

    @Test
    @DisplayName("a continuation is briefed with every beat already played and must return a real plot")
    void continuationIsBriefedWithPlayedBeatsAndNeedsRealBeats() {
        AtomicReference<String> prompt = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        StructuredLlm llm = new StructuredLlm(client(request -> {
            prompt.set(request.user());
            List<StoryBeat> beats = calls.getAndIncrement() == 0
                    ? List.of(new StoryBeat("x", "Sketch", "p", "c", "major", "t"), new StoryBeat("y", "Sketch 2", "p", "c", "major", null))
                    : story("x", "y", "z", "w").spine.beats();
            return new LlmResponse(json(new ArcOutline("Next", "A letter arrives", "Someone pushes back", beats,
                    List.of(), List.of(), List.of())), "test", 0);
        }), mapper, new LlmCallLog());
        GameSession session = new GameSession();
        session.id = "t";
        session.story = story("a", "b", "c", "d", "e");
        session.story.laterArcs.add(new ArcOutline("Earlier arc", "p", "c",
                List.of(new StoryBeat("old", "The old lighthouse", "p", "c", "major", "t")), List.of(), List.of(), List.of()));
        session.state = state("e");

        ArcOutline outline = new ArcContinuationService(llm, new ContextRenderer(), new GenvnProperties()).plan(session);

        assertEquals(2, calls.get(), "two beats, one without a turn, went back for repair");
        assertEquals(4, outline.beats().size());
        assertTrue(prompt.get().contains("BEATS ALREADY PLAYED"));
        assertTrue(prompt.get().contains("The old lighthouse"), "beats of earlier arcs are listed");
        assertTrue(prompt.get().contains("Beat a"), "beats of the arc being finished are listed");
        assertTrue(llmSystemOf(llm).contains("NEW INCITING EVENT"));
    }

    @Test
    @DisplayName("a compiled story with too few beats, or beats without turns, is sent back")
    void aSketchOfAStoryIsRejected() {
        AtomicInteger calls = new AtomicInteger();
        StructuredLlm llm = new StructuredLlm(client(request -> {
            int call = calls.getAndIncrement();
            CompiledStory story = call == 0 ? story("a", "b", "c")
                    : call == 1 ? withoutTurns(story("a", "b", "c", "d", "e"))
                    : story("a", "b", "c", "d", "e");
            return new LlmResponse(json(new CompilerResponse(List.of("There is a house."), story.bible, story.spine,
                    "loc_interior", List.of())), "test", 0);
        }), mapper, new LlmCallLog());

        var compiled = new StoryCompiler(llm).compile("t", Engine.OUTLINE, Engine.alex());

        assertEquals(3, calls.get());
        assertEquals(2, compiled.repairAttempts());
        assertEquals(5, compiled.story().spine.beats().size());
    }

    // ------------------------------------------------------------------ helpers

    private static String llmSystemOf(StructuredLlm llm) {
        return com.genvn.prompt.Prompts.ARC_SYSTEM;
    }

    private static CompiledStory withoutTurns(CompiledStory story) {
        List<StoryBeat> beats = new ArrayList<>();
        for (StoryBeat b : story.spine.beats()) beats.add(new StoryBeat(b.id(), b.title(), b.purpose(), b.completionConditions(), b.importance(), null));
        return new CompiledStory(story.authorCanon, story.bible, new StorySpine(story.spine.arcTitle(), beats));
    }

    private String sceneWithLine(String text) {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("location").put("id", "loc_interior").put("name", "Hall").put("visualDescription", "a room").put("backgroundPrompt", "a room");
        root.putArray("characters").addObject().put("characterId", "npc_a").put("name", "周辰").put("expression", "neutral").put("position", "center");
        root.putArray("blocks").addObject().put("type", "dialogue").put("speakerId", "npc_a").put("speakerName", "周辰").put("text", text);
        ArrayNode choices = root.putArray("choices");
        choices.addObject().put("id", "c1").put("text", "Go on").put("approach", "action").putNull("check");
        root.putObject("proposedStateDelta").putArray("ops");
        root.put("storyProgressNote", "test");
        root.putArray("assetRequests");
        return root.toString();
    }

    private SceneGenerator generator(Function<LlmRequest, String> responses) {
        return new SceneGenerator(new StructuredLlm(client(r -> new LlmResponse(responses.apply(r), "test", 0)), mapper, new LlmCallLog()),
                new ContextRenderer(), new StateReducer(), mapper);
    }

    private static LlmClient client(Function<LlmRequest, LlmResponse> responses) {
        return new LlmClient() {
            @Override public LlmResponse complete(LlmRequest request) { return responses.apply(request); }
            @Override public String describe() { return "test"; }
            @Override public boolean isMock() { return true; }
        };
    }

    private static GameState state(String beatId) {
        GameState state = new GameState();
        state.sessionId = "test";
        state.player = Engine.alex();
        state.currentBeatId = beatId;
        state.currentArcTitle = "A mystery";
        state.currentLocationId = "loc_interior";
        state.characters.put("npc_a", new com.genvn.game.CharacterState("npc_a", "周辰"));
        state.characters.put("npc_b", new com.genvn.game.CharacterState("npc_b", "刘老师"));
        return state;
    }

    private static CompiledStory story(String... beatIds) {
        List<StoryBeat> beats = java.util.Arrays.stream(beatIds)
                .map(id -> new StoryBeat(id, "Beat " + id, "Investigate", "Find something", "major", "Something changes in " + id)).toList();
        StoryBible bible = new StoryBible("A mystery in a house", "mysterious", List.of("trust"),
                List.of(new NpcProfile("npc_a", "周辰", "a student", "anxious", List.of(), List.of(), "short", "wary", "a boy"),
                        new NpcProfile("npc_b", "刘老师", "a teacher", "firm", List.of(), List.of(), "measured", "neutral", "a woman")),
                List.of(new LocationProfile("loc_interior", "Hall", "A room", "A dim room")),
                List.of(), List.of(), List.of("There is a house."), List.of());
        return new CompiledStory(new AuthorCanon(Engine.OUTLINE, List.of("There is a house.")),
                bible, new StorySpine("A mystery", beats));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new AssertionError(e); }
    }
}
