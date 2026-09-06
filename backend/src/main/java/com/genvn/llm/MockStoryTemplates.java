package com.genvn.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Content bank for {@link MockLlmClient}.
 *
 * The mock exists so the whole engine is playable with no API key. It is not clever -- it is a
 * deterministic template narrator -- but it is state-aware: it reads the beat, the chosen choice
 * and the engine-decided check outcome, and it always emits schema-valid structures.
 */
final class MockStoryTemplates {

    private MockStoryTemplates() {}

    static boolean isChinese(String text) {
        if (text == null) return false;
        return text.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
    }

    /** Splits an outline into the sentences that become Author Canon facts. */
    static List<String> sentences(String outline) {
        List<String> out = new ArrayList<>();
        if (outline == null) return out;
        for (String raw : outline.split("(?<=[。！？!?\\.\\n])")) {
            String s = raw.trim().replaceAll("\\s+", " ");
            if (s.length() >= 4) out.add(s);
            if (out.size() >= 8) break;
        }
        if (out.isEmpty() && outline.trim().length() >= 2) {
            out.add(outline.trim());
        }
        return out;
    }

    record Setting(String zh, String en) {}

    /** Very small keyword table so the mock's place names at least match the player's story. */
    static Setting detectSetting(String outline) {
        String s = outline == null ? "" : outline.toLowerCase(Locale.ROOT);
        if (has(s, "宅", "房子", "屋", "house", "manor", "mansion", "cottage", "estate")) return new Setting("旧宅", "the old house");
        if (has(s, "森林", "树林", "forest", "woods")) return new Setting("林地", "the woods");
        if (has(s, "船", "ship", "vessel", "boat")) return new Setting("船上", "the ship");
        if (has(s, "车站", "站台", "station", "platform")) return new Setting("车站", "the station");
        if (has(s, "学校", "学院", "school", "academy", "campus")) return new Setting("校舍", "the school");
        if (has(s, "医院", "诊所", "hospital", "clinic", "asylum")) return new Setting("医院", "the hospital");
        if (has(s, "旅馆", "旅店", "酒店", "hotel", "inn")) return new Setting("旅馆", "the inn");
        if (has(s, "实验室", "研究所", "lab", "laboratory", "facility")) return new Setting("研究所", "the facility");
        if (has(s, "寺", "庙", "教堂", "temple", "shrine", "church", "chapel")) return new Setting("庙宇", "the chapel");
        if (has(s, "城", "镇", "村", "city", "town", "village")) return new Setting("镇上", "the town");
        return new Setting("那个地方", "the place");
    }

    static String detectObject(String outline, boolean zh) {
        String s = outline == null ? "" : outline.toLowerCase(Locale.ROOT);
        if (has(s, "日记", "diary", "journal")) return zh ? "日记" : "the diary";
        if (has(s, "信", "letter", "note")) return zh ? "那封信" : "the letter";
        if (has(s, "钥匙", "key")) return zh ? "钥匙" : "the key";
        if (has(s, "照片", "photo", "photograph", "picture")) return zh ? "照片" : "the photograph";
        if (has(s, "录音", "tape", "recording")) return zh ? "录音带" : "the tape";
        if (has(s, "地图", "map")) return zh ? "地图" : "the map";
        return zh ? "被藏起来的东西" : "the hidden thing";
    }

    static String detectWitnessName(String outline, boolean zh) {
        String s = outline == null ? "" : outline.toLowerCase(Locale.ROOT);
        if (has(s, "邻居", "neighbour", "neighbor")) return zh ? "邻居 韩" : "Mrs. Hale";
        if (has(s, "看守", "管理员", "caretaker", "keeper", "warden")) return zh ? "看守 老周" : "the caretaker";
        if (has(s, "医生", "doctor")) return zh ? "医生" : "the doctor";
        if (has(s, "老板", "店主", "shopkeeper", "owner")) return zh ? "店主" : "the shopkeeper";
        return zh ? "唯一的目击者" : "the only witness";
    }

