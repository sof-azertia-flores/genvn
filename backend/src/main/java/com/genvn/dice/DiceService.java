package com.genvn.dice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ServiceConfigurationError;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * The only place in the system that produces randomness for checks.
 * The LLM never rolls; it may only propose which stat and which DC.
 */
@Service
public class DiceService {

    private static final Logger log = LoggerFactory.getLogger(DiceService.class);

    private final RandomGenerator random;

    public DiceService() {
        this(defaultGenerator());
    }

    /** Test constructor: inject a deterministic generator. */
    public DiceService(RandomGenerator random) {
        this.random = random;
    }

    /**
     * The generator to roll with when nobody injected one.
     *
     * <p>{@link RandomGenerator#getDefault()} asks for "L32X64MixRandom", and every algorithm it
     * can name ships in the {@code jdk.random} module rather than in {@code java.base}. A runtime
     * assembled with jlink -- a slim container image, a hand-trimmed JRE -- need not carry that
     * module, and there the lookup throws {@code IllegalArgumentException} and takes the whole
     * application context down at startup, long before anyone rolls a die. {@link SecureRandom}
     * is part of {@code java.base}, so it is always present, and it is no worse a die: a player
     * must not be able to predict the next d20 either way.
     */
    static RandomGenerator defaultGenerator() {
        return defaultGenerator(RandomGenerator::getDefault);
    }

    /** Visible for testing: {@code preferred} stands in for a runtime that has no jdk.random. */
    static RandomGenerator defaultGenerator(Supplier<RandomGenerator> preferred) {
        try {
            return preferred.get();
        } catch (RuntimeException | ServiceConfigurationError | LinkageError e) {
            log.warn("This Java runtime has no jdk.random module ({}), so dice roll from "
                    + "SecureRandom instead. Install a full JRE 21+ to use the JDK generator.",
                    e.toString());
            return new SecureRandom();
        }
    }

    /** The default generators are not thread-safe; two sessions rolling at once must not interleave. */
    public synchronized int d20() {
        return random.nextInt(20) + 1;
    }
}
