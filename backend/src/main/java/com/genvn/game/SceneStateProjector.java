package com.genvn.game;

import com.genvn.narrative.Choice;
import com.genvn.narrative.SceneBundle;
import com.genvn.narrative.SceneCast;
import com.genvn.story.CompiledStory;
import com.genvn.story.LocationProfile;

/** The same deterministic scene transition is used on canon and on isolated speculative copies. */
public final class SceneStateProjector {
    private SceneStateProjector() {}

    public static void register(CompiledStory story, GameState state, SceneBundle scene) {
        if (scene.location() != null && scene.location().id() != null
                && story.bible.location(scene.location().id()) == null) {
            story.registerLocation(new LocationProfile(scene.location().id(), scene.location().name(),
                    scene.location().visualDescription(), scene.location().visualDescription()));
        }
        SceneCast.apply(story, state, scene);
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    public static StateReducer.Applied apply(StateReducer reducer, CompiledStory story, GameState state,
                                             SceneBundle scene, Choice choice, String rollSummary) {
        int version = state.stateVersion;
        String beat = state.currentBeatId;
        var applied = reducer.apply(state, story, scene.proposedStateDelta(), scene.sceneId());
        state.scenesInCurrentBeat = java.util.Objects.equals(beat, state.currentBeatId)
                ? state.scenesInCurrentBeat + 1 : 0;
        state.currentSceneId = scene.sceneId();
        if (scene.location() != null && scene.location().id() != null) {
            state.currentLocationId = scene.location().id();
            if (!state.knownLocationIds.contains(scene.location().id())) state.knownLocationIds.add(scene.location().id());
        }
        state.storyProgress.scenesPlayed++;
        state.storyProgress.totalBeats = story.spine.beats().size();
        state.storyProgress.beatsCompleted = state.completedBeats.size();
        state.storyProgress.recompute();
        if (choice != null) state.addRecentEvent("The player chose: \"" + choice.text() + "\""
                + (rollSummary == null ? "" : "  [engine roll: " + rollSummary + "]"));
        scene.blocks().stream().findFirst().ifPresent(b -> state.addRecentEvent(
                truncate(b.text(), 180)));
        state.rememberScene(scene.sceneId(), scene.blocks());
        state.stateVersion = Math.max(state.stateVersion, version + 1);
        return applied;
    }
}
