package com.genvn.narrative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.genvn.asset.AssetRequest;
import com.genvn.game.StateDelta;

import java.util.List;

/** Wire shape the Scene Generator expects back from the model. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SceneResponse(
        SceneLocation location,
        List<CharacterPresence> characters,
        List<Block> blocks,
        List<Choice> choices,
        StateDelta proposedStateDelta,
        String storyProgressNote,
        List<AssetRequest> assetRequests,
        List<NpcIntroduction> newNpcs
) {
    public SceneResponse(SceneLocation location, List<CharacterPresence> characters, List<Block> blocks,
                         List<Choice> choices, StateDelta proposedStateDelta, String storyProgressNote,
                         List<AssetRequest> assetRequests) {
        this(location, characters, blocks, choices, proposedStateDelta, storyProgressNote, assetRequests, List.of());
    }
}
