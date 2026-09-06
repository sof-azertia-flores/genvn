package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.GameSession;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.MockLlmClient;
import com.genvn.llm.StructuredLlm;
import com.genvn.prompt.ContextRenderer;
import com.genvn.story.ArcContinuationService;
import com.genvn.story.ArcOutline;
import com.genvn.support.Engine;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1: when the spine runs low, the story continues into a new arc rather than stopping --
 * built on what actually happened, not on a fresh premise.
 */
class ArcContinuationTest {

    private static final class AnyRandom extends ScriptedRandom {
        private final RandomGenerator inner = RandomGenerator.of("Xoshiro256PlusPlus");

        @Override
        public int nextInt(int bound) {
            return inner.nextInt(bound);
        }
    }

    @Test
    @DisplayName("a next-arc outline is planned from the state the player actually reached")
    void plansAContinuation() {
        ObjectMapper mapper = new ObjectMapper();
        Engine engine = new Engine(new MockLlmClient(mapper), new AnyRandom(), false, true);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        for (int i = 0; i < 6 && !session.currentScene.choices().isEmpty(); i++) {
            engine.sessions.choose(session.id, session.currentScene.choices().get(0).id());
        }

        ArcContinuationService arcs = new ArcContinuationService(
                new StructuredLlm(new MockLlmClient(mapper), mapper, new LlmCallLog()),
                new ContextRenderer(), Engine.properties(true, true));
        ArcOutline outline = arcs.plan(session);

        assertNotNull(outline);
        assertFalse(outline.beats().isEmpty(), "a continuation needs beats to play");
        assertNotEquals(session.story.spine.arcTitle(), outline.arcTitle(), "it is a new arc, not a repeat");

        String canonBefore = String.join("|", session.story.authorCanon.facts());
        session.story.beginArc(outline);

        assertEquals(outline.arcTitle(), session.story.spine.arcTitle());
        assertEquals(outline.beats().size(), session.story.spine.beats().size());
        assertEquals(canonBefore, String.join("|", session.story.authorCanon.facts()),
                "starting a new arc never rewrites Author Canon");
        assertEquals(1, session.story.laterArcs.size());
        assertTrue(session.story.bible.characters().size() >= 1, "the established cast survives the arc change");
    }
}
