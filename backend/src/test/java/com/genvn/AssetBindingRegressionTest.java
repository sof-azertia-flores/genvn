package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetSpec;
import com.genvn.asset.AssetStatus;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AssetBindingRegressionTest {
    @TempDir Path directory;

    @Test
    void requestedNightVariantReplacesTheDefaultBeforeItsPictureFinishes() throws Exception {
        checkBackgroundBinding("loc_interior", "night");
    }

    @Test
    void aNewRoomReceivesItsRequestedBackgroundOnTheFirstVisit() throws Exception {
        checkBackgroundBinding("loc_unplanned_room", AssetSpec.DEFAULT_VARIANT);
    }

    private void checkBackgroundBinding(String locationId, String variant) throws Exception {
        String assetId = AssetSpec.backgroundId(locationId, variant);
        var client = new ScriptedLlmClient(new ObjectMapper(), request -> {
            if (ScriptedLlmClient.choiceOf(request) == null) {
                return SceneJson.scene("Opening.").at("loc_threshold").choice("go", "Go in", "cautious").build();
            }
            return SceneJson.scene("The requested setting.").at(locationId).backgroundHint(assetId)
                    .assetRequest("background", locationId, variant).choice("next", "Continue", "cautious").build();
        });
        FakeImageProvider images = new FakeImageProvider();
        Engine engine = new Engine(client, new ScriptedRandom(10), false, false,
                images, Engine.imageProperties(), directory);
        try {
            var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            engine.awaitAssets(session.id);
            images.gate = new CountDownLatch(1);
            images.entered = new CountDownLatch(1);

            var result = engine.sessions.choose(session.id, "go");
            assertTrue(images.entered.await(5, TimeUnit.SECONDS));
            assertEquals(assetId, result.session().currentScene.location().backgroundAssetId(),
                    "the response must already reference the pending picture, not the old fallback");
            assertEquals(assetId, engine.sessions.copySession(session).currentScene.location().backgroundAssetId());
            assertNotEquals(AssetStatus.READY, engine.assets.snapshot(session.id).orElseThrow().get(assetId).status);
            int version = session.state.stateVersion;
            int scenes = session.sceneCounter;

            images.gate.countDown();
            engine.awaitAssets(session.id);
            var ready = engine.assets.snapshot(session.id).orElseThrow().get(assetId);
            assertEquals(AssetStatus.READY, ready.status);
            assertNotNull(ready.firstNeededAt, "the first visit is recorded even before its picture exists");
            assertEquals(assetId, session.currentScene.location().backgroundAssetId());
            assertEquals(version, session.state.stateVersion, "finishing the picture changes no narrative state");
            assertEquals(scenes, session.sceneCounter);
        } finally {
            if (images.gate != null) images.gate.countDown();
            engine.assets.shutdown();
        }
    }

    @Test
    void portraitRequestsOutsideTheInitialPlanGetTheirReferenceAndBindOnCommit() throws Exception {
        var client = new ScriptedLlmClient(new ObjectMapper(), request -> {
            if (ScriptedLlmClient.choiceOf(request) == null) {
                return SceneJson.scene("Opening.").at("loc_threshold").choice("go", "Ask", "social").build();
            }
            return SceneJson.scene("The neighbour looks worried.").at("loc_threshold")
                    .withCharacter("npc_witness", "Neighbour", "worried", null)
                    .assetRequest("portrait", "npc_witness", "worried")
                    .choice("next", "Continue", "social").build();
        });
        var properties = Engine.imageProperties();
        properties.getPlan().setCharacters(0);
        FakeImageProvider images = new FakeImageProvider();
        Engine engine = new Engine(client, new ScriptedRandom(10), false, false, images, properties, directory);
        try {
            var session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            engine.awaitAssets(session.id);
            assertNull(engine.assets.snapshot(session.id).orElseThrow().get("pt.npc_witness.base"));
            var result = engine.sessions.choose(session.id, "go");
            assertEquals("pt.npc_witness.worried", result.session().currentScene.characters().get(0).assetId());
            engine.awaitAssets(session.id);
            var manifest = engine.assets.snapshot(session.id).orElseThrow();
            assertEquals(AssetStatus.READY, manifest.get("pt.npc_witness.base").status);
            assertEquals(AssetStatus.READY, manifest.get("pt.npc_witness.worried").status);
            assertEquals(AssetStatus.READY, manifest.get("card.npc_witness.default").status);
        } finally {
            engine.assets.shutdown();
        }
    }
}
