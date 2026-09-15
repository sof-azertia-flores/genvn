package com.genvn.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** The lifecycle of one planned picture, persisted in the session's asset manifest. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssetRecord {
    /** Stable identity of this appearance's lifecycle, independent of its public logical asset id. */
    public String recordVersionId = java.util.UUID.randomUUID().toString().replace("-", "");
    /** Exact base lifecycle used by this card/pose; never follows a later active-version switch. */
    public String referenceVersionId;
    /** Immutable published file identity. Previous publications remain in the manifest archive. */
    public String publicationId;
    public AssetSpec spec;
    public AssetStatus status = AssetStatus.PLANNED;
    public int attempts = 0;
    /** Once manually retried, exactly one additional attempt per accepted click, persisted across restart. */
    public int manualAttemptLimit = 0;
    /** A vanished READY file gets a bounded replacement cycle without erasing spent attempts. */
    public int replacementAttemptLimit = 0;
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
    /** Reserved sequence of the most recently published file; failed attempts may leave gaps. */
    public int generationVersion = 0;
    /** Durably reserved before a call, so a crash can never reuse an orphaned publication path. */
    public int publicationSequence = 0;

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
