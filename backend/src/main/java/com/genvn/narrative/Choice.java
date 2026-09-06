package com.genvn.narrative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Choice(
        String id,
        String text,
        /** investigation | social | cautious | risky | resource -- used to enforce variety. */
        String approach,
        Check check,
        List<String> requirements,
        /** Explicit display mode, independent of the mechanical approach/check. */
        String actionKind,
        String playerExpression
) {
    public Choice {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        actionKind = "dialogue".equalsIgnoreCase(actionKind) ? "dialogue" : "action";
        playerExpression = playerExpression == null || playerExpression.isBlank()
                ? ("dialogue".equals(actionKind) ? "talking" : "action")
                : com.genvn.asset.AssetSpec.slug(playerExpression);
    }

    public Choice(String id, String text, String approach, Check check, List<String> requirements) {
        this(id, text, approach, check, requirements, "action", null);
    }

    public boolean hasCheck() {
        return check != null && check.stat() != null;
    }
}
