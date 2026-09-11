package com.genvn.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.genvn.dice.CheckResult;
import com.genvn.game.GameState;
import com.genvn.narrative.SceneBundle;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One scene in a save's tree: either a scene that was played, or one that was pre-generated for
 * a choice the player did not take. An edge is a choice: {@link #parentNodeId} plus
 * {@link #fromChoiceId} says which choice in which scene leads here.
 *
 * A node id IS its scene id. Scene ids are minted from a counter that never goes backwards, so
 * every node ever created has a distinct, never-reused id, and {@code (parentNodeId, fromChoiceId,
 * outcome)} identifies a branch unambiguously for the life of the save -- which is what lets a
 * retained candidate stay valid across a rewind, where a state version could not.
 *
 * Dice are part of the node. Rewinding restores {@link #sceneDice} exactly, so re-taking the same
 * choice yields the same die and the same outcome: the story changes by choosing differently,
 * never by rolling again.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SceneNode {

    /** Same value as {@code scene.sceneId()}. Unique across the whole save, forever. */
    public String nodeId;
    /** The scene this one follows, or null for the opening. */
    public String parentNodeId;
    public String beatId;

    /** The choice in the parent scene that leads here. Null for the opening. */
    public String fromChoiceId;
    /** Kept verbatim so history reads the same after a rewind, without reloading the parent. */
    public String fromChoiceText;
    /** The engine's die for that choice, or null when the choice had no check. */
    public CheckResult roll;
    /** SUCCESS, FAILURE or NONE -- the outcome this scene was written for. */
    public String outcome;

    public SceneBundle scene;

    /**
     * Canonical state as it stood when this scene was committed. Null on a node that has only
     * ever been a prepared candidate: its state is produced by committing it, not stored ahead.
     */
    public GameState state;

    /** Sealed dice for this scene's own choices, keyed by choice id. Null until visited. */
    public Map<String, CheckResult> sceneDice;

    /** The compiled story as of this node, stored once per distinct story under stories/. */
    public String storyHash;

    /** False while this is a prepared candidate that play has never passed through. */
    public boolean visited;

    public String createdAt = Instant.now().toString();
    /** When play first committed this scene. Null while unvisited. */
    public String visitedAt;

    public SceneNode() {}

    /** A node for a scene that has just been committed. */
    public static SceneNode visited(String parentNodeId, SceneBundle scene, String fromChoiceId,
                                    String fromChoiceText, CheckResult roll, String outcome,
                                    GameState state, Map<String, CheckResult> sceneDice, String storyHash) {
        SceneNode node = prepared(parentNodeId, scene, fromChoiceId, fromChoiceText, roll, outcome, storyHash);
        node.markVisited(state, sceneDice);
        return node;
    }

    /** A node for a candidate that was generated ahead and may never be played. */
    public static SceneNode prepared(String parentNodeId, SceneBundle scene, String fromChoiceId,
                                     String fromChoiceText, CheckResult roll, String outcome, String storyHash) {
        SceneNode node = new SceneNode();
        node.nodeId = scene.sceneId();
        node.parentNodeId = parentNodeId;
        node.beatId = scene.beatId();
        node.fromChoiceId = fromChoiceId;
        node.fromChoiceText = fromChoiceText;
        node.roll = roll;
        node.outcome = outcome;
        node.scene = scene;
        node.storyHash = storyHash;
        node.visited = false;
        return node;
    }

    /** Promote a prepared node to one play has passed through. */
    public void markVisited(GameState state, Map<String, CheckResult> sceneDice) {
        this.state = state;
        this.sceneDice = sceneDice == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sceneDice);
        this.visited = true;
        if (this.visitedAt == null) this.visitedAt = Instant.now().toString();
    }

    /** True when this node can be rewound to: only a visited node carries the state to restore. */
    public boolean restorable() {
        return visited && state != null && scene != null;
    }
}
