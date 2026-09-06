package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.PendingRollException;
import com.genvn.game.GameSession;
import com.genvn.game.SessionService;
import com.genvn.llm.LlmException;
import com.genvn.narrative.Choice;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The die is cast BEFORE the scene is generated and persisted on the session, so the player
 * sees it at once and nothing ever rolls twice for the same choice -- not a retry after a
 * failed generation, not a refresh, not a restart. A die the player has seen is binding.
 */
class PendingRollTest {

    private static ScriptedLlmClient client(ObjectMapper mapper) {
        return new ScriptedLlmClient(mapper, request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                return SceneJson.scene("The study is dark.")
                        .choiceWithCheck("cC", "Force the desk drawer", "Perception", 13)
                        .choice("cP", "Wait", "cautious")
                        .build();
            }
            return SceneJson.scene("Something happens.").choice("c1", "Go on", "cautious").build();
        });
    }

    @Test
    @DisplayName("roll() casts the die once and persists it; asking again returns the same die")
    void rollIsCastOnceAndPersisted() {
        // If anything re-rolled, the second scripted value (15) would show up. It must not.
        Engine engine = new Engine(client(new ObjectMapper()), new ScriptedRandom(4, 15), false, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());

        SessionService.RollOutcome first = engine.sessions.roll(session.id, "cC");
        assertFalse(first.reused());
        assertEquals(4, first.roll().d20());
        assertFalse(first.roll().success(), "4 + Perception 4 = 8 vs DC 13");

        GameSession stored = engine.repository.find(session.id).orElseThrow();
        assertNotNull(stored.pendingRoll, "the die is saved with the session");
        assertEquals("cC", stored.pendingRoll.choiceId);
        assertEquals(4, stored.pendingRoll.roll.d20());

        SessionService.RollOutcome again = engine.sessions.roll(session.id, "cC");
        assertTrue(again.reused(), "the same die comes back");
        assertEquals(4, again.roll().d20(), "never re-rolled");
    }

    @Test
    @DisplayName("choose() after roll() plays the die that was shown, then clears it")
    void chooseReusesTheCastDie() {
        Engine engine = new Engine(client(new ObjectMapper()), new ScriptedRandom(4, 15), false, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());

        engine.sessions.roll(session.id, "cC");
        SessionService.ChoiceOutcome outcome = engine.sessions.choose(session.id, "cC");

        assertTrue(outcome.reusedPendingRoll());
        assertEquals(4, outcome.roll().d20(), "the story used the number the player saw");
        assertFalse(outcome.roll().success());
        assertNull(session.pendingRoll, "nothing outstanding once the scene is committed");
        assertNull(engine.repository.find(session.id).orElseThrow().pendingRoll);
    }

    @Test
    @DisplayName("a failed generation keeps the die; the retry never rolls again")
    void retryAfterFailedGenerationReusesTheDie() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger poison = new AtomicInteger(3); // one choose() = up to 3 attempts, all poisoned
        ScriptedLlmClient client = new ScriptedLlmClient(mapper, request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                return SceneJson.scene("The study is dark.")
                        .choiceWithCheck("cC", "Force the desk drawer", "Perception", 13)
                        .build();
            }
            if (poison.getAndDecrement() > 0) return "this is not json {";
            return SceneJson.scene("Recovered.").choice("c1", "Go on", "cautious").build();
        });
        // Die: 4 (failure). If the retry re-rolled it would draw 18 (success) -- that must not happen.
        Engine engine = new Engine(client, new ScriptedRandom(4, 18), false, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        int versionBefore = session.state.stateVersion;
        String sceneBefore = session.currentScene.sceneId();

        engine.sessions.roll(session.id, "cC");
        assertThrows(LlmException.class, () -> engine.sessions.choose(session.id, "cC"));

        assertEquals(versionBefore, session.state.stateVersion, "a failed generation changes nothing");
        assertEquals(sceneBefore, session.currentScene.sceneId());
        assertNotNull(session.pendingRoll, "the die survives the failure");
        assertEquals(4, session.pendingRoll.roll.d20());

        SessionService.ChoiceOutcome retry = engine.sessions.choose(session.id, "cC");
        assertTrue(retry.reusedPendingRoll());
        assertEquals(4, retry.roll().d20(), "the retry played the original die, it did not draw the 18");
        assertFalse(retry.roll().success());
        assertEquals("Recovered.", session.currentScene.blocks().get(0).text());
    }

    @Test
    @DisplayName("a die the player has seen is binding: another choice on this scene is refused")
    void aSeenDieIsBinding() {
        ObjectMapper mapper = new ObjectMapper();
        ScriptedLlmClient client = new ScriptedLlmClient(mapper, request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                return SceneJson.scene("The study is dark.")
                        .choiceWithCheck("cC", "Force the desk drawer", "Perception", 13)
                        .choiceWithCheck("cD", "Climb to the window", "Agility", 12)
                        .choice("cP", "Wait", "cautious")
                        .build();
            }
            return SceneJson.scene("Something happens.").choice("c1", "Go on", "cautious").build();
        });
        Engine engine = new Engine(client, new ScriptedRandom(4, 15, 15), false, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());

        engine.sessions.roll(session.id, "cC");

        PendingRollException viaChoose = assertThrows(PendingRollException.class,
                () -> engine.sessions.choose(session.id, "cP"), "resolving a different choice is refused");
        assertEquals("cC", viaChoose.pendingChoiceId());
        PendingRollException viaRoll = assertThrows(PendingRollException.class,
                () -> engine.sessions.roll(session.id, "cD"), "rolling a different checked choice is refused");
        assertEquals("cC", viaRoll.pendingChoiceId());

        assertEquals(4, session.pendingRoll.roll.d20(), "the original die is untouched");
        assertEquals(session.currentScene.sceneId(), session.pendingRoll.sceneId);
    }

    @Test
    @DisplayName("a plain choice needs no roll(): choose() still rolls nothing and reuses nothing")
    void plainChoiceHasNoDie() {
        Engine engine = new Engine(client(new ObjectMapper()), new ScriptedRandom(9), false, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        assertThrows(IllegalArgumentException.class, () -> engine.sessions.roll(session.id, "cP"),
                "there is nothing to roll for a choice without a check");
        SessionService.ChoiceOutcome outcome = engine.sessions.choose(session.id, "cP");
        assertNull(outcome.roll());
        assertFalse(outcome.reusedPendingRoll());
    }

    @Test
    @DisplayName("the cast die survives a save round-trip, so a restart resumes from it")
    void pendingRollSurvivesSerialisation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Engine engine = new Engine(client(mapper), new ScriptedRandom(7), false, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        engine.sessions.roll(session.id, "cC");

        GameSession restored = mapper.readValue(mapper.writeValueAsString(session), GameSession.class);
        assertNotNull(restored.pendingRoll);
        assertEquals("cC", restored.pendingRoll.choiceId);
        assertEquals(7, restored.pendingRoll.roll.d20());
        assertEquals(session.pendingRoll.roll.dc(), restored.pendingRoll.roll.dc());
        assertEquals(session.pendingRoll.roll.success(), restored.pendingRoll.roll.success());
    }

    @Test
    @DisplayName("a choice made before its branch finishes waits for it instead of regenerating")
    void cacheMissWaitsForInFlightBranch() {
        ObjectMapper mapper = new ObjectMapper();
        ScriptedLlmClient client = new ScriptedLlmClient(mapper, request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                return SceneJson.scene("The study is dark.")
                        .choiceWithCheck("cC", "Force the desk drawer", "Perception", 13)
                        .build();
            }
            try {
                Thread.sleep(250); // the branch is genuinely still being written when the player clicks
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SceneJson.scene("Branch " + ScriptedLlmClient.outcomeOf(request))
                    .choice("c1", "Go on", "cautious").build();
        });
        Engine engine = new Engine(client, new ScriptedRandom(4), true, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        // Do NOT await branches: click immediately, while the (pre-cast FAILURE) branch is in flight.
        SessionService.ChoiceOutcome outcome = engine.sessions.choose(session.id, "cC");

        // The whole point of the feature: choose() waited for the in-flight FAILURE branch instead
        // of starting a fresh generation for this choice. fromSpeculativeCache is set only when the
        // committed scene came from the branch cache -- here via awaitIfInFlight, never from a live
        // call -- so it is exactly the proof that no redundant regeneration happened. (A raw
        // sceneCallCount would be wrong here: committing also slides the frontier forward and
        // prefetches the NEXT scene, which is a feature, not a regeneration of this one.)
        assertTrue(outcome.fromSpeculativeCache(),
                "the in-flight FAILURE branch was awaited and used, not regenerated live");
        assertEquals("Branch FAILURE", session.currentScene.blocks().get(0).text());
    }
}
