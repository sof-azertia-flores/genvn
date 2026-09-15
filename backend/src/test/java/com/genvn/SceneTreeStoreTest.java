package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.GameSession;
import com.genvn.narrative.SceneRequest;
import com.genvn.persistence.SceneNode;
import com.genvn.persistence.SceneTreeStore;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The append-only scene tree beside a save. A branch the player left is kept on purpose, so
 * re-choosing it later costs nothing, and one damaged branch costs only that branch.
 */
class SceneTreeStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path root;

    private GameSession session() {
        var client = new ScriptedLlmClient(mapper, request -> ScriptedLlmClient.choiceOf(request) == null
                ? SceneJson.scene("The study is dark.")
                        .choiceWithCheck("cC", "Force the desk drawer", "Perception", 13)
                        .choice("cP", "Wait", "cautious").build()
                : SceneJson.scene("Later.").choice("c1", "Go on", "cautious").build());
        var engine = new Engine(client, new ScriptedRandom(4, 15), false, false);
        return engine.sessions.create(Engine.OUTLINE, Engine.alex());
    }

    private SceneTreeStore store() {
        return new SceneTreeStore(mapper, root);
    }

    @Test
    @DisplayName("a visited node round-trips with its scene, its state and its sealed dice")
    void visitedNodeRoundTrips() {
        GameSession session = session();
        SceneTreeStore store = store();
        String storyHash = store.writeStory(session.id, session.story);
        SceneNode node = SceneNode.visited(null, session.currentScene, null, null, null, SceneRequest.NONE,
                session.state, session.sceneDice, storyHash);
        store.writeNode(session.id, node);

        SceneNode read = store.readNode(session.id, session.currentScene.sceneId()).orElseThrow();
        assertEquals(session.currentScene.sceneId(), read.nodeId, "a node id is its scene id");
        assertNull(read.parentNodeId, "the opening has no parent");
        assertTrue(read.visited);
        assertTrue(read.restorable(), "a visited node carries everything a rewind needs");
        assertEquals(session.state.stateVersion, read.state.stateVersion);
        assertEquals(session.currentScene.blocks().size(), read.scene.blocks().size());
        // The sealed die is the whole point: rewinding must not be able to re-roll it.
        assertEquals(session.sceneDice.get("cC").d20(), read.sceneDice.get("cC").d20());
        assertFalse(read.sceneDice.containsKey("cP"), "a choice with no check has no die");
        assertEquals(storyHash, read.storyHash);
    }

    @Test
    @DisplayName("a prepared candidate is kept and listed, but cannot be rewound to until it is played")
    void preparedNodeIsKeptButNotRestorable() {
        GameSession session = session();
        SceneTreeStore store = store();
        String storyHash = store.writeStory(session.id, session.story);
        String parent = session.currentScene.sceneId();

        store.writeNode(session.id, SceneNode.visited(null, session.currentScene, null, null, null,
                SceneRequest.NONE, session.state, session.sceneDice, storyHash));
        // The candidate the engine wrote for the choice the player did not take.
        var candidate = session.currentScene.withSceneId("scene_001");
        store.writeNode(session.id, SceneNode.prepared(parent, candidate, "cP", "Wait", null,
                SceneRequest.NONE, storyHash));

        List<SceneNode> nodes = store.listNodes(session.id);
        assertEquals(List.of(parent, "scene_001"), nodes.stream().map(n -> n.nodeId).toList(),
                "the opening sorts before later scenes");
        SceneNode prepared = nodes.get(1);
        assertFalse(prepared.visited);
        assertFalse(prepared.restorable(), "state comes from committing a candidate, not from storing it ahead");
        assertNull(prepared.state);
        assertEquals(parent, prepared.parentNodeId);
        assertEquals("cP", prepared.fromChoiceId, "an edge is a choice");

        // Playing it later fills in exactly what was missing, and keeps the same identity.
        prepared.markVisited(session.state, session.sceneDice);
        store.writeNode(session.id, prepared);
        SceneNode promoted = store.readNode(session.id, "scene_001").orElseThrow();
        assertTrue(promoted.restorable());
        assertEquals("cP", promoted.fromChoiceId);
        assertNotNull(promoted.visitedAt);
    }

    @Test
    @DisplayName("identical stories share one file; a story that has changed gets its own")
    void storiesAreContentAddressed() throws Exception {
        GameSession session = session();
        SceneTreeStore store = store();

        String first = store.writeStory(session.id, session.story);
        String again = store.writeStory(session.id, session.story);
        assertEquals(first, again, "every scene in an arc points at one stored story");
        assertEquals(1, storyFileCount(session.id));

        session.story.artStyle = "ink wash, muted";
        String changed = store.writeStory(session.id, session.story);
        assertNotEquals(first, changed, "a new arc is a new story");
        assertEquals(2, storyFileCount(session.id));
        assertEquals("ink wash, muted", store.readStory(session.id, changed).orElseThrow().artStyle);
        assertTrue(store.readStory(session.id, first).isPresent(), "the earlier story is still readable");
    }

    @Test
    @DisplayName("one unreadable branch costs that branch, not the save")
    void damagedNodeIsSkipped() throws Exception {
        GameSession session = session();
        SceneTreeStore store = store();
        String storyHash = store.writeStory(session.id, session.story);
        store.writeNode(session.id, SceneNode.visited(null, session.currentScene, null, null, null,
                SceneRequest.NONE, session.state, session.sceneDice, storyHash));
        store.writeNode(session.id, SceneNode.prepared(session.currentScene.sceneId(),
                session.currentScene.withSceneId("scene_001"), "cP", "Wait", null, SceneRequest.NONE, storyHash));

        Files.writeString(root.resolve(session.id).resolve("nodes").resolve("scene_001.json"), "{ truncated");

        assertTrue(store.readNode(session.id, "scene_001").isEmpty());
        assertEquals(List.of(session.currentScene.sceneId()), store.listNodes(session.id).stream()
                .map(n -> n.nodeId).toList(), "the rest of the tree still loads");
        assertTrue(store.readNode(session.id, session.currentScene.sceneId()).orElseThrow().restorable());
    }

    @Test
    @DisplayName("a node file is published whole or not at all, and never outside its save")
    void writesArePublishedAtomicallyAndStayInsideTheSave() throws Exception {
        GameSession session = session();
        SceneTreeStore store = store();
        String storyHash = store.writeStory(session.id, session.story);
        store.writeNode(session.id, SceneNode.visited(null, session.currentScene, null, null, null,
                SceneRequest.NONE, session.state, session.sceneDice, storyHash));

        try (var files = Files.list(root.resolve(session.id).resolve("nodes"))) {
            assertEquals(List.of(session.currentScene.sceneId() + ".json"),
                    files.map(p -> p.getFileName().toString()).sorted().toList(),
                    "no temporary file is left behind for a reader to find");
        }

        SceneNode escaping = SceneNode.prepared(null, session.currentScene.withSceneId("scene_002"),
                null, null, null, SceneRequest.NONE, storyHash);
        for (String nodeId : List.of("../../escape", "..", "", "a/b", "nodes/../../x")) {
            escaping.nodeId = nodeId;
            assertThrows(IllegalArgumentException.class, () -> store.writeNode(session.id, escaping),
                    "must refuse to write through node id '" + nodeId + "'");
            assertTrue(store.readNode(session.id, nodeId).isEmpty());
        }
        for (String hash : List.of("../escape", "NOTHEX", "")) {
            assertTrue(store.readStory(session.id, hash).isEmpty());
        }
        for (String bad : List.of("../elsewhere", "", "a/b")) {
            assertTrue(store.listNodes(bad).isEmpty(), "must not read a tree through session id '" + bad + "'");
            assertFalse(store.hasNodes(bad));
        }
    }

    @Test
    @DisplayName("a tree that cannot be written says so instead of pretending it saved")
    void unwritableTreeFailsLoudly() throws Exception {
        GameSession session = session();
        // A file where the save directory belongs: nothing below it can be created.
        Files.writeString(root.resolve(session.id), "blocks the save directory");
        SceneTreeStore store = store();

        assertThrows(UncheckedIOException.class, () -> store.writeStory(session.id, session.story));
        SceneNode node = SceneNode.visited(null, session.currentScene, null, null, null, SceneRequest.NONE,
                session.state, session.sceneDice, "deadbeef");
        assertThrows(UncheckedIOException.class, () -> store.writeNode(session.id, node));
        assertFalse(store.hasNodes(session.id));
        assertTrue(store.listNodes(session.id).isEmpty());
    }

    @Test
    @DisplayName("nested prepared ids stay within the on-disk 160-character limit")
    void nestedPreparedIdsStayWithinLimit() {
        String parent = "scene_000";
        String choice = "c".repeat(64);
        for (int depth = 0; depth < 5; depth++) {
            parent = SceneNode.preparedId(parent, choice, "NONE");
            assertTrue(parent.length() <= 160, parent);
            assertTrue(parent.matches("[A-Za-z0-9_\\-]+"), parent);
        }
        SceneNode node = new SceneNode();
        node.nodeId = parent;
        node.parentNodeId = "scene_000";
        store().writeNode("nested", node);
        assertEquals(parent, store().readNode("nested", parent).orElseThrow().nodeId);
    }

    private long storyFileCount(String sessionId) throws Exception {
        try (var files = Files.list(root.resolve(sessionId).resolve("stories"))) {
            return files.count();
        }
    }
}
