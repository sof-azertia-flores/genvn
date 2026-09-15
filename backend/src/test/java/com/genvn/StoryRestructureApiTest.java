package com.genvn;

import com.genvn.api.ApiExceptionHandler;
import com.genvn.api.StoryRestructureController;
import com.genvn.game.SessionService;
import com.genvn.game.StoryRestructureService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The HTTP surface of a restructure: accepted, polled, and refused for the usual reasons. */
class StoryRestructureApiTest {

    private static String body(String instruction) {
        return "{\"expectedSceneId\":\"scene_003\",\"expectedStateVersion\":4,\"instruction\":\"" + instruction + "\"}";
    }

    @Test
    @DisplayName("a restructure is accepted as a job, polled by its own id, and never started twice by a replay")
    void acceptedAndPolled() throws Exception {
        var sessions = mock(SessionService.class);
        try (var jobs = new StoryRestructureService(sessions)) {
            var mvc = MockMvcBuilders.standaloneSetup(new StoryRestructureController(jobs))
                    .setControllerAdvice(new ApiExceptionHandler()).build();
            String key = UUID.randomUUID().toString();

            mvc.perform(post("/api/sessions/abc123/nodes/scene_003/restructure")
                            .header("Idempotency-Key", key).contentType(APPLICATION_JSON).content(body("换一个对手")))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.id").value(key))
                    .andExpect(jsonPath("$.sessionId").value("abc123"));
            // A replay of the same key recovers the same job rather than rewriting again.
            mvc.perform(post("/api/sessions/abc123/nodes/scene_003/restructure")
                            .header("Idempotency-Key", key).contentType(APPLICATION_JSON).content(body("换一个对手")))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(key));
            mvc.perform(get("/api/session-restructures/" + key))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(key));

            verify(sessions, timeout(2000).times(1))
                    .restructure(eq("abc123"), eq("scene_003"), eq("换一个对手"), eq("scene_003"), eq(4), eq(key), any());
        }
    }

    @Test
    @DisplayName("blank or oversized words are rejected before any work starts, and an expired job is a 404")
    void badRequestsAndExpiredJobs() throws Exception {
        var sessions = mock(SessionService.class);
        try (var jobs = new StoryRestructureService(sessions)) {
            var mvc = MockMvcBuilders.standaloneSetup(new StoryRestructureController(jobs))
                    .setControllerAdvice(new ApiExceptionHandler()).build();

            mvc.perform(post("/api/sessions/abc123/nodes/scene_003/restructure")
                            .contentType(APPLICATION_JSON).content(body("   ")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
            mvc.perform(post("/api/sessions/abc123/nodes/scene_003/restructure")
                            .contentType(APPLICATION_JSON).content(body("好".repeat(2001))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
            mvc.perform(post("/api/sessions/abc123/nodes/scene_003/restructure")
                            .header("Idempotency-Key", "1-1-1-1-1")
                            .contentType(APPLICATION_JSON).content(body("换一个对手")))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/session-restructures/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));

            verify(sessions, never()).restructure(anyString(), anyString(), anyString(), anyString(), anyInt(),
                    anyString(), any());
        }
    }
}
