package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.*;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class AssetQueueAdmissionTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String RETRY_ID = "bg.retry.default";

    private static final class Provider extends FakeImageProvider {
        final CountDownLatch blockerEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger retryAttempts = new AtomicInteger();
        @Override public ImageResult generate(ImageRequest request) throws ImageProviderException {
            if (request.prompt().equals("retry") && retryAttempts.incrementAndGet() == 1) {
                throw new ImageProviderException("temporary failure", true);
            }
            if (request.prompt().equals("blocker")) {
                blockerEntered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new ImageProviderException("test gate timed out", false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ImageProviderException("test gate interrupted", false);
                }
            }
            return super.generate(request);
        }
    }

    @Test void automaticRetryWaitsForQueueSpaceThenFinishesExactlyOnce() throws Exception {
        var provider = new Provider();
        var store = new AssetStore(mapper, directory);
        var pipeline = pipeline(provider, store);
        try {
            fillQueueDuringRetryBackoff(pipeline, provider);
            AssetRecord waiting = pipeline.snapshot("s").orElseThrow().get(RETRY_ID);
            assertEquals(AssetStatus.QUEUED, waiting.status);
            assertEquals(AssetStatus.QUEUED, store.readManifest("s").orElseThrow().get(RETRY_ID).status);
            assertEquals(1, waiting.attempts, "waiting for admission does not spend another provider attempt");
            assertTrue((int) pipeline.status("s").get("pending") > 0);
            provider.release.countDown();
            await(() -> pipeline.snapshot("s").orElseThrow().get(RETRY_ID).status == AssetStatus.READY);
            await(() -> pipeline.activeCount() == 0 && pipeline.queueDepth() == 0);
            assertEquals(2, provider.retryAttempts.get());
            assertEquals(4, store.readManifest("s").orElseThrow().budget.attemptsTotal);
        } finally { provider.release.countDown(); pipeline.shutdown(); }
    }

    @Test void deletingAWaitingRetryCannotRestoreItsQueueOrFiles() throws Exception {
        var provider = new Provider();
        var store = new AssetStore(mapper, directory);
        var pipeline = pipeline(provider, store);
        try {
            fillQueueDuringRetryBackoff(pipeline, provider);
            pipeline.forget("s");
            provider.release.countDown();
            await(() -> pipeline.activeCount() == 0);
            Thread.sleep(700); // Pass the pending capacity-check deadline after deletion.
            assertEquals(1, provider.retryAttempts.get());
            assertEquals(0, pipeline.queueDepth());
            assertTrue(pipeline.snapshot("s").isEmpty());
            assertTrue(store.readManifest("s").isEmpty());
        } finally { provider.release.countDown(); pipeline.shutdown(); }
    }

    @Test void waitingForQueueCapacityDoesNotOpenAnotherAutomaticRetryCycle() throws Exception {
        var provider = new Provider();
        provider.failWith = prompt -> "retry".equals(prompt) ? new ImageProviderException("still unavailable", true) : null;
        var pipeline = pipeline(provider, new AssetStore(mapper, directory));
        try {
            fillQueueDuringRetryBackoff(pipeline, provider);
            provider.release.countDown();
            await(() -> pipeline.snapshot("s").orElseThrow().get(RETRY_ID).status == AssetStatus.FAILED);
            assertEquals(2, provider.retryAttempts.get());
            assertEquals(2, pipeline.snapshot("s").orElseThrow().get(RETRY_ID).attempts);
            assertEquals(4, pipeline.snapshot("s").orElseThrow().budget.attemptsTotal);
            assertEquals(0, pipeline.status("s").get("pending"));
        } finally { provider.release.countDown(); pipeline.shutdown(); }
    }

    @Test void restartResumesTheDurableWaitingRetryWithoutResettingItsAttempts() throws Exception {
        var provider = new Provider();
        var store = new AssetStore(mapper, directory);
        var first = pipeline(provider, store);
        AssetPipeline restarted = null;
        try {
            fillQueueDuringRetryBackoff(first, provider);
            first.shutdown();
            await(() -> first.activeCount() == 0);
            assertEquals(AssetStatus.QUEUED, store.readManifest("s").orElseThrow().get(RETRY_ID).status);
            provider.release.countDown();
            restarted = pipeline(provider, store);
            AssetPipeline current = restarted;
            await(() -> current.snapshot("s").orElseThrow().get(RETRY_ID).status == AssetStatus.READY);
            assertEquals(2, provider.retryAttempts.get());
            assertEquals(2, current.snapshot("s").orElseThrow().get(RETRY_ID).attempts);
        } finally {
            provider.release.countDown(); first.shutdown();
            if (restarted != null) restarted.shutdown();
        }
    }

    @Test void restartAdmitsEveryPreviouslyQueuedRetryEvenWhenOnlyOneQueueSlotIsAvailable() throws Exception {
        var store = new AssetStore(mapper, directory);
        var manifest = new AssetManifest(); manifest.sessionId = "s";
        for (String id : List.of("retry_one", "retry_two", "retry_three", "retry_four")) {
            var record = new AssetRecord(spec(id));
            record.status = AssetStatus.QUEUED; record.attempts = 1;
            manifest.records.put(record.spec.assetId(), record);
        }
        manifest.budget.arcAttempts = 4; manifest.budget.attemptsTotal = 4;
        store.writeManifest(manifest);
        var provider = new Provider();
        provider.gate = new CountDownLatch(1); provider.entered = new CountDownLatch(1);
        var restarted = pipeline(provider, store);
        try {
            restarted.snapshot("s");
            assertTrue(provider.entered.await(3, TimeUnit.SECONDS));
            assertTrue(restarted.snapshot("s").orElseThrow().records.values().stream()
                    .allMatch(r -> r.status == AssetStatus.QUEUED || r.status == AssetStatus.GENERATING));
            provider.gate.countDown();
            await(() -> restarted.snapshot("s").orElseThrow().records.values().stream()
                    .allMatch(r -> r.status == AssetStatus.READY));
            assertEquals(4, provider.calls.get());
            assertEquals(8, store.readManifest("s").orElseThrow().budget.attemptsTotal);
            assertTrue(restarted.snapshot("s").orElseThrow().records.values().stream().allMatch(r -> r.attempts == 2));
        } finally { provider.gate.countDown(); restarted.shutdown(); }
    }

    private AssetPipeline pipeline(Provider provider, AssetStore store) {
        var props = Engine.imageProperties();
        props.setConcurrency(1); props.setQueueCapacity(1); props.setArcBudget(0);
        return new AssetPipeline(provider, store, props, mapper);
    }

    private void fillQueueDuringRetryBackoff(AssetPipeline pipeline, Provider provider) throws Exception {
        pipeline.adopt("s", new VisualPlanner.Plan("style", "style", List.of(spec("retry"))));
        await(() -> pipeline.snapshot("s").orElseThrow().get(RETRY_ID).failureReason != null);
        pipeline.adoptSpecs("s", List.of(spec("blocker")));
        assertTrue(provider.blockerEntered.await(3, TimeUnit.SECONDS));
        pipeline.adoptSpecs("s", List.of(spec("queued")));
        await(() -> {
            String reason = pipeline.snapshot("s").orElseThrow().get(RETRY_ID).failureReason;
            return reason != null && reason.contains("queue full");
        });
    }

    private static AssetSpec spec(String id) {
        return new AssetSpec("bg." + id + ".default", AssetKind.BACKGROUND, id, id, "default", id,
                "style", "beat", 0, null, AssetSpec.GENERAL, true);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "expected image state was not reached");
    }
}
