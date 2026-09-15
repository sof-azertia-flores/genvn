package com.genvn.config;

/**
 * Player-facing locale: UI chrome and model prose. Stored as {@code genvn.language}
 * ({@code zh} or {@code en}).
 */
public final class UiLanguage {

    public static final String ZH = "zh";
    public static final String EN = "en";

    private UiLanguage() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return ZH;
        String value = raw.trim().toLowerCase();
        if (value.equals(EN) || value.startsWith("en-") || value.equals("english")) return EN;
        if (value.equals(ZH) || value.startsWith("zh") || value.equals("chinese") || value.contains("中文")) return ZH;
        return ZH;
    }

    public static boolean english(String raw) {
        return EN.equals(normalize(raw));
    }

    public static boolean chinese(String raw) {
        return !english(raw);
    }

    /** English name used inside prompts so the model cannot miss the instruction. */
    public static String promptName(String raw) {
        return english(raw) ? "English" : "Simplified Chinese (简体中文)";
    }

    public static String rule(String raw) {
        return "Write ALL player-visible story text (narration, dialogue, choice labels, on-screen names) in "
                + promptName(raw) + ". JSON keys, ids and schema field names stay in English. Do not mix languages.";
    }

    public static String text(String raw, String zh, String en) {
        return english(raw) ? en : zh;
    }
}
