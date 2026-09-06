package com.genvn.game;

import com.genvn.narrative.Block;
import java.util.List;

/** Complete committed text, distinct from the authoritative numerical state. */
public record SceneMemory(String sceneId, List<Block> blocks) {
    public SceneMemory {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
