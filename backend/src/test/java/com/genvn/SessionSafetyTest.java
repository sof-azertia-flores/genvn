package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.api.*;
import com.genvn.game.*;
import com.genvn.llm.*;
import com.genvn.narrative.*;
import com.genvn.story.*;
import com.genvn.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class SessionSafetyTest {
    private static final String PASSWORD = "7391-ZEBRA";

    @Test void staleChoiceIsRejectedBeforeAnotherRollEvenWhenIdsRepeat() {
        ObjectMapper mapper = new ObjectMapper();
        var client = new ScriptedLlmClient(mapper, r -> SceneJson.scene("A new scene")
                .choiceWithCheck("c1", "Try the door", "Perception", 12).build());
        // Only a single roll is available. A stale request must never try to consume another.
        var engine = new Engine(client, new ScriptedRandom(4), false, false);
        var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        String scene = session.currentScene.sceneId();
        int version = session.state.stateVersion;
        var response = engine.sessions.choose(session.id, "c1", scene, version);
        assertEquals(version + 1, session.state.stateVersion, "Empty deltas still advance scene version");
        assertThrows(SceneConflictException.class, () -> engine.sessions.choose(session.id, "c1", scene, version));
        assertEquals(2, session.sceneCounter);
        assertEquals(2, client.sceneCallCount());
        assertNotSame(session, response.session());
        response.session().state.flags.put("external_edit", "no");
        assertFalse(session.state.flags.containsKey("external_edit"));
    }

    @Test void fullDialogueSurvivesTheNextPromptAndSaveRoundTrip() throws Exception {
        var seen = new AtomicReference<String>();
        var engine = memoryEngine(false, seen);
        var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        engine.sessions.choose(session.id, "c1");
        assertTrue(seen.get().contains(PASSWORD));
        for (int i = 0; i < 5; i++) engine.sessions.choose(session.id, "c1");
        assertEquals(4, session.state.recentScenes.size(), "Prompt memory stays bounded");
        var restored = engine.mapper.readValue(engine.mapper.writeValueAsBytes(session), GameSession.class);
        assertTrue(restored.history.getFirst().blocks.get(1).text().contains(PASSWORD));
    }

    @Test void speculativeRequestsAlsoReceiveCommittedDialogue() {
        var seen = new AtomicReference<String>();
        var engine = memoryEngine(true, seen);
        var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        engine.awaitBranches(session.id);
        assertNotNull(seen.get());
        assertTrue(seen.get().contains(PASSWORD));
        assertEquals(1, session.history.size(), "Speculative prose is not committed history");
    }

    @Test void oldSavesRecoverTheirCurrentSceneContext() throws Exception {
        var engine = memoryEngine(false, new AtomicReference<>());
        var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        ObjectNode old = (ObjectNode) engine.mapper.valueToTree(session);
        ((ObjectNode) old.get("state")).remove("recentScenes");
        for (var entry : old.withArray("history")) ((ObjectNode) entry).remove("blocks");
        var restored = engine.mapper.treeToValue(old, GameSession.class);
        engine.repository.save(restored);
        var loaded = engine.sessions.require(restored.id);
        assertTrue(loaded.state.recentScenes.getFirst().blocks().get(1).text().contains(PASSWORD));
        assertTrue(loaded.history.getFirst().blocks.isEmpty());
    }

    @Test void aValidEndingCanHandOffToAnAlreadyPlannedArc() {
        ObjectMapper mapper = new ObjectMapper();
        var client = new ScriptedLlmClient(mapper, r -> {
            if (ScriptedLlmClient.choiceOf(r) == null) return SceneJson.scene("Opening")
                    .choice("c1", "Finish", "action").build();
            GameState state = (GameState) r.mockContext().get("state");
            if ("last".equals(state.currentBeatId)) return SceneJson.scene("The mystery is solved.")
                    .op("{\"op\":\"completeBeat\",\"target\":\"last\"}").build();
            return SceneJson.scene("A new chapter opens.").choice("c1", "Explore", "action").build();
        });
        var engine = new Engine(client, new ScriptedRandom(), false, false);
        var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        var last = new StoryBeat("last", "Final beat", "Resolve", "Done", "major");
        session.story.spine = new StorySpine("First arc", List.of(last));
        session.state.currentBeatId = "last";
        session.pendingArc = new ArcOutline("Next arc", "Premise", "Conflict",
                List.of(new StoryBeat("next", "Next", "Explore", "Done", "major")), List.of(), List.of(), List.of());
        engine.sessions.choose(session.id, "c1");
        assertFalse(session.finished);
        assertEquals("next", session.state.currentBeatId);
        assertEquals("continue_arc", session.currentScene.choices().getFirst().id());
        engine.sessions.choose(session.id, "continue_arc");
        assertEquals("A new chapter opens.", session.currentScene.blocks().getFirst().text());
    }

    @Test void httpRequiresSceneGuardAndReturnsDistinctErrorsAndSaveHealth() throws Exception {
        var sessions = mock(SessionService.class);
        var llm = mock(StructuredLlm.class);
        var props = new com.genvn.config.GenvnProperties();
        var controller = new SessionController(sessions, new com.genvn.speculation.BranchCache(),
                new LlmCallLog(), llm, props);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/sessions/s1/choices/c1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/sessions/s1/choices/c1").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sessions);
        when(sessions.choose("s1", "c1", "old", 1)).thenThrow(new SceneConflictException("Reload"));
        mvc.perform(post("/api/sessions/s1/choices/c1").contentType(APPLICATION_JSON)
                .content("{\"expectedSceneId\":\"old\",\"expectedStateVersion\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("scene_conflict"));
        when(sessions.choose("s1", "c1", "current", 2))
                .thenThrow(new com.genvn.api.ChoiceResolvingException("c1"));
        mvc.perform(post("/api/sessions/s1/choices/c1").contentType(APPLICATION_JSON)
                .content("{\"expectedSceneId\":\"current\",\"expectedStateVersion\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("choice_resolving"))
                .andExpect(header().string("Retry-After", "2"));
        when(sessions.require("missing")).thenThrow(new SessionNotFoundException("Missing"));
        mvc.perform(get("/api/sessions/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("session_not_found"));
        var session = new GameSession(); session.id = "unsaved"; session.saveHealthy = false;
        session.resolvingChoiceId = "c1";
        when(sessions.require("unsaved")).thenReturn(session);
        when(sessions.copySession(session)).thenReturn(session);
        mvc.perform(get("/api/sessions/unsaved")).andExpect(status().isOk())
                .andExpect(jsonPath("$.saveHealthy").value(false))
                .andExpect(jsonPath("$.resolvingChoiceId").value("c1"));
    }

    private Engine memoryEngine(boolean speculation, AtomicReference<String> seen) {
        ObjectMapper mapper = new ObjectMapper();
        var client = new ScriptedLlmClient(mapper, r -> {
            if (ScriptedLlmClient.choiceOf(r) != null) {
                seen.set(r.user());
                return SceneJson.scene("You approach the safe.").choice("c1", "Continue", "action").build();
            }
            try {
                ObjectNode scene = (ObjectNode) mapper.readTree(SceneJson.scene("Rain taps on the window.")
                        .choice("c1", "Use the password just heard", "action").build());
                scene.withArray("blocks").addObject().put("type", "dialogue").put("speakerId", "npc_witness")
                        .put("speakerName", "Neighbour").put("text", "The safe password is " + PASSWORD);
                return scene.toString();
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        return new Engine(client, new ScriptedRandom(), speculation, false);
    }
}
