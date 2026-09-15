package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.*;
import com.genvn.config.ImageProperties;
import com.genvn.support.FakeImageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class AssetDurabilityTest {
    @TempDir Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<AssetPipeline> pipelines = new ArrayList<>();

    @AfterEach void shutdown() { pipelines.forEach(AssetPipeline::shutdown); }

    private AssetPipeline pipeline(FakeImageProvider provider, AssetStore store, ImageProperties props) {
        AssetPipeline p = new AssetPipeline(provider, store, props, mapper);
        pipelines.add(p);
        return p;
    }

    private static AssetSpec background(String location) {
        return new AssetSpec("bg." + location + ".default", AssetKind.BACKGROUND, location, location,
                "default", "background " + location, "k", "beat1", 0, null, AssetSpec.GENERAL, true);
    }

    private static VisualPlanner.Plan plan(String... locations) {
        return new VisualPlanner.Plan("style", "k", java.util.Arrays.stream(locations)
                .map(AssetDurabilityTest::background).toList());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }

    private static void settled(AssetPipeline p) throws Exception {
        await(() -> (int) p.status("s1").get("pending") == 0 && p.activeCount() == 0);
    }

    @Test void failedAttemptReservationNeverCallsProviderAndCanRecoverWithoutRestart() throws Exception {
        FakeImageProvider fake = new FakeImageProvider();
        ImageProperties props = new ImageProperties();
        props.setArcBudget(1);
        AtomicBoolean broken = new AtomicBoolean(true);
        AssetStore store = new AssetStore(mapper, dir) {
            @Override public void writeManifest(AssetManifest manifest) {
                if (broken.get() && manifest.records.values().stream().anyMatch(r -> r.status == AssetStatus.GENERATING)) {
                    throw new UncheckedIOException(new IOException("simulated full disk during reservation"));
                }
                super.writeManifest(manifest);
            }
        };
        AssetPipeline first = pipeline(fake, store, props);
        first.adopt("s1", plan("a"));
        settled(first);
        assertEquals(0, fake.calls.get());
        AssetManifest paused = first.snapshot("s1").orElseThrow();
        assertEquals(AssetStatus.PAUSED, paused.get("bg.a.default").status);
        assertTrue(paused.budget.pauseReason.contains("persistence"));
        assertEquals(0, paused.budget.arcAttempts, "unsent calls do not consume a reservation in memory");
        assertEquals(0, store.readManifest("s1").orElseThrow().budget.arcAttempts);
        first.shutdown();

        // Restart while storage is still faulty must not turn the unsaved attempt into a paid call.
        AssetPipeline restarted = pipeline(fake, store, props);
        restarted.snapshot("s1");
        settled(restarted);
        assertEquals(0, fake.calls.get());

        broken.set(false);
        restarted.ensureQueued("s1", List.of("bg.a.default"), true);
        settled(restarted);
        assertEquals(AssetStatus.READY, restarted.snapshot("s1").orElseThrow().get("bg.a.default").status);
        assertEquals(1, fake.calls.get());
        restarted.adoptSpecs("s1", List.of(background("b")));
        settled(restarted);
        assertEquals(1, fake.calls.get(), "recovery still enforces the one-call arc budget");
        assertEquals(1, store.readManifest("s1").orElseThrow().budget.arcAttempts);
    }

    @Test void failedCompletionWriteRetainsSpentBudgetAcrossRestart() throws Exception {
        FakeImageProvider fake = new FakeImageProvider();
        ImageProperties props = new ImageProperties();
        props.setArcBudget(1);
        AssetStore failingCompletion = new AssetStore(mapper, dir) {
            @Override public void writeManifest(AssetManifest manifest) {
                if (manifest.records.values().stream().anyMatch(r -> r.status == AssetStatus.READY)) {
                    throw new UncheckedIOException(new IOException("simulated disk failure after image response"));
                }
                super.writeManifest(manifest);
            }
        };
        AssetPipeline first = pipeline(fake, failingCompletion, props);
        first.adopt("s1", plan("a"));
        settled(first);
        assertEquals(1, fake.calls.get());
        assertEquals(1, failingCompletion.readManifest("s1").orElseThrow().budget.arcAttempts);
        first.shutdown();

        AssetPipeline restarted = pipeline(fake, new AssetStore(mapper, dir), props);
        restarted.snapshot("s1");
        restarted.adoptSpecs("s1", List.of(background("b")));
        settled(restarted);
        assertEquals(1, fake.calls.get(), "both recovery retries and new images retain the spent budget");
        assertEquals(1, restarted.snapshot("s1").orElseThrow().budget.arcAttempts);
    }

    @Test void unreadableExistingManifestCannotBeTreatedAsAnUnusedBudget() throws Exception {
        Files.createDirectories(dir.resolve("s1"));
        Files.writeString(dir.resolve("s1/manifest.json"), "{broken budget");
        FakeImageProvider fake = new FakeImageProvider();
        AssetPipeline p = pipeline(fake, new AssetStore(mapper, dir), new ImageProperties());
        assertThrows(UncheckedIOException.class, () -> p.adopt("s1", plan("a")));
        assertEquals(0, fake.calls.get());
        assertEquals("{broken budget", Files.readString(dir.resolve("s1/manifest.json")));
    }

    @Test void manifestWriteActuallyReportsPublicationFailure() throws Exception {
        Files.createDirectories(dir.resolve("s1/manifest.json"));
        AssetManifest manifest = new AssetManifest();
        manifest.sessionId = "s1";
        assertThrows(UncheckedIOException.class, () -> new AssetStore(mapper, dir).writeManifest(manifest));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedLateCallbacksCannotResurrectDeletedSessions(boolean retryable) throws Exception {
        FakeImageProvider fake = new FakeImageProvider();
        fake.entered = new CountDownLatch(1);
        fake.gate = new CountDownLatch(1);
        fake.failWith = prompt -> new ImageProviderException("simulated delayed failure", retryable);
        AssetPipeline p = pipeline(fake, new AssetStore(mapper, dir), new ImageProperties());
        p.adopt("s1", plan("a"));
        assertTrue(fake.entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<AssetRecord> waiter = p.subscribe("s1", "bg.a.default");
        p.forget("s1");
        assertTrue(waiter.isCancelled(), "deleted sessions release subscribers");
        fake.gate.countDown();
        await(() -> p.activeCount() == 0);
        assertFalse(Files.exists(dir.resolve("s1")));
        assertTrue(p.snapshot("s1").isEmpty());
        assertEquals(1, fake.calls.get());
        assertEquals(0, p.queueDepth());
    }

    @Test void deleteWaitsForAnAlreadyPublishingSuccessAndRemovesItsFiles() throws Exception {
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch finishSave = new CountDownLatch(1);
        AssetStore gatedStore = new AssetStore(mapper, dir) {
            @Override public Stored saveVersioned(String sid, String assetId, String recordVersionId,
                                                  int generationVersion, byte[] bytes, String mime) throws IOException {
                saving.countDown();
                try {
                    if (!finishSave.await(5, TimeUnit.SECONDS)) throw new IOException("save gate timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return super.saveVersioned(sid, assetId, recordVersionId, generationVersion, bytes, mime);
            }
        };
        AssetPipeline p = pipeline(new FakeImageProvider(), gatedStore, new ImageProperties());
        p.adopt("s1", plan("a"));
        assertTrue(saving.await(5, TimeUnit.SECONDS));
        Thread deleting = new Thread(() -> p.forget("s1"));
        deleting.start();
        try {
            await(() -> deleting.getState() == Thread.State.BLOCKED || !deleting.isAlive());
        } finally {
            finishSave.countDown();
        }
        deleting.join(5000);
        assertFalse(deleting.isAlive());
        await(() -> p.activeCount() == 0);
        assertFalse(Files.exists(dir.resolve("s1")), "a result being published before delete cannot recreate the directory afterward");
    }

    @Test void aNewArcRefillsFirstBatchOnceAndPrefersItsNewPlan() throws Exception {
        FakeImageProvider fake = new FakeImageProvider();
        ImageProperties props = new ImageProperties();
        props.setFirstBatchBudget(1);
        props.setArcBudget(1);
        AssetPipeline p = pipeline(fake, new AssetStore(mapper, dir), props);
        p.adopt("s1", plan("a", "old_optional"));
        settled(p);
        assertEquals(1, fake.calls.get());
        p.beginArc("s1", 2);
        assertEquals(1, p.adopt("s1", plan("next_arc")));
        settled(p);
        assertEquals(AssetStatus.READY, p.snapshot("s1").orElseThrow().get("bg.next_arc.default").status);
        assertEquals(AssetStatus.PLANNED, p.snapshot("s1").orElseThrow().get("bg.old_optional.default").status);
        p.beginArc("s1", 2);
        p.beginArc("s1", 1);
        assertEquals(0, p.adopt("s1", plan("extra")));
        assertEquals(1, p.snapshot("s1").orElseThrow().budget.arcAttempts);
        assertEquals(2, fake.calls.get(), "duplicate or stale notifications cannot refill the arc allowance");
    }

    @Test void unsupportedImageFormatPausesBeforeSpendingAndTextOnlyModeCanStillStart() throws Exception {
        FakeImageProvider fake = new FakeImageProvider();
        ImageProperties props = new ImageProperties();
        assertDoesNotThrow(() -> props.setOutputFormat("WebP"));
        AssetPipeline p = pipeline(fake, new AssetStore(mapper, dir), props);
        p.adopt("s1", plan("a"));
        settled(p);
        AssetRecord paused = p.snapshot("s1").orElseThrow().get("bg.a.default");
        assertEquals(AssetStatus.PAUSED, paused.status);
        assertTrue(paused.failureReason.contains("image.output-format"));
        assertTrue(paused.failureReason.contains("png or jpeg"));
        assertEquals(0, fake.calls.get());
        assertEquals(0, p.snapshot("s1").orElseThrow().budget.arcAttempts);
        props.setOutputFormat("png");
        p.ensureQueued("s1", List.of("bg.a.default"), true);
        settled(p);
        assertEquals(1, fake.calls.get(), "correcting the format permits normal generation");
        assertEquals(AssetStatus.READY, p.snapshot("s1").orElseThrow().get("bg.a.default").status);
    }
}
