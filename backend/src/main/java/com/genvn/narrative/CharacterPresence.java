package com.genvn.narrative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An NPC visible on stage for this scene. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CharacterPresence(
        String characterId,
        String name,
        /** neutral | worried | afraid | angry | sad | happy | suspicious | surprised */
        String expression,
        /** left | center | right */
        String position,
        String visualDescription,
        /** A validated portrait id from the asset manifest, or null for the placeholder. */
        String assetId
) {
    public CharacterPresence withAssetId(String newAssetId) {
        return new CharacterPresence(characterId, name, expression, position, visualDescription, newAssetId);
    }
}
