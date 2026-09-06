package com.genvn.speculation;

/** Identifies one speculative candidate: "from this scene, if the player picks C and it succeeds". */
public record BranchKey(String sceneId, String choiceId, String outcome) {
    public String asString() {
        return sceneId + "::" + choiceId + "::" + outcome;
    }
}
