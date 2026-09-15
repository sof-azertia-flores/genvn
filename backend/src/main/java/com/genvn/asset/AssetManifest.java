package com.genvn.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the asset pipeline knows about one session's pictures. Lives beside the images in
 * data/assets/&lt;sessionId&gt;/manifest.json and has its OWN version counter: an image finishing
 * never touches GameState.stateVersion, commits nothing, and re-rolls nothing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssetManifest {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Budget {
        /** Provider call attempts ever made for this session (survives restarts). */
        public int attemptsTotal = 0;
        /** Attempts in the current arc; reset when a new arc begins. */
        public int arcAttempts = 0;
        public int arcNumber = 1;
        /** How many assets the automatic first batch has queued so far. */
        public int firstBatchQueued = 0;
        public boolean paused = false;
        public String pauseReason;
    }

    public String sessionId;
    /** The look every prompt for this story shares. Fixed at first plan. */
    public String style;
    public String styleKey;
    public int version = 0;
    public String updatedAt;
    public Map<String, AssetRecord> records = new LinkedHashMap<>();
    /** All appearance lifecycles, including records not active on the current story route. */
    public Map<String, AssetRecord> versions = new LinkedHashMap<>();
    public Map<String, AssetPublication> publications = new LinkedHashMap<>();
    public Budget budget = new Budget();

    /** JSON duplicates shared objects; restore identity before any scheduler mutates a record. */
    public void normalizeVersions() {
        if (records == null) records = new LinkedHashMap<>();
        if (versions == null) versions = new LinkedHashMap<>();
        if (publications == null) publications = new LinkedHashMap<>();
        if (budget == null) budget = new Budget();
        Map<String, AssetRecord> normalized = new LinkedHashMap<>();
        for (AssetRecord record : versions.values()) register(normalized, record);
        // Active copies are authoritative if reading a manifest written before normalization.
        for (AssetRecord record : records.values()) register(normalized, record);
        versions = normalized;
        records.replaceAll((id, record) -> record == null ? null : versions.get(record.recordVersionId));
        records.values().removeIf(java.util.Objects::isNull);
        for (AssetRecord record : versions.values()) {
            record.publicationSequence = Math.max(record.publicationSequence, record.generationVersion);
            if (record.referenceVersionId == null && record.spec.dependsOn() != null) {
                AssetRecord base = records.get(record.spec.dependsOn());
                if (base != null && java.util.Objects.equals(base.spec.appearanceKey(), record.spec.appearanceKey())) {
                    record.referenceVersionId = base.recordVersionId;
                }
            }
            if (record.fileName != null) {
                if (record.publicationId == null) {
                    record.publicationId = "legacy_" + record.recordVersionId + "_" + Math.max(1, record.generationVersion);
                }
                publications.putIfAbsent(record.publicationId, new AssetPublication(record.publicationId,
                        record.spec.assetId(), record.fileName, record.mimeType));
            }
        }
    }

    private static void register(Map<String, AssetRecord> records, AssetRecord record) {
        if (record == null || record.spec == null) return;
        if (record.recordVersionId == null || record.recordVersionId.isBlank()) {
            record.recordVersionId = java.util.UUID.randomUUID().toString().replace("-", "");
        }
        records.put(record.recordVersionId, record);
    }

    public void touch() {
        version++;
        updatedAt = Instant.now().toString();
    }

    public AssetRecord get(String assetId) {
        return assetId == null ? null : records.get(assetId);
    }
}
