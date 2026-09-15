package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.game.GameSession;
import com.genvn.game.GameState;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.LlmResponse;
import com.genvn.llm.MockLlmClient;
import com.genvn.persistence.SceneNode;
import com.genvn.story.CompiledStory;
import com.genvn.support.Engine;
import com.genvn.support.TreeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StoryRestructureContinuityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void repeatedNonOpeningRevisionKeepsTheTargetFrameworkAndUsesTheParentsState(@TempDir Path dir) {
        RecordingRewriter client = new RecordingRewriter();
        try (TreeEngine engine = new TreeEngine(dir, client)) {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            session = engine.sessions.choose(session.id, session.currentScene.choices().getFirst().id()).session();
            SceneNode refused = engine.tree.readNode(session.id, session.currentNodeId).orElseThrow();
            SceneNode parent = engine.tree.readNode(session.id, refused.parentNodeId).orElseThrow();
            session = rewrite(engine, session, session.currentNodeId);
            String firstRevision = session.currentNodeId;
            session = rewrite(engine, session, firstRevision);

            assertTrue(client.stories.get(1).authorCanon.facts().contains("revision_fact_1"));
            assertTrue(client.prompts.get(1).contains("revision_fact_1"),
                    "the real provider prompt must retain the previous revision too");
            assertTrue(session.story.authorCanon.facts().contains("revision_fact_1"));
            assertTrue(session.story.authorCanon.facts().contains("revision_fact_2"));
            assertEquals(mapper.valueToTree(parent.state), mapper.valueToTree(client.states.get(1)),
                    "the prior revision's post-scene mechanics and memories must not replace the parent state");
            assertFalse(client.states.get(1).recentScenes.stream()
                    .anyMatch(scene -> firstRevision.equals(scene.sceneId()) || refused.nodeId.equals(scene.sceneId())),
                    "neither rejected take may be presented as a scene that already happened");
            assertFalse(client.prompts.get(1).contains("Scene " + firstRevision + ":"));
            SceneNode second = engine.tree.readNode(session.id, session.currentNodeId).orElseThrow();
            assertEquals(parent.nodeId, second.parentNodeId);
            assertEquals(refused.fromChoiceId, second.fromChoiceId);
            assertEquals(refused.roll, second.roll);
            assertEquals(refused.outcome, second.outcome);
        }
    }

    @Test
    void rewritingAnEarlierSiblingUsesItsFrameworkEvenWhenTheLiveStoryHasNewerRevisions(@TempDir Path dir) {
        RecordingRewriter client = new RecordingRewriter();
        try (TreeEngine engine = new TreeEngine(dir, client)) {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            session = engine.sessions.choose(session.id, session.currentScene.choices().getFirst().id()).session();
            session = rewrite(engine, session, session.currentNodeId);
            String earlierRevision = session.currentNodeId;
            session = rewrite(engine, session, earlierRevision);
            assertTrue(session.story.authorCanon.facts().contains("revision_fact_2"));

            session = rewrite(engine, session, earlierRevision);

            assertTrue(client.stories.get(2).authorCanon.facts().contains("revision_fact_1"));
            assertFalse(client.stories.get(2).authorCanon.facts().contains("revision_fact_2"),
                    "a later sibling's framework must not leak into a revision of an older take");
            assertTrue(session.story.authorCanon.facts().contains("revision_fact_1"));
            assertFalse(session.story.authorCanon.facts().contains("revision_fact_2"));
            assertTrue(session.story.authorCanon.facts().contains("revision_fact_3"));
        }
    }

    @Test
    void repeatedOpeningRevisionKeepsTheFrameworkAndTheOriginalPreState(@TempDir Path dir) {
        RecordingRewriter client = new RecordingRewriter();
        try (TreeEngine engine = new TreeEngine(dir, client)) {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            SceneNode opening = engine.tree.readNode(session.id, session.currentNodeId).orElseThrow();
            session = rewrite(engine, session, session.currentNodeId);
            session = rewrite(engine, session, session.currentNodeId);

            assertTrue(client.stories.get(1).authorCanon.facts().contains("revision_fact_1"));
            assertEquals(opening.preState.recentScenes, client.states.get(1).recentScenes);
            assertEquals(opening.preState.inventory.size(), client.states.get(1).inventory.size());
            assertEquals(opening.preState.player.hp, client.states.get(1).player.hp);
            assertNull(engine.tree.readNode(session.id, session.currentNodeId).orElseThrow().parentNodeId);
            assertEquals(1, session.history.size());
        }
    }

    private GameSession rewrite(TreeEngine engine, GameSession session, String target) {
        return engine.sessions.restructure(session.id, target, "Change the weather, preserve unrelated facts",
                session.currentScene.sceneId(), session.state.stateVersion, "rewrite-" + session.sceneCounter,
                (stage, progress, message) -> {});
    }

    private class RecordingRewriter extends MockLlmClient {
        final List<CompiledStory> stories = new ArrayList<>();
        final List<GameState> states = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();

        RecordingRewriter() { super(mapper); }

        @Override
        public LlmResponse complete(LlmRequest request) {
            LlmResponse result = super.complete(request);
            if (request.purpose() != LlmPurpose.STORY_RESTRUCTURE) return result;
            stories.add(mapper.convertValue(request.mockContext().get("story"), CompiledStory.class));
            states.add(mapper.convertValue(request.mockContext().get("state"), GameState.class));
            prompts.add(request.user());
            try {
                ObjectNode response = (ObjectNode) mapper.readTree(result.text());
                response.withArray("authorCanonFacts").add("revision_fact_" + stories.size());
                return new LlmResponse(response.toString(), result.model(), result.durationMillis());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
