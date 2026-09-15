package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.GameSession;
import com.genvn.llm.MockLlmClient;
import com.genvn.persistence.SceneNode;
import com.genvn.story.NpcProfile;
import com.genvn.story.StoryBeat;
import com.genvn.support.Engine;
import com.genvn.support.TreeEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A restructure rewrites what the story IS, from one scene onward: the author canon, the bible
 * and every beat still ahead. What it may never do is unmake what has already been played --
 * completed beats, the people the player has met, the places they have been.
 */
class StoryRestructureTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String WISH = "我不想让邻居变成反派，请让真正的对手是我自己的家族。";

    private GameSession play(TreeEngine engine, int scenes) {
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        for (int i = 0; i < scenes && !session.currentScene.choices().isEmpty(); i++) {
            session = engine.sessions.choose(session.id, session.currentScene.choices().get(0).id()).session();
        }
        return session;
    }

    private GameSession restructure(TreeEngine engine, GameSession session, String nodeId) {
        return engine.sessions.restructure(session.id, nodeId, WISH,
                session.currentScene.sceneId(), session.state.stateVersion,
                "job-1", (stage, progress, message) -> {});
    }

    @Test
    @DisplayName("the framework ahead is rewritten while everything already played is kept")
    void rewritesTheFutureAndKeepsThePast(@TempDir Path dir) {
        try (TreeEngine engine = new TreeEngine(dir, new MockLlmClient(mapper))) {
            GameSession played = play(engine, 4);
            GameSession live = engine.sessions.require(played.id);

            List<String> completedBefore = List.copyOf(live.state.completedBeats);
            Set<String> metBefore = live.state.characters.keySet().stream()
                    .filter(id -> !"player".equals(id)).collect(Collectors.toSet());
            List<String> knownPlacesBefore = List.copyOf(live.state.knownLocationIds);
            String canonBefore = String.join("|", live.story.authorCanon.facts());
            String outlineBefore = live.story.authorCanon.originalOutline();
            String artStyleBefore = live.story.artStyle;
            NpcProfile playerVisualBefore = live.story.playerVisual;
            String rejectedSceneId = live.currentScene.sceneId();
            int versionBefore = live.state.stateVersion;

            GameSession after = restructure(engine, live, live.currentNodeId);

            // The law of the story gave way to the request; the seed the player typed did not.
            assertNotEquals(canonBefore, String.join("|", after.story.authorCanon.facts()),
                    "a restructure revises author canon -- that is the point of it");
            assertEquals(outlineBefore, after.story.authorCanon.originalOutline(),
                    "the player's original outline is provenance and is never rewritten");
            assertEquals(artStyleBefore, after.story.artStyle, "art direction is not plot");
            assertEquals(playerVisualBefore, after.story.playerVisual, "the protagonist's look is not plot");

            // Completed beats still resolve, which is what keeps completedBeats meaningful.
            List<String> beatIds = after.story.spine.beats().stream().map(StoryBeat::id).toList();
            for (String completed : completedBefore) {
                assertTrue(beatIds.contains(completed),
                        "completed beat '" + completed + "' must survive in the revised spine");
            }
            assertEquals(completedBefore, after.state.completedBeats, "nothing already played is un-completed");

            // The beats ahead are new ones, and the rewritten scene opens the first of them.
            List<String> ahead = new ArrayList<>(beatIds);
            ahead.removeAll(completedBefore);
            assertFalse(ahead.isEmpty(), "a restructure must leave beats still to play");
            assertEquals(ahead.get(0), after.state.currentBeatId,
                    "the rewritten scene belongs to the first of the new beats");
            assertEquals(1, after.state.scenesInCurrentBeat,
                    "the rewritten scene is that beat's first scene, and it has just been committed");
            assertTrue(after.state.storyProgress.beatsCompleted <= after.state.storyProgress.totalBeats,
                    "progress cannot exceed the spine it is measured against");
            assertEquals(after.story.spine.beats().size(), after.state.storyProgress.totalBeats);

            // Everyone met and everywhere visited is still in the bible, with the same ids.
            Set<String> bibleIds = after.story.bible.characters().stream()
                    .map(NpcProfile::id).collect(Collectors.toSet());
            for (String met : metBefore) {
                assertTrue(bibleIds.contains(met), "character '" + met + "' has been met and must stay");
                assertTrue(after.state.characters.containsKey(met), "their relationship state survives too");
            }
            for (String place : knownPlacesBefore) {
                assertNotNull(after.story.bible.location(place), "location '" + place + "' has been visited");
            }

            assertTrue(after.state.stateVersion > versionBefore, "the version only ever moves forward");
            assertNotEquals(rejectedSceneId, after.currentScene.sceneId(),
                    "the rewrite is a new scene, not an overwrite of the one that was refused");
            assertFalse(after.currentScene.choices().isEmpty(), "the rewritten scene offers choices again");
        }
    }

    @Test
    @DisplayName("the refused scene stays in the tree as a sibling, so the rewrite can be undone")
    void theRefusedTakeSurvivesAsASibling(@TempDir Path dir) {
        try (TreeEngine engine = new TreeEngine(dir, new MockLlmClient(mapper))) {
            GameSession live = engine.sessions.require(play(engine, 3).id);
            String refusedId = live.currentNodeId;
            SceneNode refusedBefore = engine.tree.readNode(live.id, refusedId).orElseThrow();
            String parentId = refusedBefore.parentNodeId;
            String oldStoryHash = refusedBefore.storyHash;

            GameSession after = restructure(engine, live, refusedId);

            SceneNode refused = engine.tree.readNode(after.id, refusedId).orElseThrow();
            assertTrue(refused.restorable(), "the refused take is still a visited node");
            assertEquals(oldStoryHash, refused.storyHash, "it still points at the framework it was written under");

            SceneNode rewritten = engine.tree.readNode(after.id, after.currentScene.sceneId()).orElseThrow();
            assertEquals(parentId, rewritten.parentNodeId, "the rewrite is a sibling of what it replaced");
            assertEquals(refused.fromChoiceId, rewritten.fromChoiceId, "reached by the same choice");
            assertEquals(refused.outcome, rewritten.outcome, "and with the same outcome");
            assertNotEquals(oldStoryHash, rewritten.storyHash, "but under the revised framework");

            // Rewinding to the refused take restores the framework it was written under.
            GameSession back = engine.sessions.rewind(after.id, refusedId,
                    after.currentScene.sceneId(), after.state.stateVersion);
            assertEquals(refusedId, back.currentNodeId);
            assertEquals(String.join("|", refusedBefore.state.completedBeats),
                    String.join("|", back.state.completedBeats));
            assertNotEquals(String.join("|", after.story.authorCanon.facts()),
                    String.join("|", back.story.authorCanon.facts()),
                    "rewinding past a restructure brings the old canon back with it");
        }
    }

    @Test
    @DisplayName("the die the player already saw is reused, so a rewrite never flips success into failure")
    void theDieIsNotRecast(@TempDir Path dir) {
        try (TreeEngine engine = new TreeEngine(dir, new MockLlmClient(mapper))) {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            // Walk until a checked choice actually produced a die on the committed node.
            SceneNode checked = null;
            for (int i = 0; i < 8 && !session.currentScene.choices().isEmpty(); i++) {
                session = engine.sessions.choose(session.id, session.currentScene.choices().get(0).id()).session();
                SceneNode node = engine.tree.readNode(session.id, session.currentNodeId).orElseThrow();
                if (node.roll != null) { checked = node; break; }
            }
            assumeChecked(checked);

            GameSession live = engine.sessions.require(session.id);
            GameSession after = restructure(engine, live, checked.nodeId);

            SceneNode rewritten = engine.tree.readNode(after.id, after.currentScene.sceneId()).orElseThrow();
            assertEquals(checked.roll, rewritten.roll, "the number the player watched land is binding");
            assertEquals(checked.outcome, rewritten.outcome, "and so is whether it succeeded");
        }
    }

    @Test
    @DisplayName("the opening can be rewritten too: the tree stores the state it was written against")
    void theOpeningIsRestructurableFromItsStoredPreState(@TempDir Path dir) {
        try (TreeEngine engine = new TreeEngine(dir, new MockLlmClient(mapper))) {
            GameSession created = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            String openingId = created.currentNodeId;
            SceneNode root = engine.tree.readNode(created.id, openingId).orElseThrow();
            assertNull(root.parentNodeId, "the opening is the root of the tree");
            assertNotNull(root.preState, "the root stores the state the opening was written against");

            GameSession live = engine.sessions.require(created.id);
            GameSession after = restructure(engine, live, openingId);

            assertNull(engine.tree.readNode(after.id, after.currentScene.sceneId()).orElseThrow().parentNodeId,
                    "the rewritten opening is a root as well");
            assertNotEquals(openingId, after.currentScene.sceneId());
            assertEquals(1, after.history.size(), "history starts again from the new opening");
            assertEquals(after.currentScene.sceneId(), after.history.get(0).sceneId);
            assertTrue(after.state.completedBeats.isEmpty(), "nothing had been completed yet");
            assertEquals(after.story.spine.beats().get(0).id(), after.state.currentBeatId,
                    "with nothing kept, the whole spine is the revision's");
            assertFalse(after.currentScene.choices().isEmpty());

            // And the refused opening is still on disk, so the player can go back to it.
            assertTrue(engine.tree.readNode(after.id, openingId).orElseThrow().restorable());
        }
    }

    @Test
    @DisplayName("a save written before pre-states existed refuses to rewrite its opening, and says why")
    void olderSavesCannotRewriteTheirOpening(@TempDir Path dir) {
        try (TreeEngine engine = new TreeEngine(dir, new MockLlmClient(mapper))) {
            GameSession created = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            String openingId = created.currentNodeId;
            SceneNode root = engine.tree.readNode(created.id, openingId).orElseThrow();
            root.preState = null; // what an older save looks like
            engine.tree.writeNode(created.id, root);

            GameSession live = engine.sessions.require(created.id);
            var refused = assertThrows(IllegalArgumentException.class,
                    () -> restructure(engine, live, openingId));
            assertTrue(refused.getMessage().contains("开场"), "the message names what cannot be done");
            assertNull(engine.sessions.require(created.id).restructuringJobId,
                    "a refusal before any model call leaves no marker behind");
        }
    }

    private static void assumeChecked(SceneNode checked) {
        org.junit.jupiter.api.Assumptions.assumeTrue(checked != null,
                "the mock story offered no checked choice in the first scenes");
    }
}
