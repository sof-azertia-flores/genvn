package com.genvn.llm;

public enum LlmPurpose {
    STORY_COMPILE,
    SCENE_GENERATE,
    ARC_CONTINUE,
    CHOICE_PROBABILITIES,
    /** Replace spare appearance-only designs after a scene has given one an identity. */
    SPARE_DESIGNS,
    /** Re-plan canon, bible and the unplayed beats when the player rejects where the plot went. */
    STORY_RESTRUCTURE
}
