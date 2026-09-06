package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.*;
import com.genvn.config.GenvnProperties;
import com.genvn.game.PlayerCharacter;
import com.genvn.game.SessionCreationService;
import com.genvn.game.SessionService;
import com.genvn.game.Stat;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.SceneBundle;
import com.genvn.speculation.Branch;
import com.genvn.speculation.BranchKey;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionProgressApiTest {
    @Test void idempotencyHeaderReturnsThePredictableJobIdAndRejectsMalformedOrConflictingReuse() throws Exception {
        var sessions = mock(SessionService.class);
        when(sessions.create(anyString(), any(), any())).thenAnswer(call -> {
            var session = new com.genvn.game.GameSession(); session.id = "single-save"; return session;
        });
        try (var jobs = new SessionCreationService(sessions)) {
            var mvc = MockMvcBuilders.standaloneSetup(new SessionCreationController(jobs))
                    .setControllerAdvice(new ApiExceptionHandler()).build();
            String key = java.util.UUID.randomUUID().toString();
            String body = "{\"storyOutline\":\"Story\"}";
            mvc.perform(post("/api/session-creations").header("Idempotency-Key", key)
                    .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(key));
            mvc.perform(post("/api/session-creations").header("Idempotency-Key", key)
                    .contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(key));
            mvc.perform(get("/api/session-creations/" + key)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(key));
            mvc.perform(post("/api/session-creations").header("Idempotency-Key", "1-1-1-1-1")
                    .contentType(APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            mvc.perform(post("/api/session-creations").header("Idempotency-Key", key)
                    .contentType(APPLICATION_JSON).content("{\"storyOutline\":\"Another story\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
            verify(sessions, timeout(1000).times(1)).create(anyString(), any(), any());
        }
    }

    @Test void asyncCreateUsesTheSameInputValidationAndPlayerNormalization() throws Exception {
        var creations = mock(SessionCreationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SessionCreationController(creations))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/session-creations").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/session-creations").contentType(APPLICATION_JSON)
                .content("{\"storyOutline\":\"Story\",\"player\":{\"name\":\"\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/session-creations").contentType(APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(java.util.Map.of("storyOutline", "Story", "artStyle", "x".repeat(6001)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/session-creations").contentType(APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(java.util.Map.of("storyOutline", "Story",
                        "player", java.util.Map.of("name", "Alex", "visualDescription", "x".repeat(2001))))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(creations);
        var view = new Dtos.CreationJobView("job1", "QUEUED", "等待开始", 0, List.of(), null, null);
        when(creations.submit(anyString(), any(), any(), any())).thenReturn(view);
        mvc.perform(post("/api/session-creations").contentType(APPLICATION_JSON).content("""
                {"storyOutline":"Story", "artStyle":"水彩线稿", "player":{"name":" Alex ","stats":{"perception":99},"visualDescription":"银发与黑色手套"}}
                """))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value("job1"))
                .andExpect(jsonPath("$.progress").value(0));
        var player = ArgumentCaptor.forClass(PlayerCharacter.class);
        verify(creations).submit(eq("Story"), player.capture(), isNull(), eq("水彩线稿"));
        assertEquals("Alex", player.getValue().name);
        assertEquals("银发与黑色手套", player.getValue().visualDescription);
        assertEquals(Stat.MAX, player.getValue().stats.get(Stat.PERCEPTION));
        when(creations.require("job1")).thenReturn(view);
        mvc.perform(get("/api/session-creations/job1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        when(creations.require("expired")).thenThrow(new NotFoundException("任务已过期"));
        mvc.perform(get("/api/session-creations/expired")).andExpect(status().isNotFound());
        when(creations.submit(anyString(), any(), any(), any())).thenThrow(new CreationQueueFullException());
        mvc.perform(post("/api/session-creations").contentType(APPLICATION_JSON)
                .content("{\"storyOutline\":\"Story\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("creation_queue_full"))
                .andExpect(header().string("Retry-After", "5"));
    }

    @Test void playerTaskQueueIsSmallReadOnlyAndNeverExposesCandidateProseOrErrors() throws Exception {
        var client = new ScriptedLlmClient(new ObjectMapper(), request -> SceneJson.scene("Visible opening")
                .choiceWithCheck("check", "Inspect the shelves", "Perception", 12).build());
        // d20 15 + Perception 4 vs DC 12: the pre-cast die succeeds, so SUCCESS is the live branch.
        var engine = new Engine(client, new ScriptedRandom(15), false, false);
        var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        assertTrue(session.sceneDice.get("check").success());
        var success = new BranchKey(session.currentScene.sceneId(), "check", "SUCCESS");
        var failure = new BranchKey(session.currentScene.sceneId(), "check", "FAILURE");
        engine.branchCache.put(session.id, new Branch(success,
                CompletableFuture.completedFuture(session.currentScene), session.state.stateVersion,
                System.currentTimeMillis(), new AtomicReference<>("SECRET_CANDIDATE_PROSE")));
        var broken = new CompletableFuture<SceneBundle>();
        broken.completeExceptionally(new RuntimeException("sk-secret-provider-key"));
        engine.branchCache.put(session.id, new Branch(failure, broken, session.state.stateVersion,
                System.currentTimeMillis(), new AtomicReference<>("SECRET_FAILURE_PROSE")));
        var props = new GenvnProperties(); props.getSpeculation().setThreads(3);
        var controller = new SessionController(engine.sessions, engine.branchCache, new LlmCallLog(), engine.llm, props);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        int version = session.state.stateVersion;
        String body = mvc.perform(get("/api/sessions/" + session.id + "/tasks"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.branches.length()").value(1))
                .andExpect(jsonPath("$.branches[0].choiceText").value("Inspect the shelves"))
                .andExpect(jsonPath("$.branches[0].check").value(true))
                .andExpect(jsonPath("$.branches[0].status").value("ready"))
                .andExpect(jsonPath("$.speculationConcurrency").value(3)).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("SECRET_")); assertFalse(body.contains("sk-secret"));
        assertFalse(body.contains("Visible opening"));
        for (String spoiler : List.of("SUCCESS", "FAILURE", "success", "failure", "outcome")) {
            assertFalse(body.contains(spoiler), "the queue must not reveal how a pre-cast die landed: " + spoiler);
        }
        assertEquals(version, session.state.stateVersion);
        assertEquals(1, client.sceneCallCount());
        assertEquals(2, engine.branchCache.size(session.id));
    }
}
