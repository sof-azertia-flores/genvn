package com.genvn.story;

import com.genvn.asset.AssetSpec;
import com.genvn.game.CharacterState;
import com.genvn.game.ContinuityEntry;
import com.genvn.game.GameState;
import com.genvn.game.PlayerCharacter;
import com.genvn.game.CreationMilestones;
import com.genvn.game.SessionService;
import com.genvn.game.Stat;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.StructuredLlm;
import com.genvn.prompt.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Turns a player's outline plus their character into structured, runnable world data. */
@Service
public class StoryCompiler {

    private static final Logger log = LoggerFactory.getLogger(StoryCompiler.class);

    /** User story text is data, never instructions -- but it is also not unbounded. */
    public static final int MAX_OUTLINE_CHARS = 6000;
    /** The prompt asks for 8-12 beats; fewer than this is a sketch and goes back for repair. */
    public static final int MIN_BEATS = 5;

    private final StructuredLlm llm;

    public StoryCompiler(StructuredLlm llm) {
        this.llm = llm;
    }

    public record Compiled(CompiledStory story, GameState state, long millis, int repairAttempts) {}

    public Compiled compile(String sessionId, String rawOutline, PlayerCharacter player) {
        return compile(sessionId, rawOutline, player, "");
    }

    public Compiled compile(String sessionId, String rawOutline, PlayerCharacter player, String artStyle) {
        return compile(sessionId, rawOutline, player, artStyle, (stage, value, message) -> {});
    }

    public Compiled compile(String sessionId, String rawOutline, PlayerCharacter player, String artStyle,
                            SessionService.CreationProgress progress) {
        String outline = sanitize(rawOutline);
        progress.report("整理故事输入", 4, "故事输入与角色资料已整理，准备编译世界框架。");
        log.info("Session {}: compiling story for player '{}' ({} chars of outline)",
                sessionId, player.name, outline.length());
        Map<String, Object> mockContext = Map.of(
                "outline", outline,
                "playerName", player.name == null ? "" : player.name,
                "playerBackground", player.background == null ? "" : player.background);

        LlmRequest request = LlmRequest.of(LlmPurpose.STORY_COMPILE,
                Prompts.COMPILER_SYSTEM,
                Prompts.compilerUser(outline, playerBrief(player)),
                mockContext);
        progress.report("准备编译请求", 6, "已准备故事边界、人物资料和结构要求，开始请求故事框架。");

        StructuredLlm.Parsed<CompilerResponse> parsed =
                llm.call(request, CompilerResponse.class, StoryCompiler::validate, CreationMilestones.model(progress, false));
        CompilerResponse response = parsed.value();

        AuthorCanon canon = new AuthorCanon(outline, dedupe(response.authorCanonFacts()));
        progress.report("确认故事事实", 40, "已整理你指定的故事事实，后续生成将以此为依据。");
        CompiledStory story = new CompiledStory(canon, response.bible(), response.spine());
        story.preparedVisuals = new ArrayList<>(response.preparedVisuals());
        progress.report("建立世界与地点", 42, "世界设定与地点清单已建立。");
        progress.report("建立人物档案", 44, "已收录故事框架中的人物，准备各自的外貌资料。");
        progress.report("展开故事节拍", 46, "章节目标与故事节拍已确认，将据此安排后续场景。");
        story.artStyle = sanitize(artStyle);
        String appearance = player.visualDescription == null ? "" : player.visualDescription.trim();
        String background = player.background == null ? "" : player.background.trim();
        String traits = String.join("; ", player.traits);
        String visual = appearance.isBlank()
                ? "Protagonist " + player.name + ". Background: " + background + ". Traits: " + traits
                    + ". Choose a coherent appearance suited to these details."
                : appearance + ". Character background: " + background + ". Traits: " + traits;
        story.playerVisual = new NpcProfile(PlayerCharacter.ID, player.name, background, traits,
                List.of(), List.of(), "Only words explicitly chosen by the player", "player-controlled protagonist", visual);
        progress.report("准备玩家形象", 48, "已根据你填写的特征建立玩家形象，接下来安排人物与场景图片。");
        GameState state = initialState(sessionId, story, player, response);
        progress.report("建立初始游戏状态", 51, "初始地点、角色属性、关系和待追踪线索已建立。");
        log.info("Session {}: compiled '{}' -- {} canon facts, {} characters, {} locations, {} beats, {} threads ({}s)",
                sessionId, story.spine.arcTitle(), canon.facts().size(), story.bible.characters().size(),
                story.bible.locations().size(), story.spine.beats().size(), state.continuityLedger.size(),
                parsed.totalMillis() / 1000);
        return new Compiled(story, state, parsed.totalMillis(), parsed.repairAttempts());
    }

