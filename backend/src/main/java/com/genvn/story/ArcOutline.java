package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Produced by the continuation service when the current spine is nearly exhausted. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArcOutline(
        String arcTitle,
        String premise,
        String majorConflict,
        List<StoryBeat> beats,
        List<String> threadsToAdvance,
        List<String> threadsToResolve,
        List<String> optionalNewThreads
) {
    public ArcOutline {
        beats = beats == null ? List.of() : List.copyOf(beats);
        threadsToAdvance = threadsToAdvance == null ? List.of() : List.copyOf(threadsToAdvance);
        threadsToResolve = threadsToResolve == null ? List.of() : List.copyOf(threadsToResolve);
        optionalNewThreads = optionalNewThreads == null ? List.of() : List.copyOf(optionalNewThreads);
    }
}
