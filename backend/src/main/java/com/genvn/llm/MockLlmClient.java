package com.genvn.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.game.GameState;
import com.genvn.narrative.Choice;
import com.genvn.story.CompiledStory;
import com.genvn.story.StoryBeat;

import java.util.List;

import static com.genvn.llm.MockStoryTemplates.*;

/**
 * A deterministic, offline stand-in for a real model.
 *
 * It goes through exactly the same JSON contract as a real provider, so the parse / validate /
 * repair / reduce pipeline is genuinely exercised without an API key. It reads the canonical
 * GameState and the engine-decided check outcome, so the story it tells is consistent and
 * fail-forward -- not random text.
 */
public class MockLlmClient implements LlmClient {

    private final ObjectMapper mapper;
    private final String reason;

    public MockLlmClient(ObjectMapper mapper) {
        this(mapper, "no LLM credentials configured");
    }

    public MockLlmClient(ObjectMapper mapper, String reason) {
        this.mapper = mapper;
        this.reason = reason;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long started = System.currentTimeMillis();
        String json = switch (request.purpose()) {
            case STORY_COMPILE -> compile(request);
            case SCENE_GENERATE -> scene(request);
            case ARC_CONTINUE -> arc(request);
            case CHOICE_PROBABILITIES -> MockChoiceProbabilities.generate(mapper, request);
            case SPARE_DESIGNS -> spareDesigns(request);
        };
        // A little latency so the speculative-prefetch machinery is observable in the inspector.
        sleep(180 + (Math.abs(json.hashCode()) % 220));
        return new LlmResponse(json, "mock", System.currentTimeMillis() - started);
    }

    @Override
    public String describe() {
        return "mock (" + reason + ")";
    }

    @Override
    public boolean isMock() {
        return true;
    }

    // ------------------------------------------------------------------ story compile

