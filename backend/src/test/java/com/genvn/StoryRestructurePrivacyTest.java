package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.Dtos;
import com.genvn.game.GameSession;
import com.genvn.llm.MockLlmClient;
import com.genvn.support.Engine;
import com.genvn.support.TreeEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the player types to change the story is authorial intent, not a diary entry. It reaches
 * the two model calls and then it is gone: the only record a restructure leaves is the revised
 * framework itself. That was an explicit requirement, so it gets an explicit test.
 */
class StoryRestructurePrivacyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    /** Distinctive enough that a substring search over the whole save cannot miss it. */
    private static final String SECRET = "ZZQ-PRIVATE-REVISION-MARKER-7431";

    @Test
    @DisplayName("the player's own words are never written to the save, the tree or the job view")
    void theInstructionIsNeverPersisted(@TempDir Path dir) throws IOException {
        try (TreeEngine engine = new TreeEngine(dir, new MockLlmClient(mapper))) {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            for (int i = 0; i < 3 && !session.currentScene.choices().isEmpty(); i++) {
                session = engine.sessions.choose(session.id, session.currentScene.choices().get(0).id()).session();
            }
            GameSession live = engine.sessions.require(session.id);

            Dtos.CreationJobView job = engine.restructures.submit(live.id, live.currentNodeId,
                    "请把这一段改掉。" + SECRET, live.currentScene.sceneId(), live.state.stateVersion,
                    UUID.randomUUID().toString());
            Dtos.CreationJobView finished = awaitTerminal(engine, job.id());
            assertEquals("READY", finished.status(), "the restructure itself must succeed for this to mean anything");

            // Every byte the save consists of: session.json, every node, every story blob.
            List<Path> files = new ArrayList<>();
            try (var walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
            assertFalse(files.isEmpty(), "the save must actually be on disk for this test to prove anything");
            for (Path file : files) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(content.contains(SECRET),
                        "the player's instruction leaked into " + dir.relativize(file));
            }

            // And nothing in the job the client polls carries it either.
            String view = mapper.writeValueAsString(finished);
            assertFalse(view.contains(SECRET), "the job view must not echo the instruction back");
        }
    }

    private Dtos.CreationJobView awaitTerminal(TreeEngine engine, String jobId) {
        for (int i = 0; i < 400; i++) {
            Dtos.CreationJobView view = engine.restructures.require(jobId);
            if ("READY".equals(view.status()) || "FAILED".equals(view.status())) return view;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return view;
            }
        }
        return fail("the restructure job never finished");
    }
}
