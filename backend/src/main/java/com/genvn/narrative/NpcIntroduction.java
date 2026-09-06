package com.genvn.narrative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.genvn.story.NpcProfile;

/** Branch-local proposal. A character's identity is established only when its scene commits. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NpcIntroduction(NpcProfile profile, String preparedVisualId, boolean recurring) {}
