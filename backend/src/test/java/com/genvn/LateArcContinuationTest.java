package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.GameState;
import com.genvn.llm.*;
import com.genvn.story.CompiledStory;
import com.genvn.support.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LateArcContinuationTest {
    @Test void plannerFinishingAfterTheEndingReopensAPlayableContinuation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch plannerStarted = new CountDownLatch(1);
        CountDownLatch releasePlanner = new CountDownLatch(1);
        var mock = new MockLlmClient(mapper);
        LlmClient client = new LlmClient() {
            @Override public LlmResponse complete(LlmRequest request) {
                if (request.purpose() == LlmPurpose.ARC_CONTINUE) {
                    plannerStarted.countDown();
                    try {
                        if (!releasePlanner.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Planner test timed out");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return mock.complete(request);
                }
                if (request.purpose() != LlmPurpose.SCENE_GENERATE) return mock.complete(request);
                GameState state = (GameState) request.mockContext().get("state");
                CompiledStory story = (CompiledStory) request.mockContext().get("story");
                var scene = SceneJson.scene("Committed scene");
                if (request.mockContext().get("choice") == null) {
                    scene.choice("c1", "Investigate", "action");
                } else if (state.storyProgress.scenesPlayed == 1) {
                    for (int i = 0; i < story.spine.beats().size() - 1; i++) {
                        scene.op("{\"op\":\"completeBeat\",\"target\":\"" + story.spine.beats().get(i).id() + "\"}");
                    }
                    scene.choice("c1", "Resolve", "action");
                } else if (state.storyProgress.scenesPlayed == 2) {
                    // The final beat, like any other, needs a scene before it can close.
                    scene.choice("c1", "Press on", "action");
                } else {
                    scene.op("{\"op\":\"completeBeat\",\"target\":\"" + state.currentBeatId + "\"}");
                }
                return new LlmResponse(scene.build(), "scripted", 0);
            }
            @Override public String describe() { return "scripted"; }
            @Override public boolean isMock() { return true; }
        };
        var engine = new Engine(client, new ScriptedRandom(), false, true);
        var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        try {
            engine.sessions.choose(session.id, "c1");
            assertTrue(plannerStarted.await(2, TimeUnit.SECONDS));
            engine.sessions.choose(session.id, "c1"); // develops the final beat
            var ending = engine.sessions.choose(session.id, "c1");
            assertTrue(ending.session().finished);
            assertTrue(ending.session().continuationPending);
            int endingVersion = session.state.stateVersion;
            releasePlanner.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (session.continuationPending && System.nanoTime() < deadline) Thread.sleep(10);
            var resumed = engine.sessions.copySession(session);
            assertFalse(resumed.continuationPending);
            assertFalse(resumed.finished);
            assertEquals(2, resumed.state.storyProgress.arcNumber);
            assertNotNull(resumed.state.currentBeatId);
            assertEquals("continue_arc", resumed.currentScene.choices().getFirst().id());
            assertEquals(endingVersion + 1, resumed.state.stateVersion);
        } finally { releasePlanner.countDown(); }
    }
}
