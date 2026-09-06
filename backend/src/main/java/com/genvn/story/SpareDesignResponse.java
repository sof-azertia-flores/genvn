package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Wire shape for a batch of replacement appearance-only designs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpareDesignResponse(List<PreparedVisual> preparedVisuals) {
    public SpareDesignResponse {
        preparedVisuals = preparedVisuals == null ? List.of() : List.copyOf(preparedVisuals);
    }
}
