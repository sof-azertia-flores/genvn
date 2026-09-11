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
import java.util.regex.Pattern;

/**
 * Local-first save: one directory per session under data/sessions, holding session.json plus
 * (from the branching save format) the scene tree beside it. An in-memory map keeps the live
 * object graph, so a session being played is a single shared instance, not a fresh copy per
 * request. Writes use a unique temp file and an atomic move where supported, preserving the
 * last good save if serialization fails.
 *
 * Saves written by earlier versions are a single data/sessions/&lt;id&gt;.json file. Those are
 * still read as-is; the next successful save rewrites them into the directory layout and drops
 * the flat file, so no player loses a save to the format change.
 */
@Repository
public class FileGameSessionRepository implements GameSessionRepository {

    private static final Logger log = LoggerFactory.getLogger(FileGameSessionRepository.class);

    /** The session file inside a save directory. */
    static final String SESSION_FILE = "session.json";

    /**
     * Session ids are minted locally, but they also arrive as path variables. Every id that
     * reaches the filesystem is matched against this first: a save directory is deleted
     * recursively, so a traversal here would be a recursive delete of an arbitrary directory.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    private final ObjectMapper mapper;
    private final Path directory;
    private final Map<String, GameSession> live = new ConcurrentHashMap<>();

    public FileGameSessionRepository(ObjectMapper mapper, GenvnProperties properties) {
        this.mapper = mapper;
        this.directory = Path.of(properties.getDataDir(), "sessions").toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            log.warn("Could not create save directory {}: {}. Sessions will stay in memory only.", directory, e.getMessage());
        }
    }

    public static boolean safeSessionId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    // ------------------------------------------------------------------ paths

    /** The save directory for a session, proven to stay inside the save root. */
    private Path sessionDir(String id) {
        if (!safeSessionId(id)) throw new IllegalArgumentException("invalid session id");
        Path dir = directory.resolve(id).normalize();
        if (!dir.startsWith(directory) || dir.equals(directory)) {
            throw new IllegalArgumentException("session path escapes the save root");
        }
        return dir;
    }

    private Path sessionFile(String id) {
        return sessionDir(id).resolve(SESSION_FILE);
    }

    /** Where a save written by an earlier version lives. */
    private Path legacyFile(String id) {
        if (!safeSessionId(id)) throw new IllegalArgumentException("invalid session id");
        Path file = directory.resolve(id + ".json").normalize();
        if (!file.startsWith(directory)) throw new IllegalArgumentException("session path escapes the save root");
        return file;
    }

    /** True when this save is still in the single-file format and a save would migrate it. */
    public boolean hasLegacyLayout(String id) {
        try {
            return Files.isRegularFile(legacyFile(id));
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ write

    @Override
    public void save(GameSession session) {
        // SessionService also holds this monitor while committing a choice. Keep the monitor
        // through serialization and replacement so another save cannot publish an older snapshot
        // after a newer one, or serialize collections while a choice is changing them.
        synchronized (session) {
            // A background arc or an already-started request may still hold the deleted object.
            // Check under the same monitor as delete before it can rejoin the live map.
            if (session.deleted) return;
            if (!safeSessionId(session.id)) {
                // Nothing can be written for this id, and it must not enter the live map either.
                session.saveHealthy = false;
                log.warn("Refusing to persist a session with an unusable id");
                return;
            }
            GameSession existing = live.putIfAbsent(session.id, session);
            if (existing != null && existing != session) {
                session.saveHealthy = false;
                log.warn("Refusing to overwrite the live session {} with a different instance", session.id);
                return;
            }
            session.touch();
            Path tmp = null;
            try {
                Path dir = sessionDir(session.id);
                Files.createDirectories(dir);
                tmp = Files.createTempFile(dir, "session-", ".json.tmp");
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), session);
                move(tmp, dir.resolve(SESSION_FILE));
                session.saveHealthy = true;
                // The directory is now the good save, so the old flat file may go. A failure
                // here is harmless: reads prefer the directory, and the next save tries again.
                dropLegacy(session.id);
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

    private void dropLegacy(String id) {
        try {
            if (Files.deleteIfExists(legacyFile(id))) {
                log.info("Save {}: migrated to the directory save format", id);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Save {}: could not remove the old single-file save: {}", id, e.toString());
        }
    }

    // ------------------------------------------------------------------ read

    @Override
    public Optional<GameSession> find(String id) {
        if (!safeSessionId(id)) return Optional.empty();
        // Only one cold read may publish the canonical instance. This mapping function must
        // never acquire a session monitor: save takes that monitor before accessing this map.
        return Optional.ofNullable(live.computeIfAbsent(id, this::readFromDisk));
    }

    private GameSession readFromDisk(String id) {
        Path file;
        try {
            file = sessionFile(id);
            if (!Files.isRegularFile(file)) {
                // An older save that has not been rewritten yet is still perfectly playable.
                file = legacyFile(id);
                if (!Files.isRegularFile(file)) return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
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
                stream.forEach(path -> {
                    String name = path.getFileName().toString();
                    if (Files.isDirectory(path)) {
                        if (safeSessionId(name) && Files.isRegularFile(path.resolve(SESSION_FILE))) ids.add(name);
                    } else if (name.endsWith(".json")) {
                        String id = name.substring(0, name.length() - ".json".length());
                        if (safeSessionId(id)) ids.add(id);
                    }
                });
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

    // ------------------------------------------------------------------ delete

    @Override
    public boolean delete(String id) {
        if (!safeSessionId(id)) return false;
        GameSession session = find(id).orElse(null);
        if (session != null) {
            synchronized (session) {
                if (session.deleted) return false;
                // Keep the same playable object if the on-disk deletion could not complete.
                if (!purge(id)) return false;
                session.deleted = true;
                live.remove(id, session);
                return true;
            }
        }
        // Corrupt saves cannot be loaded, but should still be removable by their id.
        return existsOnDisk(id) && purge(id);
    }

    private boolean existsOnDisk(String id) {
        try {
            return Files.isDirectory(sessionDir(id)) || Files.isRegularFile(legacyFile(id));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Removes a save. A directory is renamed out of the way first, which is atomic on one
     * filesystem: either the save is gone as a whole, or nothing was touched at all and the
     * session stays playable. Only then are the bytes walked and unlinked, so a file that
     * refuses to go leaves invisible leftovers rather than a half-deleted save.
     *
     * @return true when nothing for this id is visible on disk any more.
     */
    private boolean purge(String id) {
        try {
            Path dir = sessionDir(id);
            if (Files.exists(dir)) {
                // A leading dot cannot be a session id, so a leftover is never listed or loaded.
                Path trash = directory.resolve("." + id + ".deleting-" + System.nanoTime());
                move(dir, trash);
                deleteRecursively(trash);
            }
            Files.deleteIfExists(legacyFile(id));
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete session {}: {}", id, e.toString());
            return false;
        }
    }

    /** Children before their directory. The tree is already invisible, so this is best effort. */
    private void deleteRecursively(Path root) {
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            log.warn("Could not walk {}: {}", root, e.toString());
            return;
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Could not delete {}: {}", path, e.toString());
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