    private String compile(LlmRequest request) {
        String outline = str(request.mockContext().get("outline"));
        String playerName = str(request.mockContext().get("playerName"));
        boolean zh = isChinese(outline);
        Setting setting = detectSetting(outline);
        String place = zh ? setting.zh() : setting.en();
        String object = detectObject(outline, zh);
        String witness = detectWitnessName(outline, zh);
        String absent = detectAbsentName(outline, zh);

        ObjectNode root = mapper.createObjectNode();

        ArrayNode facts = root.putArray("authorCanonFacts");
        List<String> canon = sentences(outline);
        canon.forEach(facts::add);
        if (canon.isEmpty()) facts.add(zh ? "玩家来到一个陌生的地方寻找答案。" : "The player came somewhere unfamiliar looking for answers.");

        ObjectNode bible = root.putObject("bible");
        bible.put("premise", zh
                ? "%s 来到%s，想弄清楚%s留下的东西究竟是什么。".formatted(playerName, place, absent)
                : "%s comes to %s to find out what %s left behind.".formatted(playerName, place, absent));
        bible.put("tone", zh ? "缓慢推进的悬疑，安静的不安感" : "slow-burn mystery, quiet dread");
        ArrayNode themes = bible.putArray("themes");
        if (zh) { themes.add("记忆"); themes.add("遗留之物"); themes.add("独自面对"); }
        else { themes.add("memory"); themes.add("inheritance"); themes.add("being alone with it"); }

        ArrayNode characters = bible.putArray("characters");
        ObjectNode npc = characters.addObject();
        npc.put("id", "npc_witness");
        npc.put("name", witness);
        npc.put("description", zh
                ? "住在附近多年的人，是唯一还愿意谈起这件事的人。"
                : "Someone who has lived nearby for years, and the only person still willing to talk about it.");
        npc.put("personality", zh ? "谨慎、话不说满、真心不希望你久留。" : "Careful, never says quite everything, genuinely does not want you to stay.");
        npc.putArray("goals").add(zh ? "让你尽早离开" : "get you to leave early");
        npc.putArray("secrets").add(zh
                ? "多年前的某个夜里，他们也进去过一次，从此不再靠近。"
                : "They went inside once, years ago, and have not gone near it since.");
        npc.put("speakingStyle", zh ? "短句，常常停在半路上。" : "Short sentences that stop halfway.");
        npc.put("relationshipToPlayer", zh ? "礼貌但保持距离" : "polite, keeping distance");
        npc.put("visualDescription", zh
                ? "上了年纪的人，穿着洗旧的外套，站在光线之外，手一直插在口袋里"
                : "an older person in a washed-out coat, standing just outside the light, hands in pockets");

        ObjectNode absentNpc = characters.addObject();
        absentNpc.put("id", "npc_absent");
        absentNpc.put("name", absent);
        absentNpc.put("description", zh ? "已经不在这里的人，但整个故事都围绕着他。" : "No longer here, and yet the whole story is about them.");
        absentNpc.put("personality", zh ? "谨慎、有条理、把重要的话写下来而不说出口。" : "Methodical; wrote down what mattered instead of saying it.");
        absentNpc.putArray("goals").add(zh ? "让该找到的人找到" : "be found by the right person");
        absentNpc.putArray("secrets").add(zh ? "他把东西藏在了一个只有家里人才会想到的地方。" : "They hid it somewhere only family would think to look.");
        absentNpc.put("speakingStyle", zh ? "只在信件和字条里出现。" : "Appears only in letters and notes.");
        absentNpc.put("relationshipToPlayer", zh ? "血缘或旧交，缺席但无处不在" : "absent, but present in everything");
        absentNpc.put("visualDescription", zh ? "旧照片里的人，面孔略微失焦" : "a face in an old photograph, slightly out of focus");

        ArrayNode locations = bible.putArray("locations");
        putLocation(locations, "loc_threshold",
                zh ? place + "门前" : "Outside " + place,
                zh ? "故事开始的地方。你还可以转身。" : "Where the story starts. You can still turn around.",
                zh ? "黄昏，门廊，长草，未点灯的窗户，冷色调" : "dusk, a porch, long grass, unlit windows, cold palette");
        putLocation(locations, "loc_interior",
                zh ? place + "内部" : "Inside " + place,
                zh ? "落满灰的房间，家具还保持着某人离开那天的样子。" : "Dusty rooms, furniture still arranged the way someone left it.",
                zh ? "室内，灰尘在斜射的光里，盖着白布的家具，暖褐色与阴影" : "interior, dust in slanted light, sheeted furniture, warm brown and shadow");
        putLocation(locations, "loc_upper",
                zh ? "二楼" : "The Upper Floor",
                zh ? "声音来自这里。白天它什么也不是。" : "Where the sound comes from. In daylight it is nothing at all.",
                zh ? "狭窄的楼梯与走廊，尽头是一扇关着的门，几乎全黑" : "a narrow stair and corridor, a closed door at the end, nearly black");
        putLocation(locations, "loc_hidden",
                zh ? "藏匿之处" : "The Hiding Place",
                zh ? "整栋房子都在替它保守秘密。" : "The whole building has been keeping this one secret.",
                zh ? "被拆开的墙板后面的空腔，手电光，木屑与旧纸" : "a cavity behind pried-open panelling, torch light, sawdust and old paper");

        ArrayNode objects = bible.putArray("importantObjects");
        objects.add(object);
        objects.add(zh ? "一串黄铜钥匙" : "a ring of brass keys");

        ArrayNode mysteries = bible.putArray("mysteries");
        mysteries.add(zh ? "被藏起来的究竟是什么？" : "What exactly was hidden here?");
        if (mentionsNight(outline)) {
            mysteries.add(zh ? "夜里的声音来自什么？" : "What makes the sound at night?");
        } else {
            mysteries.add(zh ? "这个地方在隐瞒什么？" : "What is this place hiding?");
        }
        mysteries.add(zh ? "%s最后那几天发生了什么？".formatted(absent) : "What happened to %s in the last days?".formatted(absent));

        ArrayNode hard = bible.putArray("hardCanon");
        canon.forEach(hard::add);
        ArrayNode soft = bible.putArray("softCanon");
        soft.add(zh ? "%s 曾经进去过一次，之后再没靠近。".formatted(witness) : "%s went inside once and never went back.".formatted(witness));
        soft.add(zh ? "%s习惯把重要的事写下来。".formatted(absent) : "%s wrote important things down.".formatted(absent));

        ObjectNode spine = root.putObject("spine");
        spine.put("arcTitle", zh ? place + "的第一夜" : "First Night at " + place);
        ArrayNode beats = spine.putArray("beats");
        String[] ids = {"beat_arrival", "beat_explore", "beat_evidence", "beat_confront", "beat_resolve"};
        for (int i = 0; i < ids.length; i++) {
            ObjectNode b = beats.addObject();
            b.put("id", ids[i]);
            b.put("title", beatTitle(i, zh, place));
            b.put("purpose", beatPurpose(i, zh));
            b.put("turn", beatTurn(i, zh));
            b.put("completionConditions", beatCompletion(i, zh));
            b.put("importance", i == 0 || i == ids.length - 1 ? "critical" : "major");
        }

        root.put("openingLocationId", "loc_threshold");
        ArrayNode threads = root.putArray("continuityThreads");
        mysteries.forEach(m -> threads.add(m.asText()));

        ArrayNode preparedVisuals = root.putArray("preparedVisuals");
        preparedVisuals.addObject().put("id", "npc_visual_1").put("visualDescription", zh
                ? "约三十岁的成人，深棕色短卷发，窄脸，橄榄色外套，米白色衬衣，深灰长裤，素色配饰。"
                : "An adult around thirty, short dark brown curls, a narrow face, olive coat, cream shirt, dark grey trousers, plain accessories.");
        preparedVisuals.addObject().put("id", "npc_visual_2").put("visualDescription", zh
                ? "约五十岁的成人，银灰色及肩直发，圆脸，海军蓝长外套，暗红围巾，黑色平底鞋。"
                : "An adult around fifty, straight shoulder-length silver-grey hair, a round face, navy long coat, muted red scarf, black flat shoes.");

        return root.toString();
    }

