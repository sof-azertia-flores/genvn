package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.AccessGate;
import com.genvn.api.AssetController;
import com.genvn.asset.AssetCoordinator;
import com.genvn.asset.AssetManifest;
import com.genvn.asset.AssetPipeline;
import com.genvn.asset.AssetPublication;
import com.genvn.asset.AssetRecord;
import com.genvn.asset.AssetStatus;
import com.genvn.asset.AssetStore;
import com.genvn.persistence.GameSessionRepository;
import com.genvn.support.FakeImageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetPublicationTest {
    private static final String ASSET_ID = "card.npc_lin.default";
    @TempDir Path directory;
    private AssetStore store;
    private AssetManifest manifest;
    private AssetPipeline pipeline;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new AssetStore(new ObjectMapper(), directory);
        manifest = new AssetManifest();
        manifest.sessionId = "s1";
        pipeline = mock(AssetPipeline.class);
        when(pipeline.snapshot("s1")).thenAnswer(call -> Optional.of(manifest));
        when(pipeline.snapshot("s2")).thenReturn(Optional.empty());
        mvc = MockMvcBuilders.standaloneSetup(new AssetController(pipeline, store,
                mock(GameSessionRepository.class))).build();
    }

    private String publish(String version, int generation, String seed) throws Exception {
        var stored = store.saveVersioned("s1", ASSET_ID, version, generation,
                FakeImageProvider.png(seed, 8, 8), "image/png");
        String publicationId = AssetStore.publicationId(version, generation);
        manifest.publications.put(publicationId,
                new AssetPublication(publicationId, ASSET_ID, stored.fileName(), stored.mimeType()));
        var active = new AssetRecord();
        active.status = AssetStatus.READY;
        active.fileName = stored.fileName();
        active.publicationId = publicationId;
        active.generationVersion = generation;
        manifest.records.put(ASSET_ID, active);
        return publicationId;
    }

    @Test
    void oldUrlsKeepTheirBytesAfterAppearanceSwitchRetryAndArchival() throws Exception {
        String first = publish("appearance_a", 1, "red armour");
        mvc.perform(get("/api/assets/s1/" + ASSET_ID).param("v", first))
                .andExpect(status().isOk()).andExpect(content().bytes(FakeImageProvider.png("red armour", 8, 8)));
        String second = publish("appearance_b", 1, "blue robe");
        String retry = publish("appearance_b", 2, "blue robe retry");

        for (String version : List.of(first, second, retry)) {
            String seed = version.equals(first) ? "red armour" : version.equals(second) ? "blue robe" : "blue robe retry";
            mvc.perform(get("/api/assets/s1/" + ASSET_ID).param("v", version))
                    .andExpect(status().isOk()).andExpect(content().bytes(FakeImageProvider.png(seed, 8, 8)))
                    .andExpect(header().string("Cache-Control", containsString("private")))
                    .andExpect(header().string("Cache-Control", containsString("immutable")))
                    .andExpect(header().string("Cache-Control", containsString("max-age=31536000")));
        }
        manifest.records.clear();
        store.writeManifest(manifest);
        manifest = store.readManifest("s1").orElseThrow();
        mvc.perform(get("/api/assets/s1/" + ASSET_ID).param("v", first))
                .andExpect(status().isOk()).andExpect(content().bytes(FakeImageProvider.png("red armour", 8, 8)));
    }

    @Test
    void legacyPublicationRetainsOriginalFileWhileNewGenerationUsesItsOwnPath() throws Exception {
        var old = store.save("s1", ASSET_ID, FakeImageProvider.png("legacy", 8, 8), "image/png");
        String publicationId = "legacy_appearance_a_1";
        manifest.publications.put(publicationId,
                new AssetPublication(publicationId, ASSET_ID, old.fileName(), old.mimeType()));
        publish("appearance_b", 1, "current");
        mvc.perform(get("/api/assets/s1/" + ASSET_ID).param("v", publicationId))
                .andExpect(status().isOk()).andExpect(content().bytes(FakeImageProvider.png("legacy", 8, 8)))
                .andExpect(header().string("Cache-Control", containsString("immutable")));
        assertEquals(ASSET_ID + ".png", old.fileName());
    }

    @Test
    void unversionedAndOldNumericUrlsAreCurrentAliasesWithoutImmutableCache() throws Exception {
        publish("appearance_a", 1, "old");
        publish("appearance_b", 1, "current");
        mvc.perform(get("/api/assets/s1/" + ASSET_ID))
                .andExpect(status().isOk()).andExpect(content().bytes(FakeImageProvider.png("current", 8, 8)))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().string("Cache-Control", not(containsString("immutable"))));
        mvc.perform(get("/api/assets/s1/" + ASSET_ID).param("v", "123"))
                .andExpect(status().isOk()).andExpect(content().bytes(FakeImageProvider.png("current", 8, 8)))
                .andExpect(header().string("Cache-Control", containsString("no-cache")));
    }

    @Test
    void versionsCannotSelectAnotherAssetSessionOrPath() throws Exception {
        String publicationId = publish("appearance_a", 1, "original");
        mvc.perform(get("/api/assets/s1/card.somebody_else.default").param("v", publicationId))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/assets/s2/" + ASSET_ID).param("v", publicationId))
                .andExpect(status().isNotFound());
        for (String invalid : List.of("", "../manifest.json", "v_absent_1", "v_a/1", "v_a\\1")) {
            mvc.perform(get("/api/assets/s1/" + ASSET_ID).param("v", invalid))
                    .andExpect(status().isNotFound());
        }
        Files.delete(store.imagePath("s1", manifest.get(ASSET_ID).fileName).orElseThrow());
        mvc.perform(get("/api/assets/s1/" + ASSET_ID).param("v", publicationId))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusSignsOpaquePublicationUrlsAndKeepsLegacyAliasFreeOfTheAccessKey() throws Exception {
        AccessGate gate = new AccessGate("a test-only secret");
        String publicationId = publish("appearance_a", 1, "original");
        when(pipeline.status("s1")).thenAnswer(call -> {
            var current = new HashMap<String, Object>(Map.of("assetId", ASSET_ID, "status", "READY",
                    "publicationId", publicationId));
            var legacy = new HashMap<String, Object>(Map.of("assetId", "pt.player.base", "status", "READY",
                    "generationVersion", 2));
            var pending = new HashMap<String, Object>(Map.of("assetId", "bg.hall.default", "status", "QUEUED"));
            return new HashMap<>(Map.of("assets", new ArrayList<>(List.of(current, legacy, pending))));
        });
        mvc = MockMvcBuilders.standaloneSetup(new AssetController(pipeline, store,
                mock(GameSessionRepository.class), AssetCoordinator.disabled(), gate)).build();
        mvc.perform(get("/api/sessions/s1/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets[0].url").value("/api/assets/s1/" + ASSET_ID + "?v="
                        + publicationId + "&t=" + gate.assetToken("s1")))
                .andExpect(jsonPath("$.assets[1].url").value("/api/assets/s1/pt.player.base?t=" + gate.assetToken("s1")))
                .andExpect(jsonPath("$.assets[2].url").isEmpty())
                .andExpect(content().string(not(containsString("a test-only secret"))));
    }

    @Test
    void publishingSameVersionAgainCannotReplaceItsBytes() throws Exception {
        String publicationId = publish("appearance_a", 1, "original");
        assertThrows(FileAlreadyExistsException.class, () -> store.saveVersioned("s1", ASSET_ID,
                "appearance_a", 1, FakeImageProvider.png("replacement", 8, 8), "image/png"));
        assertArrayEquals(FakeImageProvider.png("original", 8, 8),
                Files.readAllBytes(store.imagePath("s1", manifest.publications.get(publicationId).fileName()).orElseThrow()));
        try (var files = Files.list(directory.resolve("s1"))) {
            assertEquals(1, files.count(), "failed duplicate publication leaves no temporary file");
        }
    }

    @Test
    void competingPublicationWritersHaveOneWinnerWithoutPartialFiles() throws Exception {
        var entered = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> racePublish(entered, "first"));
            var second = executor.submit(() -> racePublish(entered, "second"));
            entered.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
        Path image = store.imagePath("s1", "v_appearance_a_1.png").orElseThrow();
        byte[] result = Files.readAllBytes(image);
        assertTrue(java.util.Arrays.equals(FakeImageProvider.png("first", 8, 8), result)
                || java.util.Arrays.equals(FakeImageProvider.png("second", 8, 8), result));
    }

    private boolean racePublish(CountDownLatch entered, String seed) throws Exception {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            store.saveVersioned("s1", ASSET_ID, "appearance_a", 1, FakeImageProvider.png(seed, 8, 8), "image/png");
            return true;
        } catch (FileAlreadyExistsException expected) {
            return false;
        }
    }

    @Test
    void publicationWritesValidateBothLogicalAndPhysicalIdentitiesBeforeWriting() {
        byte[] image = FakeImageProvider.png("original", 8, 8);
        assertThrows(IllegalArgumentException.class,
                () -> store.saveVersioned("s1", "../outside", "appearance_a", 1, image, "image/png"));
        assertThrows(IllegalArgumentException.class,
                () -> store.saveVersioned("s1", ASSET_ID, "../outside", 1, image, "image/png"));
        assertThrows(IllegalArgumentException.class,
                () -> store.saveVersioned("../outside", ASSET_ID, "appearance_a", 1, image, "image/png"));
        assertThrows(IllegalArgumentException.class,
                () -> store.saveVersioned("s1", ASSET_ID, "appearance_a", 0, image, "image/png"));
        assertThrows(java.io.IOException.class,
                () -> store.saveVersioned("s1", ASSET_ID, "appearance_a", 1, new byte[]{1, 2}, "image/png"));
    }
}
