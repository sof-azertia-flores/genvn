package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.DeltaOp;
import com.genvn.game.GameSession;
import com.genvn.game.GameState;
import com.genvn.game.StateDelta;
import com.genvn.game.StateReducer;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case 3 from the spec: the model may propose anything at all; the runtime decides what is real.
 */
class StateReducerTest {

    private Engine engine;
    private GameSession session;
    private final StateReducer reducer = new StateReducer();

    @BeforeEach
    void setUp() {
        engine = new Engine(new ScriptedLlmClient(new ObjectMapper(), request ->
                SceneJson.scene("A scene.").choice("c1", "Go on", "cautious").build()),
                new ScriptedRandom(), false, false);
        session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
    }

    @Test
    @DisplayName("an absurd hp change is clamped, not applied")
    void hpIsClamped() {
        GameState state = session.state;
        state.player.hp = 10;

        reducer.apply(state, session.story, StateDelta.of(
                DeltaOp.amount(DeltaOp.HP_DELTA, "player", 999999, "the model got excited")), "scene_x");
        assertEquals(10, state.player.hp, "hp can never exceed maxHp");

        reducer.apply(state, session.story, StateDelta.of(
                DeltaOp.amount(DeltaOp.HP_DELTA, "player", -999999, "nor go below zero")), "scene_x");
        assertEquals(0, state.player.hp);
        assertTrue(state.player.hp >= 0);
    }

    @Test
    @DisplayName("ops outside the allow-list are refused and recorded")
    void forbiddenOpsAreRejected() {
        GameState state = session.state;
        String premiseBefore = session.story.bible.premise();
        List<String> canonBefore = session.story.authorCanon.facts();

        StateReducer.Applied applied = reducer.apply(state, session.story, StateDelta.of(
                new DeltaOp("setAuthorCanon", "authorCanon", null, "[]", "trying to rewrite the law"),
                new DeltaOp("deleteStoryBible", null, null, null, "no"),
                new DeltaOp("setPlayer", "player", null, "{\"hp\":9999}", "no"),
                new DeltaOp("eval", null, null, "System.exit(0)", "definitely no"),
                new DeltaOp("__proto__", "x", null, "y", "no")), "scene_x");

        assertEquals(0, applied.applied().size(), "nothing outside the allow-list is ever applied");
        assertEquals(5, applied.rejected().size());
        assertTrue(applied.rejected().stream().allMatch(r -> r.contains("forbidden op")));
        assertEquals(premiseBefore, session.story.bible.premise(), "the Story Bible is untouched");
        assertEquals(canonBefore, session.story.authorCanon.facts(), "Author Canon is untouched");
        assertFalse(state.rejectedOpsLog.isEmpty(), "the violation is visible in the inspector");
    }

    @Test
    @DisplayName("references that do not resolve are refused")
    void danglingReferencesAreRejected() {
        GameState state = session.state;
        StateReducer.Applied applied = reducer.apply(state, session.story, StateDelta.of(
                DeltaOp.of(DeltaOp.CHANGE_LOCATION, "loc_atlantis"),
                DeltaOp.of(DeltaOp.COMPLETE_BEAT, "beat_that_does_not_exist"),
                DeltaOp.of(DeltaOp.RESOLVE_THREAD, "P999"),
                DeltaOp.of(DeltaOp.REMOVE_INVENTORY, "a sword we never had"),
                DeltaOp.amount(DeltaOp.RELATIONSHIP_DELTA, "npc_nobody", 2, "no such person")), "scene_x");

        assertEquals(0, applied.applied().size());
        assertEquals(5, applied.rejected().size());
        assertFalse(state.completedBeats.contains("beat_that_does_not_exist"));
    }

    @Test
    @DisplayName("relationship swings are bounded in both the step and the total")
    void relationshipIsBounded() {
        GameState state = session.state;
        String npcId = session.story.bible.characters().get(0).id();
        for (int i = 0; i < 12; i++) {
            reducer.apply(state, session.story, StateDelta.of(
                    DeltaOp.amount(DeltaOp.RELATIONSHIP_DELTA, npcId, 99, "spam")), "scene_x");
        }
        assertEquals(10, state.characters.get(npcId).relationship, "capped at +10");

        for (int i = 0; i < 30; i++) {
            reducer.apply(state, session.story, StateDelta.of(
                    DeltaOp.amount(DeltaOp.RELATIONSHIP_DELTA, npcId, -99, "spam")), "scene_x");
        }
        assertEquals(-10, state.characters.get(npcId).relationship, "and at -10");
    }

    @Test
    @DisplayName("the ledger cannot be flooded with new threads in a single scene")
    void threadSpamIsBounded() {
        GameState state = session.state;
        int before = state.continuityLedger.size();
        StateReducer.Applied applied = reducer.apply(state, session.story, new StateDelta(List.of(
                DeltaOp.of(DeltaOp.ADD_THREAD, null, "Question one?", "r"),
                DeltaOp.of(DeltaOp.ADD_THREAD, null, "Question two?", "r"),
                DeltaOp.of(DeltaOp.ADD_THREAD, null, "Question three?", "r"),
                DeltaOp.of(DeltaOp.ADD_THREAD, null, "Question four?", "r"))), "scene_x");

        assertEquals(2, applied.applied().size(), "at most two new threads per scene");
        assertEquals(before + 2, state.continuityLedger.size());
        assertNotNull(state.continuityLedger.get(before).id);
    }

    @Test
    @DisplayName("valid ops still work, and only they bump the state version")
    void validOpsApply() {
        GameState state = session.state;
        int version = state.stateVersion;

        StateReducer.Applied ok = reducer.apply(state, session.story, StateDelta.of(
                DeltaOp.of(DeltaOp.ADD_INVENTORY, "A brass key", "cold to the touch", "found it"),
                DeltaOp.of(DeltaOp.SET_FLAG, "Entered The House!", "true", "went in")), "scene_x");
        assertEquals(2, ok.applied().size());
        assertTrue(state.hasItem("A brass key"));
        assertTrue(state.flags.containsKey("entered_the_house"), "flag keys are normalised");
        assertEquals(version + 1, state.stateVersion);

        StateReducer.Applied none = reducer.apply(state, session.story,
                StateDelta.of(new DeltaOp("nonsense", null, null, null, null)), "scene_x");
        assertEquals(0, none.applied().size());
        assertEquals(version + 1, state.stateVersion, "a fully rejected delta does not bump the version");
    }
}
