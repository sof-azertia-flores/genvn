package com.genvn;

import com.genvn.game.GameSession;
import com.genvn.narrative.Choice;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case 1 from the spec: an unchosen speculative branch must never touch canonical state.
 *
 * Branch A picks up a key. Branch B does not. The player picks B. The key must not exist.
 */
class BranchIsolationTest {

    private static final String KEY = "Brass Key";

    @Test
    @DisplayName("the key from the branch the player did not choose never reaches canonical state")
    void unchosenBranchNeverMutatesCanon() {
        Engine engine = new Engine(new ScriptedLlmClient(new com.fasterxml.jackson.databind.ObjectMapper(), request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                // Opening scene: two checkless choices, deliberately different.
                return SceneJson.scene("You stand in the hall.")
                        .choice("cA", "Open the desk drawer", "investigation")
                        .choice("cB", "Leave the desk alone", "cautious")
                        .build();
            }
            if ("cA".equals(choice.id())) {
                return SceneJson.scene("The drawer holds a brass key.")
                        .addInventory(KEY)
                        .setFlag("took_key", "true")
                        .choice("c1", "Go on", "cautious")
                        .build();
            }
            return SceneJson.scene("You walk past the desk without touching it.")
                    .setFlag("left_desk_alone", "true")
                    .choice("c1", "Go on", "cautious")
                    .build();
        }), new ScriptedRandom(), true, false);

        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        engine.awaitBranches(session.id);

        // Both branches are generated and ready before the player commits to anything.
        var branches = engine.branchCache.status(session.id);
        assertEquals(2, branches.size(), "one branch per checkless choice");
        assertTrue(branches.stream().anyMatch(b -> String.valueOf(b.get("provisional")).contains(KEY)),
                "branch A really did provisionally acquire the key inside its own fork");
        assertFalse(session.state.hasItem(KEY),
                "but the canonical state must be untouched while the branch only exists speculatively");

        engine.sessions.choose(session.id, "cB");

        assertFalse(session.state.hasItem(KEY), "the key belonged to the branch that was discarded");
        assertTrue(session.state.flags.containsKey("left_desk_alone"), "the chosen branch did commit");
        assertFalse(session.state.flags.containsKey("took_key"), "the discarded branch did not");
        assertEquals(0, engine.branchCache.size(session.id) - engine.branchCache.status(session.id).size(),
                "cache bookkeeping stays consistent");
    }

    @Test
    @DisplayName("a branch forked from an older state version is refused and regenerated")
    void staleBranchesAreNotUsed() {
        Engine engine = new Engine(new ScriptedLlmClient(new com.fasterxml.jackson.databind.ObjectMapper(), request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                return SceneJson.scene("Start.").choice("cA", "One", "cautious").build();
            }
            return SceneJson.scene("Next.").setFlag("moved", "true").choice("cA", "One", "cautious").build();
        }), new ScriptedRandom(), true, false);

        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        engine.awaitBranches(session.id);

        // Simulate canonical state moving on underneath a prefetched branch.
        session.state.stateVersion += 1;
        var key = new com.genvn.speculation.BranchKey(session.currentScene.sceneId(), "cA", "NONE");
        assertTrue(engine.branchCache.takeIfFresh(session.id, key, session.state.stateVersion - 1) != null,
                "the branch is usable at the version it was forked from");
        assertEquals(null, engine.branchCache.takeIfFresh(session.id, key, session.state.stateVersion),
                "but not once canonical state has advanced");
    }
}
