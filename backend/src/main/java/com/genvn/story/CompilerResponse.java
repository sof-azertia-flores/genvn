package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Wire shape the Story Compiler expects back from the model. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompilerResponse(
        List<String> authorCanonFacts,
        StoryBible bible,
        StorySpine spine,
        String openingLocationId,
        List<String> continuityThreads,
        List<PreparedVisual> preparedVisuals
) {
    public CompilerResponse {
        preparedVisuals = preparedVisuals == null ? List.of() : List.copyOf(preparedVisuals);
    }

    public CompilerResponse(List<String> authorCanonFacts, StoryBible bible, StorySpine spine,
                            String openingLocationId, List<String> continuityThreads) {
        this(authorCanonFacts, bible, spine, openingLocationId, continuityThreads, List.of());
    }
}
