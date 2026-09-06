package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.GameSession;
import com.genvn.game.SessionService;
import com.genvn.narrative.Choice;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case 2 from the spec: both outcomes of a check are pre-generated, the die is rolled only when
 * the player commits, and the candidate matching the real outcome is the one that becomes canon.
 */
class CheckOutcomeTest {

    private static final String SUCCESS_TEXT = "You ease the drawer open in silence.";
    private static final String FAILURE_TEXT = "The lock gives, but a sliver of metal snaps off with a bright crack.";

    private Engine engineRolling(int d20) {
        return new Engine(new ScriptedLlmClient(new ObjectMapper(), request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                return SceneJson.scene("The study is dark.")
                        .choiceWithCheck("cC", "Force the desk drawer", "Perception", 13)
                        .build();
            }
            boolean success = "SUCCESS".equals(ScriptedLlmClient.outcomeOf(request));
            return (success
                    ? SceneJson.scene(SUCCESS_TEXT).addInventory("The letter").setFlag("silent", "true")
                    : SceneJson.scene(FAILURE_TEXT).addInventory("The letter").setFlag("heard_upstairs", "true"))
                    .choice("c1", "Go on", "cautious")
                    .build();
        }), new ScriptedRandom(d20), true, false);
    }

    @Test
    @DisplayName("a pre-cast failing die means only the FAILURE candidate is ever written, and it is the one used")
    void failureUsesTheFailureCandidate() {
        // Perception 4, DC 13: a 4 on the die is 8 total -- a failure.
        Engine engine = engineRolling(4);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        engine.awaitBranches(session.id);

        var branches = engine.branchCache.status(session.id);
        assertEquals(1, branches.size(), "the die was cast at commit, so only its outcome is written ahead");
        assertEquals("FAILURE", branches.get(0).get("outcome"));
        assertEquals(4, session.sceneDice.get("cC").d20(), "the die exists before the player clicks");
        assertNull(session.pendingRoll, "but nothing has been revealed");

        SessionService.ChoiceOutcome outcome = engine.sessions.choose(session.id, "cC");

        assertFalse(outcome.roll().success(), "d20 4 + Perception 4 = 8 vs DC 13");
        assertEquals(4, outcome.roll().d20());
        assertEquals(4, outcome.roll().statModifier());
        assertEquals(8, outcome.roll().total());
        assertEquals(13, outcome.roll().dc());
        assertTrue(outcome.fromSpeculativeCache(), "the FAILURE candidate was already waiting");
        assertEquals(FAILURE_TEXT, session.currentScene.blocks().get(0).text());
        assertTrue(session.state.flags.containsKey("heard_upstairs"));
        assertFalse(session.state.flags.containsKey("silent"), "the SUCCESS candidate was discarded");
        assertEquals(0, outcome.discardedBranches(), "nothing was written for the outcome that could not happen");
        assertEquals(0, engine.branchCache.status(session.id).stream()
                .filter(b -> session.currentScene.sceneId().equals(b.get("sceneId"))).count());
    }

    @Test
    @DisplayName("a successful roll consumes the SUCCESS candidate")
    void successUsesTheSuccessCandidate() {
        // Perception 4, DC 13: a 15 on the die is 19 total -- a success.
        Engine engine = engineRolling(15);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        engine.awaitBranches(session.id);

        SessionService.ChoiceOutcome outcome = engine.sessions.choose(session.id, "cC");

        assertTrue(outcome.roll().success());
        assertEquals(19, outcome.roll().total());
        assertEquals(SUCCESS_TEXT, session.currentScene.blocks().get(0).text());
        assertTrue(session.state.flags.containsKey("silent"));
        assertFalse(session.state.flags.containsKey("heard_upstairs"));
    }

    @Test
    @DisplayName("both outcomes still advance the story -- failure is never a dead end")
    void failureStillMovesTheStory() {
        Engine engine = engineRolling(1);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        engine.awaitBranches(session.id);
        int versionBefore = session.state.stateVersion;

        engine.sessions.choose(session.id, "cC");

        assertTrue(session.state.hasItem("The letter"), "the fail-forward branch still yields the letter");
        assertTrue(session.state.stateVersion > versionBefore, "canonical state moved");
        assertFalse(session.currentScene.choices().isEmpty(), "the player still has somewhere to go");
    }
}
