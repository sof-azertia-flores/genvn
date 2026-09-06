package com.genvn.narrative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Non-narrative bookkeeping, shown in the dev inspector. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SceneMeta(
        String generatedBy,
        boolean fromSpeculativeCache,
        long generationMillis,
        /** NONE | SUCCESS | FAILURE -- which outcome branch this scene was written for. */
        String outcomeContext,
        String sourceChoiceId,
        int repairAttempts
) {
    public SceneMeta withCacheHit(boolean hit) {
        return new SceneMeta(generatedBy, hit, generationMillis, outcomeContext, sourceChoiceId, repairAttempts);
    }
}
