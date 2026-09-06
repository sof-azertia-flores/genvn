package com.genvn.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A structured "I need a picture that does not exist yet" from the scene generator. It is a
 * request, not a command: the runtime validates the subject, sanitises the variant, applies
 * the budget, and only ever acts on it when the scene is COMMITTED -- never for a speculative
 * branch, so unchosen futures cost nothing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssetRequest(
        /** background | portrait */
        String kind,
        /** An established location id or character id. Unknown subjects are dropped. */
        String subjectId,
        /** e.g. "night", "after_fire", "afraid". Sanitised to a slug. */
        String variant,
        String description
) {}
