package com.genvn.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** The lifecycle of one planned picture, persisted in the session's asset manifest. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssetRecord {
    public AssetSpec spec;
    public AssetStatus status = AssetStatus.PLANNED;
    public int attempts = 0;
    /** Once manually retried, exactly one additional attempt per accepted click, persisted across restart. */
    public int manualAttemptLimit = 0;
    public String failureReason;
    public boolean retryable = true;
    /** Paused because this picture's reference was unavailable; the reference can wake it on success. */
    public boolean referenceUnavailable = false;

    public String fileName;
    public String mimeType;
    public int width;
    public int height;
    public long bytes;
    public String contentHash;
    public String model;
    public String provider;
    /** Bumps every time a new file is published for this id, so clients can cache by id+version. */
    public int generationVersion = 0;

    public String plannedAt;
    public String queuedAt;
    public String startedAt;
    public String readyAt;
    /** When a committed scene first referenced this picture. Compared with readyAt for "in time?". */
    public String firstNeededAt;
    public int reuseCount = 0;

    public AssetRecord() {}

    public AssetRecord(AssetSpec spec) {
        this.spec = spec;
        this.plannedAt = Instant.now().toString();
    }

    public boolean isTerminalFailure() {
        return status == AssetStatus.FAILED;
    }

    public boolean isPendingWork() {
        return status == AssetStatus.QUEUED || status == AssetStatus.GENERATING;
    }
}
