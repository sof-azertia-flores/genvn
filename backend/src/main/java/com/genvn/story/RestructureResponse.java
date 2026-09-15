package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A revised story framework, produced when the player rejects where the plot went.
 *
 * Unlike {@link CompilerResponse} this is a REVISION: {@code authorCanonFacts} and {@code bible}
 * are the complete corrected versions (anything omitted is dropped on purpose), while
 * {@code spine} carries only the beats still to come -- the engine keeps the completed ones and
 * splices this tail after them.
 *
 * @param changeSummary one sentence for the player's progress log. Never persisted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RestructureResponse(
        List<String> authorCanonFacts,
        StoryBible bible,
        StorySpine spine,
        List<String> newThreads,
        String changeSummary
) {
    public RestructureResponse {
        authorCanonFacts = authorCanonFacts == null ? List.of() : List.copyOf(authorCanonFacts);
        newThreads = newThreads == null ? List.of() : List.copyOf(newThreads);
    }
}
