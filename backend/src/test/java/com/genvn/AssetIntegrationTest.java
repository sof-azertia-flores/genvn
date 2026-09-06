package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetKind;
import com.genvn.asset.AssetManifest;
import com.genvn.asset.AssetRecord;
import com.genvn.asset.AssetResolver;
import com.genvn.asset.AssetSpec;
import com.genvn.asset.AssetStatus;
import com.genvn.game.GameSession;
import com.genvn.llm.MockLlmClient;
import com.genvn.narrative.Choice;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pictures and prose through the real loop: images start before the opening text finishes,
 * prose and dice never wait on a picture, a committed scene references validated ids, a
 * picture finishing changes nothing canonical, and unchosen branches buy nothing.
 */
class AssetIntegrationTest {

    private Path tmp() throws Exception {
        return Files.createTempDirectory("genvn-it");
    }

    @Test
    @DisplayName("image requests reach the provider while the opening text is still being written")
    void picturesStartBeforeTheOpeningTextIsDone() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FakeImageProvider fake = new FakeImageProvider();
        fake.entered = new CountDownLatch(1);
        AtomicInteger imageCallsWhenOpeningReleased = new AtomicInteger(-1);
        ScriptedLlmClient client = new ScriptedLlmClient(mapper, request -> {
            if (ScriptedLlmClient.choiceOf(request) != null) return null; // branches: mock
            // The opening prose "takes a while": it waits until at least one picture request has
            // entered the image provider, which is only possible if planning ran first.
            try {
                assertTrue(fake.entered.await(5, TimeUnit.SECONDS), "an image request arrived during opening generation");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            imageCallsWhenOpeningReleased.set(fake.calls.get());
            return SceneJson.scene("The house waits.").at("loc_threshold")
                    .choiceWithCheck("cC", "Look around", "Perception", 12).build();
        });
        Engine engine = new Engine(client, new ScriptedRandom(15, 15, 15), true, false, fake, Engine.imageProperties(), tmp());
        try {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            assertTrue(imageCallsWhenOpeningReleased.get() >= 1,
                    "at least one picture was already in the provider when the opening text was released");
            assertNotNull(session.currentScene.location().backgroundAssetId(), "the opening references its background");
            assertEquals("bg.loc_threshold.default", session.currentScene.location().backgroundAssetId());
        } finally {
            engine.assets.shutdown();
        }
    }

    @Test
    @DisplayName("prose, choices and real dice never wait for a picture; a picture finishing changes no canon")
    void proseNeverWaitsForPictures() throws Exception {
        FakeImageProvider fake = new FakeImageProvider();
        fake.gate = new CountDownLatch(1);           // hold EVERY picture
        fake.entered = new CountDownLatch(1);
        Engine engine = new Engine(new MockLlmClient(new ObjectMapper()), new ScriptedRandom(20, 20, 20, 20), true, false,
                fake, Engine.imageProperties(), tmp());
        try {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            assertTrue(fake.entered.await(5, TimeUnit.SECONDS));
            assertFalse(session.currentScene.blocks().isEmpty(), "the opening is readable with zero pictures done");

            Choice checked = session.currentScene.choices().stream().filter(Choice::hasCheck).findFirst().orElseThrow();
            var rolled = engine.sessions.roll(session.id, checked.id());
            assertNotNull(rolled.roll(), "the die was cast while all pictures were held");
            var outcome = engine.sessions.choose(session.id, checked.id());
            assertNotNull(outcome.session().currentScene, "the next scene committed while all pictures were held");
            int versionAfterCommit = session.state.stateVersion;
            var statusHeld = engine.assets.status(session.id);
            assertTrue((int) statusHeld.get("pending") > 0, "pictures are still pending at this point");

            fake.gate.countDown();
            engine.awaitAssets(session.id);
            AssetManifest m = engine.assets.snapshot(session.id).orElseThrow();
            assertTrue(m.records.values().stream().anyMatch(r -> r.status == AssetStatus.READY));
            assertEquals(versionAfterCommit, session.state.stateVersion,
                    "pictures finishing did not touch GameState.stateVersion");
            assertEquals(2, session.state.storyProgress.scenesPlayed, "and committed no scene");
        } finally {
            engine.assets.shutdown();
        }
    }

    @Test
    @DisplayName("a committed scene's references are validated ids, marked as needed, and reused across scenes")
    void referencesAreValidatedAndReused() throws Exception {
        FakeImageProvider fake = new FakeImageProvider();
        Engine engine = new Engine(new MockLlmClient(new ObjectMapper()), new ScriptedRandom(20, 20, 20, 20, 20, 20), true, false,
                fake, Engine.imageProperties(), tmp());
        try {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            engine.awaitAssets(session.id);
            String bg = session.currentScene.location().backgroundAssetId();
            assertNotNull(bg);
            AssetRecord r = engine.assets.snapshot(session.id).orElseThrow().get(bg);
            assertEquals(AssetStatus.READY, r.status);
            assertNotNull(r.firstNeededAt, "commit recorded when the picture was first needed");
            assertTrue(r.reuseCount >= 1);
            assertTrue(session.currentScene.characters().stream().allMatch(c -> c.assetId() != null),
                    "everyone on stage resolved to a portrait");
            int callsAfterOpening = fake.calls.get();

            // Play on: the same rooms and faces come back without a single new request.
            for (int i = 0; i < 3; i++) {
                engine.sessions.choose(session.id, session.currentScene.choices().get(0).id());
                engine.awaitAssets(session.id);
            }
            assertTrue(fake.calls.get() <= callsAfterOpening + 2,
                    "later scenes reused the library; only newly-needed pictures were requested");
            AssetRecord again = engine.assets.snapshot(session.id).orElseThrow().get(bg);
            assertTrue(again.reuseCount >= r.reuseCount);
        } finally {
            engine.assets.shutdown();
        }
    }

