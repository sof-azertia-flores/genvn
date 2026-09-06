package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One phase of a story arc. A beat is an event, not a task: {@code turn} names what changes
 * irreversibly when it lands, which is what keeps the story from circling in place.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoryBeat(
        String id,
        String title,
        String purpose,
        String completionConditions,
        String importance,
        /** What is different once this beat lands: a revelation, a loss, a reversal. Null in older saves. */
        String turn
) {
    public StoryBeat(String id, String title, String purpose, String completionConditions, String importance) {
        this(id, title, purpose, completionConditions, importance, null);
    }
}
