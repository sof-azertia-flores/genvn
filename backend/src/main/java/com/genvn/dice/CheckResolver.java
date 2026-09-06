package com.genvn.dice;

import com.genvn.game.PlayerCharacter;
import com.genvn.game.Stat;
import com.genvn.narrative.Check;
import org.springframework.stereotype.Service;

/**
 * Rule: d20 + stat modifier >= DC.
 *
 * The LLM proposes stat and DC; both are validated here. An out-of-range DC is clamped
 * rather than rejected, so a bad number can never stall the game.
 */
@Service
public class CheckResolver {

    private final DiceService dice;

    public CheckResolver(DiceService dice) {
        this.dice = dice;
    }

    public CheckResult resolve(Check check, PlayerCharacter player) {
        Stat stat = Stat.fromLoose(check.stat());
        if (stat == null) stat = Stat.WILL; // never stall on a stat name we do not recognise
        int requestedDc = check.dc();
        int dc = clampDc(requestedDc);
        int roll = dice.d20();
        int mod = player.mod(stat);
        int total = roll + mod;
        boolean success = total >= dc;
        return new CheckResult(stat, stat.display(), roll, mod, total, dc, success,
                roll == 20, roll == 1, requestedDc);
    }

    public static int clampDc(int dc) {
        return Math.max(Check.MIN_DC, Math.min(Check.MAX_DC, dc));
    }
}
