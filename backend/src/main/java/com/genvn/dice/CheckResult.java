package com.genvn.dice;

import com.genvn.game.Stat;

/** The authoritative outcome of one stat check. Produced only by {@link CheckResolver}. */
public record CheckResult(
        Stat stat,
        String statDisplay,
        int d20,
        int statModifier,
        int total,
        int dc,
        boolean success,
        boolean critical,
        boolean fumble,
        /** The DC the LLM asked for, before clamping. Shown in the inspector. */
        int requestedDc
) {
    public String outcome() {
        return success ? "SUCCESS" : "FAILURE";
    }

    public String summary() {
        return "d20 %d + %s %d = %d vs DC %d -> %s"
                .formatted(d20, statDisplay, statModifier, total, dc, outcome());
    }
}
