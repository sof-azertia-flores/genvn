package com.genvn.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * A d20 that rolls exactly what the test says it rolls. Dice are now cast for every checked
 * choice the moment a scene becomes current, so a test's script is consumed in choice order at
 * each commit; once it runs out the die settles on {@link #FALLBACK} rather than failing, which
 * keeps a guard like "a re-roll would have produced the next scripted 20" meaningful.
 */
public class ScriptedRandom implements RandomGenerator {

    public static final int FALLBACK = 10;

    private final Deque<Integer> rolls = new ArrayDeque<>();

    public ScriptedRandom(Integer... d20Values) {
        rolls.addAll(List.of(d20Values));
    }

    public void queue(int... d20Values) {
        for (int v : d20Values) rolls.add(v);
    }

    @Override
    public int nextInt(int bound) {
        Integer next = rolls.poll();
        if (next == null) next = FALLBACK;
        return (next - 1) % bound;
    }

    @Override
    public long nextLong() {
        return nextInt(20);
    }
}
