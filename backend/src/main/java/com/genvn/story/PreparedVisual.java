package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.genvn.game.PlayerCharacter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** An unassigned illustration design. It carries no name, role, personality or story facts. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreparedVisual(String id, String visualDescription) {
    public static final int MAX_PREPARED = 3;
    // Match the asset slug limit so two long ids can never collapse into one local image key.
    private static final Pattern SAFE_ID = Pattern.compile("[a-z][a-z0-9_]{0,47}");

    public static boolean validId(String id) {
        return id != null && SAFE_ID.matcher(id).matches() && !PlayerCharacter.ID.equals(id);
    }

    public static String validateAll(List<PreparedVisual> visuals, StoryBible bible) {
        if (visuals == null) return null;
        if (visuals.size() > MAX_PREPARED) return "preparedVisuals must contain at most three appearance-only designs";
        Set<String> ids = new HashSet<>();
        for (PreparedVisual visual : visuals) {
            if (visual == null || !validId(visual.id())) return "preparedVisuals has an invalid id";
            if (!ids.add(visual.id()) || bible.character(visual.id()) != null) return "preparedVisuals id must be unique and unassigned";
            if (visual.visualDescription() == null || visual.visualDescription().isBlank()
                    || visual.visualDescription().length() > 2000) return "preparedVisuals needs an appearance description of 1-2000 characters";
        }
        return null;
    }
}
