package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.SessionService;
import com.genvn.llm.LlmException;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionCreationProgressTest {
    @TempDir Path directory;

    @Test void realMilestonesExposeOpeningWaitAndReadyAfterTheSessionIsSaved() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var client = new ScriptedLlmClient(new ObjectMapper(), request -> {
            entered.countDown(); await(release);
            return SceneJson.scene("Opening prose").choice("c1", "Continue", "action").build();
        });
        var engine = new Engine(client, new ScriptedRandom(), false, false);
        List<Integer> progress = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        SessionService.CreationProgress report = (stage, value, message) -> {
            synchronized (progress) { progress.add(value); logs.add(message); }
            if (value == 99) assertEquals(1, engine.repository.list().size(), "Final preparation must follow persistence");
        };
        try (var worker = Executors.newSingleThreadExecutor()) {
            var creating = worker.submit(() -> engine.sessions.create(Engine.OUTLINE, Engine.alex(), report));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                synchronized (progress) {
                    assertTrue(progress.size() >= 18, "Compilation must expose its smaller real milestones");
                    assertEquals(62, progress.getLast(), "The opening is still waiting on its model");
                    assertTrue(logs.stream().anyMatch(message -> message.contains("后台队列独立处理")));
                    assertTrue(logs.getLast().contains("等待文字模型"));
                }
                assertTrue(engine.repository.list().isEmpty());
            } finally { release.countDown(); }
            var session = creating.get(5, TimeUnit.SECONDS);
            assertTrue(progress.size() >= 27);
            assertEquals(99, progress.getLast(), "100 is reserved for the job publishing READY");
            for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1));
            assertTrue(progress.stream().allMatch(value -> value < 100));
            assertEquals(session.id, engine.sessions.list().getFirst().id());
        }
    }

    @Test void failedOpeningClearsQueuedImagesAndDiscardsLateProviderResults() throws Exception {
        var images = new FakeImageProvider();
        images.entered = new CountDownLatch(1);
        images.gate = new CountDownLatch(1);
        var client = new ScriptedLlmClient(new ObjectMapper(), request -> {
            try { assertTrue(images.entered.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            throw new LlmException("Opening unavailable");
        });
        var engine = new Engine(client, new ScriptedRandom(), false, false, images,
                Engine.imageProperties(), directory);
        try {
            assertThrows(LlmException.class, () -> engine.sessions.create(Engine.OUTLINE, Engine.alex()));
            assertTrue(engine.repository.list().isEmpty());
            int sentBeforeCleanup = images.calls.get();
            assertTrue(sentBeforeCleanup <= 2, "Only already-running requests may reach the provider");
            // Provider calls already sent may still finish. Queue removal and guarded late
            // publication must prevent any further calls or resurrection of invisible files.
            images.gate.countDown();
            for (int i = 0; i < 200 && engine.assets.activeCount() != 0; i++) Thread.sleep(10);
            assertEquals(0, engine.assets.activeCount());
            assertEquals(sentBeforeCleanup, images.calls.get());
            try (var files = Files.walk(directory)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().equals("manifest.json")));
            }
        } finally { images.gate.countDown(); }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
