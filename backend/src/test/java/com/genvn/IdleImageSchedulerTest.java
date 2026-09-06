package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetKind;
import com.genvn.asset.AssetPipeline;
import com.genvn.asset.AssetSpec;
import com.genvn.asset.AssetStatus;
import com.genvn.asset.AssetStore;
import com.genvn.asset.ImageProviderException;
import com.genvn.asset.VisualPlanner;
import com.genvn.config.ImageProperties;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class IdleImageSchedulerTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void fourDefaultImageWorkersActuallyEnterTheProviderAtOnce() throws Exception {
        ImageProperties props = new ImageProperties();
        assertEquals(4, props.getConcurrency());
        props.setEnabled(true); props.setApiKey("test"); props.setFirstBatchBudget(8); props.setArcBudget(12);
        var provider = new FakeImageProvider();
        provider.gate = new CountDownLatch(1); provider.entered = new CountDownLatch(4);
        var pipeline = new AssetPipeline(provider, new AssetStore(mapper, directory), props, mapper);
        try {
            pipeline.adopt("four", plan(IntStream.range(0, 8).mapToObj(i -> image("foreground_" + i, false)).toList()));
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            assertEquals(4, provider.active.get());
            assertEquals(4, provider.highWater.get());
            provider.gate.countDown();
            await(() -> readyCount(pipeline, "four") == 8);
            assertEquals(8, provider.calls.get());
            assertEquals(4, pipeline.highWaterConcurrency());
        } finally { provider.gate.countDown(); pipeline.shutdown(); }
    }

    @Test void sparePicturesLeaveFourDurableAttemptsForForegroundScenes() throws Exception {
        var props = properties(7);
        var provider = new FakeImageProvider();
        var pipeline = new AssetPipeline(provider, new AssetStore(mapper, directory), props, mapper);
        try {
            pipeline.adopt("reserve", plan(IntStream.range(0, 6).mapToObj(i -> image("spare_" + i, true)).toList()));
            await(() -> readyCount(pipeline, "reserve") == 3);
            assertEquals(3, pipeline.snapshot("reserve").orElseThrow().budget.arcAttempts);
            assertEquals(0, pipeline.snapshot("reserve").orElseThrow().budget.firstBatchQueued,
                    "idle designs never consume opening-batch slots");
            pipeline.adoptSpecs("reserve", IntStream.range(0, 4).mapToObj(i -> image("needed_" + i, false)).toList());
            await(() -> readyCount(pipeline, "reserve") == 7);
            assertEquals(7, provider.calls.get());
            for (int i = 0; i < 4; i++) assertEquals(AssetStatus.READY,
                    pipeline.snapshot("reserve").orElseThrow().get("pt.needed_" + i + ".base").status);
        } finally { pipeline.shutdown(); }
    }

    @Test void automaticIdleRetriesCannotConsumeTheFourReservedAttempts() throws Exception {
        var props = properties(7);
        var provider = new FakeImageProvider();
        provider.failWith = prompt -> new ImageProviderException("HTTP 429", true, 429, 0);
        // Make the three initial attempts overlap before any backoff can reserve another call.
        // With instantaneous failures, a valid schedule may retry one image before a third starts.
        provider.gate = new CountDownLatch(1);
        provider.entered = new CountDownLatch(3);
        var pipeline = new AssetPipeline(provider, new AssetStore(mapper, directory), props, mapper);
        try {
            pipeline.adopt("retry", plan(IntStream.range(0, 3).mapToObj(i -> image("spare_" + i, true)).toList()));
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            assertEquals(3, provider.calls.get());
            provider.gate.countDown();
            await(() -> pipeline.snapshot("retry").orElseThrow().records.values().stream()
                    .allMatch(r -> r.status == AssetStatus.PAUSED));
            assertEquals(3, provider.calls.get());
            assertEquals(3, pipeline.snapshot("retry").orElseThrow().budget.arcAttempts);
            assertTrue(pipeline.snapshot("retry").orElseThrow().records.values().stream()
                    .allMatch(r -> r.failureReason.contains("保留 4 次")));
            provider.failWith = null;
            pipeline.ensureQueued("retry", List.of("pt.spare_0.base"), true);
            await(() -> pipeline.snapshot("retry").orElseThrow().get("pt.spare_0.base").status == AssetStatus.READY);
            assertEquals(4, provider.calls.get(), "a picture explicitly needed by a committed scene may use the reserve");
        } finally { provider.gate.countDown(); pipeline.shutdown(); }
    }

    @Test void idleCardsWaitForTheirTransparentBaseAndTheirPromptsContainOnlyAppearance() throws Exception {
        var engine = new Engine(new com.genvn.llm.MockLlmClient(mapper), new ScriptedRandom(12), false, false);
        var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
        session.story.playerVisual = null;
        var props = properties(12);
        props.getPlan().setCharacters(0); props.getPlan().setLocations(0);
        var plan = new VisualPlanner(props).plan(session.story, session.state, null);
        assertEquals(4, plan.specs().size());
        assertTrue(plan.specs().stream().allMatch(spec -> spec.idlePreparation() && spec.beatId() == null));
        for (var spec : plan.specs()) {
            assertFalse(spec.subjectName().equals("Mara"));
            assertFalse(spec.prompt().contains("find the parcel"));
            if (spec.kind() == AssetKind.CHARACTER_CARD) assertEquals("pt." + spec.subjectId() + ".base", spec.dependsOn());
        }
        var provider = new FakeImageProvider();
        provider.gate = new CountDownLatch(1); provider.entered = new CountDownLatch(2);
        var pipeline = new AssetPipeline(provider, new AssetStore(mapper, directory), props, mapper);
        try {
            pipeline.adopt("cards", plan);
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, provider.generateCalls.get());
            assertEquals(0, provider.editCalls.get(), "no card is sent until its exact base image exists");
            provider.gate.countDown();
            await(() -> readyCount(pipeline, "cards") == 4);
            assertEquals(2, provider.generateCalls.get()); assertEquals(2, provider.editCalls.get());
            for (var edit : provider.edits) {
                assertFalse(edit.transparentBackground());
                assertTrue(AssetStore.hasTransparentContent(edit.referenceImage()));
            }
            assertEquals(4, provider.calls.get());
        } finally { provider.gate.countDown(); pipeline.shutdown(); }
    }

    @Test void assigningAReservedPictureChangesItsLabelButKeepsThePromptFileAndVersion() throws Exception {
        var props = properties(12);
        var provider = new FakeImageProvider();
        var pipeline = new AssetPipeline(provider, new AssetStore(mapper, directory), props, mapper);
        try {
            AssetSpec spare = image("spare", true);
            pipeline.adopt("label", plan(List.of(spare)));
            await(() -> readyCount(pipeline, "label") == 1);
            var before = pipeline.snapshot("label").orElseThrow().get(spare.assetId());
            byte[] bytes = Files.readAllBytes(pipeline.readyFile("label", spare.assetId()).orElseThrow());
            pipeline.adoptSpecs("label", List.of(new AssetSpec(spare.assetId(), spare.kind(), spare.subjectId(), "Mara",
                    spare.variant(), "a different prompt for a named role", spare.styleKey(), "beat_now", 1,
                    spare.dependsOn(), spare.applicability(), spare.landscape())));
            var after = pipeline.snapshot("label").orElseThrow().get(spare.assetId());
            assertEquals("Mara", after.spec.subjectName());
            assertFalse(after.spec.idlePreparation());
            assertEquals(before.spec.prompt(), after.spec.prompt());
            assertEquals(before.generationVersion, after.generationVersion);
            assertArrayEquals(bytes, Files.readAllBytes(pipeline.readyFile("label", spare.assetId()).orElseThrow()));
            assertEquals(1, provider.calls.get());
        } finally { pipeline.shutdown(); }
    }

    private ImageProperties properties(int budget) {
        var props = new ImageProperties();
        props.setEnabled(true); props.setApiKey("test"); props.setConcurrency(4);
        props.setFirstBatchBudget(0); props.setArcBudget(budget); props.setMaxAttempts(2);
        return props;
    }

    private AssetSpec image(String id, boolean idle) {
        return new AssetSpec("pt." + id + ".base", AssetKind.PORTRAIT, id, "Unassigned appearance", "base",
                "Appearance only: short brown hair, grey coat, plain shirt.", "style", idle ? null : "beat_now",
                idle ? AssetSpec.IDLE_PRIORITY : 0, null, AssetSpec.GENERAL, false);
    }

    private VisualPlanner.Plan plan(List<AssetSpec> specs) { return new VisualPlanner.Plan("style", "style", specs); }
    private long readyCount(AssetPipeline pipeline, String session) {
        return pipeline.snapshot(session).orElseThrow().records.values().stream().filter(r -> r.status == AssetStatus.READY).count();
    }
    private void await(BooleanSupplier condition) throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> { while (!condition.getAsBoolean()) Thread.sleep(20); });
    }
}