    private void putLocation(ArrayNode arr, String id, String name, String description, String visual) {
        ObjectNode n = arr.addObject();
        n.put("id", id);
        n.put("name", name);
        n.put("description", description);
        n.put("visualDescription", visual);
    }

    // ------------------------------------------------------------------ scene generation

    private String scene(LlmRequest request) {
        CompiledStory story = (CompiledStory) request.mockContext().get("story");
        GameState state = (GameState) request.mockContext().get("state");
        Choice choice = (Choice) request.mockContext().get("choice");
        String outcome = str(request.mockContext().getOrDefault("outcome", "NONE"));
        int sceneIndex = (int) request.mockContext().getOrDefault("sceneIndex", 0);

        String outline = story.authorCanon.originalOutline();
        boolean zh = isChinese(outline);
        Setting setting = detectSetting(outline);
        String place = zh ? setting.zh() : setting.en();
        String object = detectObject(outline, zh);
        String witness = detectWitnessName(outline, zh);
        String absent = detectAbsentName(outline, zh);

        boolean ending = state.currentBeatId == null;
        // A beat gets two scenes: the one that opens it and the one that fulfils it.
        boolean completesBeat = !ending && state.scenesInCurrentBeat >= 1;
        int narrationBeat = archetypeOf(story, state, state.currentBeatId);
        StoryBeat next = ending ? null : story.spine.nextAfter(state.currentBeatId);
        // The scene that closes a beat still narrates that beat, but its choices belong to the
        // next one -- they are what the player will actually be doing in the following scene.
        int choiceBeat = !completesBeat ? narrationBeat
                : (next == null ? FINAL_BEAT : archetypeOf(story, state, next.id()));

        int seed = sceneIndex * 7 + narrationBeat * 3 + outcome.length();
        return new MockSceneWriter(mapper, zh, place, object, witness, absent, state, story,
                choice, outcome, narrationBeat, choiceBeat, completesBeat, ending, seed, sceneIndex).write();
    }

    /** Marker for "the spine has run out": the scene offers only a way to close the story. */
    static final int FINAL_BEAT = -1;

    /**
     * Maps a beat to one of the five narrative archetypes the template bank is written for.
     * Later arcs start at the "traces" archetype so a continuation does not replay an arrival.
     */
    private int archetypeOf(CompiledStory story, GameState state, String beatId) {
        List<StoryBeat> beats = story.spine.beats();
        int index = MockStoryTemplates.BEAT_RESOLVE;
        for (int i = 0; i < beats.size(); i++) {
            if (beats.get(i).id().equals(beatId)) {
                index = i;
                break;
            }
        }
        int offset = state.storyProgress.arcNumber > 1 ? MockStoryTemplates.BEAT_EVIDENCE : 0;
        return Math.min(index + offset, MockStoryTemplates.BEAT_RESOLVE);
    }

    // ------------------------------------------------------------------ arc continuation

