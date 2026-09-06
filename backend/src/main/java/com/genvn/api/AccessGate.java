package com.genvn.api;

import com.genvn.config.GenvnProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The one door into the API.
 *
 * With no key configured (the local default) the door stands open. With {@code genvn.access-key}
 * set, every {@code /api} call must carry the key in the {@value #HEADER} header. Three things are
 * let through without it:
 * <ul>
 *   <li>CORS preflights, which carry no custom headers by design;</li>
 *   <li>{@code GET /api/access}, which tells the browser whether a key is needed and whether the
 *       one it holds is right;</li>
 *   <li>picture bytes, which an {@code <img>} tag fetches without headers. Their URLs carry a
 *       token derived from the key and the session id: good for that session's pictures and
 *       for nothing else, and never the key itself.</li>
 * </ul>
 * Comparisons are constant-time.
 */
@Component
public class AccessGate {

    public static final String HEADER = "X-Genvn-Key";
    public static final String ASSET_TOKEN_PARAM = "t";

    private static final AccessGate OPEN = new AccessGate("");

    private final byte[] key;

    @Autowired
    public AccessGate(GenvnProperties properties) {
        this(properties.getAccessKey());
    }

    public AccessGate(String key) {
        this.key = key == null ? new byte[0] : key.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** A gate with no key: the local default, and what tests and hand-wired controllers use. */
    public static AccessGate open() {
        return OPEN;
    }

    public boolean required() {
        return key.length > 0;
    }

    /** True when the presented value is the configured key, or when no key is configured. */
    public boolean matches(String presented) {
        if (!required()) return true;
        if (presented == null) return false;
        return MessageDigest.isEqual(key, presented.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Token an {@code <img>} may present for this session's pictures; empty when the gate is open. */
    public String assetToken(String sessionId) {
        if (!required() || sessionId == null) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(("asset:" + sessionId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    public boolean assetTokenMatches(String sessionId, String token) {
        if (!required()) return true;
        if (sessionId == null || token == null) return false;
        return MessageDigest.isEqual(assetToken(sessionId).getBytes(StandardCharsets.UTF_8),
                token.trim().getBytes(StandardCharsets.UTF_8));
    }
}
