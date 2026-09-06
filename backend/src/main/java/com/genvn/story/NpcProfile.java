package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NpcProfile(
        String id,
        String name,
        String description,
        String personality,
        List<String> goals,
        List<String> secrets,
        String speakingStyle,
        String relationshipToPlayer,
        String visualDescription
) {
    public NpcProfile {
        goals = goals == null ? List.of() : List.copyOf(goals);
        secrets = secrets == null ? List.of() : List.copyOf(secrets);
    }
}
