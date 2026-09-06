package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The compiled world. Written once by the Story Compiler; the LLM may not edit it during play.
 * hardCanon is inviolable; softCanon is elaboration the generator is allowed to build on.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoryBible(
        String premise,
        String tone,
        List<String> themes,
        List<NpcProfile> characters,
        List<LocationProfile> locations,
        List<String> importantObjects,
        List<String> mysteries,
        List<String> hardCanon,
        List<String> softCanon
) {
    public StoryBible {
        themes = safe(themes);
        characters = characters == null ? List.of() : List.copyOf(characters);
        locations = locations == null ? List.of() : List.copyOf(locations);
        importantObjects = safe(importantObjects);
        mysteries = safe(mysteries);
        hardCanon = safe(hardCanon);
        softCanon = safe(softCanon);
    }

    private static List<String> safe(List<String> in) {
        return in == null ? List.of() : List.copyOf(in);
    }

    public NpcProfile character(String id) {
        if (id == null) return null;
        return characters.stream().filter(c -> id.equals(c.id())).findFirst().orElse(null);
    }

    public LocationProfile location(String id) {
        if (id == null) return null;
        return locations.stream().filter(l -> id.equals(l.id())).findFirst().orElse(null);
    }
}
