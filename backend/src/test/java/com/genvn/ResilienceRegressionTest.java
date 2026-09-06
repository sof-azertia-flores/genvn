package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.ApiExceptionHandler;
import com.genvn.api.HistoryController;
import com.genvn.asset.AssetKind;
import com.genvn.asset.AssetPipeline;
import com.genvn.asset.AssetResolver;
import com.genvn.asset.AssetSpec;
import com.genvn.asset.AssetStatus;
import com.genvn.asset.AssetStore;
import com.genvn.asset.ImageProviderException;
import com.genvn.asset.ImageRequest;
import com.genvn.asset.ImageResult;
import com.genvn.asset.VisualPlanner;
import com.genvn.config.ImageProperties;
import com.genvn.game.GameState;
import com.genvn.game.SceneStateProjector;
import com.genvn.game.SessionHistoryService;
import com.genvn.game.SessionService;
import com.genvn.game.StateDelta;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.LlmClient;
import com.genvn.llm.LlmException;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.LlmResponse;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.Block;
import com.genvn.narrative.CharacterPresence;
import com.genvn.narrative.NpcIntroduction;
import com.genvn.narrative.SceneBundle;
import com.genvn.narrative.SceneCast;
import com.genvn.narrative.SceneGenerator;
import com.genvn.narrative.SceneLocation;
import com.genvn.narrative.SceneRequest;
import com.genvn.prompt.ContextRenderer;
import com.genvn.story.AuthorCanon;
import com.genvn.story.CompiledStory;
import com.genvn.story.LocationProfile;
import com.genvn.story.NpcProfile;
import com.genvn.story.StoryBeat;
import com.genvn.story.StoryBible;
import com.genvn.story.StorySpine;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import com.genvn.support.SceneJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regression coverage for the second audit round: things that used to wedge, leak or grow. */
class ResilienceRegressionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("an unreadable picture manifest leaves the story playable and says why")
    void unreadablePictureManifestDoesNotStopTheProse(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("s1"));
        Files.writeString(dir.resolve("s1/manifest.json"), "{ this is not json");
        AssetPipeline pipeline = new AssetPipeline(new FakeImageProvider(), new AssetStore(mapper, dir),
                Engine.imageProperties(), mapper);
        try {
            assertTrue(pipeline.snapshot("s1").isEmpty());
            assertNotNull(pipeline.status("s1").get("manifestError"), "the status endpoint names the problem");

            SceneGenerator generator = new SceneGenerator(scripted(r -> SceneJson.scene("Rain.")
                    .choice("c1", "Go", "action").build()), new ContextRenderer(), new AssetResolver(pipeline));
            GameState state = state();
            state.sessionId = "s1";
            SceneBundle scene = generator.generate(new SceneRequest(story(), state, null, SceneRequest.NONE, null, 1, false));
            assertEquals("Rain.", scene.blocks().get(0).text());
            assertNull(scene.location().backgroundAssetId());
        } finally {
            pipeline.shutdown();
        }

        // Any lookup that throws, not only the pipeline's, is survivable.
        SceneGenerator generator = new SceneGenerator(scripted(r -> SceneJson.scene("Wind.")
                .choice("c1", "Go", "action").build()), new ContextRenderer(),
                new AssetResolver(id -> { throw new UncheckedIOException(new IOException("disk gone")); }));
        assertEquals("Wind.", generator.generate(new SceneRequest(story(), state(), null, SceneRequest.NONE, null, 1, false))
                .blocks().get(0).text());
    }

    @Test
    @DisplayName("an image worker survives an Error from one picture and keeps serving the next")
    void anImageWorkerSurvivesAnErrorFromTheProvider(@TempDir Path dir) {
        FakeImageProvider fake = new FakeImageProvider() {
            @Override
            public ImageResult generate(ImageRequest request) throws ImageProviderException {
                if (request.prompt().contains("boom")) throw new OutOfMemoryError("simulated decoder blow-up");
                return super.generate(request);
            }
        };
        ImageProperties props = Engine.imageProperties();
        props.setConcurrency(1);
        AssetPipeline pipeline = new AssetPipeline(fake, new AssetStore(mapper, dir), props, mapper);
        try {
            pipeline.adopt("s1", new VisualPlanner.Plan("style", "k", List.of(bg("boom", 0), bg("fine", 1))));
            settle(pipeline, "s1");
            var m = pipeline.snapshot("s1").orElseThrow();
            assertEquals(AssetStatus.FAILED, m.get("bg.boom.default").status);
            assertEquals(AssetStatus.READY, m.get("bg.fine.default").status, "the only worker is still alive");
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    @DisplayName("arc-budget 0 means no cap, while concurrency and bounded retries still apply")
    void anArcBudgetOfZeroMeansNoCap(@TempDir Path dir) {
        ImageProperties props = Engine.imageProperties();
        props.setArcBudget(0);
        props.setFirstBatchBudget(100);
        FakeImageProvider fake = new FakeImageProvider();
        AssetPipeline pipeline = new AssetPipeline(fake, new AssetStore(mapper, dir), props, mapper);
        try {
            List<AssetSpec> specs = new ArrayList<>();
            for (int i = 0; i < 30; i++) specs.add(bg("loc_" + i, i));
            pipeline.adopt("s1", new VisualPlanner.Plan("style", "k", specs));
            settle(pipeline, "s1");
            var m = pipeline.snapshot("s1").orElseThrow();
            assertEquals(30, m.records.values().stream().filter(r -> r.status == AssetStatus.READY).count());
            assertFalse(m.budget.paused);
            assertEquals(30, fake.calls.get());
            assertTrue(fake.highWater.get() <= 2, "concurrency is still bounded");

            fake.failWith = prompt -> new ImageProviderException("flaky", true);
            pipeline.adopt("s1", new VisualPlanner.Plan("style", "k", List.of(bg("loc_flaky", 99))));
            settle(pipeline, "s1");
            var flaky = pipeline.snapshot("s1").orElseThrow().get("bg.loc_flaky.default");
            assertEquals(AssetStatus.FAILED, flaky.status, "retries are still bounded by max-attempts");
            assertEquals(props.getMaxAttempts(), flaky.attempts);
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    @DisplayName("a new session id never reuses one that already names a save")
    void newSessionIdsSkipCandidatesThatAlreadyNameASave() {
        Set<String> offered = new HashSet<>();
        AtomicInteger rejected = new AtomicInteger();
        String id = SessionService.uniqueSessionId(candidate -> {
            offered.add(candidate);
            return rejected.getAndIncrement() < 2;
        });
        assertEquals(8, id.length());
        assertEquals(3, offered.size());
        assertTrue(offered.contains(id));
    }

    @Test
    @DisplayName("an unsafe new location id is repaired before it can be registered or collide with an asset")
    void anUnsafeNewLocationIdIsRepairedBeforeItIsRegistered() {
        CompiledStory story = story();
        AtomicInteger calls = new AtomicInteger();
        SceneGenerator generator = new SceneGenerator(scripted(r -> SceneJson.scene("A door.")
                .at(calls.getAndIncrement() == 0 ? "The Old Attic!!" : "the_old_attic")
                .choice("c1", "Go", "action").build()), new ContextRenderer());
        SceneBundle scene = generator.generate(new SceneRequest(story, state(), null, SceneRequest.NONE, null, 1, false));
        assertEquals(2, calls.get());
        assertEquals("the_old_attic", scene.location().id());
        SceneStateProjector.register(story, state(), scene);
        assertNotNull(story.bible.location("the_old_attic"));

        SceneGenerator garbage = new SceneGenerator(scripted(r -> SceneJson.scene("A door.")
                .at("???").choice("c1", "Go", "action").build()), new ContextRenderer());
        assertThrows(LlmException.class, () -> garbage.generate(
                new SceneRequest(story, state(), null, SceneRequest.NONE, null, 1, false)));
    }

    @Test
    @DisplayName("a long game's supporting cast retains every canonical identity")
    void transientSupportingCastRetainsItsCanonicalArchive() {
        CompiledStory story = story();
        GameState state = state();
        for (int i = 1; i <= 40; i++) {
            String id = "npc_extra_" + i;
            NpcProfile profile = new NpcProfile(id, "Extra " + i, "a passer-by", "quiet", List.of(), List.of(),
                    "short", "neutral", "someone");
            SceneBundle scene = new SceneBundle("scene_%03d".formatted(i), "a",
                    new SceneLocation("loc_interior", "Hall", "", "", null),
                    List.of(new CharacterPresence(id, "Extra " + i, "neutral", "center", "someone", null)),
                    List.of(Block.narration("...")), List.of(), StateDelta.empty(), null, List.of(),
                    List.of(new NpcIntroduction(profile, null, false)), null);
            SceneCast.apply(story, state, scene);
        }
        assertEquals(40, story.encounteredNpcs.size());
        assertNotNull(story.visualCharacter("npc_extra_40"), "the person on stage right now is always kept");
        assertNotNull(story.visualCharacter("npc_extra_1"), "old identities remain available for a later encounter");
        assertTrue(story.bible.characters().isEmpty(), "nonrecurring NPCs need not enter the Bible to retain identity");
    }

    @Test
    @DisplayName("a rejected request is not retried; a flapping one is resent unchanged, three times")
    void aRejectedRequestIsNotRetriedButAFlappingOneIsResentUnchanged() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient rejecting = client(r -> {
            calls.incrementAndGet();
            throw new LlmException("LLM HTTP 401: bad key", 401);
        });
        LlmException e = assertThrows(LlmException.class, () -> new StructuredLlm(rejecting, mapper, new LlmCallLog())
                .call(LlmRequest.of(LlmPurpose.SCENE_GENERATE, "sys", "user", Map.of()), Map.class, null));
        assertEquals(1, calls.get());
        assertFalse(e.isRetryable());
        assertEquals(401, e.statusCode());

        List<String> prompts = new ArrayList<>();
        LlmClient flapping = client(r -> {
            prompts.add(r.user());
            throw new LlmException("LLM HTTP 503: busy", 503);
        });
        assertThrows(LlmException.class, () -> new StructuredLlm(flapping, mapper, new LlmCallLog())
                .call(LlmRequest.of(LlmPurpose.SCENE_GENERATE, "sys", "user", Map.of()), Map.class, null));
        assertEquals(List.of("user", "user", "user"), prompts, "a transport failure is not a rejected answer to repair");
    }

    @Test
    @DisplayName("a malformed history parameter is a bad request, not a server error")
    void aMalformedHistoryParameterIsABadRequestNotAServerError() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new HistoryController(mock(SessionHistoryService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/sessions/s1/history").param("throughSceneId", "scene_001").param("throughBlockIndex", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    @DisplayName("a failed picture adopts the planner's newer prompt, and a manual retry sends that text")
    void aFailedPictureTakesTheNewerPromptBeforeItIsRetried(@TempDir Path dir) {
        FakeImageProvider fake = new FakeImageProvider();
        ImageProperties props = Engine.imageProperties();
        AssetPipeline pipeline = new AssetPipeline(fake, new AssetStore(mapper, dir), props, mapper);
        try {
            AssetSpec old = bg("loc_a", 0).withPrompt("old wording");
            fake.failWith = prompt -> new ImageProviderException("opaque", false);
            pipeline.adopt("s1", new VisualPlanner.Plan("style", "k", List.of(old)));
            settle(pipeline, "s1");
            assertEquals(AssetStatus.FAILED, pipeline.snapshot("s1").orElseThrow().get(old.assetId()).status);

            pipeline.adoptSpecs("s1", List.of(old.withPrompt("new wording")));
            assertEquals("new wording", pipeline.snapshot("s1").orElseThrow().get(old.assetId()).spec.prompt());

            fake.failWith = null;
            pipeline.manualRetry("s1", old.assetId());
            settle(pipeline, "s1");
            assertEquals(AssetStatus.READY, pipeline.snapshot("s1").orElseThrow().get(old.assetId()).status);
            assertEquals("new wording", fake.prompts().get(fake.prompts().size() - 1));

            // A finished picture keeps the wording that produced it.
            pipeline.adoptSpecs("s1", List.of(old.withPrompt("third wording")));
            assertEquals("new wording", pipeline.snapshot("s1").orElseThrow().get(old.assetId()).spec.prompt());
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    @DisplayName("a manual retry re-derives the pose prompt from the current planner before sending it")
    void aManualRetryUsesTheCurrentPlannerWording(@TempDir Path dir) throws Exception {
        FakeImageProvider fake = new FakeImageProvider();
        ImageProperties props = Engine.imageProperties();
        props.getPlan().setCharacters(0);
        props.getPlan().setLocations(0);
        Engine engine = new Engine(new com.genvn.llm.MockLlmClient(mapper), new com.genvn.support.ScriptedRandom(12),
                false, false, fake, props, dir);
        try {
            fake.failWith = prompt -> prompt.contains("pose") ? new ImageProviderException("opaque", false) : null;
            var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            engine.awaitAssets(session.id);
            var talking = engine.assets.snapshot(session.id).orElseThrow().get("pt.player.talking");
            assertEquals(AssetStatus.FAILED, talking.status);

            // Simulate a save whose failed record still carries wording from an older build.
            engine.assets.adoptSpecs(session.id, List.of(talking.spec.withPrompt("older wording, painted background")));
            assertEquals("older wording, painted background",
                    engine.assets.snapshot(session.id).orElseThrow().get("pt.player.talking").spec.prompt());

            var coordinator = new com.genvn.asset.AssetCoordinator(engine.assets, new VisualPlanner(props), props);
            fake.failWith = null;
            var controller = new com.genvn.api.AssetController(engine.assets, new AssetStore(mapper, dir), engine.repository, coordinator);
            controller.retry(session.id, "pt.player.talking");
            engine.awaitAssets(session.id);

            var retried = engine.assets.snapshot(session.id).orElseThrow().get("pt.player.talking");
            assertEquals(AssetStatus.READY, retried.status);
            assertTrue(retried.spec.prompt().startsWith("Same character as the reference;"), retried.spec.prompt());
            assertTrue(fake.prompts().get(fake.prompts().size() - 1).startsWith("Same character as the reference;"));
        } finally {
            engine.assets.shutdown();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static AssetSpec bg(String loc, int priority) {
        return new AssetSpec(AssetSpec.backgroundId(loc, "default"), AssetKind.BACKGROUND, loc, loc, "default",
                "background of " + loc, "k", "b1", priority, null, AssetSpec.GENERAL, true);
    }

    private static void settle(AssetPipeline pipeline, String sid) {
        for (int i = 0; i < 400; i++) {
            var st = pipeline.status(sid);
            if ((int) st.get("pending") == 0 && pipeline.activeCount() == 0) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { return; }
        }
    }

    private static LlmClient client(Function<LlmRequest, LlmResponse> responses) {
        return new LlmClient() {
            @Override public LlmResponse complete(LlmRequest request) { return responses.apply(request); }
            @Override public String describe() { return "test"; }
            @Override public boolean isMock() { return true; }
        };
    }

    private StructuredLlm scripted(Function<LlmRequest, String> responses) {
        return new StructuredLlm(client(r -> new LlmResponse(responses.apply(r), "test", 0)), mapper, new LlmCallLog());
    }

    private static GameState state() {
        GameState state = new GameState();
        state.sessionId = "test";
        state.player = Engine.alex();
        state.currentBeatId = "a";
        state.currentArcTitle = "A mystery";
        state.currentLocationId = "loc_interior";
        return state;
    }

    private static CompiledStory story() {
        StoryBible bible = new StoryBible("A mystery in a house", "mysterious", List.of(), List.of(),
                List.of(new LocationProfile("loc_interior", "Hall", "A room", "A dim room")),
                List.of(), List.of(), List.of(), List.of());
        return new CompiledStory(new AuthorCanon(Engine.OUTLINE, List.of("There is a house.")),
                bible, new StorySpine("A mystery", List.of(new StoryBeat("a", "Beat a", "Investigate", "Find something", "major"))));
    }

    @SuppressWarnings("unused")
    private static Optional<Void> unused() { return Optional.empty(); }
}
