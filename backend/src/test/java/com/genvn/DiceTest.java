package com.genvn;

import com.genvn.dice.CheckResolver;
import com.genvn.dice.CheckResult;
import com.genvn.dice.DiceService;
import com.genvn.game.PlayerCharacter;
import com.genvn.game.Stat;
import com.genvn.narrative.Check;
import com.genvn.support.Engine;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiceTest {

    private CheckResolver resolverRolling(int... rolls) {
        ScriptedRandom random = new ScriptedRandom();
        random.queue(rolls);
        return new CheckResolver(new DiceService(random));
    }

    @Test
    @DisplayName("d20 + stat >= DC, exactly as advertised in the UI")
    void resolvesTheDocumentedRule() {
        PlayerCharacter alex = Engine.alex();
        CheckResult r = resolverRolling(11).resolve(new Check("Perception", 13, "spotting the seam"), alex);

        assertEquals(11, r.d20());
        assertEquals(4, r.statModifier());
        assertEquals(15, r.total());
        assertEquals(13, r.dc());
        assertTrue(r.success());
        assertEquals("d20 11 + Perception 4 = 15 vs DC 13 -> SUCCESS", r.summary());
    }

    @Test
    @DisplayName("meeting the DC exactly succeeds")
    void tiesGoToThePlayer() {
        CheckResult r = resolverRolling(9).resolve(new Check("Perception", 13, null), Engine.alex());
        assertEquals(13, r.total());
        assertTrue(r.success());
    }

    @Test
    @DisplayName("a DC the model invented outside the sane range is clamped, never rejected")
    void dcIsClamped() {
        CheckResult high = resolverRolling(20).resolve(new Check("Will", 9999, null), Engine.alex());
        assertEquals(Check.MAX_DC, high.dc());
        assertEquals(9999, high.requestedDc(), "the original is kept for the inspector");

        CheckResult low = resolverRolling(1).resolve(new Check("Will", -5, null), Engine.alex());
        assertEquals(Check.MIN_DC, low.dc());
        assertFalse(low.success(), "d20 1 + Will 3 = 4 vs DC 8");
    }

    @Test
    @DisplayName("a stat name the model spelled oddly still resolves rather than stalling the game")
    void statNamesAreLenient() {
        assertEquals(Stat.PERCEPTION, Stat.fromLoose("perception"));
        assertEquals(Stat.PERCEPTION, Stat.fromLoose("PERCEPTION"));
        assertEquals(Stat.PERCEPTION, Stat.fromLoose("感知"));
        assertEquals(Stat.INTELLECT, Stat.fromLoose("Intellect"));
        assertEquals(Stat.BODY, Stat.fromLoose("strength"));

        CheckResult r = resolverRolling(12).resolve(new Check("nonsense-stat", 12, null), Engine.alex());
        assertEquals(Stat.WILL.display(), r.statDisplay(), "falls back rather than throwing");
    }

    @Test
    @DisplayName("naturals are flagged for the UI")
    void naturalsAreFlagged() {
        assertTrue(resolverRolling(20).resolve(new Check("Will", 12, null), Engine.alex()).critical());
        assertTrue(resolverRolling(1).resolve(new Check("Will", 12, null), Engine.alex()).fumble());
    }

    @Test
    @DisplayName("the die really is a d20")
    void diceStayInRange() {
        DiceService dice = new DiceService();
        for (int i = 0; i < 4000; i++) {
            int roll = dice.d20();
            assertTrue(roll >= 1 && roll <= 20, "rolled " + roll);
        }
    }
}
