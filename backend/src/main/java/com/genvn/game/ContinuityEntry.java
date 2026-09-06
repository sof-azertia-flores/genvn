package com.genvn.game;

/** One item in the Continuity Ledger: a question the story has opened and not yet closed. */
public class ContinuityEntry {
    public static final String UNRESOLVED = "unresolved";
    public static final String PARTIALLY_RESOLVED = "partiallyResolved";
    public static final String RESOLVED = "resolved";

    public String id;
    public String description;
    public String status = UNRESOLVED;
    public String introducedAtScene;
    public String resolvedAtScene;

    public ContinuityEntry() {}

    public ContinuityEntry(String id, String description, String introducedAtScene) {
        this.id = id;
        this.description = description;
        this.introducedAtScene = introducedAtScene;
    }
}
