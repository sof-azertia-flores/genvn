package com.genvn.narrative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SceneLocation(
        String id,
        String name,
        String visualDescription,
        String backgroundPrompt,
        /** A validated id from the session's asset manifest, or null for the placeholder. */
        String backgroundAssetId
) {
    public SceneLocation withBackgroundAssetId(String assetId) {
        return new SceneLocation(id, name, visualDescription, backgroundPrompt, assetId);
    }
}
