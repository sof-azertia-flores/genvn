package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.config.GenvnProperties;
import com.genvn.game.*;
import com.genvn.narrative.*;
import com.genvn.prompt.ContextRenderer;
import com.genvn.speculation.*;
import com.genvn.story.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class SecondRoundSpeculationTest {
    @Test void secondRoundWaitsForEveryFirstBranchThenUsesExactlyTheFourLargestProducts() throws Exception {
        try (Fixture f = new Fixture(true, false, false)) {
            String before = f.mapper.writeValueAsString(f.session);
            f.speculative.prefetch(f.session);
            assertTrue(f.source.firstEntered.await(5, TimeUnit.SECONDS));
            await(() -> f.cache.status(f.session.id).stream().filter(row -> "ready".equals(row.get("status"))).count() == 3);
            assertEquals(0, f.estimator.calls.get(), "Ranking cannot begin with one first-round branch still outstanding");
            assertEquals("waiting_first_round", f.speculative.secondRoundStatus(f.session).status());
            assertEquals(0, f.source.secondCalls().size());
            f.source.firstRelease.countDown();
            await(() -> "ready".equals(f.speculative.secondRoundStatus(f.session).status()));

            var status = f.speculative.secondRoundStatus(f.session);
            assertEquals(4, status.limit());
            assertEquals(List.of("a/x", "b/x", "a/y", "c/x"), status.candidates().stream()
                    .map(row -> row.parentChoiceId() + "/" + row.choiceId()).toList());
            assertEquals(List.of(0.3, 0.24, 0.15, 0.135), status.candidates().stream().map(row -> row.probability()).toList());
            assertEquals(4, f.source.secondCalls().size());
            assertEquals(1, f.estimator.calls.get());
            assertEquals(List.of("询问守门人"), f.estimator.history);
            assertEquals(before, f.mapper.writeValueAsString(f.session), "Neither layer can mutate canonical state or story");
            for (Call call : f.source.secondCalls()) {
                assertEquals("scene_001", call.sceneId);
                assertEquals("room_" + call.parent, call.location);
                assertEquals(1, call.playedScenes);
                assertTrue(call.rememberedOpening, "The second layer must receive the projected parent prose");
                assertTrue(call.registeredRoom, "New parent locations must exist only on that projected story");
            }
        }
    }

    @Test void checkedSecondChoicesAreWrittenForTheirPreCastOutcomeOnlyAndStillRespectTheFourJobLimit() throws Exception {
        try (Fixture f = new Fixture(false, false, true)) {
            f.speculative.prefetch(f.session);
            await(() -> "ready".equals(f.speculative.secondRoundStatus(f.session).status()));
            var candidates = f.speculative.secondRoundStatus(f.session).candidates();
            assertEquals(4, candidates.size());
            // Each parent branch cast the die for its checked child, so "x" is one job, not two.
            assertEquals(List.of("a/x", "b/x", "a/y", "c/x"), candidates.stream()
                    .map(row -> row.parentChoiceId() + "/" + row.choiceId()).toList());
            assertEquals(List.of(true, true, false, true), candidates.stream().map(row -> row.check()).toList());
            assertEquals(List.of(0.3, 0.24, 0.15, 0.135), candidates.stream().map(row -> row.probability()).toList());
            assertTrue(candidates.stream().noneMatch(row -> row.key().contains("SUCCESS") || row.key().contains("FAILURE")),
                    "the player-facing view never says how a pre-cast die landed");
            assertNull(f.session.pendingRoll, "Predictive work never reveals or persists a die");
            assertEquals(4, f.source.secondCalls().size());
            for (Call call : f.source.secondCalls()) {
                if (call.choice.equals("x")) assertTrue(List.of("SUCCESS", "FAILURE").contains(call.outcome));
                else assertEquals("NONE", call.outcome);
            }
        }
    }

    @Test void selectedParentsPreparedSuccessorsArePromotedWithoutGeneratingThemAgain() throws Exception {
        try (Fixture f = new Fixture(false, false, false)) {
            f.speculative.prefetch(f.session);
            await(() -> "ready".equals(f.speculative.secondRoundStatus(f.session).status()));
            BranchKey key = new BranchKey("scene_000", "a", SceneRequest.NONE);
            var scene = f.cache.takeIfFresh(f.session.id, key, f.session.state.stateVersion);
            assertNotNull(scene);
            var selected = f.session.currentScene.choice("a");
            f.speculative.prioritize(f.session, key);
            synchronized (f.session) {
                SceneStateProjector.register(f.session.story, f.session.state, scene);
                SceneStateProjector.apply(f.reducer, f.session.story, f.session.state, scene, selected, null);
                f.session.currentScene = scene;
                f.session.sceneCounter++;
                var history = new GameSession.HistoryEntry(scene.sceneId(), scene.beatId(), selected.text(), null, scene.blocks().getFirst().text());
                history.blocks = scene.blocks();
                f.session.history.add(history);
                f.cache.discardAll(f.session.id);
                f.speculative.prefetch(f.session);
            }
            await(() -> f.cache.status(f.session.id).size() == 3
                    && f.cache.status(f.session.id).stream().allMatch(row -> "ready".equals(row.get("status"))));
            assertNotNull(f.cache.takeIfFresh(f.session.id, new BranchKey("scene_001", "x", SceneRequest.NONE), f.session.state.stateVersion));
            assertNotNull(f.cache.takeIfFresh(f.session.id, new BranchKey("scene_001", "y", SceneRequest.NONE), f.session.state.stateVersion));
            assertEquals(1, f.source.secondCalls().stream().filter(call -> call.parent.equals("a") && call.choice.equals("x")).count());
            assertEquals(1, f.source.secondCalls().stream().filter(call -> call.parent.equals("a") && call.choice.equals("y")).count());
            assertEquals("a", f.session.state.flags.get("parent"));
            assertFalse(f.session.state.flags.containsKey("child"), "Promoted cache entries still cannot apply their deltas to canon");
        }
    }

    @Test void lateProbabilityResultsAreDiscardedWhenTheCanonicalStateHasMovedOn() throws Exception {
        try (Fixture f = new Fixture(false, true, false)) {
            f.speculative.prefetch(f.session);
            assertTrue(f.estimator.entered.await(5, TimeUnit.SECONDS));
            synchronized (f.session) { f.session.state.stateVersion++; }
            f.estimator.release.countDown();
            assertTrue(f.estimator.exited.await(5, TimeUnit.SECONDS));
            await(() -> "skipped".equals(f.speculative.secondRoundStatus(f.session).status()));
            assertEquals(0, f.source.secondCalls().size());
            assertTrue(f.speculative.secondRoundStatus(f.session).candidates().isEmpty());
        }
    }

    @Test void deletingASessionInterruptsRankingAndCannotStartLateSecondRoundWork() throws Exception {
        try (Fixture f = new Fixture(false, true, false)) {
            f.speculative.prefetch(f.session);
            assertTrue(f.estimator.entered.await(5, TimeUnit.SECONDS));
            synchronized (f.session) { f.session.deleted = true; }
            f.speculative.cancelSession(f.session.id);
            f.cache.discardAll(f.session.id);
            assertTrue(f.estimator.exited.await(5, TimeUnit.SECONDS));
            assertEquals(0, f.source.secondCalls().size());
            assertEquals(0, f.cache.size(f.session.id));
            assertTrue(f.speculative.secondRoundStatus(f.session).candidates().isEmpty());
            assertTrue(f.speculative.prefetch(f.session).isEmpty());
        }
    }

    private record Call(String parent, String choice, String outcome, String sceneId, String location,
                        int playedScenes, boolean rememberedOpening, boolean registeredRoom) {}

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper();
        final BranchCache cache = new BranchCache();
        final StateReducer reducer = new StateReducer();
        final GameSession session = session();
        final Source source;
        final Estimate estimator;
        final SpeculativeGenerator speculative;

        Fixture(boolean holdLastFirst, boolean holdEstimator, boolean checkedChildren) {
            source = new Source(holdLastFirst, checkedChildren);
            estimator = new Estimate(holdEstimator);
            GenvnProperties properties = new GenvnProperties();
            properties.getSpeculation().setEnabled(true);
            properties.getSpeculation().setThreads(4);
            properties.getSpeculation().setMaxBranches(6);
            speculative = new SpeculativeGenerator(source, cache, reducer, mapper, properties, estimator);
        }
        @Override public void close() {
            source.firstRelease.countDown(); estimator.release.countDown(); speculative.close();
        }
    }

    private static final class Source extends SceneGenerator {
        final CountDownLatch firstEntered = new CountDownLatch(4);
        final CountDownLatch firstRelease;
        final List<Call> second = new CopyOnWriteArrayList<>();
        final boolean checkedChildren;
        Source(boolean holdLastFirst, boolean checkedChildren) {
            super(null, new ContextRenderer());
            firstRelease = new CountDownLatch(holdLastFirst ? 1 : 0);
            this.checkedChildren = checkedChildren;
        }
        @Override public SceneBundle generate(SceneRequest request) {
            String id = request.choice().id();
            boolean first = request.sceneIndex() == 1;
            if (first) {
                firstEntered.countDown();
                if (id.equals("d")) waitFor(firstRelease);
            } else {
                String parent = request.state().flags.get("parent");
                second.add(new Call(parent, id, request.outcome(), request.state().currentSceneId, request.state().currentLocationId,
                        request.state().storyProgress.scenesPlayed,
                        request.state().recentScenes.stream().anyMatch(memory -> memory.blocks().stream()
                                .anyMatch(block -> block.text().equals("parent " + parent))),
                        request.story().bible.location("room_" + parent) != null));
            }
            String room = first ? "room_" + id : request.state().currentLocationId;
            List<Choice> children = first ? List.of(
                    new Choice("x", "Inspect", "investigation", checkedChildren ? new Check("Perception", 12, "Inspect") : null, List.of()),
                    choice("y"), choice("z")) : List.of();
            return new SceneBundle("scene_%03d".formatted(request.sceneIndex()), "beat",
                    new SceneLocation(room, room, "room", "room", null), List.of(),
                    List.of(Block.narration((first ? "parent " : "child ") + id)), children,
                    StateDelta.of(DeltaOp.of(DeltaOp.SET_FLAG, first ? "parent" : "child", id, "selected branch")),
                    "", List.of(), new SceneMeta("test", false, 1, request.outcome(), id, 0));
        }
        List<Call> secondCalls() { return List.copyOf(second); }
    }

    private static final class Estimate extends ChoiceProbabilityEstimator {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), exited = new CountDownLatch(1), release;
        volatile List<String> history = List.of();
        Estimate(boolean hold) { super(null); release = new CountDownLatch(hold ? 1 : 0); }
        @Override public Probabilities estimate(List<String> previousChoices, List<Choice> currentChoices, List<BranchOptions> branches) {
            calls.incrementAndGet(); history = List.copyOf(previousChoices); entered.countDown();
            try {
                waitFor(release);
                Map<String, Map<String, Double>> next = new LinkedHashMap<>();
                for (BranchOptions branch : branches) {
                    String parent = branch.branchId().split("::")[1];
                    next.put(branch.branchId(), switch (parent) {
                        case "a" -> Map.of("x", 0.6, "y", 0.3, "z", 0.1);
                        case "b" -> Map.of("x", 0.8, "y", 0.15, "z", 0.05);
                        case "c" -> Map.of("x", 0.9, "y", 0.1, "z", 0.0);
                        default -> Map.of("x", 1.0, "y", 0.0, "z", 0.0);
                    });
                }
                return new Probabilities(Map.of("a", 0.5, "b", 0.3, "c", 0.15, "d", 0.05), next);
            } finally { exited.countDown(); }
        }
    }

    private static GameSession session() {
        GameSession session = new GameSession(); session.id = "second-round";
        session.story = new CompiledStory(new AuthorCanon("A house", List.of("A house")),
                new StoryBible("A house", "mystery", List.of(), List.of(),
                        List.of(new LocationProfile("room", "room", "room", "room")), List.of(), List.of(), List.of(), List.of()),
                new StorySpine("Chapter", List.of(new StoryBeat("beat", "Explore", "Explore", "Find truth", "major"))));
        session.state = new GameState(); session.state.sessionId = session.id;
        session.state.currentBeatId = "beat"; session.state.currentSceneId = "scene_000";
        session.state.currentLocationId = "room"; session.state.knownLocationIds.add("room");
        session.currentScene = new SceneBundle("scene_000", "beat", new SceneLocation("room", "room", "room", "room", null),
                List.of(), List.of(Block.narration("Opening")), List.of(choice("a"), choice("b"), choice("c"), choice("d")),
                StateDelta.empty(), "", List.of(), new SceneMeta("test", false, 1, SceneRequest.NONE, null, 0));
        session.sceneCounter = 1;
        session.history.add(new GameSession.HistoryEntry("scene_000", "beat", "询问守门人", null, "Opening"));
        return session;
    }
    private static Choice choice(String id) { return new Choice(id, "Choice " + id, "investigation", null, List.of()); }
    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
    }
    private static void await(BooleanSupplier ready) throws InterruptedException {
        for (int i = 0; i < 500; i++) { if (ready.getAsBoolean()) return; Thread.sleep(10); }
        fail("Asynchronous work did not reach the expected state");
    }
}