    static String detectAbsentName(String outline, boolean zh) {
        String s = outline == null ? "" : outline.toLowerCase(Locale.ROOT);
        if (has(s, "祖父", "爷爷", "grandfather", "grandpa")) return zh ? "祖父" : "your grandfather";
        if (has(s, "父亲", "爸爸", "father", "dad")) return zh ? "父亲" : "your father";
        if (has(s, "母亲", "妈妈", "mother", "mom")) return zh ? "母亲" : "your mother";
        if (has(s, "姐姐", "妹妹", "sister")) return zh ? "姐姐" : "your sister";
        if (has(s, "朋友", "friend")) return zh ? "那个朋友" : "your friend";
        return zh ? "那个失踪的人" : "the one who vanished";
    }

    static boolean mentionsNight(String outline) {
        String s = outline == null ? "" : outline.toLowerCase(Locale.ROOT);
        return has(s, "午夜", "半夜", "夜里", "深夜", "midnight", "night", "dark");
    }

    private static boolean has(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ beat archetypes

    static final int BEAT_ARRIVAL = 0;
    static final int BEAT_EXPLORE = 1;
    static final int BEAT_EVIDENCE = 2;
    static final int BEAT_CONFRONT = 3;
    static final int BEAT_RESOLVE = 4;

    static String beatTitle(int beat, boolean zh, String setting) {
        return switch (beat) {
            case BEAT_ARRIVAL -> zh ? "抵达" : "Arrival";
            case BEAT_EXPLORE -> zh ? "查看" + setting : "Searching " + setting;
            case BEAT_EVIDENCE -> zh ? "痕迹" : "Traces";
            case BEAT_CONFRONT -> zh ? "夜里的声音" : "The Sound at Night";
            default -> zh ? "被藏起来的东西" : "What Was Hidden";
        };
    }

    static String beatPurpose(int beat, boolean zh) {
        return switch (beat) {
            case BEAT_ARRIVAL -> zh ? "确立地点、气氛，以及玩家为什么来这里。" : "Establish the place, the mood, and why the player came.";
            case BEAT_EXPLORE -> zh ? "让玩家熟悉环境，并发现第一个不对劲的地方。" : "Let the player learn the space and find the first thing that is wrong.";
            case BEAT_EVIDENCE -> zh ? "让玩家找到与失踪者有关的第一份实物证据。" : "Give the player their first physical evidence of the missing person.";
            case BEAT_CONFRONT -> zh ? "直面故事中反复出现的异常。" : "Face the anomaly the story keeps circling.";
            default -> zh ? "找到被藏起来的东西，回答核心谜题。" : "Find what was hidden and answer the central question.";
        };
    }

    static String beatTurn(int beat, boolean zh) {
        return switch (beat) {
            case BEAT_ARRIVAL -> zh ? "玩家不再是访客：门在身后关上了。" : "The player is no longer a visitor: the door has closed behind them.";
            case BEAT_EXPLORE -> zh ? "这里的秩序是被人刻意摆出来的。" : "The order here was arranged by someone on purpose.";
            case BEAT_EVIDENCE -> zh ? "失踪的人留下了话，而且是留给玩家的。" : "The missing person left words, and they were meant for the player.";
            case BEAT_CONFRONT -> zh ? "异常有一个来源，而且它知道玩家在这里。" : "The anomaly has a source, and it knows the player is here.";
            default -> zh ? "核心谜题有了答案，代价也随之到来。" : "The central question has its answer, and the price arrives with it.";
        };
    }

    static String beatCompletion(int beat, boolean zh) {
        return switch (beat) {
            case BEAT_ARRIVAL -> zh ? "玩家进入内部。" : "The player goes inside.";
            case BEAT_EXPLORE -> zh ? "玩家注意到某个明显不属于这里的细节。" : "The player notices something that does not belong.";
            case BEAT_EVIDENCE -> zh ? "玩家拿到一件与失踪者直接相关的物品。" : "The player holds an object tied to the missing person.";
            case BEAT_CONFRONT -> zh ? "玩家亲眼或亲耳确认了异常的来源。" : "The player confirms the source of the anomaly.";
            default -> zh ? "被藏起来的东西被找到。" : "The hidden thing is found.";
        };
    }
}
