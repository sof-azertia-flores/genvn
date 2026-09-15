package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.ChoiceResolvingException;
import com.genvn.api.SessionNotFoundException;
import com.genvn.config.GenvnProperties;
import com.genvn.dice.CheckResolver;
import com.genvn.dice.DiceService;
import com.genvn.game.GameSession;
import com.genvn.game.SessionService;
import com.genvn.game.StateReducer;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.LlmException;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.SceneGenerator;
import com.genvn.persistence.FileGameSessionRepository;
import com.genvn.prompt.ContextRenderer;
import com.genvn.speculation.BranchCache;
import com.genvn.speculation.SpeculativeGenerator;
import com.genvn.story.ArcContinuationService;
import com.genvn.story.StoryCompiler;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConcurrencyRegressionTest {
    @TempDir Path directory;

    @Test void cannotRollAnotherChoiceWhileAnUncheckedChoiceIsResolving() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var mapper = new ObjectMapper();
        var client = new ScriptedLlmClient(mapper, request -> {
            if (ScriptedLlmClient.choiceOf(request) == null) return opening();
            entered.countDown();
            await(release);
            return nextScene();
        });
        var engine = new Engine(client, new ScriptedRandom(4), false, false);
        var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        try (var clicks = Executors.newSingleThreadExecutor()) {
            var chosen = clicks.submit(() -> engine.sessions.choose(session.id, "go"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                assertThrows(ChoiceResolvingException.class, () -> engine.sessions.roll(session.id, "check"));
                assertNull(session.pendingRoll, "no die can be shown and subsequently discarded");
            } finally {
                release.countDown();
            }
            assertNull(chosen.get(5, TimeUnit.SECONDS).roll());
            assertNull(session.history.getLast().rollSummary);
        }
    }

    @Test void anExistingDieRemainsReadableDuringItsOwnResolution() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var client = new ScriptedLlmClient(new ObjectMapper(), request -> {
            if (ScriptedLlmClient.choiceOf(request) == null) return opening();
            entered.countDown(); await(release); return nextScene();
        });
        var engine = new Engine(client, new ScriptedRandom(4), false, false);
        var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        var die = engine.sessions.roll(session.id, "check");
        try (var clicks = Executors.newSingleThreadExecutor()) {
            var chosen = clicks.submit(() -> engine.sessions.choose(session.id, "check"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                var again = engine.sessions.roll(session.id, "check");
                assertTrue(again.reused());
                assertEquals(die.roll(), again.roll());
                assertThrows(ChoiceResolvingException.class, () -> engine.sessions.choose(session.id, "go"));
            } finally { release.countDown(); }
            assertEquals(die.roll(), chosen.get(5, TimeUnit.SECONDS).roll());
        }
    }

    @Test void selectingAQueuedBranchInterruptsTheUnusedWorkerAndReusesExactlyOneCandidate() throws Exception {
        var mapper = new ObjectMapper();
        CountDownLatch unusedEntered = new CountDownLatch(1), unusedInterrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger unusedCalls = new AtomicInteger(), chosenCalls = new AtomicInteger();
        var client = new ScriptedLlmClient(mapper, request -> {
            var choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) return SceneJson.scene("Opening")
                    .choice("unused", "Leave it", "action").choice("selected", "Enter", "action").build();
            if (choice.id().equals("unused")) {
                unusedCalls.incrementAndGet(); unusedEntered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) {
                    unusedInterrupted.countDown();
                    // Even an adapter wrapping InterruptedException must not trigger a retry.
                    throw new LlmException("Interrupted provider", e);
                }
            }
            if (choice.id().equals("selected")) chosenCalls.incrementAndGet();
            return nextScene();
        });
        try (var fixture = fixture(mapper, client, null); var clicks = Executors.newSingleThreadExecutor()) {
            var session = fixture.sessions.create(Engine.OUTLINE, Engine.alex());
            assertTrue(unusedEntered.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.cache.status(session.id).stream().anyMatch(row ->
                    "selected".equals(row.get("choiceId")) && "queued".equals(row.get("status"))));
            var chosen = clicks.submit(() -> fixture.sessions.choose(session.id, "selected"));
            try {
                assertTrue(unusedInterrupted.await(3, TimeUnit.SECONDS), "the actual provider thread was interrupted");
                var result = chosen.get(3, TimeUnit.SECONDS);
                assertTrue(result.fromSpeculativeCache());
                assertEquals(1, unusedCalls.get(), "cancelled transport was not repaired/retried");
                assertEquals(1, chosenCalls.get(), "queued work was promoted and reused, not generated twice");
            } finally { release.countDown(); }
        }
    }

    @Test void deletingDuringCommitClearsTheNewFrontierAndRejectsLaterScheduling() throws Exception {
        var mapper = new ObjectMapper();
        var properties = properties();
        CountDownLatch commitSaving = new CountDownLatch(1), releaseCommit = new CountDownLatch(1);
        CountDownLatch deletionObservedSession = new CountDownLatch(1);
        var repository = new FileGameSessionRepository(mapper, properties) {
            @Override public void save(GameSession session) {
                if (session.sceneCounter == 2) { commitSaving.countDown(); await(releaseCommit); }
                super.save(session);
            }
            @Override public java.util.Optional<GameSession> find(String id) {
                var found = super.find(id);
                if (Thread.currentThread().getName().equals("deleting-session")) deletionObservedSession.countDown();
                return found;
            }
        };
        var client = new ScriptedLlmClient(mapper, r -> nextScene());
        try (var fixture = fixture(mapper, client, repository);
             var clicks = Executors.newSingleThreadExecutor();
             var deletes = Executors.newSingleThreadExecutor(r -> new Thread(r, "deleting-session"))) {
            var session = fixture.sessions.create(Engine.OUTLINE, Engine.alex());
            var chosen = clicks.submit(() -> fixture.sessions.choose(session.id, "go"));
            assertTrue(commitSaving.await(5, TimeUnit.SECONDS));
            var deleted = deletes.submit(() -> fixture.sessions.delete(session.id));
            try { assertTrue(deletionObservedSession.await(5, TimeUnit.SECONDS)); }
            finally { releaseCommit.countDown(); }
            chosen.get(5, TimeUnit.SECONDS);
            assertTrue(deleted.get(5, TimeUnit.SECONDS));
            assertTrue(repository.find(session.id).isEmpty());
            assertFalse(Files.exists(directory.resolve("sessions").resolve(session.id)));
            assertEquals(0, fixture.cache.size(session.id));
            assertTrue(fixture.speculative.prefetch(session).isEmpty());
            assertEquals(0, fixture.cache.size(session.id), "late callers cannot repopulate a deleted frontier");
        }
    }

    @Test void deletingASelectedInflightBranchNeverStartsAFallbackRequest() throws Exception {
        var mapper = new ObjectMapper();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger generations = new AtomicInteger();
        var client = new ScriptedLlmClient(mapper, request -> {
            if (ScriptedLlmClient.choiceOf(request) == null) return nextScene();
            generations.incrementAndGet(); entered.countDown(); await(release); return nextScene();
        });
        try (var fixture = fixture(mapper, client, null); var clicks = Executors.newSingleThreadExecutor()) {
            var session = fixture.sessions.create(Engine.OUTLINE, Engine.alex());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var chosen = clicks.submit(() -> fixture.sessions.choose(session.id, "go"));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (session.resolvingChoiceId == null && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals("go", session.resolvingChoiceId);
                assertTrue(fixture.sessions.delete(session.id));
                var failure = assertThrows(ExecutionException.class, () -> chosen.get(3, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof CancellationException
                        || failure.getCause() instanceof SessionNotFoundException);
                assertEquals(1, generations.get(), "cancelling the selected request did not generate live again");
                assertEquals(0, fixture.cache.size(session.id));
            } finally { release.countDown(); }
        }
    }

    private GenvnProperties properties() {
        var properties = Engine.properties(true, false);
        properties.getSpeculation().setThreads(1);
        properties.setDataDir(directory.toString());
        return properties;
    }

    private Fixture fixture(ObjectMapper mapper, ScriptedLlmClient client, FileGameSessionRepository repository) {
        var properties = properties();
        var llm = new StructuredLlm(client, mapper, new LlmCallLog());
        var generator = new SceneGenerator(llm, new ContextRenderer());
        var reducer = new StateReducer();
        var cache = new BranchCache();
        var speculative = new SpeculativeGenerator(generator, cache, reducer, mapper, properties);
        var arcs = new ArcContinuationService(llm, new ContextRenderer(), properties);
        var repo = repository == null ? new FileGameSessionRepository(mapper, properties) : repository;
        var sessions = new SessionService(new StoryCompiler(llm), generator, reducer,
                new CheckResolver(new DiceService(new ScriptedRandom())), cache, speculative, arcs, repo, mapper);
        return new Fixture(sessions, cache, speculative, arcs);
    }

    private record Fixture(SessionService sessions, BranchCache cache, SpeculativeGenerator speculative,
                           ArcContinuationService arcs) implements AutoCloseable {
        @Override public void close() { speculative.close(); arcs.close(); }
    }

    private static String opening() {
        return SceneJson.scene("Opening").choice("go", "Enter", "action")
                .choiceWithCheck("check", "Examine", "Perception", 13).build();
    }
    private static String nextScene() { return SceneJson.scene("Inside").choice("go", "Continue", "action").build(); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("Cancelled test provider"); }
    }
}
