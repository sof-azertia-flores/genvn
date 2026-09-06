package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.GameSession;
import com.genvn.narrative.Choice;
import com.genvn.support.Engine;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proof, not a claim: two speculative branch generations are inside the provider at the same
 * time before either completes, and the count never exceeds the configured pool.
 */
class TextBranchConcurrencyTest {

    @Test
    @DisplayName("independent branch generations really overlap, and never exceed the pool size")
    void branchesOverlap() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger highWater = new AtomicInteger();
        ScriptedLlmClient client = new ScriptedLlmClient(mapper, request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                // Two checked choices: with dice cast ahead each one is exactly one branch.
                return SceneJson.scene("Opening.").at("loc_threshold")
                        .choiceWithCheck("cC", "Try the drawer", "Perception", 13)
                        .choiceWithCheck("cD", "Climb the shelves", "Agility", 12).build();
            }
            int now = inside.incrementAndGet();
            highWater.accumulateAndGet(now, Math::max);
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inside.decrementAndGet();
            }
            return SceneJson.scene("Branch " + ScriptedLlmClient.outcomeOf(request)).choice("c1", "On", "cautious").build();
        });
        Engine engine = new Engine(client, new ScriptedRandom(4, 4), true, false);
        GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());

        assertTrue(entered.await(5, TimeUnit.SECONDS),
                "both choices' branches were inside the provider before either finished");
        assertEquals(2, inside.get());
        release.countDown();
        engine.awaitBranches(session.id);
        assertEquals(2, highWater.get());
        assertTrue(highWater.get() <= Engine.properties(true, false).getSpeculation().getThreads());
    }
}
