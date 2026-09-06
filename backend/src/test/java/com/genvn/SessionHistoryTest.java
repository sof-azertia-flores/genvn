package com.genvn;

import com.genvn.api.ApiExceptionHandler;
import com.genvn.api.HistoryController;
import com.genvn.game.GameSession;
import com.genvn.game.SessionHistoryService;
import com.genvn.narrative.Block;
import com.genvn.support.InMemoryGameSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionHistoryTest {
    private static GameSession game(int count) {
        var session = new GameSession(); session.id = "s1";
        for (int i = 0; i < count; i++) {
            var entry = new GameSession.HistoryEntry("scene" + i, "beat" + i,
                    i == 0 ? null : "choice" + i, i == 0 ? null : "roll" + i, "opening" + i);
            entry.blocks = List.of(Block.narration("read " + i),
                    Block.dialogue("npc", "人物", "second " + i, "base"));
            session.history.add(entry);
        }
        return session;
    }

    @Test void olderUiSceneBoundsHistoryEvenIfTheServerHasAlreadyCommittedAFutureScene() {
        var repository = new InMemoryGameSessionRepository();
        var game = game(8); repository.save(game);
        var history = new SessionHistoryService(repository);
        var page = history.read("s1", "scene4", 0, null, 20);
        assertEquals(5, page.entries().size());
        assertEquals(2, page.entries().get(3).blocks().size());
        assertEquals(1, page.entries().get(4).blocks().size());
        assertEquals("read 4", page.entries().get(4).blocks().getFirst().text());
        assertFalse(page.toString().contains("second 4"));
        assertFalse(page.toString().contains("scene5"));
        assertEquals(8, game.history.size(), "history access cannot mutate canon");
        assertEquals(2, game.history.get(4).blocks.size());
        assertEquals(0, history.read("s1", "scene4", -1, null, 20).entries().get(4).blocks().size());
    }

    @Test void pagesWalkBackwardWithoutDuplicatesButEachPageIsChronological() {
        var repository = new InMemoryGameSessionRepository(); repository.save(game(45));
        var history = new SessionHistoryService(repository);
        var newest = history.read("s1", "scene41", 1, null, 20);
        assertEquals("scene22", newest.entries().getFirst().sceneId());
        assertEquals("scene41", newest.entries().getLast().sceneId());
        assertEquals("scene22", newest.nextBeforeSceneId());
        var older = history.read("s1", "scene41", 1, newest.nextBeforeSceneId(), 20);
        assertEquals("scene2", older.entries().getFirst().sceneId());
        assertEquals("scene21", older.entries().getLast().sceneId());
        var oldest = history.read("s1", "scene41", 1, older.nextBeforeSceneId(), 20);
        assertEquals(List.of("scene0", "scene1"), oldest.entries().stream().map(SessionHistoryService.Entry::sceneId).toList());
        assertNull(oldest.nextBeforeSceneId());
    }

    @Test void endpointRejectsUnknownFutureAndInvalidBoundariesAndDeletedGames() throws Exception {
        var repository = new InMemoryGameSessionRepository(); var game = game(4); repository.save(game);
        var mvc = MockMvcBuilders.standaloneSetup(new HistoryController(new SessionHistoryService(repository)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/sessions/s1/history").param("throughSceneId", "scene2").param("throughBlockIndex", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[2].choiceText").value("choice2"))
                .andExpect(jsonPath("$.entries[2].rollSummary").value("roll2"))
                .andExpect(jsonPath("$.entries[2].blocks.length()").value(1));
        mvc.perform(get("/api/sessions/s1/history")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/s1/history").param("throughSceneId", "candidate-branch"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/s1/history").param("throughSceneId", "scene1").param("beforeSceneId", "scene3"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/s1/history").param("throughSceneId", "scene1").param("throughBlockIndex", "-2"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/s1/history").param("throughSceneId", "scene1").param("limit", "0"))
                .andExpect(status().isBadRequest());
        game.deleted = true;
        mvc.perform(get("/api/sessions/s1/history").param("throughSceneId", "scene1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/absent/history").param("throughSceneId", "scene1")).andExpect(status().isNotFound());
    }
}
