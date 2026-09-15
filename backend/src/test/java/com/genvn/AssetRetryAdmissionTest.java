package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetKind;
import com.genvn.asset.AssetPipeline;
import com.genvn.asset.AssetSpec;
import com.genvn.asset.AssetStatus;
import com.genvn.asset.AssetStore;
import com.genvn.asset.ImageProviderException;
import com.genvn.config.ImageProperties;
import com.genvn.support.FakeImageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class AssetRetryAdmissionTest {
    @TempDir Path directory;

    private static AssetSpec background(String id) {
        return new AssetSpec("bg." + id + ".default", AssetKind.BACKGROUND, id, id, "default", id,
                "style", "beat", 0, null, AssetSpec.GENERAL, true);
    }

    private static AssetSpec base() {
        return new AssetSpec("pt.npc.base", AssetKind.PORTRAIT, "npc", "npc", "base", "base",
                "style", "beat", 0, null, AssetSpec.GENERAL, false).withAppearanceKey("A");
    }

    private static AssetSpec card() {
        return new AssetSpec("card.npc.default", AssetKind.CHARACTER_CARD, "npc", "npc", "default", "card",
                "style", "beat", 1, "pt.npc.base", AssetSpec.GENERAL, false).withAppearanceKey("A");
    }

    private static ImageProperties properties(int capacity) {
        var properties = new ImageProperties();
        properties.setConcurrency(1);
        properties.setQueueCapacity(capacity);
        properties.setArcBudget(0);
        properties.setMaxAttempts(1);
        return properties;
    }

    private AssetPipeline pipeline(FakeImageProvider provider, ImageProperties properties) {
        var mapper = new ObjectMapper();
        return new AssetPipeline(provider, new AssetStore(mapper, directory), properties, mapper);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "expected image state was not reached");
    }

    @Test
    void manualRetryWaitsForFullQueue() throws Exception {
        var provider = new FakeImageProvider();
        provider.failWith = prompt -> prompt.equals("retry") ? new ImageProviderException("test failure", false) : null;
        var pipeline = pipeline(provider, properties(1));
        try {
            pipeline.adoptSpecs("s", List.of(background("retry")));
            await(() -> pipeline.snapshot("s").orElseThrow().get("bg.retry.default").status == AssetStatus.FAILED);
            provider.gate = new CountDownLatch(1);
            provider.entered = new CountDownLatch(1);
            pipeline.adoptSpecs("s", List.of(background("blocker")));
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            pipeline.adoptSpecs("s", List.of(background("queued")));
            assertEquals(1, pipeline.queueDepth());

            provider.failWith = null;
            pipeline.manualRetry("s", "bg.retry.default");
            assertEquals(AssetStatus.QUEUED, pipeline.snapshot("s").orElseThrow().get("bg.retry.default").status);
            provider.gate.countDown();
            await(() -> pipeline.snapshot("s").orElseThrow().get("bg.retry.default").status == AssetStatus.READY);
            await(() -> pipeline.activeCount() == 0 && pipeline.queueDepth() == 0);
            assertEquals(2, pipeline.snapshot("s").orElseThrow().get("bg.retry.default").attempts);
            assertEquals(4, provider.calls.get(), "the manual retry grants exactly one additional call");
        } finally {
            if (provider.gate != null) provider.gate.countDown();
            pipeline.shutdown();
        }
    }

    @Test
    void recoveredReferenceResumesCardAfterQueueFrees() throws Exception {
        var properties = properties(4);
        var provider = new FakeImageProvider();
        provider.failWith = prompt -> prompt.equals("base") ? new ImageProviderException("test failure", false) : null;
        var pipeline = pipeline(provider, properties);
        try {
            pipeline.adoptSpecs("s", List.of(base(), card()));
            await(() -> pipeline.snapshot("s").orElseThrow().get("card.npc.default").referenceUnavailable);
            properties.setQueueCapacity(1);
            provider.failWith = null;
            provider.gate = new CountDownLatch(1);
            provider.entered = new CountDownLatch(1);
            pipeline.manualRetry("s", "pt.npc.base");
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            pipeline.adoptSpecs("s", List.of(background("queued")));
            assertEquals(1, pipeline.queueDepth());

            provider.gate.countDown();
            await(() -> pipeline.snapshot("s").orElseThrow().get("card.npc.default").status == AssetStatus.READY);
            await(() -> pipeline.activeCount() == 0 && pipeline.queueDepth() == 0);
            assertEquals(4, provider.calls.get());
            assertEquals(1, pipeline.snapshot("s").orElseThrow().get("card.npc.default").attempts);
            assertArrayEquals(Files.readAllBytes(pipeline.readyFile("s", "pt.npc.base").orElseThrow()),
                    provider.edits.getFirst().referenceImage());
        } finally {
            if (provider.gate != null) provider.gate.countDown();
            pipeline.shutdown();
        }
    }
}
