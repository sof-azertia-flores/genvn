package com.genvn.asset;

import com.genvn.story.NpcProfile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/** Stable identity of the appearance actually supplied to the base sprite generator. */
public final class AppearanceIdentity {
    private AppearanceIdentity() {}

    public static String key(NpcProfile character, String style) {
        return key(character.id(), effectiveAppearance(character), style);
    }

    public static String effectiveAppearance(NpcProfile character) {
        String visual = character.visualDescription();
        return visual != null && !visual.isBlank() ? visual : character.description();
    }

    public static String key(String subjectId, String appearance, String style) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            // Length framing avoids ambiguous joins, including text containing delimiters.
            for (String part : new String[]{"appearance-v1", subjectId, appearance, style}) {
                byte[] bytes = normalize(part).getBytes(StandardCharsets.UTF_8);
                hash.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .replaceAll("(?U)\\s+", " ").trim();
    }
}
