package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.dice.CheckResolver;
import com.genvn.dice.DiceService;
import com.genvn.game.GameSession;
import com.genvn.game.SessionService;
import com.genvn.game.StateReducer;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.SceneGenerator;
import com.genvn.narrative.SceneRequest;
import com.genvn.persistence.FileGameSessionRepository;
import com.genvn.persistence.SceneNode;
import com.genvn.persistence.SceneTreeStore;
import com.genvn.prompt.ContextRenderer;
import com.genvn.speculation.BranchCache;
import com.genvn.speculation.SpeculativeGenerator;
import com.genvn.story.ArcContinuationService;
import com.genvn.story.StoryCompiler;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SaveTreeIntegrityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private record Stack(SessionService sessions, FileGameSessionRepository repository, SceneTreeStore tree,
                         SpeculativeGenerator speculative, ScriptedLlmClient client, BranchCache cache) {
        void close() { speculative.close(); }
    }

    private Stack stack(Path dataDir, boolean speculation) {
        var client = new ScriptedLlmClient(mapper, request -> {
            var choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                return SceneJson.scene("The hall is quiet.")
                        .choice("stairs", "Take the stairs", "action")
                        .choice("window", "Climb through the window", "action")
                        .build();
            }
            if ("stairs".equals(choice.id())) {
                return SceneJson.scene("The stairwell smells of dust.")
                        .choice("onward", "Keep climbing", "action").build();
            }
            return SceneJson.scene("Rain hits the windowsill.")
                    .choice("onward", "Drop to the garden", "action").build();
        });
        var properties = Engine.properties(speculation, false);
        properties.setDataDir(dataDir.toString());
        var llm = new StructuredLlm(client, mapper, new LlmCallLog());
        var generator = new SceneGenerator(llm, new ContextRenderer());
        var reducer = new StateReducer();
        var cache = new BranchCache();
        var resolver = new CheckResolver(new DiceService(new ScriptedRandom(4, 15)));
        var speculative = new SpeculativeGenerator(generator, cache, reducer, mapper, properties, resolver);
        var repository = new FileGameSessionRepository(mapper, properties);
        var tree = new SceneTreeStore(mapper, dataDir.resolve("sessions"));
        speculative.setSceneTree(tree);
        var sessions = new SessionService(new StoryCompiler(llm), generator, reducer, resolver, cache, speculative,
                new ArcContinuationService(llm, new ContextRenderer(), properties), repository, mapper,
                com.genvn.asset.AssetCoordinator.disabled(), null, tree);
        return new Stack(sessions, repository, tree, speculative, client, cache);
    }

    @Test
    @DisplayName("a crash after writing a node does not reuse that scene id or parent a node to itself")
    void crashAfterNodeWriteDoesNotOverwriteTheOrphan(@TempDir Path dir) throws Exception {
        Stack stack = stack(dir, false);
        try {
            GameSession created = stack.sessions.create(Engine.OUTLINE, Engine.alex());
            String opening = created.currentScene.sceneId();
            stack.sessions.choose(created.id, "stairs");
            GameSession after = stack.sessions.require(created.id);
            String firstChild = after.currentScene.sceneId();
            assertEquals("scene_001", firstChild);
            SceneNode original = stack.tree.readNode(created.id, firstChild).orElseThrow();
            assertEquals(opening, original.parentNodeId);
            String originalText = original.scene.blocks().getFirst().text();

            Path sessionFile = dir.resolve("sessions").resolve(created.id).resolve("session.json");
            GameSession rolledBack = mapper.readValue(sessionFile.toFile(), GameSession.class);
            SceneNode openingNode = stack.tree.readNode(created.id, opening).orElseThrow();
            rolledBack.currentScene = openingNode.scene;
            rolledBack.currentNodeId = opening;
            rolledBack.sceneCounter = 1;
            rolledBack.state = openingNode.state;
            rolledBack.history = rolledBack.history.isEmpty() ? rolledBack.history
                    : java.util.List.of(rolledBack.history.getFirst());
            mapper.writerWithDefaultPrettyPrinter().writeValue(sessionFile.toFile(), rolledBack);

            Stack restarted = stack(dir, false);
            try {
                GameSession loaded = restarted.sessions.require(created.id);
                assertEquals(opening, loaded.currentScene.sceneId());
                assertTrue(loaded.sceneCounter > 1, "the counter must pass every scene_NNN already on disk");
                GameSession next = restarted.sessions.choose(created.id, "window").session();
                assertNotEquals(firstChild, next.currentScene.sceneId(), "the orphaned child must keep its id");
                SceneNode kept = restarted.tree.readNode(created.id, firstChild).orElseThrow();
                assertEquals(opening, kept.parentNodeId);
                assertNotEquals(kept.nodeId, kept.parentNodeId);
                assertEquals(originalText, kept.scene.blocks().getFirst().text());
                SceneNode fresh = restarted.tree.readNode(created.id, next.currentScene.sceneId()).orElseThrow();
                assertEquals(opening, fresh.parentNodeId);
                assertNotEquals(fresh.nodeId, fresh.parentNodeId);
            } finally {
                restarted.close();
            }
        } finally {
            stack.close();
        }
    }

    @Test
    @DisplayName("rewinding does not spend model calls on children the tree already holds")
    void rewindDoesNotPrefetchSavedChildren(@TempDir Path dir) {
        Stack stack = stack(dir, true);
        try {
            GameSession created = stack.sessions.create(Engine.OUTLINE, Engine.alex());
            String opening = created.currentScene.sceneId();
            awaitBranches(stack, created.id);
            stack.sessions.choose(created.id, "stairs");
            assertTrue(stack.tree.findChild(created.id, opening, "window", SceneRequest.NONE).isPresent());
            int calls = stack.client.sceneCallCount();
            GameSession atStairs = stack.sessions.require(created.id);
            stack.sessions.rewind(created.id, opening, atStairs.currentScene.sceneId(), atStairs.state.stateVersion);
            assertEquals(calls, stack.client.sceneCallCount(),
                    "saved branches must not be generated again after a rewind");
        } finally {
            stack.close();
        }
    }

    private static void awaitBranches(Stack stack, String sessionId) {
        for (int i = 0; i < 200; i++) {
            var rows = stack.cache.status(sessionId);
            if (!rows.isEmpty() && rows.stream().noneMatch(r -> "generating".equals(r.get("status"))
                    || "queued".equals(r.get("status")))) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("speculative branches did not finish");
    }
}
