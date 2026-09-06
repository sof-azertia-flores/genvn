package com.genvn.narrative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.genvn.asset.AssetRequest;
import com.genvn.game.StateDelta;

import java.util.List;

/**
 * Everything the visual-novel runtime needs to play one scene. Deliberately structured:
 * the frontend never has to guess what a blob of prose means.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SceneBundle(
        String sceneId,
        String beatId,
        SceneLocation location,
        List<CharacterPresence> characters,
        List<Block> blocks,
        List<Choice> choices,
        StateDelta proposedStateDelta,
        String storyProgressNote,
        /** Pictures the generator wanted that do not exist yet. Acted on at commit only. */
        List<AssetRequest> assetRequests,
        /** Uncommitted identities and optional future-recurrence requests. */
        List<NpcIntroduction> newNpcs,
        SceneMeta meta
) {
    public SceneBundle {
        characters = characters == null ? List.of() : List.copyOf(characters);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        choices = choices == null ? List.of() : List.copyOf(choices);
        proposedStateDelta = proposedStateDelta == null ? StateDelta.empty() : proposedStateDelta;
        assetRequests = assetRequests == null ? List.of() : List.copyOf(assetRequests);
        newNpcs = newNpcs == null ? List.of() : List.copyOf(newNpcs);
    }

    public SceneBundle(String sceneId, String beatId, SceneLocation location, List<CharacterPresence> characters,
                       List<Block> blocks, List<Choice> choices, StateDelta proposedStateDelta,
                       String storyProgressNote, List<AssetRequest> assetRequests, SceneMeta meta) {
        this(sceneId, beatId, location, characters, blocks, choices, proposedStateDelta,
                storyProgressNote, assetRequests, List.of(), meta);
    }

    public Choice choice(String id) {
        if (id == null) return null;
        return choices.stream().filter(c -> id.equals(c.id())).findFirst().orElse(null);
    }

    public SceneBundle withMeta(SceneMeta newMeta) {
        return new SceneBundle(sceneId, beatId, location, characters, blocks, choices,
                proposedStateDelta, storyProgressNote, assetRequests, newNpcs, newMeta);
    }

    public SceneBundle withSceneId(String newId) {
        return new SceneBundle(newId, beatId, location, characters, blocks, choices,
                proposedStateDelta, storyProgressNote, assetRequests, newNpcs, meta);
    }

    public SceneBundle withDelta(StateDelta delta) {
        return new SceneBundle(sceneId, beatId, location, characters, blocks, choices,
                delta, storyProgressNote, assetRequests, newNpcs, meta);
    }
}
