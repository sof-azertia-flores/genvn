package com.genvn.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Locale;

/**
 * One planned picture. The id is derived from stable story identity (location/character id,
 * variant) so the same picture is only ever requested once no matter how many beats or
 * speculative branches would like it. Character appearance is identified explicitly; prompt
 * wording and scheduling hints do not decide whether an existing picture can be reused.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssetSpec(
        String assetId,
        AssetKind kind,
        String subjectId,
        String subjectName,
        String variant,
        String prompt,
        String styleKey,
        /** First beat expected to need it; a scheduling hint, not a plot fact. */
        String beatId,
        /** Lower runs sooner. 0 = needed for the opening. */
        int priority,
        /** For variants: the base portrait this is edited from. */
        String dependsOn,
        /** GENERAL, or OUTCOME:SUCCESS / OUTCOME:FAILURE for pictures tied to a consequence. */
        String applicability,
        boolean landscape,
        /** Stable character appearance; null for backgrounds and unattributed legacy records. */
        String appearanceKey
) {
    public AssetSpec(String assetId, AssetKind kind, String subjectId, String subjectName, String variant,
                     String prompt, String styleKey, String beatId, int priority, String dependsOn,
                     String applicability, boolean landscape) {
        this(assetId, kind, subjectId, subjectName, variant, prompt, styleKey, beatId, priority,
                dependsOn, applicability, landscape, null);
    }

    /** Pure appearance reserves run only when a worker has no foreground work. */
    public static final int IDLE_PRIORITY = 1000;

    public boolean idlePreparation() { return priority >= IDLE_PRIORITY; }

    public static final String GENERAL = "GENERAL";
    public static final String BASE_VARIANT = "base";
    public static final String DEFAULT_VARIANT = "default";

    public static String backgroundId(String locationId, String variant) {
        return "bg." + slug(locationId) + "." + slug(variant);
    }

    public static String portraitId(String characterId, String variant) {
        return "pt." + slug(characterId) + "." + slug(variant);
    }

    public static String characterCardId(String characterId) {
        return "card." + slug(characterId) + ".default";
    }

    /** Story identities must survive asset-key construction unchanged and without truncation. */
    public static boolean safeSubjectId(String id) {
        return id != null && id.matches("[a-z0-9\\u4e00-\\u9fff][a-z0-9_\\-\\u4e00-\\u9fff]{0,47}")
                && id.equals(slug(id));
    }

    /** Only characters that are safe in a URL segment and a file name. */
    public static String slug(String raw) {
        if (raw == null) return "unknown";
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-\\u4e00-\\u9fff]+", "_");
        s = s.replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "unknown";
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    public boolean appliesTo(String outcome) {
        if (applicability == null || GENERAL.equals(applicability)) return true;
        return applicability.equalsIgnoreCase("OUTCOME:" + outcome);
    }

    /** Same picture, newer wording: used only for records that have not produced a file yet. */
    public AssetSpec withPrompt(String newPrompt) {
        return new AssetSpec(assetId, kind, subjectId, subjectName, variant, newPrompt, styleKey, beatId,
                priority, dependsOn, applicability, landscape, appearanceKey);
    }

    public AssetSpec withPriority(int newPriority) {
        return new AssetSpec(assetId, kind, subjectId, subjectName, variant, prompt, styleKey, beatId,
                newPriority, dependsOn, applicability, landscape, appearanceKey);
    }

    /** A spare appearance was cast: relabel and prioritize it without changing its drawn identity. */
    public AssetSpec assignedTo(String name, String firstBeat, int newPriority) {
        return new AssetSpec(assetId, kind, subjectId, name, variant, prompt, styleKey, firstBeat,
                newPriority, dependsOn, applicability, landscape, appearanceKey);
    }

    public AssetSpec withAppearanceKey(String key) {
        return new AssetSpec(assetId, kind, subjectId, subjectName, variant, prompt, styleKey, beatId,
                priority, dependsOn, applicability, landscape, key);
    }
}
