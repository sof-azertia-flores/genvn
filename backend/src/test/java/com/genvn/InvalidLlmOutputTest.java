package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.GameSession;
import com.genvn.llm.JsonExtractor;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A model that returns garbage must cost a retry, not the player's save file. */
class InvalidLlmOutputTest {

    @Test
    @DisplayName("JSON wrapped in prose or fences is still recovered")
    void extractsJsonFromMessyOutput() {
        assertEquals("{\"a\":1}", JsonExtractor.extractObject("Sure! Here you go:\n```json\n{\"a\":1}\n```\nHope that helps"));
        assertEquals("{\"a\":\"}\"}", JsonExtractor.extractObject("{\"a\":\"}\"}"), "braces inside strings do not confuse it");
        assertEquals("{\"a\":{\"b\":2}}", JsonExtractor.extractObject("noise {\"a\":{\"b\":2}} trailing"));
        assertNull(JsonExtractor.extractObject("no json at all"));
    }

    @Test
    @DisplayName("a malformed scene is repaired on retry and play continues")
    void repairsAndContinues() {
        AtomicInteger attempts = new AtomicInteger();
        Engine engine = new Engine(new ScriptedLlmClient(new ObjectMapper(), request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null && attempts.getAndIncrement() == 0) {
                return "I'm sorry, I can't do that. Here is a poem instead.";
            }
            if (choice == null && attempts.get() == 2) {
                return "{\"location\": {\"id\":\"loc_interior\"}, \"blocks\": []}"; // schema-valid JSON, useless scene
            }
            return SceneJson.scene("The hall is cold.").choice("c1", "Go on", "cautious").build();
        }), new ScriptedRandom(), false, false);

        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());

        assertNotNull(session.currentScene);
        assertEquals("The hall is cold.", session.currentScene.blocks().get(0).text());
        assertTrue(session.currentScene.meta().repairAttempts() >= 1, "it took at least one repair round");
    }

    @Test
    @DisplayName("a model that never recovers fails cleanly and leaves canonical state untouched")
    void givesUpWithoutCorruptingState() {
        AtomicInteger sceneCalls = new AtomicInteger();
        Engine engine = new Engine(new ScriptedLlmClient(new ObjectMapper(), request -> {
            if (ScriptedLlmClient.choiceOf(request) == null) {
                return SceneJson.scene("You arrive.").choice("c1", "Go on", "cautious").build();
            }
            sceneCalls.incrementAndGet();
            return "still not json";
        }), new ScriptedRandom(), false, false);

        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        String sceneBefore = session.currentScene.sceneId();
        int versionBefore = session.state.stateVersion;
        int scenesBefore = session.state.storyProgress.scenesPlayed;

        assertThrows(LlmException.class, () -> engine.sessions.choose(session.id, "c1"));

        assertEquals(sceneBefore, session.currentScene.sceneId(), "the player is still on the scene they were on");
        assertEquals(versionBefore, session.state.stateVersion, "canonical state never moved");
        assertEquals(scenesBefore, session.state.storyProgress.scenesPlayed);
        assertTrue(sceneCalls.get() >= 3, "it retried before giving up");

        // And the session is still playable once the model behaves again.
        assertNotNull(engine.sessions.require(session.id));
    }

    @Test
    @DisplayName("an out-of-range DC and an unknown stat are normalised at generation time")
    void badChecksAreNormalisedNotFatal() {
        Engine engine = new Engine(new ScriptedLlmClient(new ObjectMapper(), request -> """
                {
                  "location": {"id":"loc_interior","name":"Hall","visualDescription":"x","backgroundPrompt":"x"},
                  "characters": [],
                  "blocks": [{"type":"narration","text":"A scene."},
                             {"type":"dialogue","speakerId":null,"speakerName":null,"text":"Orphan line."}],
                  "choices": [
                    {"id":"c1","text":"Absurd DC","approach":"risky","check":{"stat":"Perception","dc":95,"description":"x"}},
                    {"id":"c1","text":"Duplicate id","approach":"cautious","check":null},
                    {"id":"c3","text":"Unknown stat","approach":"social","check":{"stat":"Vibes","dc":12,"description":"x"}}
                  ],
                  "proposedStateDelta": {"ops":[]},
                  "storyProgressNote": "x"
                }
                """), new ScriptedRandom(), false, false);

        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        var scene = session.currentScene;

        assertEquals(3, scene.choices().size());
        assertEquals(18, scene.choices().get(0).check().dc(), "clamped into the sane band");
        assertEquals(3, scene.choices().stream().map(Choice::id).distinct().count(), "duplicate ids are made unique");
        assertNull(scene.choices().get(2).check(), "an unresolvable stat becomes a checkless choice");
        assertTrue(scene.blocks().stream().allMatch(b -> "narration".equals(b.type())),
                "a dialogue line with no speaker is demoted to narration rather than rendering a blank nameplate");
    }
}
