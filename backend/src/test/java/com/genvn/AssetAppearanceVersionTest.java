package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.*;
import com.genvn.config.ImageProperties;
import com.genvn.support.FakeImageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class AssetAppearanceVersionTest {
    @TempDir Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<AssetPipeline> pipelines = new ArrayList<>();
    private final FakeImageProvider provider = new FakeImageProvider();
    private final ImageProperties props = new ImageProperties();

    private AssetPipeline pipeline() {
        props.setConcurrency(1);
        props.setArcBudget(0);
        props.setFirstBatchBudget(20);
        var pipeline = new AssetPipeline(provider, new AssetStore(mapper, dir), props, mapper);
        pipelines.add(pipeline);
        return pipeline;
    }

    @AfterEach void stop() {
        if (provider.gate != null) provider.gate.countDown();
        pipelines.forEach(AssetPipeline::shutdown);
    }

    private static AssetSpec base(String key) {
        return new AssetSpec("pt.npc.base", AssetKind.PORTRAIT, "npc", "name", "base", "appearance " + key,
                "style", "beat", 0, null, AssetSpec.GENERAL, false).withAppearanceKey(key);
    }

    private static AssetSpec card(String key) {
        return new AssetSpec("card.npc.default", AssetKind.CHARACTER_CARD, "npc", "name", "default",
                "A framed card using only the reference image", "style", "beat", 1,
                "pt.npc.base", AssetSpec.GENERAL, false).withAppearanceKey(key);
    }

    private static AssetSpec pose(String key) {
        return new AssetSpec("pt.npc.happy", AssetKind.PORTRAIT_VARIANT, "npc", "name", "happy",
                "The reference person smiles", "style", "beat", 2,
                "pt.npc.base", AssetSpec.GENERAL, false).withAppearanceKey(key);
    }

    private static AssetRecord record(AssetPipeline pipeline, String id) {
        return pipeline.snapshot("s").orElseThrow().get(id);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(15);
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }

    private void ready(AssetPipeline pipeline, String... ids) throws Exception {
        await(() -> {
            var manifest = pipeline.snapshot("s").orElseThrow();
            return java.util.Arrays.stream(ids).allMatch(id -> manifest.get(id) != null && manifest.get(id).status == AssetStatus.READY)
                    && pipeline.activeCount() == 0;
        });
    }

    @Test void unchangedAppearanceRewindKeepsCardsPosesAndFilesDespiteDifferentPromptWords() throws Exception {
        var pipeline = pipeline();
        pipeline.adoptSpecs("s", List.of(base("A"), card("A"), pose("A")));
        ready(pipeline, "pt.npc.base", "card.npc.default", "pt.npc.happy");
        var before = pipeline.snapshot("s").orElseThrow();
        pipeline.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of());
        pipeline.adoptSpecs("s", List.of(base("A").withPrompt("different prose"), card("A").withPrompt("new card wording"), pose("A")));
        ready(pipeline, "pt.npc.base", "card.npc.default", "pt.npc.happy");
        assertEquals(3, provider.calls.get());
        for (var previous : before.records.values()) {
            var current = record(pipeline, previous.spec.assetId());
            assertEquals(previous.recordVersionId, current.recordVersionId);
            assertEquals(previous.fileName, current.fileName);
            assertTrue(Files.isRegularFile(dir.resolve("s").resolve(previous.fileName)));
        }
    }

    @Test void twoAppearancesSurviveRoundTripsAndRestartWithoutExtraCalls() throws Exception {
        var pipeline = pipeline();
        pipeline.adoptSpecs("s", List.of(base("A"), card("A")));
        ready(pipeline, "pt.npc.base", "card.npc.default");
        var a = record(pipeline, "card.npc.default");
        pipeline.reconcileCharacterVersions("s", Map.of("npc", "B"), Map.of());
        pipeline.adoptSpecs("s", List.of(card("B"), base("B")));
        ready(pipeline, "pt.npc.base", "card.npc.default");
        var b = record(pipeline, "card.npc.default");
        assertNotEquals(a.fileName, b.fileName);
        assertNotEquals(a.publicationId, b.publicationId);
        pipeline.shutdown();
        var restarted = pipeline();
        restarted.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of());
        restarted.adoptSpecs("s", List.of(base("A"), card("A")));
        ready(restarted, "pt.npc.base", "card.npc.default");
        assertEquals(a.fileName, record(restarted, "card.npc.default").fileName);
        restarted.reconcileCharacterVersions("s", Map.of("npc", "B"), Map.of());
        restarted.adoptSpecs("s", List.of(base("B"), card("B")));
        ready(restarted, "pt.npc.base", "card.npc.default");
        var manifest = restarted.snapshot("s").orElseThrow();
        assertEquals(b.fileName, manifest.get("card.npc.default").fileName);
        assertEquals(4, manifest.versions.size());
        assertEquals(4, manifest.publications.size());
        assertSame(manifest.get("card.npc.default"), manifest.versions.get(b.recordVersionId));
        assertEquals(4, provider.calls.get());
        assertEquals(4, manifest.budget.attemptsTotal);
    }

    @Test void lateSuccessPublishesOnlyItsArchivedVersionAndCannotWakeAnotherVersionsCard() throws Exception {
        props.setQueueCapacity(1);
        provider.entered = new CountDownLatch(1);
        provider.gate = new CountDownLatch(1);
        var pipeline = pipeline();
        pipeline.adoptSpecs("s", List.of(base("A"), card("A")));
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
        var a = record(pipeline, "pt.npc.base");
        var waiter = pipeline.subscribe("s", "pt.npc.base");
        pipeline.reconcileCharacterVersions("s", Map.of("npc", "B"), Map.of());
        pipeline.adoptSpecs("s", List.of(base("B"), card("B")));
        provider.gate.countDown();
        ready(pipeline, "pt.npc.base", "card.npc.default");
        assertEquals(a.recordVersionId, waiter.get(5, TimeUnit.SECONDS).recordVersionId);
        assertEquals(3, provider.calls.get(), "obsolete queued card A never reached the provider");
        assertEquals("B", record(pipeline, "card.npc.default").spec.appearanceKey());
        assertArrayEquals(Files.readAllBytes(pipeline.readyFile("s", "pt.npc.base").orElseThrow()), provider.edits.getFirst().referenceImage());
        assertEquals(AssetStatus.READY, pipeline.snapshot("s").orElseThrow().versions.get(a.recordVersionId).status);
        pipeline.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of());
        pipeline.adoptSpecs("s", List.of(base("A"), card("A")));
        ready(pipeline, "pt.npc.base", "card.npc.default");
        assertEquals(4, provider.calls.get(), "returning to A only fills its previously unsent card");
        assertArrayEquals(Files.readAllBytes(pipeline.readyFile("s", "pt.npc.base").orElseThrow()), provider.edits.getLast().referenceImage());
    }

    @Test void exhaustedArchivedFailureIsNotResetOnReturn() throws Exception {
        props.setMaxAttempts(1);
        provider.entered = new CountDownLatch(1);
        provider.gate = new CountDownLatch(1);
        provider.failWith = prompt -> prompt.contains("appearance A") ? new ImageProviderException("A failed", true) : null;
        var pipeline = pipeline();
        pipeline.adoptSpecs("s", List.of(base("A")));
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
        pipeline.reconcileCharacterVersions("s", Map.of("npc", "B"), Map.of());
        pipeline.adoptSpecs("s", List.of(base("B")));
        provider.gate.countDown();
        ready(pipeline, "pt.npc.base");
        pipeline.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of());
        pipeline.adoptSpecs("s", List.of(base("A")));
        assertEquals(AssetStatus.FAILED, record(pipeline, "pt.npc.base").status);
        assertEquals(1, record(pipeline, "pt.npc.base").attempts);
        assertEquals(2, provider.calls.get());
    }

    @Test void verifiedLegacyBaseBindsItsReferenceCardsAndPosesWithoutSubstringMatching() throws Exception {
        var store = new AssetStore(mapper, dir);
        var manifest = new AssetManifest();
        manifest.sessionId = "s";
        for (AssetSpec spec : List.of(base(null), card(null), pose(null))) {
            var record = new AssetRecord(spec);
            record.status = AssetStatus.READY;
            record.fileName = store.save("s", spec.assetId(), FakeImageProvider.png(spec.prompt(), 16, 24, true), "image/png").fileName();
            record.generationVersion = 1;
            record.attempts = 1;
            manifest.records.put(spec.assetId(), record);
        }
        manifest.budget.attemptsTotal = 3;
        store.writeManifest(manifest);
        var pipeline = pipeline();
        pipeline.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of(manifest.get("pt.npc.base").recordVersionId, "A"));
        pipeline.adoptSpecs("s", List.of(base("A"), card("A"), pose("A")));
        ready(pipeline, "pt.npc.base", "card.npc.default", "pt.npc.happy");
        assertEquals(0, provider.calls.get());
        assertEquals("A", record(pipeline, "card.npc.default").spec.appearanceKey());
        assertEquals(record(pipeline, "pt.npc.base").recordVersionId, record(pipeline, "card.npc.default").referenceVersionId);
        assertTrue(record(pipeline, "card.npc.default").publicationId.startsWith("legacy_"));
    }

    @Test void statusLoadDoesNotSendUnverifiedLegacyCharacterWork() throws Exception {
        var manifest = new AssetManifest();
        manifest.sessionId = "s";
        var stale = new AssetRecord(base(null));
        stale.status = AssetStatus.QUEUED;
        manifest.records.put(stale.spec.assetId(), stale);
        new AssetStore(mapper, dir).writeManifest(manifest);
        var pipeline = pipeline();
        pipeline.snapshot("s");
        Thread.sleep(100);
        assertEquals(0, provider.calls.get());
        pipeline.reconcileCharacterVersions("s", Map.of("npc", "B"), Map.of());
        pipeline.adoptSpecs("s", List.of(base("B")));
        ready(pipeline, "pt.npc.base");
        assertEquals(List.of("appearance B"), provider.prompts());
        assertEquals(2, pipeline.snapshot("s").orElseThrow().versions.size());
    }

    @Test void missingReadyFileGetsOneBoundedReplacementAndNewPublicationEvenWithSingleAttemptLimit() throws Exception {
        props.setMaxAttempts(1);
        var pipeline = pipeline();
        pipeline.adoptSpecs("s", List.of(base("A")));
        ready(pipeline, "pt.npc.base");
        var original = record(pipeline, "pt.npc.base");
        Files.delete(pipeline.readyFile("s", "pt.npc.base").orElseThrow());
        pipeline.adoptSpecs("s", List.of(base("A")));
        pipeline.adoptSpecs("s", List.of(base("A")));
        ready(pipeline, "pt.npc.base");
        var replacement = record(pipeline, "pt.npc.base");
        assertEquals(original.recordVersionId, replacement.recordVersionId);
        assertNotEquals(original.publicationId, replacement.publicationId);
        assertEquals(2, replacement.attempts);
        assertEquals(2, provider.calls.get());
        assertEquals(2, pipeline.snapshot("s").orElseThrow().publications.size());
    }

    @Test void disabledGenerationKeepsMissingWorkWithoutSendingRequests() throws Exception {
        provider.enabled = false;
        var pipeline = pipeline();
        pipeline.adoptSpecs("s", List.of(base("A"), card("A")));
        assertEquals(AssetStatus.PAUSED, record(pipeline, "pt.npc.base").status);
        assertEquals(0, provider.calls.get());
    }

    @Test void repeatedReconciliationDoesNotRewriteReadyManifest() throws Exception {
        var pipeline = pipeline();
        pipeline.adoptSpecs("s", List.of(base("A"), card("A")));
        ready(pipeline, "pt.npc.base", "card.npc.default");
        int version = pipeline.snapshot("s").orElseThrow().version;
        byte[] stored = Files.readAllBytes(dir.resolve("s/manifest.json"));
        for (int i = 0; i < 4; i++) {
            pipeline.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of());
            pipeline.adoptSpecs("s", List.of(base("A"), card("A")));
        }
        assertEquals(version, pipeline.snapshot("s").orElseThrow().version);
        assertArrayEquals(stored, Files.readAllBytes(dir.resolve("s/manifest.json")));
        assertEquals(2, provider.calls.get());
    }

    @Test void restartSkipsOrphanedPublicationReservedBeforeCrash() throws Exception {
        props.setMaxAttempts(2);
        var store = new AssetStore(mapper, dir);
        var manifest = new AssetManifest();
        manifest.sessionId = "s";
        var interrupted = new AssetRecord(base("A"));
        interrupted.status = AssetStatus.GENERATING;
        interrupted.attempts = 1;
        interrupted.publicationSequence = 1;
        manifest.records.put(interrupted.spec.assetId(), interrupted);
        manifest.budget.attemptsTotal = 1;
        manifest.budget.arcAttempts = 1;
        store.writeManifest(manifest);
        var orphan = store.saveVersioned("s", interrupted.spec.assetId(), interrupted.recordVersionId, 1,
                FakeImageProvider.png("already written before crash", 16, 24, true), "image/png");
        byte[] orphanBytes = Files.readAllBytes(dir.resolve("s").resolve(orphan.fileName()));
        var pipeline = pipeline();
        pipeline.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of());
        pipeline.adoptSpecs("s", List.of(base("A")));
        ready(pipeline, "pt.npc.base");
        assertEquals(2, record(pipeline, "pt.npc.base").generationVersion);
        assertEquals(2, record(pipeline, "pt.npc.base").attempts);
        assertEquals(1, provider.calls.get());
        assertNotEquals(orphan.fileName(), record(pipeline, "pt.npc.base").fileName);
        assertArrayEquals(orphanBytes, Files.readAllBytes(dir.resolve("s").resolve(orphan.fileName())));
    }
}
