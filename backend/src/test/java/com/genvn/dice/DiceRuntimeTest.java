package com.genvn.dice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dice must come up on whatever Java the server happens to have. A runtime trimmed with
 * jlink carries java.base but not jdk.random, and RandomGenerator.getDefault() throws there --
 * which used to fail the DiceService bean and, through CheckResolver and SessionService, the
 * entire application context at startup.
 */
class DiceRuntimeTest {

    /** What java.util.random.RandomGeneratorFactory.findProvider throws on a trimmed runtime. */
    private static RandomGenerator noJdkRandomModule() {
        throw new IllegalArgumentException("No implementation of the random number generator "
                + "algorithm \"L32X64MixRandom\" is available");
    }

    @Test
    @DisplayName("a JRE without the jdk.random module still gives us a die, rather than no server")
    void fallsBackWhenTheJdkGeneratorIsMissing() {
        RandomGenerator generator = DiceService.defaultGenerator(DiceRuntimeTest::noJdkRandomModule);

        assertNotNull(generator, "startup must not depend on an optional JDK module");

        DiceService dice = new DiceService(generator);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 4000; i++) {
            int roll = dice.d20();
            assertTrue(roll >= 1 && roll <= 20, "rolled " + roll);
            seen.add(roll);
        }
        assertTrue(seen.size() == 20, "the fallback must reach every face, saw " + seen.size());
    }

    @Test
    @DisplayName("on a normal JDK we keep rolling with the JDK's own generator")
    void prefersTheJdkGeneratorWhenItIsThere() {
        RandomGenerator generator = DiceService.defaultGenerator();
        assertNotNull(generator);

        DiceService dice = new DiceService(generator);
        for (int i = 0; i < 4000; i++) {
            int roll = dice.d20();
            assertTrue(roll >= 1 && roll <= 20, "rolled " + roll);
        }
    }
}
