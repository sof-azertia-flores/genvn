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
    public Budget budget = new Budget();

    public void touch() {
        version++;
        updatedAt = Instant.now().toString();
    }

    public AssetRecord get(String assetId) {
        return assetId == null ? null : records.get(assetId);
    }
}
