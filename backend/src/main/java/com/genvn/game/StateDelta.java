package com.genvn.game;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StateDelta(List<DeltaOp> ops) {
    public StateDelta {
        ops = ops == null ? List.of() : List.copyOf(ops);
    }

    public static StateDelta empty() {
        return new StateDelta(List.of());
    }

    public static StateDelta of(DeltaOp... ops) {
        return new StateDelta(new ArrayList<>(List.of(ops)));
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }
}
