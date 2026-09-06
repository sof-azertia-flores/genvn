package com.genvn.api;

/**
 * A die has already been cast for another choice on the current scene. A die the player has
 * seen is binding, so the game refuses to roll or resolve a different choice until that one
 * is played out. Maps to HTTP 409 / "roll_pending"; the client reloads and resumes from it.
 */
public class PendingRollException extends RuntimeException {

    private final String pendingChoiceId;

    public PendingRollException(String pendingChoiceId) {
        super("A die has already been cast for choice '" + pendingChoiceId
                + "' on this scene and is binding. Resolve that choice first.");
        this.pendingChoiceId = pendingChoiceId;
    }

    public String pendingChoiceId() {
        return pendingChoiceId;
    }
}
