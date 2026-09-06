package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.*;
import com.genvn.config.ImageProperties;
import com.genvn.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Two display tracks must share an identity, a reference and one durable card. */
class CharacterArtTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();

    private VisualPlanner.Plan plan(ImageProperties props) {
        props.getPlan().setLocations(0);
        props.getPlan().setCharacters(1);
        props.getPlan().setExpressions("worried,suspicious");
        return new VisualPlanner(props).plan(VisualPlannerTest.story(1, 1), VisualPlannerTest.stateAt("loc_0"), null);
    }

    private void settle(AssetPipeline pipeline) throws Exception {
        for (int i = 0; i < 400; i++) {
            if ((int) pipeline.status("s1").get("pending") == 0 && pipeline.activeCount() == 0) return;
            Thread.sleep(25);
        }
        fail("image work did not settle");
    }

    @Test void cardAndEveryPoseUseTheSameTransparentBaseAndPersistOnce() throws Exception {
        ImageProperties props = Engine.imageProperties();
        props.setOutputFormat("jpeg"); // stage sprites must still be PNG
        var plan = plan(props);
        FakeImageProvider fake = new FakeImageProvider();
        AssetPipeline pipeline = new AssetPipeline(fake, new AssetStore(mapper, directory), props, mapper);
        try {
            pipeline.adopt("s1", plan);
            settle(pipeline);
            var manifest = pipeline.snapshot("s1").orElseThrow();
            var base = manifest.get("pt.npc_0.base");
            var card = manifest.get("card.npc_0.default");
            assertEquals(AssetStatus.READY, card.status);
            assertEquals(AssetKind.CHARACTER_CARD, card.spec.kind());
            assertEquals("pt.npc_0.base", card.spec.dependsOn());
            assertTrue(card.startedAt.compareTo(base.readyAt) >= 0);
            byte[] reference = Files.readAllBytes(pipeline.readyFile("s1", base.spec.assetId()).orElseThrow());
            assertTrue(AssetStore.hasTransparentContent(reference));
            assertEquals(1, fake.generateCalls.get());
            assertEquals(3, fake.editCalls.get());
            assertTrue(fake.generations.get(0).transparentBackground());
            assertEquals("png", fake.generations.get(0).format());
            for (ImageEditRequest edit : fake.edits) {
                assertArrayEquals(reference, edit.referenceImage());
                if (edit.prompt().contains("permanent character card")) {
                    assertFalse(edit.transparentBackground());
                    assertEquals("jpeg", edit.format());
                    assertTrue(edit.prompt().contains("border"));
                } else {
                    assertTrue(edit.transparentBackground());
                    assertEquals("png", edit.format());
                }
            }
            pipeline.adopt("s1", plan);
            pipeline.ensureQueued("s1", manifest.records.keySet(), true);
            settle(pipeline);
            assertEquals(4, fake.calls.get(), "new scenes and expressions do not regenerate the card");
            assertEquals(4, manifest.budget.attemptsTotal, "card edits count against the same durable budget");
            pipeline.shutdown();
            AssetPipeline restarted = new AssetPipeline(fake, new AssetStore(mapper, directory), props, mapper);
            try {
                assertEquals(AssetStatus.READY, restarted.snapshot("s1").orElseThrow().get(card.spec.assetId()).status);
                assertEquals(4, fake.calls.get());
            } finally { restarted.shutdown(); }
        } finally { pipeline.shutdown(); }
    }

    @Test void firstBatchPrioritisesTheSharedCardBeforeOptionalPoses() throws Exception {
        ImageProperties props = Engine.imageProperties();
        props.setFirstBatchBudget(2);
        FakeImageProvider fake = new FakeImageProvider();
        AssetPipeline pipeline = new AssetPipeline(fake, new AssetStore(mapper, directory), props, mapper);
        try {
            pipeline.adopt("s1", plan(props));
            settle(pipeline);
            var manifest = pipeline.snapshot("s1").orElseThrow();
            assertEquals(AssetStatus.READY, manifest.get("pt.npc_0.base").status);
            assertEquals(AssetStatus.READY, manifest.get("card.npc_0.default").status);
            assertEquals(AssetStatus.PLANNED, manifest.get("pt.npc_0.worried").status);
            assertEquals(2, fake.calls.get());
        } finally { pipeline.shutdown(); }
    }

    @Test void failedTransparentBaseNeverBecomesAnOpaqueSpriteOrAnUnrelatedCard() throws Exception {
        ImageProperties props = Engine.imageProperties();
        FakeImageProvider fake = new FakeImageProvider();
        fake.forceOpaque = true;
        AssetPipeline pipeline = new AssetPipeline(fake, new AssetStore(mapper, directory), props, mapper);
        try {
            pipeline.adopt("s1", plan(props));
            settle(pipeline);
            var manifest = pipeline.snapshot("s1").orElseThrow();
            assertEquals(AssetStatus.FAILED, manifest.get("pt.npc_0.base").status);
            assertEquals(AssetStatus.PAUSED, manifest.get("card.npc_0.default").status);
            assertTrue(pipeline.readyFile("s1", "pt.npc_0.base").isEmpty());
            assertEquals(props.getMaxAttempts(), fake.calls.get(), "only the base retries within its limit; cards and variants still wait for a valid reference");
        } finally { pipeline.shutdown(); }
    }

    @Test void unusablePoseRetriesFromTheOriginalTransparentReferenceAndCountsEachAttempt() throws Exception {
        ImageProperties props = Engine.imageProperties();
        props.setMaxAttempts(2);
        FakeImageProvider fake = new FakeImageProvider() {
            @Override public ImageResult edit(ImageEditRequest request) throws ImageProviderException {
                ImageResult result = super.edit(request);
                if (editCalls.get() == 1) return new ImageResult(FakeImageProvider.png("opaque pose", 16, 24), "image/png", 16, 24, "gpt-image-2");
                return result;
            }
        };
        AssetSpec base = new AssetSpec("pt.npc_0.base", AssetKind.PORTRAIT, "npc_0", "NPC", "base", "base", "style", "b1", 0, null, AssetSpec.GENERAL, false);
        AssetSpec pose = new AssetSpec("pt.npc_0.suspicious", AssetKind.PORTRAIT_VARIANT, "npc_0", "NPC", "suspicious", "pose", "style", "b1", 1, base.assetId(), AssetSpec.GENERAL, false);
        AssetPipeline pipeline = new AssetPipeline(fake, new AssetStore(mapper, directory), props, mapper);
        try {
            pipeline.adopt("s1", new VisualPlanner.Plan("style", "style", List.of(base, pose)));
            settle(pipeline);
            var manifest = pipeline.snapshot("s1").orElseThrow();
            assertEquals(AssetStatus.READY, manifest.get(pose.assetId()).status);
            assertEquals(2, manifest.get(pose.assetId()).attempts);
            assertEquals(3, manifest.budget.arcAttempts);
            assertArrayEquals(fake.edits.get(0).referenceImage(), fake.edits.get(1).referenceImage());
            assertTrue(AssetStore.hasTransparentContent(Files.readAllBytes(pipeline.readyFile("s1", pose.assetId()).orElseThrow())));
        } finally { pipeline.shutdown(); }
    }

    @Test void unsupportedCharacterCapabilitiesPauseBeforeAnyProviderCall() throws Exception {
        ImageProperties props = Engine.imageProperties();
        FakeImageProvider fake = new FakeImageProvider();
        fake.transparent = false;
        fake.supportsEdit = false;
        AssetPipeline pipeline = new AssetPipeline(fake, new AssetStore(mapper, directory), props, mapper);
        try {
            pipeline.adopt("s1", plan(props));
            settle(pipeline);
            assertEquals(0, fake.calls.get());
            assertTrue(pipeline.snapshot("s1").orElseThrow().records.values().stream()
                    .allMatch(r -> r.status == AssetStatus.PAUSED));
        } finally { pipeline.shutdown(); }
    }

    @Test void aNewlyPresentNpcGetsBothTracksWithoutAnAssetRequest() throws Exception {
        var llm = new ScriptedLlmClient(mapper, request -> SceneJson.scene("A neighbour waits.").at("loc_threshold")
                .withCharacter("npc_witness", "Neighbour", "neutral", "card.npc_witness.default")
                .choice("next", "Listen", "social").build());
        ImageProperties props = Engine.imageProperties();
        props.getPlan().setCharacters(0);
        FakeImageProvider fake = new FakeImageProvider();
        Engine engine = new Engine(llm, new ScriptedRandom(10), false, false, fake, props, directory);
        try {
            var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            engine.awaitAssets(session.id);
            var manifest = engine.assets.snapshot(session.id).orElseThrow();
            assertEquals(AssetStatus.READY, manifest.get("pt.npc_witness.base").status);
            assertEquals(AssetStatus.READY, manifest.get("card.npc_witness.default").status);
            assertEquals("pt.npc_witness.base", session.currentScene.characters().get(0).assetId(),
                    "a malicious/wrong card hint can never be displayed as a transparent sprite");
            assertEquals(2, manifest.records.values().stream().filter(r -> r.spec.kind() == AssetKind.CHARACTER_CARD
                            && !r.spec.idlePreparation()).count(),
                    "one permanent card each for the player and the newly present NPC");
        } finally { engine.assets.shutdown(); }
    }

    @Test void scenePromptsDoNotOfferCardsAsStagePictures() {
        ImageProperties props = Engine.imageProperties();
        AssetManifest manifest = new AssetManifest();
        for (AssetSpec spec : plan(props).specs()) manifest.records.put(spec.assetId(), new AssetRecord(spec));
        var resolver = AssetResolver.none();
        assertFalse(resolver.renderForPrompt(manifest).contains("card.npc_0.default"));
        assertEquals("pt.npc_0.worried", resolver.resolvePortrait(manifest, "npc_0", "worried", "card.npc_0.default", "NONE"));
    }
}
