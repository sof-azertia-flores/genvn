package com.genvn;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.genvn.config.GenvnProperties;
import com.genvn.game.GameSession;
import com.genvn.persistence.FileGameSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileGameSessionRepositoryTest {

    @TempDir
    Path data;

    @Test
    void concurrentColdLoadsShareOneSessionAndDoNotLoseCommittedUpdates() throws Exception {
        GenvnProperties properties = properties();
        ObjectMapper initialMapper = new ObjectMapper();
        new FileGameSessionRepository(initialMapper, properties).save(session("shared"));

        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch releaseReads = new CountDownLatch(1);
        AtomicInteger diskReads = new AtomicInteger();
        ObjectMapper coldMapper = new ObjectMapper() {
            @Override
            public <T> T readValue(File source, Class<T> type) throws IOException {
                diskReads.incrementAndGet();
                firstRead.countDown();
                await(releaseReads);
                return super.readValue(source, type);
            }
        };
        FileGameSessionRepository repository = new FileGameSessionRepository(coldMapper, properties);
        int callers = 8;
        CountDownLatch started = new CountDownLatch(callers);
        var pool = Executors.newFixedThreadPool(callers);
        List<Future<GameSession>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    started.countDown();
                    GameSession loaded = repository.find("shared").orElseThrow();
                    // This is the same lock-and-save protocol used by SessionService.choose.
                    for (int turn = 0; turn < 5; turn++) {
                        synchronized (loaded) {
                            loaded.sceneCounter++;
                            repository.save(loaded);
                        }
                    }
                    return loaded;
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(firstRead.await(5, TimeUnit.SECONDS));
            releaseReads.countDown();
            GameSession canonical = futures.get(0).get(10, TimeUnit.SECONDS);
            for (Future<GameSession> future : futures) {
                assertSame(canonical, future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, diskReads.get(), "a cold session is deserialized only once");
            assertEquals(callers * 5, canonical.sceneCounter);
            GameSession restored = new FileGameSessionRepository(initialMapper, properties)
                    .find("shared").orElseThrow();
            assertEquals(callers * 5, restored.sceneCounter, "all committed progress survives restart");
            assertOnlySaveExists("shared");
        } finally {
            releaseReads.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousSavesNeverSerializeTheSameSessionConcurrently() throws Exception {
        CountDownLatch firstSerialization = new CountDownLatch(1);
        CountDownLatch overlappingSerialization = new CountDownLatch(1);
        CountDownLatch releaseSerialization = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(GameSession.class, new JsonSerializer<>() {
            @Override
            public void serialize(GameSession value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                if (active.incrementAndGet() > 1) overlappingSerialization.countDown();
                try {
                    firstSerialization.countDown();
                    await(releaseSerialization);
                    gen.writeStartObject();
                    gen.writeStringField("id", value.id);
                    gen.writeNumberField("sceneCounter", value.sceneCounter);
                    gen.writeEndObject();
                } finally {
                    active.decrementAndGet();
                }
            }
        });
        mapper.registerModule(module);
        FileGameSessionRepository repository = new FileGameSessionRepository(mapper, properties());
        GameSession session = session("writes");
        session.sceneCounter = 7;
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> repository.save(session));
            assertTrue(firstSerialization.await(5, TimeUnit.SECONDS));
            Future<?> second = pool.submit(() -> {
                secondStarted.countDown();
                repository.save(session);
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(overlappingSerialization.await(250, TimeUnit.MILLISECONDS),
                    "an in-flight save must finish before the next snapshot is serialized");
            releaseSerialization.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertTrue(session.saveHealthy);
            assertEquals(7, new ObjectMapper().readValue(savePath("writes").toFile(), GameSession.class).sceneCounter);
            assertOnlySaveExists("writes");
        } finally {
            releaseSerialization.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failedSerializationKeepsPreviousSaveCleansTempAndRecoversHealth() throws Exception {
        AtomicBoolean failSerialization = new AtomicBoolean();
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new JsonSerializer<>() {
            @Override
            public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                if (failSerialization.get()) throw new IOException("simulated interrupted save");
                gen.writeString(value);
            }
        });
        mapper.registerModule(module);
        FileGameSessionRepository repository = new FileGameSessionRepository(mapper, properties());
        GameSession session = session("recover");
        repository.save(session);
        byte[] saved = Files.readAllBytes(savePath("recover"));

        session.sceneCounter = 9;
        failSerialization.set(true);
        assertDoesNotThrow(() -> repository.save(session));
        assertFalse(session.saveHealthy);
        assertSame(session, repository.find(session.id).orElseThrow());
        assertArrayEquals(saved, Files.readAllBytes(savePath("recover")), "a failed replacement preserves the old save");
        assertOnlySaveExists("recover");

        failSerialization.set(false);
        repository.save(session);
        assertTrue(session.saveHealthy, "a later successful save clears the warning");
        assertEquals(9, new FileGameSessionRepository(new ObjectMapper(), properties())
                .find(session.id).orElseThrow().sceneCounter);
        assertFalse(new ObjectMapper().readTree(savePath("recover").toFile()).has("saveHealthy"),
                "persistence health is a runtime status, not a fact restored from an old save");
        assertOnlySaveExists("recover");
    }

    @Test
    void unavailableDirectoryLeavesANewSessionPlayableAndListedUntilSavingRecovers() throws Exception {
        Files.writeString(data.resolve("sessions"), "temporarily blocks the save directory");
        FileGameSessionRepository repository = new FileGameSessionRepository(new ObjectMapper(), properties());
        GameSession session = session("memory");
        repository.save(session);

        assertFalse(session.saveHealthy);
        assertSame(session, repository.find("memory").orElseThrow());
        assertEquals(List.of("memory"), repository.list().stream().map(s -> s.id()).toList());

        Files.delete(data.resolve("sessions"));
        repository.save(session);
        assertTrue(session.saveHealthy);
        assertTrue(Files.isRegularFile(savePath("memory")));
        assertOnlySaveExists("memory");
    }

    @Test
    void aLateBackgroundSaveCannotReviveADeletedSession() {
        FileGameSessionRepository repository = new FileGameSessionRepository(new ObjectMapper(), properties());
        GameSession session = session("deleted");
        repository.save(session);

        assertTrue(repository.delete(session.id));
        assertTrue(session.deleted);
        assertFalse(Files.exists(savePath(session.id)));

        // An arc completion callback can retain this reference long after the user deletes it.
        session.sceneCounter++;
        repository.save(session);
        assertTrue(repository.find(session.id).isEmpty());
        assertTrue(repository.list().isEmpty());
        assertFalse(Files.exists(savePath(session.id)));
    }

    @Test
    void failedDiskDeletionChangesNothingAndKeepsTheLiveSessionPlayable() throws Exception {
        FileGameSessionRepository repository = new FileGameSessionRepository(new ObjectMapper(), properties());
        GameSession session = session("kept");
        session.sceneCounter = 2;
        repository.save(session);
        byte[] saved = Files.readAllBytes(savePath(session.id));

        // A save directory is renamed out of the way before anything is unlinked, so a save
        // root that refuses the rename must leave the save exactly as it was.
        assumeTrue(sessions().toFile().setWritable(false), "needs a filesystem that honours read-only directories");
        try {
            assertFalse(repository.delete(session.id));
            assertFalse(session.deleted);
            assertSame(session, repository.find(session.id).orElseThrow());
            assertEquals(List.of(session.id), repository.list().stream().map(s -> s.id()).toList());
            assertArrayEquals(saved, Files.readAllBytes(savePath(session.id)),
                    "a delete that could not start must not remove part of the save");
        } finally {
            assertTrue(sessions().toFile().setWritable(true));
        }

        session.sceneCounter = 3;
        repository.save(session);
        assertTrue(session.saveHealthy);
        assertEquals(3, new FileGameSessionRepository(new ObjectMapper(), properties())
                .find(session.id).orElseThrow().sceneCounter);
        assertFalse(new ObjectMapper().readTree(savePath(session.id).toFile()).has("deleted"));
        assertOnlySaveExists(session.id);
    }

    @Test
    void deletingASaveRemovesItsWholeDirectory() throws Exception {
        FileGameSessionRepository repository = new FileGameSessionRepository(new ObjectMapper(), properties());
        GameSession session = session("tree");
        repository.save(session);
        // Stand in for the scene tree that lives beside session.json.
        Path nodes = sessions().resolve(session.id).resolve("nodes");
        Files.createDirectories(nodes);
        Files.writeString(nodes.resolve("scene_000.json"), "{}");

        assertTrue(repository.delete(session.id));
        assertFalse(Files.exists(sessions().resolve(session.id)));
        assertTrue(repository.find(session.id).isEmpty());
        try (var entries = Files.list(sessions())) {
            assertEquals(List.of(), entries.map(p -> p.getFileName().toString()).toList(),
                    "no leftovers, visible or hidden");
        }
    }

    @Test
    void aSaveFromAnEarlierVersionStillLoadsAndTheNextSaveMigratesIt() throws Exception {
        GenvnProperties properties = properties();
        Files.createDirectories(sessions());
        GameSession original = session("legacy");
        original.sceneCounter = 11;
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(legacyPath("legacy").toFile(), original);

        FileGameSessionRepository repository = new FileGameSessionRepository(new ObjectMapper(), properties);
        assertTrue(repository.hasLegacyLayout("legacy"));
        assertEquals(List.of("legacy"), repository.list().stream().map(s -> s.id()).toList(),
                "an old single-file save is still offered on the start screen");
        GameSession loaded = repository.find("legacy").orElseThrow();
        assertEquals(11, loaded.sceneCounter, "nothing is lost reading the old format");

        loaded.sceneCounter = 12;
        repository.save(loaded);
        assertFalse(repository.hasLegacyLayout("legacy"), "the old flat file is gone");
        assertFalse(Files.exists(legacyPath("legacy")));
        assertOnlySaveExists("legacy");
        assertEquals(12, new FileGameSessionRepository(new ObjectMapper(), properties)
                .find("legacy").orElseThrow().sceneCounter);
    }

    @Test
    void aLeftoverLegacyPathThatCannotBeDeletedMustNotDropTheDirectorySave() throws Exception {
        FileGameSessionRepository repository = new FileGameSessionRepository(new ObjectMapper(), properties());
        GameSession session = session("mixed");
        session.sceneCounter = 10;
        repository.save(session);

        Path leftover = legacyPath("mixed");
        Files.createDirectories(leftover);
        Files.writeString(leftover.resolve("stuck"), "an undeletable leftover");

        assertFalse(repository.delete(session.id), "the leftover must fail closed");
        assertFalse(session.deleted);
        assertEquals(10, new FileGameSessionRepository(new ObjectMapper(), properties())
                .find(session.id).orElseThrow().sceneCounter, "the latest directory save must still load");
    }

    @Test
    void aSessionIdThatIsNotASessionIdCanNeverReachTheFilesystem() throws Exception {
        Files.createDirectories(sessions());
        Path outsider = data.resolve("outside.json");
        new ObjectMapper().writeValue(outsider.toFile(), session("outside"));
        FileGameSessionRepository repository = new FileGameSessionRepository(new ObjectMapper(), properties());

        for (String id : List.of("../outside", "..", ".", "", "a/b", "a\\b", ".hidden", "x".repeat(65))) {
            assertTrue(repository.find(id).isEmpty(), "must not read through id '" + id + "'");
            assertFalse(repository.delete(id), "must not delete through id '" + id + "'");
        }
        assertTrue(Files.exists(outsider), "a save outside the save root is untouched");

        GameSession bad = session("../escape");
        repository.save(bad);
        assertFalse(bad.saveHealthy, "an unusable id is reported as unsaved rather than written anywhere");
        try (var entries = Files.list(sessions())) {
            assertEquals(List.of(), entries.map(p -> p.getFileName().toString()).toList());
        }
    }

    private GenvnProperties properties() {
        GenvnProperties properties = new GenvnProperties();
        properties.setDataDir(data.toString());
        return properties;
    }

    private static GameSession session(String id) {
        GameSession session = new GameSession();
        session.id = id;
        session.title = "Repository regression test";
        return session;
    }

    private Path sessions() {
        return data.resolve("sessions");
    }

    private Path savePath(String id) {
        return sessions().resolve(id).resolve("session.json");
    }

    /** Where a save written by an earlier version of the engine lives. */
    private Path legacyPath(String id) {
        return sessions().resolve(id + ".json");
    }

    /** The save root holds exactly this save, and its directory holds exactly session.json. */
    private void assertOnlySaveExists(String id) throws IOException {
        try (var entries = Files.list(sessions())) {
            assertEquals(List.of(id), entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
        try (var files = Files.list(sessions().resolve(id))) {
            assertEquals(List.of("session.json"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out waiting for concurrent work");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
