package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.dice.CheckResolver;
import com.genvn.dice.DiceService;
import com.genvn.game.GameSession;
import com.genvn.game.SessionHistoryService;
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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scene tree is the save's memory of every path that was generated. Rewinding restores a
 * visited node exactly, including its sealed dice; an unused ready candidate is adopted without
 * another model call.
 */
class SaveTreeRewindTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private record Stack(SessionService sessions, FileGameSessionRepository repository, SceneTreeStore tree,
                         SpeculativeGenerator speculative, ScriptedLlmClient client, BranchCache cache) {
        void close() { speculative.close(); }
    }

    private Stack stack(Path dataDir, boolean speculation, ScriptedRandom dice) {
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
        var resolver = new CheckResolver(new DiceService(dice));
        var speculative = new SpeculativeGenerator(generator, cache, reducer, mapper, properties, resolver);
        var repository = new FileGameSessionRepository(mapper, properties);
        var tree = new SceneTreeStore(mapper, dataDir.resolve("sessions"));
        var sessions = new SessionService(new StoryCompiler(llm), generator, reducer, resolver, cache, speculative,
                new ArcContinuationService(llm, new ContextRenderer(), properties), repository, mapper,
                com.genvn.asset.AssetCoordinator.disabled(), null, tree);
        return new Stack(sessions, repository, tree, speculative, client, cache);
    }

    @Test
    @DisplayName("rewinding restores a visited scene and its sealed dice; taking the same choice does not re-apply it")
    void rewindRestoresVisitedPathWithoutRegenerating(@TempDir Path dir) {
        Stack stack = stack(dir, false, new ScriptedRandom(4, 15));
        try {
            GameSession created = stack.sessions.create(Engine.OUTLINE, Engine.alex());
            String opening = created.currentScene.sceneId();
            int openingVersion = created.state.stateVersion;
            int scenesBefore = stack.client.sceneCallCount();

            stack.sessions.choose(created.id, "stairs");
            GameSession after = stack.sessions.require(created.id);
            assertEquals("The stairwell smells of dust.", after.currentScene.blocks().getFirst().text());
            assertNotEquals(opening, after.currentScene.sceneId());
            assertTrue(stack.tree.readNode(created.id, opening).orElseThrow().restorable());
            assertTrue(stack.tree.readNode(created.id, after.currentScene.sceneId()).orElseThrow().restorable());

            GameSession rewound = stack.sessions.rewind(created.id, opening, after.currentScene.sceneId(),
                    after.state.stateVersion);
            assertEquals(opening, rewound.currentScene.sceneId());
            assertEquals("The hall is quiet.", rewound.currentScene.blocks().getFirst().text());
            assertEquals(1, rewound.history.size(), "history follows the path to the restored node");
            assertTrue(rewound.state.stateVersion > openingVersion, "stale clients still 409");
            int afterRewind = stack.client.sceneCallCount();

            GameSession same = stack.sessions.choose(created.id, "stairs").session();
            assertEquals("The stairwell smells of dust.", same.currentScene.blocks().getFirst().text());
            assertEquals(afterRewind, stack.client.sceneCallCount(),
                    "re-taking a played path restores the visited child; it must not generate again");
            assertEquals(scenesBefore + 1, stack.client.sceneCallCount(),
                    "only the first stairs choice paid for a scene");
        } finally {
            stack.close();
        }
    }

    @Test
    @DisplayName("a finished unused branch is kept on disk and adopted after rewind without a new model call")
    void unusedReadyBranchIsAdoptedAfterRewind(@TempDir Path dir) {
        Stack stack = stack(dir, true, new ScriptedRandom(4, 15));
        try {
            GameSession created = stack.sessions.create(Engine.OUTLINE, Engine.alex());
            String opening = created.currentScene.sceneId();
            awaitBranches(stack, created.id);

            stack.sessions.choose(created.id, "stairs");
            String windowId = SceneNode.preparedId(opening, "window", SceneRequest.NONE);
            SceneNode unused = stack.tree.readNode(created.id, windowId).orElseThrow();
            assertFalse(unused.visited);
            assertEquals("window", unused.fromChoiceId);
            assertEquals("Rain hits the windowsill.", unused.scene.blocks().getFirst().text());

            GameSession atStairs = stack.sessions.require(created.id);
            stack.sessions.rewind(created.id, opening, atStairs.currentScene.sceneId(), atStairs.state.stateVersion);
            awaitBranches(stack, created.id);
            var adopted = stack.sessions.choose(created.id, "window");
            GameSession viaWindow = adopted.session();
            assertEquals("Rain hits the windowsill.", viaWindow.currentScene.blocks().getFirst().text());
            assertEquals(windowId, viaWindow.currentScene.sceneId());
            assertTrue(adopted.fromSpeculativeCache(),
                    "adopting a retained candidate must not generate live");
            assertTrue(stack.tree.readNode(created.id, windowId).orElseThrow().visited);

            var history = new SessionHistoryService(stack.repository, stack.tree)
                    .read(created.id, viaWindow.currentScene.sceneId(), 0, null, 20);
            assertTrue(history.entries().getFirst().restorable(), "the opening can be rewound to");
            assertFalse(history.entries().getLast().restorable(), "the current head cannot");
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