    /** Fresh appearance-only designs with ids nobody holds; no names, roles or plot. */
    @SuppressWarnings("unchecked")
    private String spareDesigns(LlmRequest request) {
        CompiledStory story = (CompiledStory) request.mockContext().get("story");
        boolean zh = story != null && isChinese(story.authorCanon.originalOutline());
        int count = request.mockContext().get("count") instanceof Integer n ? n : 1;
        List<String> taken = request.mockContext().get("takenIds") instanceof List<?> l ? (List<String>) l : List.of();
        String[] looks = zh
                ? new String[]{
                    "约二十岁的青年，浅棕色齐肩发，圆脸，芥末黄针织衫，深蓝直筒裤，帆布鞋。",
                    "约四十岁的成人，黑色后梳短发，方脸，炭灰色大衣，白色高领，皮质手套。",
                    "约六十岁的长者，花白盘发，瘦长脸，墨绿色长裙，浅灰披肩，圆框眼镜。",
                    "约十六岁的少年，蓬松黑短发，尖下巴，卡其色夹克，条纹围巾，运动鞋。",
                    "约三十五岁的成人，酒红色长卷发，高颧骨，象牙色风衣，黑色长靴，银色耳饰。",
                    "约五十岁的成人，剃短的灰发，宽下颌，藏青色工装外套，深棕长裤，旧皮靴。"}
                : new String[]{
                    "A young adult around twenty, light brown shoulder-length hair, a round face, mustard knit sweater, dark blue straight trousers, canvas shoes.",
                    "An adult around forty, black hair combed back, a square face, charcoal overcoat, white turtleneck, leather gloves.",
                    "An elder around sixty, greying hair in a bun, a long thin face, deep green long dress, pale grey shawl, round glasses.",
                    "A teenager around sixteen, tousled short black hair, a pointed chin, khaki jacket, striped scarf, sneakers.",
                    "An adult around thirty-five, long wine-red curls, high cheekbones, ivory trench coat, black boots, silver earrings.",
                    "An adult around fifty, close-cropped grey hair, a broad jaw, navy work jacket, dark brown trousers, worn leather boots."};
        ObjectNode root = mapper.createObjectNode();
        ArrayNode designs = root.putArray("preparedVisuals");
        int k = 1;
        for (int made = 0; made < count; k++) {
            String id = "npc_visual_" + k;
            if (taken.contains(id)) continue;
            designs.addObject().put("id", id).put("visualDescription", looks[(k - 1) % looks.length]);
            made++;
        }
        return root.toString();
    }

    private String arc(LlmRequest request) {
        CompiledStory story = (CompiledStory) request.mockContext().get("story");
        GameState state = (GameState) request.mockContext().get("state");
        boolean zh = isChinese(story.authorCanon.originalOutline());
        Setting setting = detectSetting(story.authorCanon.originalOutline());
        String place = zh ? setting.zh() : setting.en();
        String absent = detectAbsentName(story.authorCanon.originalOutline(), zh);

        List<String> unresolved = state.continuityLedger.stream()
                .filter(e -> !com.genvn.game.ContinuityEntry.RESOLVED.equals(e.status))
                .map(e -> e.id)
                .toList();

        ObjectNode root = mapper.createObjectNode();
        root.put("arcTitle", zh ? "第二夜：还没有结束的事" : "The Second Night: What Is Not Finished");
        root.put("premise", zh
                ? "你拿到了想要的东西，但它指向的不是终点，而是另一个地址。%s留下的不止一份。".formatted(absent)
                : "You got what you came for, and it points not at an ending but at another address. %s left more than one.".formatted(absent));
        root.put("majorConflict", zh
                ? "继续挖下去意味着把还活着的人也牵进来。"
                : "Digging further means pulling someone still living into it.");
        ArrayNode beats = root.putArray("beats");
        String[][] rows = zh
                ? new String[][]{
                    {"beat2_letter", "第二封信", "一封寄给玩家的信送到了，署名是本不该还活着的人。", "玩家读完信。", "失踪者可能还活着。"},
                    {"beat2_ask", "再问一次", "玩家带着信回去找目击者，目击者不肯再开口。", "目击者说出他一直没说的部分。", "目击者当年撒了谎。"},
                    {"beat2_return", "回到" + place, "有人抢在玩家前面回到这里，翻动了藏匿处。", "第二样东西被找到。", "玩家不是唯一在找的人。"},
                    {"beat2_choice", "第二个地址", "玩家必须决定是否把那个还活着的人牵进来。", "玩家做出决定并动身。", "真相有了代价，而且是别人来付。"}}
                : new String[][]{
                    {"beat2_letter", "The second letter", "A letter reaches the player, signed by someone who should not be alive.", "The player reads the letter.", "The missing person may be alive."},
                    {"beat2_ask", "Asking again", "The player takes the letter to the witness, who refuses to speak.", "The witness says the part they held back.", "The witness lied back then."},
                    {"beat2_return", "Back to " + place, "Someone got here first and went through the hiding place.", "The second thing is found.", "The player is not the only one looking."},
                    {"beat2_choice", "The second address", "The player must decide whether to pull the living person into it.", "The player decides and sets out.", "The truth has a price, and someone else pays it."}};
        for (String[] row : rows) {
            ObjectNode b = beats.addObject();
            b.put("id", row[0]);
            b.put("title", row[1]);
            b.put("purpose", row[2]);
            b.put("completionConditions", row[3]);
            b.put("turn", row[4]);
            b.put("importance", "major");
        }
        ArrayNode advance = root.putArray("threadsToAdvance");
        unresolved.stream().limit(2).forEach(advance::add);
        ArrayNode resolve = root.putArray("threadsToResolve");
        unresolved.stream().findFirst().ifPresent(resolve::add);
        ArrayNode neu = root.putArray("optionalNewThreads");
        neu.add(zh ? "第二个地址上住着谁？" : "Who lives at the second address?");
        return root.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LlmCancellation.check();
        }
    }
}
