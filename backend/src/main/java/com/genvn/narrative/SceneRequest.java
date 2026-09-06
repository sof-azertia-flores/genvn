package com.genvn.narrative;

import com.genvn.dice.CheckResult;
import com.genvn.game.GameState;
import com.genvn.story.CompiledStory;

/**
 * @param state       the state this scene is written against: the canonical state, or a forked
 *                    copy when generating speculatively.
 * @param choice      the choice that led here, or null for the opening scene.
 * @param outcome     NONE / SUCCESS / FAILURE -- already decided by the engine.
 * @param checkResult the real roll, or null when this is a speculative candidate (no dice yet).
 */
public record SceneRequest(
        CompiledStory story,
        GameState state,
        Choice choice,
        String outcome,
        CheckResult checkResult,
        int sceneIndex,
        boolean speculative
) {
    public static final String NONE = "NONE";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
}
