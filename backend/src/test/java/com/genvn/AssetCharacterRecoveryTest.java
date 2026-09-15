package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.*;
import com.genvn.config.ImageProperties;
import com.genvn.game.GameSession;
import com.genvn.game.GameState;
import com.genvn.narrative.Block;
import com.genvn.narrative.SceneBundle;
import com.genvn.story.*;
import com.genvn.support.FakeImageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class AssetCharacterRecoveryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ImageProperties props = new ImageProperties();
    private final FakeImageProvider provider = new FakeImageProvider();
    private final List<AssetPipeline> pipelines = new ArrayList<>();

    private AssetPipeline pipeline() {
        props.setConcurrency(1);
        props.setArcBudget(0);
        var pipeline = new AssetPipeline(provider, store(), props, mapper);
        pipelines.add(pipeline);
        return pipeline;
    }

    private AssetStore store() { return new AssetStore(mapper, directory); }

    @AfterEach void stop() {
        if (provider.gate != null) provider.gate.countDown();
        pipelines.forEach(AssetPipeline::shutdown);
    }

    @Test void loadingTheCurrentSceneResumesOnlyPreviouslyQueuedFutureCharacterWork() throws Exception {
        var npc = new NpcProfile("npc_later", "Later", "description", "kind", List.of(), List.of(),
                "voice", "stranger", "silver hair");
        var story = new CompiledStory(new AuthorCanon("outline", List.of()),
                new StoryBible("premise", "tone", List.of(), List.of(npc), List.of(), List.of(),
                        List.of(), List.of(), List.of()),
                new StorySpine("arc", List.of(new StoryBeat("b1", "first", "purpose", "conflict", "major"),
                        new StoryBeat("b2", "second", "purpose", "conflict", "major"))));
        String style = VisualPlanner.styleFor(story);
        String styleKey = VisualPlanner.styleKey(style);
        var planner = new VisualPlanner(props);
        var spec = planner.fromRequest(story, new AssetRequest("portrait", npc.id(), "base", null),
                style, styleKey, "b2");
        var queued = record(spec, AssetStatus.QUEUED, 0);
        var plannedCard = record(new AssetSpec(AssetSpec.characterCardId(npc.id()), AssetKind.CHARACTER_CARD,
                npc.id(), npc.name(), "default", "framed reference", styleKey, "b2", 20,
                spec.assetId(), AssetSpec.GENERAL, false).withAppearanceKey(spec.appearanceKey()), AssetStatus.PLANNED, 0);
        var manifest = write(queued, plannedCard);
        manifest.style = style;
        manifest.styleKey = styleKey;
        store().writeManifest(manifest);

        // Status polling, even across another restart, must neither spend nor forget the queued grant.
        var first = pipeline();
        assertEquals(AssetStatus.QUEUED, first.snapshot("s").orElseThrow().get(spec.assetId()).status);
        assertEquals(0, first.queueDepth());
        assertEquals(0, provider.calls.get());
        first.shutdown();
        assertEquals(AssetStatus.QUEUED, store().readManifest("s").orElseThrow().get(spec.assetId()).status);

        var restored = pipeline();
        var coordinator = new AssetCoordinator(restored, planner, props);
        var session = new GameSession();
        session.id = "s";
        session.story = story;
        session.state = new GameState();
        session.state.currentBeatId = "b1";
        session.currentScene = new SceneBundle("scene_1", "b1", null, List.of(),
                List.of(Block.narration("Alone in the first scene")), List.of(), null, "", List.of(), null);
        for (int i = 0; i < 4; i++) coordinator.ensureCurrentSceneAssets(session);
        await(() -> status(restored, spec.assetId()) == AssetStatus.READY && restored.activeCount() == 0);
        assertEquals(1, provider.calls.get(), "a future NPC resumes without waiting for another scene commit");
        assertEquals(AssetStatus.PLANNED, status(restored, plannedCard.spec.assetId()), "never-admitted plans stay planned");
        assertEquals(0, restored.snapshot("s").orElseThrow().budget.firstBatchQueued);
    }

    @Test void verifiedRecoveryKeepsRetryCountsAndReferenceDependenciesWhenTheQueueIsFull() throws Exception {
        props.setMaxAttempts(2);
        props.setQueueCapacity(1);
        var base = record(base("A"), AssetStatus.GENERATING, 1);
        var card = record(dependent("A", "card", AssetKind.CHARACTER_CARD), AssetStatus.QUEUED, 0);
        var pose = record(dependent("A", "pose", AssetKind.PORTRAIT_VARIANT), AssetStatus.QUEUED, 0);
        write(base, card, pose);
        provider.entered = new CountDownLatch(1);
        provider.gate = new CountDownLatch(1);
        var restored = pipeline();
        restored.snapshot("s");
        assertEquals(0, provider.calls.get());
        for (int i = 0; i < 4; i++) restored.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of());
        assertTrue(provider.entered.await(3, TimeUnit.SECONDS));
        assertTrue(restored.queueDepth() <= 1);
        assertEquals(AssetStatus.QUEUED, status(restored, card.spec.assetId()));
        assertEquals(AssetStatus.QUEUED, status(restored, pose.spec.assetId()));
        assertEquals(2, restored.snapshot("s").orElseThrow().get(base.spec.assetId()).attempts);
        provider.gate.countDown();
        await(() -> restored.snapshot("s").orElseThrow().records.values().stream()
                .allMatch(r -> r.status == AssetStatus.READY) && restored.activeCount() == 0);
        assertEquals(3, provider.calls.get());
        assertEquals(1, provider.generateCalls.get());
        assertEquals(2, provider.editCalls.get());
        assertEquals(4, store().readManifest("s").orElseThrow().budget.attemptsTotal);
        assertEquals(2, restored.snapshot("s").orElseThrow().get(base.spec.assetId()).attempts);
    }

    @Test void obsoleteAndArchivedWorkCannotResumeWhenAnotherAppearanceIsRestored() throws Exception {
        var obsolete = record(base("A"), AssetStatus.QUEUED, 0);
        var current = record(base("B"), AssetStatus.PLANNED, 0);
        var manifest = write(obsolete);
        manifest.versions.put(current.recordVersionId, current);
        store().writeManifest(manifest);
        var restored = pipeline();
        restored.reconcileCharacterVersions("s", Map.of("npc", "B"), Map.of());
        assertEquals("B", restored.snapshot("s").orElseThrow().get(current.spec.assetId()).spec.appearanceKey());
        assertEquals(AssetStatus.PLANNED, status(restored, current.spec.assetId()));
        restored.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of());
        assertEquals(AssetStatus.PLANNED, status(restored, obsolete.spec.assetId()));
        assertEquals(0, restored.queueDepth());
        assertEquals(0, provider.calls.get(), "returning to an archived route requires explicit admission");
    }

    @Test void interruptedArchivedCallsDoNotResumeWithTheCurrentRoute() throws Exception {
        var archived = record(base("A"), AssetStatus.GENERATING, 1);
        var current = record(base("B"), AssetStatus.QUEUED, 0);
        var manifest = write(current);
        manifest.versions.put(archived.recordVersionId, archived);
        manifest.budget.attemptsTotal = 1;
        manifest.budget.arcAttempts = 1;
        store().writeManifest(manifest);
        var restored = pipeline();
        restored.reconcileCharacterVersions("s", Map.of("npc", "B"), Map.of());
        await(() -> status(restored, current.spec.assetId()) == AssetStatus.READY && restored.activeCount() == 0);
        assertEquals(List.of("appearance B"), provider.prompts());
        var retained = restored.snapshot("s").orElseThrow().versions.get(archived.recordVersionId);
        assertEquals(AssetStatus.PLANNED, retained.status);
        assertEquals(1, retained.attempts);
    }

    @Test void exhaustedManualAttemptAndDisabledGenerationCannotSpendDuringRecovery() {
        var exhausted = record(base("A"), AssetStatus.GENERATING, 2);
        exhausted.manualAttemptLimit = 2;
        write(exhausted);
        var restored = pipeline();
        restored.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of());
        assertEquals(AssetStatus.FAILED, status(restored, exhausted.spec.assetId()));
        assertEquals(0, provider.calls.get());
        restored.shutdown();

        write(record(base("A"), AssetStatus.QUEUED, 1));
        provider.enabled = false;
        var disabled = pipeline();
        disabled.reconcileCharacterVersions("s", Map.of("npc", "A"), Map.of());
        assertEquals(AssetStatus.PAUSED, status(disabled, exhausted.spec.assetId()));
        assertEquals(1, disabled.snapshot("s").orElseThrow().budget.attemptsTotal);
        assertEquals(0, provider.calls.get());
    }

    private AssetManifest write(AssetRecord... records) {
        var manifest = new AssetManifest();
        manifest.sessionId = "s";
        for (var record : records) {
            manifest.records.put(record.spec.assetId(), record);
            manifest.versions.put(record.recordVersionId, record);
            manifest.budget.attemptsTotal += record.attempts;
            manifest.budget.arcAttempts += record.attempts;
        }
        store().writeManifest(manifest);
        return manifest;
    }

    private static AssetRecord record(AssetSpec spec, AssetStatus status, int attempts) {
        var record = new AssetRecord(spec);
        record.status = status;
        record.attempts = attempts;
        return record;
    }

    private static AssetSpec base(String key) {
        return new AssetSpec("pt.npc.base", AssetKind.PORTRAIT, "npc", "NPC", "base", "appearance " + key,
                "style", "b2", 10, null, AssetSpec.GENERAL, false).withAppearanceKey(key);
    }

    private static AssetSpec dependent(String key, String id, AssetKind kind) {
        return new AssetSpec(id + ".npc.default", kind, "npc", "NPC", "default", "reference " + id,
                "style", "b2", 20, "pt.npc.base", AssetSpec.GENERAL, false).withAppearanceKey(key);
    }

    private static AssetStatus status(AssetPipeline pipeline, String assetId) {
        return pipeline.snapshot("s").orElseThrow().get(assetId).status;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "expected image state was not reached");
    }
}
