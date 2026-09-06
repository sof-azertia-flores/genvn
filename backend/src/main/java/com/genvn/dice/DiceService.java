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
     * <p>{@link RandomGenerator#getDefault()} asks for "L32X64MixRandom". Through Java 24 that
     * algorithm, and every other one it can name, ships in the optional {@code jdk.random} module
     * rather than in {@code java.base} -- and Temurin's *JRE* package does not carry that module
     * (its JDK package does), nor does a runtime trimmed with jlink. There the lookup throws
     * {@code IllegalArgumentException} and this bean fails to build, which through CheckResolver
     * and SessionService takes the whole application context down at startup, long before anyone
     * rolls a die. Java 25 moved the implementations into {@code java.base}, so on 25+ the module
     * is gone from --list-modules yet getDefault() works and this fallback is never reached.
     *
     * <p>{@link SecureRandom} is part of {@code java.base} in every version, so it is always
     * there, and it is no worse a die: a player must not be able to predict the next d20 either
     * way.
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
                    + "SecureRandom instead. The game is unaffected; to use the JDK's own "
                    + "generator, run on the JDK package rather than the JRE, or on Java 25+.",
                    e.toString());
            return new SecureRandom();
        }
    }

    /** The default generators are not thread-safe; two sessions rolling at once must not interleave. */
    public synchronized int d20() {
        return random.nextInt(20) + 1;
    }
}
