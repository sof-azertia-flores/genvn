package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.NotFoundException;
import com.genvn.api.RestructureInProgressException;
import com.genvn.api.SceneConflictException;
import com.genvn.game.GameSession;
import com.genvn.llm.MockLlmClient;
import com.genvn.support.Engine;
import com.genvn.support.TreeEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A restructure holds the save for two model calls. Everything else that would mutate it has to
 * conflict rather than race a story that is being rewritten underneath it.
 */
class StoryRestructureConflictTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String WISH = "让对手换成我的家族。";

    private GameSession played(TreeEngine engine, int scenes) {
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        for (int i = 0; i < scenes && !session.currentScene.choices().isEmpty(); i++) {
            session = engine.sessions.choose(session.id, session.currentScene.choices().get(0).id()).session();
        }
        return engine.sessions.require(session.id);
    }

    @Test
    @DisplayName("a page that is behind gets a conflict instead of rewriting a scene it is not looking at")
    void staleExpectationsConflict(@TempDir Path dir) {
        try (TreeEngine engine = new TreeEngine(dir, new MockLlmClient(mapper))) {
            GameSession live = played(engine, 2);
            String node = live.currentNodeId;

            assertThrows(SceneConflictException.class, () -> engine.sessions.restructure(live.id, node, WISH,
                    "scene_999", live.state.stateVersion, "job", (s, p, m) -> {}));
            assertThrows(SceneConflictException.class, () -> engine.sessions.restructure(live.id, node, WISH,
                    live.currentScene.sceneId(), live.state.stateVersion + 5, "job", (s, p, m) -> {}));
        }
    }

    @Test
    @DisplayName("an unknown or never-played scene cannot be restructured, and neither can empty words")
    void unusableAnchorsAreRefused(@TempDir Path dir) {
        try (TreeEngine engine = new TreeEngine(dir, new MockLlmClient(mapper))) {
            GameSession live = played(engine, 2);
            String scene = live.currentScene.sceneId();
            int version = live.state.stateVersion;

            assertThrows(NotFoundException.class, () -> engine.sessions.restructure(live.id, "scene_777", WISH,
                    scene, version, "job", (s, p, m) -> {}));
            assertThrows(IllegalArgumentException.class, () -> engine.sessions.restructure(live.id,
                    live.currentNodeId, "   ", scene, version, "job", (s, p, m) -> {}));
        }
    }

    @Test
    @DisplayName("while the story is being rewritten, choosing, rolling and rewinding all conflict")
    void everyOtherMutationConflictsDuringARestructure(@TempDir Path dir) throws InterruptedException {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Hold the restructure inside its first model call, so the marker is provably set while
        // the session monitor is provably free.
        var client = new MockLlmClient(mapper) {
            @Override
            public com.genvn.llm.LlmResponse complete(com.genvn.llm.LlmRequest request) {
                if (request.purpose() == com.genvn.llm.LlmPurpose.STORY_RESTRUCTURE) {
                    inside.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.complete(request);
            }
        };
        try (TreeEngine engine = new TreeEngine(dir, client)) {
            GameSession live = played(engine, 2);
            String scene = live.currentScene.sceneId();
            int version = live.state.stateVersion;
            String choiceId = live.currentScene.choices().get(0).id();
            String node = live.currentNodeId;

            Thread worker = new Thread(() -> engine.sessions.restructure(live.id, node, WISH, scene, version,
                    "job-a", (s, p, m) -> {}));
            worker.setDaemon(true);
            worker.start();
            assertTrue(inside.await(10, TimeUnit.SECONDS), "the restructure never reached its model call");

            try {
                assertThrows(RestructureInProgressException.class,
                        () -> engine.sessions.choose(live.id, choiceId, scene, version));
                assertThrows(RestructureInProgressException.class,
                        () -> engine.sessions.rewind(live.id, node, scene, version));
                assertThrows(RestructureInProgressException.class,
                        () -> engine.sessions.restructure(live.id, node, WISH, scene, version, "job-b", (s, p, m) -> {}));
            } finally {
                release.countDown();
            }
            worker.join(TimeUnit.SECONDS.toMillis(20));

            // And once it is done the save is usable again, with the marker cleared.
            GameSession after = engine.sessions.require(live.id);
            assertNull(after.restructuringJobId, "the marker must never outlive the job that set it");
            assertFalse(after.currentScene.choices().isEmpty());
            assertNotNull(engine.sessions.choose(after.id, after.currentScene.choices().get(0).id()).session());
        }
    }

    @Test
    @DisplayName("one save rewrites one story at a time; a second request is refused, not queued")
    void oneRestructurePerSave(@TempDir Path dir) throws InterruptedException {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var client = new MockLlmClient(mapper) {
            @Override
            public com.genvn.llm.LlmResponse complete(com.genvn.llm.LlmRequest request) {
                if (request.purpose() == com.genvn.llm.LlmPurpose.STORY_RESTRUCTURE) {
                    inside.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.complete(request);
            }
        };
        try (TreeEngine engine = new TreeEngine(dir, client)) {
            GameSession live = played(engine, 2);
            String key = UUID.randomUUID().toString();
            var first = engine.restructures.submit(live.id, live.currentNodeId, WISH,
                    live.currentScene.sceneId(), live.state.stateVersion, key);
            assertTrue(inside.await(10, TimeUnit.SECONDS), "the first job never started");

            try {
                // Replaying the SAME key is a recovery, not a second rewrite.
                assertEquals(first.id(), engine.restructures.submit(live.id, live.currentNodeId, WISH,
                        live.currentScene.sceneId(), live.state.stateVersion, key).id());
                // A different key on the same save is refused outright.
                assertThrows(RestructureInProgressException.class, () -> engine.restructures.submit(live.id,
                        live.currentNodeId, WISH, live.currentScene.sceneId(), live.state.stateVersion,
                        UUID.randomUUID().toString()));
                // The same key pointed at a different anchor is a client bug, not a recovery.
                assertThrows(IllegalArgumentException.class, () -> engine.restructures.submit(live.id,
                        "scene_000", WISH, live.currentScene.sceneId(), live.state.stateVersion, key));
            } finally {
                release.countDown();
            }
        }
    }
}
