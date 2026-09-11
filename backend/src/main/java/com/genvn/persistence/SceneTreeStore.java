package com.genvn.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.config.GenvnProperties;
import com.genvn.story.CompiledStory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The on-disk scene tree for a save: {@code data/sessions/<id>/nodes/<nodeId>.json}, plus the
 * compiled story each node belongs to under {@code stories/<hash>.json}.
 *
 * Nodes are append-only. A node is never rewritten to erase a branch the player left, which is
 * the point: an outdated candidate is exactly what makes re-choosing instant later. Stories are
 * content-addressed and shared, so the arcs a long save accumulates cost one copy each rather
 * than one per scene.
 *
 * Every write goes to a temp file and is published with an atomic move, so a half-written node
 * is never readable. Reading a damaged node returns empty rather than throwing: a corrupt branch
 * must cost the player that branch, not the save.
 */
@Component
public class SceneTreeStore {

    private static final Logger log = LoggerFactory.getLogger(SceneTreeStore.class);

    static final String NODES_DIR = "nodes";
    static final String STORIES_DIR = "stories";

    /** Node ids are scene ids or parent__choice__outcome keys; neither may steer a path. */
    private static final Pattern SAFE_NODE_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,160}");
    private static final Pattern SAFE_HASH = Pattern.compile("[a-f0-9]{4,64}");

    private final ObjectMapper mapper;
    private final Path root;

    @Autowired
    public SceneTreeStore(ObjectMapper mapper, GenvnProperties properties) {
        this(mapper, Path.of(properties.getDataDir(), "sessions"));
    }

    public SceneTreeStore(ObjectMapper mapper, Path root) {
        this.mapper = mapper;
        this.root = root.toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ paths

    private Path sessionDir(String sessionId) {
        if (!FileGameSessionRepository.safeSessionId(sessionId)) {
            throw new IllegalArgumentException("invalid session id");
        }
        Path dir = root.resolve(sessionId).normalize();
        if (!dir.startsWith(root) || dir.equals(root)) {
            throw new IllegalArgumentException("session path escapes the save root");
        }
        return dir;
    }

    private Path nodeFile(String sessionId, String nodeId) {
        if (nodeId == null || !SAFE_NODE_ID.matcher(nodeId).matches()) {
            throw new IllegalArgumentException("invalid node id");
        }
        return sessionDir(sessionId).resolve(NODES_DIR).resolve(nodeId + ".json");
    }

    private Path storyFile(String sessionId, String hash) {
        if (hash == null || !SAFE_HASH.matcher(hash).matches()) {
            throw new IllegalArgumentException("invalid story hash");
        }
        return sessionDir(sessionId).resolve(STORIES_DIR).resolve(hash + ".json");
    }

    // ------------------------------------------------------------------ nodes

    /**
     * Publishes one node. Throws {@link UncheckedIOException} when it could not be written: the
     * caller decides whether that is fatal, exactly as an asset reservation does.
     */
    public void writeNode(String sessionId, SceneNode node) {
        if (node == null || node.nodeId == null) throw new IllegalArgumentException("a node needs an id");
        Path file = nodeFile(sessionId, node.nodeId);
        write(file, node, "node " + node.nodeId + " of session " + sessionId);
    }

    public Optional<SceneNode> readNode(String sessionId, String nodeId) {
        Path file;
        try {
            file = nodeFile(sessionId, nodeId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            SceneNode node = mapper.readValue(file.toFile(), SceneNode.class);
            if (node.nodeId == null || !node.nodeId.equals(nodeId)) {
                log.warn("Save {}: node file {} names a different node", sessionId, nodeId);
                return Optional.empty();
            }
            return Optional.of(node);
        } catch (Exception e) {
            // One unreadable branch must not take the save down with it.
            log.warn("Save {}: could not read node {}: {}", sessionId, nodeId, e.toString());
            return Optional.empty();
        }
    }

    /** Every readable node, ordered by id so the opening comes first. Unreadable ones are skipped. */
    public List<SceneNode> listNodes(String sessionId) {
        List<SceneNode> nodes = new ArrayList<>();
        Path dir;
        try {
            dir = sessionDir(sessionId).resolve(NODES_DIR);
        } catch (RuntimeException e) {
            return nodes;
        }
        if (!Files.isDirectory(dir)) return nodes;
        List<String> ids = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .forEach(ids::add);
        } catch (IOException e) {
            log.warn("Save {}: could not list the scene tree: {}", sessionId, e.toString());
            return nodes;
        }
        ids.sort(Comparator.naturalOrder());
        for (String id : ids) readNode(sessionId, id).ifPresent(nodes::add);
        return nodes;
    }

    /**
     * The child reached by {@code choiceId} (and, when given, this exact outcome) from
     * {@code parentNodeId}. A visited child wins over a prepared candidate for the same edge.
     */
    public Optional<SceneNode> findChild(String sessionId, String parentNodeId, String choiceId, String outcome) {
        SceneNode visited = null;
        SceneNode prepared = null;
        for (SceneNode node : listNodes(sessionId)) {
            if (!java.util.Objects.equals(parentNodeId, node.parentNodeId)) continue;
            if (!java.util.Objects.equals(choiceId, node.fromChoiceId)) continue;
            if (outcome != null && !outcome.equals(node.outcome)) continue;
            if (node.restorable()) visited = node;
            else prepared = node;
        }
        return Optional.ofNullable(visited != null ? visited : prepared);
    }

    public boolean hasNodes(String sessionId) {
        try {
            Path dir = sessionDir(sessionId).resolve(NODES_DIR);
            if (!Files.isDirectory(dir)) return false;
            try (var stream = Files.list(dir)) {
                return stream.anyMatch(p -> p.getFileName().toString().endsWith(".json"));
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ stories

    /**
     * Stores a compiled story if this save has not seen it before and returns its hash. Identical
     * stories share one file, so the common case -- every scene in an arc -- costs nothing.
     */
    public String writeStory(String sessionId, CompiledStory story) {
        byte[] bytes;
        try {
            bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(story);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("a compiled story must be serializable", e));
        }
        String hash = sha256(bytes);
        Path file = storyFile(sessionId, hash);
        if (Files.isRegularFile(file)) return hash;
        writeBytes(file, bytes, "story " + hash + " of session " + sessionId);
        return hash;
    }

    public Optional<CompiledStory> readStory(String sessionId, String hash) {
        Path file;
        try {
            file = storyFile(sessionId, hash);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), CompiledStory.class));
        } catch (Exception e) {
            log.warn("Save {}: could not read story {}: {}", sessionId, hash, e.toString());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void write(Path file, Object value, String what) {
        byte[] bytes;
        try {
            bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("could not serialize " + what, e));
        }
        writeBytes(file, bytes, what);
    }

    private void writeBytes(Path file, byte[] bytes, String what) {
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            tmp = Files.createTempFile(file.getParent(), "write-", ".json.tmp");
            Files.write(tmp, bytes);
            move(tmp, file);
        } catch (IOException e) {
            log.warn("Could not persist {}: {}", what, e.toString());
            throw new UncheckedIOException("Could not persist " + what, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.warn("Could not remove the temporary file for {}: {}", what, e.toString());
                }
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 must be available", e);
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
