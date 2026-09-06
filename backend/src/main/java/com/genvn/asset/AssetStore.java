package com.genvn.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.config.GenvnProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Local asset library: data/assets/&lt;sessionId&gt;/{manifest.json, *.png}.
 *
 * A picture is written to a temp file, decoded to prove it is a complete image with real
 * dimensions, then atomically published. Only a file that passed that check is ever READY.
 * Every path is built from a validated id and checked to stay inside the library root, so
 * neither a model's output nor a client parameter can name an arbitrary file.
 */
@Component
public class AssetStore {

    private static final Logger log = LoggerFactory.getLogger(AssetStore.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.\\-\\u4e00-\\u9fff]{1,140}");
    private static final Pattern SAFE_SESSION = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    public record Stored(String fileName, String mimeType, int width, int height, long bytes, String contentHash) {}

    private final ObjectMapper mapper;
    private final Path root;

    @Autowired
    public AssetStore(ObjectMapper mapper, GenvnProperties properties) {
        this(mapper, Path.of(properties.getDataDir(), "assets"));
    }

    public AssetStore(ObjectMapper mapper, Path root) {
        this.mapper = mapper;
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            log.warn("Could not create asset directory {}: {}", this.root, e.getMessage());
        }
    }

    public Path root() {
        return root;
    }

    public static boolean safeAssetId(String id) {
        return id != null && SAFE_ID.matcher(id).matches() && !id.contains("..");
    }

    public static boolean safeSessionId(String id) {
        return id != null && SAFE_SESSION.matcher(id).matches();
    }

    private Path sessionDir(String sessionId) {
        if (!safeSessionId(sessionId)) throw new IllegalArgumentException("invalid session id");
        Path dir = root.resolve(sessionId).normalize();
        if (!dir.startsWith(root)) throw new IllegalArgumentException("session path escapes the asset root");
        return dir;
    }

    private Path inside(Path dir, String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new IllegalArgumentException("invalid asset file name");
        }
        Path p = dir.resolve(fileName).normalize();
        if (!p.startsWith(dir)) throw new IllegalArgumentException("asset path escapes its session directory");
        return p;
    }

    // ------------------------------------------------------------------ manifest

    public Optional<AssetManifest> readManifest(String sessionId) {
        Path file = sessionDir(sessionId).resolve("manifest.json");
        if (Files.notExists(file)) return Optional.empty();
        try {
            if (!Files.isRegularFile(file)) throw new IOException("manifest is not a readable regular file");
            AssetManifest m = mapper.readValue(file.toFile(), AssetManifest.class);
            if (m.records == null) m.records = new java.util.LinkedHashMap<>();
            if (m.budget == null) m.budget = new AssetManifest.Budget();
            return Optional.of(m);
        } catch (IOException e) {
            log.warn("Could not read asset manifest for {}: {}", sessionId, e.toString());
            // An unreadable budget is not an unused budget. Never silently replace it with zero.
            throw new UncheckedIOException("Cannot read asset budget for " + sessionId, e);
        }
    }

    public void writeManifest(AssetManifest manifest) {
        Path dir = sessionDir(manifest.sessionId);
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, "manifest-", ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), manifest);
            move(tmp, dir.resolve("manifest.json"));
        } catch (IOException e) {
            log.warn("Could not persist asset manifest for {}: {}", manifest.sessionId, e.toString());
            throw new UncheckedIOException("Cannot persist asset budget for " + manifest.sessionId, e);
        } finally {
            cleanup(tmp);
        }
    }

    // ------------------------------------------------------------------ images

    /** Decode-validate, then publish atomically. Throws if the bytes are not a complete image. */
    public Stored save(String sessionId, String assetId, byte[] bytes, String mimeType) throws IOException {
        if (!safeAssetId(assetId)) throw new IllegalArgumentException("invalid asset id");
        Path dir = sessionDir(sessionId);
        Files.createDirectories(dir);
        BufferedImage image = decode(bytes);
        String ext = extensionFor(mimeType);
        String fileName = assetId + "." + ext;
        Path target = inside(dir, fileName);
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, assetId + "-", "." + ext + ".tmp");
            Files.write(tmp, bytes);
            // Re-read the temp file: what we publish must itself decode, not just the buffer.
            if (decode(Files.readAllBytes(tmp)) == null) throw new IOException("temp file did not decode");
            move(tmp, target);
        } finally {
            cleanup(tmp);
        }
        return new Stored(fileName, mimeType, image.getWidth(), image.getHeight(), bytes.length, sha256(bytes));
    }

    /** The file for a READY record, only if it still exists and still decodes. */
    public Optional<Path> imagePath(String sessionId, String fileName) {
        try {
            Path p = inside(sessionDir(sessionId), fileName);
            if (!Files.isRegularFile(p)) return Optional.empty();
            return Optional.of(p);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Cheap header-level check used at restart: exists, non-empty, decodes to real dimensions. */
    public boolean validate(String sessionId, String fileName) {
        Optional<Path> p = imagePath(sessionId, fileName);
        if (p.isEmpty()) return false;
        try {
            byte[] bytes = Files.readAllBytes(p.get());
            return bytes.length > 0 && decode(bytes) != null;
        } catch (IOException e) {
            return false;
        }
    }

    public void deleteSession(String sessionId) {
        Path dir;
        try {
            dir = sessionDir(sessionId);
        } catch (RuntimeException e) {
            return;
        }
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", p, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Could not remove asset directory for {}: {}", sessionId, e.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Null means a usable cutout. Do not mistake a PNG container for transparent image content. */
    public static String transparencyProblem(byte[] bytes) throws IOException {
        BufferedImage image = decode(bytes);
        if (!image.getColorModel().hasAlpha()) return "返回图像没有 Alpha 透明通道";
        boolean transparent = false;
        boolean visible = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                transparent |= alpha == 0;
                visible |= alpha > 0;
                if (transparent && visible) return null;
            }
        }
        return visible ? "返回图像未包含完全透明的背景像素" : "返回图像完全透明，人物内容为空";
    }

    public static boolean hasTransparentContent(byte[] bytes) throws IOException {
        return transparencyProblem(bytes) == null;
    }

    /** Larger than any picture the planner asks for; protects the decoder from a hostile header. */
    private static final long MAX_PIXELS = 48L * 1024 * 1024;
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("empty image payload");
        if (bytes.length > MAX_BYTES) throw new IOException("image payload too large (" + bytes.length + " bytes)");
        checkDimensions(bytes);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            throw new IOException("payload is not a decodable image");
        }
        return img;
    }

    /** Reads only the header: a declared size the decoder would choke on is rejected first. */
    private static void checkDimensions(byte[] bytes) throws IOException {
        try (javax.imageio.stream.ImageInputStream in =
                     new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("payload is not a decodable image");
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long w = reader.getWidth(0);
                long h = reader.getHeight(0);
                if (w <= 0 || h <= 0) throw new IOException("payload is not a decodable image");
                if (w * h > MAX_PIXELS) throw new IOException("image dimensions too large (" + w + "x" + h + ")");
            } finally {
                reader.dispose();
            }
        }
    }

    public static String extensionFor(String mimeType) {
        if (mimeType == null) return "png";
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
    }

    public static String mimeFor(String fileName) {
        String f = fileName == null ? "" : fileName.toLowerCase();
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void cleanup(Path tmp) {
        if (tmp == null) return;
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
