package com.genvn.game;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One proposed change to the world, as emitted by the LLM.
 *
 * A single flat shape keeps the model's job easy and the validator's job easy.
 * Unknown ops are rejected wholesale by {@link StateValidator} -- there is deliberately
 * no field here that could express "overwrite the player", "edit Author Canon" or
 * "set an arbitrary state path".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeltaOp(
        String op,
        String target,
        Integer amount,
        String value,
        String reason
) {
    public static final String ADD_INVENTORY = "addInventory";
    public static final String REMOVE_INVENTORY = "removeInventory";
    public static final String SET_FLAG = "setFlag";
    public static final String RELATIONSHIP_DELTA = "relationshipDelta";
    public static final String HP_DELTA = "hpDelta";
    public static final String ADD_CONDITION = "addCondition";
    public static final String REMOVE_CONDITION = "removeCondition";
    public static final String CHANGE_LOCATION = "changeLocation";
    public static final String COMPLETE_BEAT = "completeBeat";
    public static final String ADD_THREAD = "addThread";
    public static final String RESOLVE_THREAD = "resolveThread";
    public static final String MEET_CHARACTER = "meetCharacter";

    public static DeltaOp of(String op, String target) {
        return new DeltaOp(op, target, null, null, null);
    }

    public static DeltaOp of(String op, String target, String value, String reason) {
        return new DeltaOp(op, target, null, value, reason);
    }

    public static DeltaOp amount(String op, String target, int amount, String reason) {
        return new DeltaOp(op, target, amount, null, reason);
    }
}