    @Test
    @DisplayName("a picture tied to SUCCESS is never handed to the FAILURE branch, and a wrong-subject hint is refused")
    void outcomeSpecificAndHints() {
        AssetManifest m = new AssetManifest();
        m.sessionId = "s1";
        AssetSpec general = new AssetSpec("bg.loc_x.default", AssetKind.BACKGROUND, "loc_x", "X", "default", "p", "k",
                null, 0, null, AssetSpec.GENERAL, true);
        AssetSpec onlySuccess = new AssetSpec("bg.loc_x.opened", AssetKind.BACKGROUND, "loc_x", "X", "opened", "p", "k",
                null, 0, null, "OUTCOME:SUCCESS", true);
        AssetSpec otherPlace = new AssetSpec("bg.loc_y.default", AssetKind.BACKGROUND, "loc_y", "Y", "default", "p", "k",
                null, 0, null, AssetSpec.GENERAL, true);
        AssetSpec face = new AssetSpec("pt.npc_a.base", AssetKind.PORTRAIT, "npc_a", "A", "base", "p", "k",
                null, 0, null, AssetSpec.GENERAL, false);
        for (AssetSpec s : new AssetSpec[]{general, onlySuccess, otherPlace, face}) m.records.put(s.assetId(), new AssetRecord(s));

        AssetResolver resolver = AssetResolver.none();
        assertEquals("bg.loc_x.opened", resolver.resolveBackground(m, "loc_x", "bg.loc_x.opened", "SUCCESS"));
        assertEquals("bg.loc_x.default", resolver.resolveBackground(m, "loc_x", "bg.loc_x.opened", "FAILURE"),
                "the success-only picture is refused on failure; the general one is used");
        assertEquals("bg.loc_x.default", resolver.resolveBackground(m, "loc_x", "bg.loc_y.default", "NONE"),
                "a hint naming another place is ignored");
        assertEquals("bg.loc_x.default", resolver.resolveBackground(m, "loc_x", "bg.does_not_exist", "NONE"));
        assertNull(resolver.resolveBackground(m, "loc_nowhere", null, "NONE"));
        assertEquals("pt.npc_a.base", resolver.resolvePortrait(m, "npc_a", "worried", null, "NONE"),
                "no worried variant: the base portrait stands in");
        assertEquals("pt.npc_a.base", resolver.resolvePortrait(m, "npc_a", "worried", "bg.loc_x.default", "NONE"),
                "a background hint can never become someone's face: it is ignored and the base portrait is used");
        assertNull(resolver.resolvePortrait(m, "npc_unknown", "worried", "pt.npc_a.base", "NONE"),
                "a hint naming another character is ignored, and an unknown character has no portrait");
    }

    @Test
    @DisplayName("a scene's picture request is honoured only on commit: speculative branches buy nothing")
    void requestsAreHonouredOnlyOnCommit() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FakeImageProvider fake = new FakeImageProvider();
        ScriptedLlmClient client = new ScriptedLlmClient(mapper, request -> {
            Choice choice = ScriptedLlmClient.choiceOf(request);
            if (choice == null) {
                return SceneJson.scene("Opening.").at("loc_threshold").choice("go", "Go in", "cautious").build();
            }
            // Every branch asks for a night version of the interior.
            return SceneJson.scene("Inside at night.").at("loc_interior")
                    .assetRequest("background", "loc_interior", "night")
                    .choice("on", "Onward", "cautious").build();
        });
        Engine engine = new Engine(client, new ScriptedRandom(10, 10, 10), true, false, fake, Engine.imageProperties(), tmp());
        try {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            engine.awaitBranches(session.id);
            engine.awaitAssets(session.id);
            assertNull(engine.assets.snapshot(session.id).orElseThrow().get("bg.loc_interior.night"),
                    "the branch was generated but its request bought nothing");
            int before = fake.calls.get();

            engine.sessions.choose(session.id, "go");
            engine.awaitAssets(session.id);
            AssetRecord night = engine.assets.snapshot(session.id).orElseThrow().get("bg.loc_interior.night");
            assertNotNull(night, "on commit the request became a planned, queued picture");
            assertEquals(AssetStatus.READY, night.status);
            assertEquals("bg.loc_interior.night", session.currentScene.location().backgroundAssetId(),
                    "the selected speculative candidate is bound to the picture it requested on commit");
            assertEquals(before + 1, fake.calls.get(), "exactly one new picture for the one new need");
        } finally {
            engine.assets.shutdown();
        }
    }

    @Test
    @DisplayName("deleting a session removes its pictures and drops results that arrive afterwards")
    void deleteDropsLateResults() throws Exception {
        FakeImageProvider fake = new FakeImageProvider();
        fake.gate = new CountDownLatch(1);
        fake.entered = new CountDownLatch(1);
        Path dir = tmp();
        Engine engine = new Engine(new MockLlmClient(new ObjectMapper()), new ScriptedRandom(20, 20), false, false,
                fake, Engine.imageProperties(), dir);
        try {
            GameSession session = engine.sessions.create(Engine.OUTLINE, Engine.alex());
            assertTrue(fake.entered.await(5, TimeUnit.SECONDS));
            assertTrue(engine.sessions.delete(session.id));
            fake.gate.countDown();
            Thread.sleep(400);
            assertFalse(Files.exists(dir.resolve(session.id)), "the session's asset directory is gone and stays gone");
            assertTrue(engine.assets.snapshot(session.id).isEmpty());
        } finally {
            engine.assets.shutdown();
        }
    }
}
