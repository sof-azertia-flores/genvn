package com.genvn.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerInputLimitsTest {

    @Test
    void traitsAreTrimmedBoundedInLengthAndCount() {
        String essay = "x".repeat(5000);
        var player = SessionController.toPlayer(new Dtos.PlayerInput("Alex", "bg", Map.of(),
                List.of(essay, " brave ", "", "a", "b", "c", "d", "e"), 10));

        assertEquals(6, player.traits.size());
        assertEquals(SessionController.MAX_TRAIT_CHARS, player.traits.get(0).length());
        assertEquals("brave", player.traits.get(1));
    }
}
