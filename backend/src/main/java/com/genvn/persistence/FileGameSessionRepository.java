package com.genvn.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.config.GenvnProperties;
import com.genvn.game.GameSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-first save: one JSON file per session under data/sessions, with an in-memory map as the
 * live object graph (so a session being played is a single shared instance, not a fresh copy
 * per request). Writes use a unique temp file and an atomic move where supported, preserving
 * the last good save if serialization fails.
 */
@Repository
public class FileGameSessionRepository implements GameSessionRepository {

    private static final Logger log = LoggerFactory.getLogger(FileGameSessionRepository.class);

    private final ObjectMapper mapper;
    private final Path directory;
    private final Map<String, GameSession> live = new ConcurrentHashMap<>();

    public FileGameSessionRepository(ObjectMapper mapper, GenvnProperties properties) {
        this.mapper = mapper;
        this.directory = Path.of(properties.getDataDir(), "sessions");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            log.warn("Could not create save directory {}: {}. Sessions will stay in memory only.", directory, e.getMessage());
        }
    }

    @Override
    public void save(GameSession session) {
        // SessionService also holds this monitor while committing a choice. Keep the monitor
        // through serialization and replacement so another save cannot publish an older snapshot
        // after a newer one, or serialize collections while a choice is changing them.
        synchronized (session) {
            // A background arc or an already-started request may still hold the deleted object.
            // Check under the same monitor as delete before it can rejoin the live map.
            if (session.deleted) return;
            GameSession existing = live.putIfAbsent(session.id, session);
            if (existing != null && existing != session) {
                session.saveHealthy = false;
                log.warn("Refusing to overwrite the live session {} with a different instance", session.id);
                return;
            }
            session.touch();
            Path tmp = null;
            try {
                Files.createDirectories(directory);
                Path target = directory.resolve(session.id + ".json");
                tmp = Files.createTempFile(directory, session.id + "-", ".json.tmp");
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), session);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                session.saveHealthy = true;
            } catch (Exception e) {
                // Play can continue in memory, but the API must tell the player it is not saved.
                session.saveHealthy = false;
                log.warn("Could not persist session {}: {}", session.id, e.toString());
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException e) {
                        log.warn("Could not remove temporary save for session {}: {}", session.id, e.toString());
                    }
                }
            }
        }
    }

    @Override
    public Optional<GameSession> find(String id) {
        // Only one cold read may publish the canonical instance. This mapping function must
        // never acquire a session monitor: save takes that monitor before accessing this map.
        return Optional.ofNullable(live.computeIfAbsent(id, this::readFromDisk));
    }

    private GameSession readFromDisk(String id) {
        Path file = directory.resolve(id + ".json");
        if (!Files.isRegularFile(file)) return null;
        try {
            GameSession session = mapper.readValue(file.toFile(), GameSession.class);
            if (!id.equals(session.id)) {
                log.warn("Could not read session {}: save contains a different session id", id);
                return null;
            }
            return session;
        } catch (Exception e) {
            log.warn("Could not read session {}: {}", id, e.toString());
            return null;
        }
    }

    @Override
    public List<SessionSummary> list() {
        List<SessionSummary> out = new ArrayList<>();
        // Memory-only sessions must remain selectable after a temporary persistence failure.
        Set<String> ids = new LinkedHashSet<>(live.keySet());
        if (Files.isDirectory(directory)) {
            try (var stream = Files.list(directory)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .map(p -> p.getFileName().toString().replaceAll("\\.json$", ""))
                        .forEach(ids::add);
            } catch (IOException e) {
                log.warn("Could not list sessions: {}", e.toString());
            }
        }
        for (String id : ids) {
            find(id).ifPresent(s -> {
                synchronized (s) {
                    out.add(new SessionSummary(
                            s.id, s.title, s.createdAt, s.updatedAt,
                            s.state != null && s.state.player != null ? s.state.player.name : "?",
                            s.state != null ? s.state.storyProgress.scenesPlayed : 0,
                            s.finished));
                }
            });
        }
        out.sort(Comparator.comparing((SessionSummary s) -> s.updatedAt()).reversed());
        return out;
    }

    @Override
    public boolean delete(String id) {
        GameSession session = find(id).orElse(null);
        if (session != null) {
            synchronized (session) {
                if (session.deleted) return false;
                try {
                    Files.deleteIfExists(directory.resolve(id + ".json"));
                    session.deleted = true;
                    live.remove(id, session);
                    return true;
                } catch (IOException e) {
                    // Keep the same playable object if the on-disk deletion could not complete.
                    log.warn("Could not delete session {}: {}", id, e.toString());
                    return false;
                }
            }
        }
        // Corrupt saves cannot be loaded, but should still be removable by their file id.
        try {
            return Files.deleteIfExists(directory.resolve(id + ".json"));
        } catch (IOException e) {
            log.warn("Could not delete session {}: {}", id, e.toString());
            return false;
        }
    }
}