    /** Domain validation on top of schema deserialization. Returns null when usable. */
    static String validate(CompilerResponse r) {
        if (r == null) return "empty response";
        if (r.bible() == null) return "missing 'bible'";
        if (r.spine() == null || r.spine().beats().isEmpty()) return "missing 'spine.beats' (need at least one beat)";
        if (r.bible().locations().isEmpty()) return "'bible.locations' must contain at least one location";
        if (r.bible().premise() == null || r.bible().premise().isBlank()) return "missing 'bible.premise'";
        String beatProblem = StorySpine.validateBeats(r.spine().beats(), MIN_BEATS, true);
        if (beatProblem != null) return beatProblem;
        var locationIds = new HashSet<String>();
        for (LocationProfile loc : r.bible().locations()) {
            if (!AssetSpec.safeSubjectId(loc.id())) return "location ids must be unique lowercase slugs of at most 48 characters; use loc_example";
            if (!locationIds.add(loc.id())) return "duplicate location id '" + loc.id() + "'; every location needs its own id";
        }
        var characterIds = new HashSet<String>();
        for (NpcProfile npc : r.bible().characters()) {
            if (!AssetSpec.safeSubjectId(npc.id())) return "character ids must be unique lowercase slugs of at most 48 characters; use npc_example";
            if (PlayerCharacter.ID.equals(npc.id())) return "'player' is reserved for the protagonist; bible.characters is NPCs only";
            if (!characterIds.add(npc.id())) return "duplicate character id '" + npc.id() + "'; every character needs their own id";
            if (npc.name() == null || npc.name().isBlank()) return "character '" + npc.id() + "' is missing its 'name'";
        }
        String preparedProblem = PreparedVisual.validateAll(r.preparedVisuals(), r.bible());
        if (preparedProblem != null) return preparedProblem;
        if (r.authorCanonFacts() == null || r.authorCanonFacts().isEmpty()) {
            return "'authorCanonFacts' must restate what the player actually asserted";
        }
        return null;
    }

    private GameState initialState(String sessionId, CompiledStory story, PlayerCharacter player, CompilerResponse r) {
        GameState state = new GameState();
        state.sessionId = sessionId;
        state.player = player;
        state.currentArcTitle = story.spine.arcTitle();
        state.currentBeatId = story.spine.beats().get(0).id();
        state.storyProgress.totalBeats = story.spine.beats().size();
        state.storyProgress.recompute();

        for (NpcProfile npc : story.bible.characters()) {
            state.characters.put(npc.id(), new CharacterState(npc.id(), npc.name()));
        }

        String opening = r.openingLocationId();
        if (opening == null || story.bible.location(opening) == null) {
            opening = story.bible.locations().get(0).id();
        }
        state.currentLocationId = opening;
        state.knownLocationIds.add(opening);

        List<String> threads = new ArrayList<>();
        if (r.continuityThreads() != null) threads.addAll(r.continuityThreads());
        if (threads.isEmpty()) threads.addAll(story.bible.mysteries());
        int n = 1;
        for (String t : threads) {
            if (t == null || t.isBlank()) continue;
            state.continuityLedger.add(new ContinuityEntry(String.format("P%03d", n++), t.trim(), "scene_000"));
            if (n > 6) break;
        }
        return state;
    }

    private static String playerBrief(PlayerCharacter player) {
        String stats = player.stats.entrySet().stream()
                .map(e -> e.getKey().display() + " " + e.getValue())
                .collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(player.name).append('\n');
        sb.append("Background: ").append(player.background == null || player.background.isBlank()
                ? "(the player did not specify one -- invent something modest and consistent)"
                : player.background).append('\n');
        sb.append("Visual appearance: ").append(player.visualDescription == null ? "" : sanitize(player.visualDescription)).append('\n');
        sb.append("Stats (0-5 modifiers): ").append(stats).append('\n');
        sb.append("HP: ").append(player.hp).append('/').append(player.maxHp).append('\n');
        sb.append("Traits: ").append(player.traits.isEmpty() ? "(none given)" : String.join("; ", player.traits));
        return sb.toString();
    }

    private static List<String> dedupe(List<String> in) {
        if (in == null) return List.of();
        Map<String, String> seen = new LinkedHashMap<>();
        for (String s : in) {
            if (s == null || s.isBlank()) continue;
            String trimmed = s.trim();
            seen.putIfAbsent(trimmed.toLowerCase(), trimmed);
        }
        return new ArrayList<>(seen.values());
    }

    public static String sanitize(String outline) {
        if (outline == null) return "";
        String s = outline.trim();
        if (s.length() > MAX_OUTLINE_CHARS) s = s.substring(0, MAX_OUTLINE_CHARS);
        // Neutralise the delimiter so story text cannot close its own quoting block.
        return s.replace("<<<", "<< <").replace(">>>", "> >>");
    }

    public static PlayerCharacter defaultPlayer() {
        PlayerCharacter p = new PlayerCharacter();
        for (Stat s : Stat.values()) p.stats.put(s, 2);
        return p;
    }
}
