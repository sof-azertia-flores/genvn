package com.genvn.game;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.genvn.dice.CheckResult;
import com.genvn.narrative.Block;
import com.genvn.narrative.SceneBundle;
import com.genvn.story.ArcOutline;
import com.genvn.story.CompiledStory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One saved play-through: the compiled story, the canonical state, and where the player is. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GameSession {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HistoryEntry {
        public String sceneId;
        public String beatId;
        public String choiceText;
        public String rollSummary;
        public String openingLine;
        /** Full committed dialogue/narration; absent in saves made by earlier versions. */
        public List<Block> blocks = List.of();
        public String at;

        public HistoryEntry() {}

        public HistoryEntry(String sceneId, String beatId, String choiceText, String rollSummary, String openingLine) {
            this.sceneId = sceneId;
            this.beatId = beatId;
            this.choiceText = choiceText;
            this.rollSummary = rollSummary;
            this.openingLine = openingLine;
            this.at = Instant.now().toString();
        }
    }

    /**
     * A die cast for a checked choice on the current scene, not yet resolved into a scene.
     * Persisted so a retry, a page refresh or a backend restart reuses it: the number the
     * player saw is the number the story uses. Cleared on commit.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PendingRoll {
        public String sceneId;
        public String choiceId;
        public CheckResult roll;
        public String at;

        public PendingRoll() {}

        public PendingRoll(String sceneId, String choiceId, CheckResult roll) {
            this.sceneId = sceneId;
            this.choiceId = choiceId;
            this.roll = roll;
            this.at = Instant.now().toString();
        }
    }

    public String id;
    public String title;
    public String createdAt = Instant.now().toString();
    public String updatedAt = Instant.now().toString();
    public CompiledStory story;
    public GameState state;
    public SceneBundle currentScene;
    /**
     * Head of the scene tree: the node id of {@link #currentScene}. Node ids are scene ids
     * (or a parent__choice__outcome key for a candidate that was generated and not yet played).
     * Absent in saves written before the tree existed.
     */
    public String currentNodeId;
    public List<HistoryEntry> history = new ArrayList<>();
    public int sceneCounter = 0;
    public boolean finished = false;
    /** A process-local durability signal. Reloading an actual file starts healthy. */
    @JsonIgnore
    public volatile boolean saveHealthy = true;
    @JsonIgnore
    public volatile boolean deleted = false;
    @JsonIgnore
    public volatile boolean continuationPending = false;
    /**
     * Bumped on rewind so an in-flight arc plan from a previous path cannot write back here.
     * Process-local: a restart has no in-flight planner.
     */
    @JsonIgnore
    public volatile int continuationEpoch;
    /** Next-arc outline generated in the background; consumed when the current spine runs out. */
    public volatile ArcOutline pendingArc;
    /** See {@link PendingRoll}. Null whenever no die is outstanding. */
    public volatile PendingRoll pendingRoll;
    /**
     * Dice cast for every checked choice of {@link #currentScene} the moment that scene became
     * current, keyed by choice id. Casting ahead lets the prefetch write only the outcome that
     * will actually happen. Nothing here is shown until the player picks the choice and the
     * reveal runs; a shown die is then recorded in {@link #pendingRoll} and becomes binding.
     * Absent in older saves, in which case dice are cast on first use.
     */
    public Map<String, CheckResult> sceneDice = new LinkedHashMap<>();
    /**
     * The choice whose scene is being generated right now, or null. Set and cleared under the
     * session monitor; lets the monitor be RELEASED during the model call while a second,
     * concurrent click still gets an immediate conflict instead of a duplicate generation.
     */
    @JsonIgnore
    public volatile String resolvingChoiceId;

    public void touch() {
        updatedAt = Instant.now().toString();
    }
}
