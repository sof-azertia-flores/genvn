package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.SceneConflictException;
import com.genvn.api.ChoiceResolvingException;
import com.genvn.game.GameSession;
import com.genvn.game.SessionService;
import com.genvn.narrative.Choice;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model call can take a minute. While it does, reading the session, its state snapshot or
 * its save list must not block -- and a second click must be refused at once, not doubled.
 */
class ChooseLockTest {

    @Test
    @DisplayName("while a choice waits on generation, session reads return at once and a second click is refused")
    void generationDoesNotHoldTheSessionMonitor() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedLlmClient client = new ScriptedLlmClient(mapper, request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                return SceneJson.scene("Opening.").at("loc_threshold").choice("go", "Go in", "cautious").build();
            }
            entered.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SceneJson.scene("Inside.").at("loc_interior").choice("on", "Onward", "cautious").build();
        });
        Engine engine = new Engine(client, new ScriptedRandom(10, 10), true, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the prefetch branch is now mid-generation");

        // The player clicks: choose() must wait for that in-flight branch -- WITHOUT the monitor.
        AtomicReference<SessionService.ChoiceOutcome> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread click = new Thread(() -> {
            try {
                result.set(engine.sessions.choose(session.id, "go"));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "player-click");
        click.start();
        for (int i = 0; i < 200 && session.resolvingChoiceId == null; i++) Thread.sleep(10);
        assertEquals("go", session.resolvingChoiceId, "phase 2 is running");

        long t0 = System.nanoTime();
        GameSession view = engine.sessions.copySession(engine.sessions.require(session.id));
        engine.sessions.snapshot(session);
        engine.sessions.list();
        long millis = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(millis < 2_000, "reads returned in " + millis + "ms while generation was in flight");
        assertEquals("scene_000", view.currentScene.sceneId(), "and they see the un-committed scene");
        assertEquals("go", view.resolvingChoiceId, "GET responses can distinguish a running choice from a failed one");
        GameSession restored = mapper.readValue(mapper.writeValueAsBytes(session), GameSession.class);
        assertNull(restored.resolvingChoiceId, "process-local work is never resurrected by loading a save");

        // A second click on the same scene while the first is resolving: refused at once, no second generation.
        long t1 = System.nanoTime();
        assertThrows(ChoiceResolvingException.class, () -> engine.sessions.choose(session.id, "go"));
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 2_000, "the refusal did not wait for the model");
        assertEquals(2, client.sceneCallCount(), "opening + the one branch: the double click generated nothing");

        release.countDown();
        click.join(10_000);
        assertNull(failure.get(), () -> "first click failed: " + failure.get());
        assertNotNull(result.get());
        assertTrue(result.get().fromSpeculativeCache(), "the in-flight branch was awaited and used");
        assertEquals("scene_001", session.currentScene.sceneId());
        assertNull(session.resolvingChoiceId, "the marker is cleared after commit");

        // The page that was behind: a stale expected scene/version is a conflict, not a second commit.
        assertThrows(SceneConflictException.class,
                () -> engine.sessions.choose(session.id, "on", "scene_000", 1));
        assertEquals("scene_001", session.currentScene.sceneId());
    }

    @Test
    @DisplayName("a die cast by choose() itself is persisted before generation, so a failed generation never re-rolls")
    void chooseCastDieSurvivesFailure() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<Boolean> failOnce = new AtomicReference<>(true);
        ScriptedLlmClient client = new ScriptedLlmClient(mapper, request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                return SceneJson.scene("Opening.").at("loc_threshold")
                        .choiceWithCheck("cC", "Force it", "Perception", 13).build();
            }
            if (failOnce.getAndSet(false)) return "this is not json at all";
            return SceneJson.scene("Result " + ScriptedLlmClient.outcomeOf(request)).choice("on", "On", "cautious").build();
        });
        // d20 = 4 -> 8 vs 13 FAILURE. Any re-roll would consume the next scripted 20 and succeed.
        Engine engine = new Engine(client, new ScriptedRandom(4, 20, 20, 20), false, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        SessionService.ChoiceOutcome outcome;
        try {
            outcome = engine.sessions.choose(session.id, "cC");
        } catch (RuntimeException firstAttemptFailed) {
            assertNotNull(session.pendingRoll, "the die cast by choose() was persisted before generation");
            assertEquals(4, session.pendingRoll.roll.d20());
            outcome = engine.sessions.choose(session.id, "cC");
        }
        assertEquals(4, outcome.roll().d20(), "the retry replayed the same die");
        assertFalse(outcome.roll().success());
        assertTrue(session.currentScene.blocks().get(0).text().contains("FAILURE"));
    }
}
