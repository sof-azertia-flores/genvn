package com.genvn.narrative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A proposed stat check attached to a Choice. The Runtime, not the LLM, resolves it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Check(String stat, int dc, String description) {
    public static final int MIN_DC = 8;
    public static final int MAX_DC = 18;
}
