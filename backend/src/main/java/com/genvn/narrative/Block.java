package com.genvn.narrative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One advanceable unit of the visual novel text box. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Block(
        /** narration | dialogue */
        String type,
        String speakerId,
        String speakerName,
        String text,
        String expression
) {
    public static Block narration(String text) {
        return new Block("narration", null, null, text, null);
    }

    public static Block dialogue(String speakerId, String speakerName, String text, String expression) {
        return new Block("dialogue", speakerId, speakerName, text, expression);
    }
}
