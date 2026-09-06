package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.ApiExceptionHandler;
import com.genvn.api.AssetController;
import com.genvn.api.NotFoundException;
import com.genvn.asset.*;
import com.genvn.config.ImageProperties;
import com.genvn.game.GameSession;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import com.genvn.support.InMemoryGameSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssetManualRetryTest {
    @TempDir Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<AssetPipeline> pipelines = new ArrayList<>();
    private static final String ID = "bg.room.default";

    @AfterEach void cleanup() { pipelines.forEach(AssetPipeline::shutdown); }

    private AssetPipeline pipeline(FakeImageProvider fake, AssetStore store, ImageProperties props) {
        var pipeline = new AssetPipeline(fake, store, props, mapper); pipelines.add(pipeline); return pipeline;
    }

    private static AssetSpec spec() {
        return new AssetSpec(ID, AssetKind.BACKGROUND, "room", "房间", "default", "A room", "k", "beat1",
                0, null, AssetSpec.GENERAL, true);
    }

    private static void start(AssetPipeline pipeline) {
        pipeline.adopt("s1", new VisualPlanner.Plan("style", "k", List.of(spec())));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "expected state was not reached");
    }

    private static AssetRecord record(AssetPipeline pipeline) { return pipeline.snapshot("s1").orElseThrow().get(ID); }
    private static void settle(AssetPipeline pipeline) throws Exception {
        await(() -> (int) pipeline.status("s1").get("pending") == 0 && pipeline.activeCount() == 0);
    }

    @Test void manualRetryAllowsNonRetryableFailuresButEachClickOnlyAddsOneAttempt() throws Exception {
        var fake = new FakeImageProvider();
        fake.failWith = prompt -> new ImageProviderException("initial non-retryable failure", false);
        var props = Engine.imageProperties(); props.setMaxAttempts(5);
        var store = new AssetStore(mapper, dir); var pipeline = pipeline(fake, store, props);
        start(pipeline); settle(pipeline);
        assertEquals(AssetStatus.FAILED, record(pipeline).status);
        fake.failWith = prompt -> new ImageProviderException("retryable failure on manual attempt", true);
        pipeline.manualRetry("s1", ID); settle(pipeline);
        assertEquals(AssetStatus.FAILED, record(pipeline).status);
        assertEquals(2, fake.calls.get(), "manual retry is one call even when max-attempts leaves spare retries");
        assertEquals(2, record(pipeline).attempts);
        assertEquals(2, store.readManifest("s1").orElseThrow().budget.attemptsTotal);
        fake.failWith = null;
        pipeline.manualRetry("s1", ID); settle(pipeline);
        assertEquals(AssetStatus.READY, record(pipeline).status);
        assertEquals(3, fake.calls.get());
        assertEquals(3, store.readManifest("s1").orElseThrow().budget.arcAttempts);
        pipeline.manualRetry("s1", ID);
        assertEquals(3, fake.calls.get(), "READY is idempotent");
    }

    @Test void repeatedClicksWhileGenerationIsInFlightDoNotAuthorizeMoreAttempts() throws Exception {
        var fake = new FakeImageProvider(); fake.failWith = prompt -> new ImageProviderException("failure", false);
        var pipeline = pipeline(fake, new AssetStore(mapper, dir), Engine.imageProperties());
        start(pipeline); settle(pipeline);
        fake.gate = new CountDownLatch(1); fake.entered = new CountDownLatch(1);
        pipeline.manualRetry("s1", ID);
        assertTrue(fake.entered.await(3, TimeUnit.SECONDS));
        for (int i = 0; i < 20; i++) pipeline.manualRetry("s1", ID);
        assertEquals(2, record(pipeline).manualAttemptLimit);
        assertEquals(2, fake.calls.get());
        fake.gate.countDown(); settle(pipeline);
        assertEquals(AssetStatus.FAILED, record(pipeline).status);
        assertEquals(2, fake.calls.get());
    }

    @Test void exhaustedArcBudgetCannotBeResetByManualRetry() throws Exception {
        var fake = new FakeImageProvider(); fake.failWith = prompt -> new ImageProviderException("failure", false);
        var props = Engine.imageProperties(); props.setArcBudget(1);
        var pipeline = pipeline(fake, new AssetStore(mapper, dir), props);
        start(pipeline); settle(pipeline);
        pipeline.manualRetry("s1", ID); settle(pipeline);
        assertEquals(AssetStatus.PAUSED, record(pipeline).status);
        assertEquals(1, fake.calls.get());
        assertEquals(1, pipeline.snapshot("s1").orElseThrow().budget.arcAttempts);
        assertEquals(1, pipeline.snapshot("s1").orElseThrow().budget.attemptsTotal);
    }

    @Test void storageFailureBlocksManualGenerationAndRecoveryRetainsSpentBudget() throws Exception {
        var fake = new FakeImageProvider(); fake.failWith = prompt -> new ImageProviderException("failure", false);
        var broken = new AtomicBoolean(false);
        var store = new AssetStore(mapper, dir) {
            @Override public void writeManifest(AssetManifest manifest) {
                if (broken.get()) throw new UncheckedIOException(new IOException("full disk"));
                super.writeManifest(manifest);
            }
        };
        var pipeline = pipeline(fake, store, Engine.imageProperties()); start(pipeline); settle(pipeline);
        broken.set(true); pipeline.manualRetry("s1", ID); settle(pipeline);
        assertEquals(1, fake.calls.get());
        assertTrue(pipeline.snapshot("s1").orElseThrow().budget.pauseReason.contains("persistence"));
        pipeline.manualRetry("s1", ID); assertEquals(1, fake.calls.get());
        broken.set(false); fake.failWith = null;
        pipeline.manualRetry("s1", ID); settle(pipeline);
        assertEquals(2, fake.calls.get());
        assertEquals(2, store.readManifest("s1").orElseThrow().budget.attemptsTotal);
    }

    @Test void restartCannotTurnAnAlreadySentManualAttemptIntoAnAutomaticRetry() {
        var store = new AssetStore(mapper, dir); var manifest = new AssetManifest(); manifest.sessionId = "s1";
        var record = new AssetRecord(spec()); record.status = AssetStatus.GENERATING;
        record.attempts = 2; record.manualAttemptLimit = 2;
        manifest.records.put(ID, record); manifest.budget.arcAttempts = 2; manifest.budget.attemptsTotal = 2;
        store.writeManifest(manifest);
        var fake = new FakeImageProvider(); var props = Engine.imageProperties(); props.setMaxAttempts(5);
        var restarted = pipeline(fake, store, props);
        assertEquals(AssetStatus.FAILED, record(restarted).status);
        assertEquals(0, fake.calls.get());
        assertEquals(2, restarted.snapshot("s1").orElseThrow().budget.attemptsTotal);
    }

    @Test void retryApiRejectsUnknownAndDeletedSessionsAndReturnsTheSameAssetStatusShape() throws Exception {
        var fake = new FakeImageProvider(); fake.failWith = prompt -> new ImageProviderException("failure", false);
        var store = new AssetStore(mapper, dir); var pipeline = pipeline(fake, store, Engine.imageProperties());
        start(pipeline); settle(pipeline);
        var repository = new InMemoryGameSessionRepository(); var game = new GameSession(); game.id = "s1"; repository.save(game);
        var mvc = MockMvcBuilders.standaloneSetup(new AssetController(pipeline, store, repository))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/sessions/s1/assets/unknown/retry")).andExpect(status().isNotFound());
        mvc.perform(post("/api/sessions/unknown/assets/" + ID + "/retry")).andExpect(status().isNotFound());
        fake.gate = new CountDownLatch(1);
        mvc.perform(post("/api/sessions/s1/assets/" + ID + "/retry"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assets[0].assetId").value(ID))
                .andExpect(jsonPath("$.budget.attemptsTotal").exists());
        fake.gate.countDown(); settle(pipeline);
        game.deleted = true;
        mvc.perform(post("/api/sessions/s1/assets/" + ID + "/retry")).andExpect(status().isNotFound());
        pipeline.forget("s1");
        assertThrows(NotFoundException.class, () -> pipeline.manualRetry("s1", ID));
        assertEquals(2, fake.calls.get());
    }

    @ParameterizedTest @ValueSource(ints = {2, 3})
    void retryingABaseWakesItsPausedCardWithinTheExistingBudget(int arcBudget) throws Exception {
        String baseId = "pt.npc.base", cardId = "card.npc.base";
        var base = new AssetSpec(baseId, AssetKind.PORTRAIT, "npc", "人物", "base", "base sprite", "k", "beat1",
                0, null, AssetSpec.GENERAL, false);
        var card = new AssetSpec(cardId, AssetKind.CHARACTER_CARD, "npc", "人物", "base", "framed card", "k", "beat1",
                1, baseId, AssetSpec.GENERAL, false);
        var fake = new FakeImageProvider(); fake.failWith = prompt -> new ImageProviderException("base failed", false);
        var props = Engine.imageProperties(); props.setArcBudget(arcBudget);
        var pipeline = pipeline(fake, new AssetStore(mapper, dir), props);
        pipeline.adopt("s1", new VisualPlanner.Plan("style", "k", List.of(base, card))); settle(pipeline);
        assertEquals(AssetStatus.FAILED, pipeline.snapshot("s1").orElseThrow().get(baseId).status);
        assertTrue(pipeline.snapshot("s1").orElseThrow().get(cardId).referenceUnavailable);
        assertEquals(1, fake.calls.get(), "the card never generates without its reference");
        fake.failWith = null; pipeline.manualRetry("s1", baseId); settle(pipeline);
        assertEquals(AssetStatus.READY, pipeline.snapshot("s1").orElseThrow().get(baseId).status);
        assertEquals(arcBudget == 3 ? AssetStatus.READY : AssetStatus.PAUSED,
                pipeline.snapshot("s1").orElseThrow().get(cardId).status);
        assertEquals(arcBudget, fake.calls.get());
        assertEquals(arcBudget, pipeline.snapshot("s1").orElseThrow().budget.attemptsTotal);
        assertEquals(arcBudget == 3 ? 1 : 0, fake.editCalls.get());
    }
}
