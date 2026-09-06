package com.genvn.game;

import java.util.Locale;

/** The six core stats. Values are direct modifiers (0..5), no D&D-style score conversion. */
public enum Stat {
    BODY("Body"),
    AGILITY("Agility"),
    PERCEPTION("Perception"),
    INTELLECT("Intellect"),
    WILL("Will"),
    PRESENCE("Presence");

    public static final int MIN = 0;
    public static final int MAX = 5;

    private final String display;

    Stat(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    /** Lenient parse: the LLM may say "perception", "Perception", "PER" or "感知". Returns null if unknown. */
    public static Stat fromLoose(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        for (Stat stat : values()) {
            if (stat.name().toLowerCase(Locale.ROOT).equals(s)) return stat;
            if (stat.display.toLowerCase(Locale.ROOT).equals(s)) return stat;
            if (s.length() >= 3 && stat.name().toLowerCase(Locale.ROOT).startsWith(s)) return stat;
        }
        return switch (s) {
            case "体能", "力量", "strength", "str", "con" -> BODY;
            case "敏捷", "dex", "dexterity" -> AGILITY;
            case "感知", "wis", "wisdom", "awareness", "per" -> PERCEPTION;
            case "智识", "智力", "int", "intelligence", "reason" -> INTELLECT;
            case "意志", "wil", "resolve", "composure" -> WILL;
            case "魅力", "cha", "charisma", "social", "pre" -> PRESENCE;
            default -> null;
        };
    }
}
