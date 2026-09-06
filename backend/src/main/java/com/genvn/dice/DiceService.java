package com.genvn.dice;

import org.springframework.stereotype.Service;

import java.util.random.RandomGenerator;

/**
 * The only place in the system that produces randomness for checks.
 * The LLM never rolls; it may only propose which stat and which DC.
 */
@Service
public class DiceService {

    private final RandomGenerator random;

    public DiceService() {
        this(RandomGenerator.getDefault());
    }

    /** Test constructor: inject a deterministic generator. */
    public DiceService(RandomGenerator random) {
        this.random = random;
    }

    /** The default generators are not thread-safe; two sessions rolling at once must not interleave. */
    public synchronized int d20() {
        return random.nextInt(20) + 1;
    }
}
