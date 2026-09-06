package com.genvn.game;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The player-controlled character. Mutable runtime state: only ever mutated by
 * {@link StateReducer} on a state that the caller owns (canonical or a forked branch copy).
 */
public class PlayerCharacter {
    public static final String ID = "player";
    public final String id = ID;
    /** User-authored appearance; copied into the visual profile before any picture is planned. */
    public String visualDescription = "";
    public String name = "Traveller";
    public String background = "";
    public Map<Stat, Integer> stats = new EnumMap<>(Stat.class);
    public int hp = 10;
    public int maxHp = 10;
    public List<String> traits = new ArrayList<>();
    public List<String> conditions = new ArrayList<>();

    public PlayerCharacter() {
        for (Stat s : Stat.values()) stats.put(s, 2);
    }

    public int mod(Stat stat) {
        Integer v = stats.get(stat);
        return v == null ? 0 : v;
    }
}
