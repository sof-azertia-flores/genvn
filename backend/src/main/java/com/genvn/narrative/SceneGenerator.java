package com.genvn.narrative;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetManifest;
import com.genvn.asset.AssetResolver;
import com.genvn.asset.AssetSpec;
import com.genvn.dice.CheckResolver;
import com.genvn.game.GameState;
import com.genvn.game.PlayerCharacter;
import com.genvn.game.Stat;
import com.genvn.game.StateDelta;
import com.genvn.game.StateReducer;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.GenerationProgress;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.StructuredLlm;
import com.genvn.prompt.ContextRenderer;
import com.genvn.prompt.Prompts;
import com.genvn.story.CompiledStory;
import com.genvn.story.NpcProfile;
import com.genvn.story.PreparedVisual;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Produces one SceneBundle for a given situation.
 *
 * It is deliberately forgiving: anything that can be repaired locally (a stat spelled oddly, a
 * DC out of range, a duplicate choice id, a dialogue line attributed to nobody) is repaired
 * here rather than costing a round trip. Only structural damage triggers a repair request.
 */
@Service
public class SceneGenerator {

    private static final Logger log = LoggerFactory.getLogger(SceneGenerator.class);

    private static final int MAX_CHOICES = 4;
    private static final int MAX_ON_STAGE = 3;
    private static final Pattern CHOICE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> EXPRESSIONS = Set.of(
            "neutral", "worried", "afraid", "angry", "sad", "happy", "suspicious", "surprised", "talking", "action");
    private static final Set<String> POSITIONS = Set.of("left", "center", "right");

    private final StructuredLlm llm;
    private final ContextRenderer context;
    private final StateReducer reducer;
    private final ObjectMapper mapper;
    private final AssetResolver assets;

    public SceneGenerator(StructuredLlm llm, ContextRenderer context) {
        this(llm, context, new StateReducer(), new ObjectMapper(), AssetResolver.none());
    }

    public SceneGenerator(StructuredLlm llm, ContextRenderer context, AssetResolver assets) {
        this(llm, context, new StateReducer(), new ObjectMapper(), assets);
    }

    public SceneGenerator(StructuredLlm llm, ContextRenderer context, StateReducer reducer, ObjectMapper mapper) {
        this(llm, context, reducer, mapper, AssetResolver.none());
    }

    @Autowired
    public SceneGenerator(StructuredLlm llm, ContextRenderer context, StateReducer reducer, ObjectMapper mapper,
                          AssetResolver assets) {
        this.llm = llm;
        this.context = context;
        this.reducer = reducer;
        this.mapper = mapper;
        this.assets = assets == null ? AssetResolver.none() : assets;
    }

    public SceneBundle generate(SceneRequest request) {
        return generate(request, GenerationProgress.NONE);
    }

    public SceneBundle generate(SceneRequest request, GenerationProgress progress) {
        CompiledStory story = request.story();
        log.info("Session {}: generating scene_{} ({}beat {}{}{})",
                request.state().sessionId, String.format("%03d", request.sceneIndex()),
                request.speculative() ? "speculative, " : "",
                request.state().currentBeatId,
                request.choice() == null ? ", opening" : ", after '" + request.choice().id() + "'",
                SceneRequest.NONE.equals(request.outcome()) ? "" : ", outcome " + request.outcome());

        String situation = request.choice() == null
                ? Prompts.openingSituation()
                : Prompts.choiceSituation(request.choice().text(), outcomeInstruction(request))
                    + "\nSelected choice actionKind: " + request.choice().actionKind()
                    + "; player expression: " + request.choice().playerExpression()
                    + ". Only a dialogue kind permits a verbatim player speech block.";
        var currentBeat = story.spine.beat(request.state().currentBeatId);
        if (currentBeat != null) {
            situation += "\n" + Prompts.beatPacing(request.state().scenesInCurrentBeat, currentBeat.title(), currentBeat.turn());
        }

        Map<String, Object> mockContext = new LinkedHashMap<>();
        mockContext.put("story", story);
        mockContext.put("state", request.state());
        mockContext.put("choice", request.choice());
        mockContext.put("outcome", request.outcome());
        mockContext.put("sceneIndex", request.sceneIndex());

        // A read-only snapshot of the picture manifest: the prompt sees what exists, the
        // resolver validates whatever the model references. Never bytes, never paths.
        Optional<AssetManifest> manifest = manifestFor(request.state().sessionId);
        LlmRequest llmRequest = LlmRequest.of(LlmPurpose.SCENE_GENERATE,
                Prompts.SCENE_SYSTEM,
                Prompts.sceneUser(
                        context.renderStoryFoundation(story, request.state()),
                        context.renderGameState(request.state(), story),
                        manifest.map(assets::renderForPrompt).orElse(""),
                        situation),
                mockContext);

        StructuredLlm.Parsed<SceneResponse> parsed = llm.call(llmRequest, SceneResponse.class,
                r -> validate(r, request), progress);

        String sceneId = "scene_%03d".formatted(request.sceneIndex());
        SceneMeta meta = new SceneMeta(parsed.model(), false, parsed.totalMillis(),
                request.outcome(), request.choice() == null ? null : request.choice().id(),
                parsed.repairAttempts());
        SceneBundle bundle = normalize(parsed.value(), story, sceneId, request, meta, manifest.orElse(null));
        log.info("Session {}: {} ready -- {} blocks, {} choices, {} proposed ops, at '{}' ({}s{})",
                request.state().sessionId, sceneId, bundle.blocks().size(), bundle.choices().size(),
                bundle.proposedStateDelta().ops().size(), bundle.location().name(),
                parsed.totalMillis() / 1000, request.speculative() ? ", speculative" : "");
        return bundle;
    }

    /** Pictures are optional: a picture library that cannot be read must not stop the prose. */
    private Optional<AssetManifest> manifestFor(String sessionId) {
        try {
            return assets.snapshot(sessionId);
        } catch (RuntimeException e) {
            log.warn("Session {}: picture manifest unavailable, writing the scene without it: {}", sessionId, e.toString());
            return Optional.empty();
        }
    }

    private String outcomeInstruction(SceneRequest request) {
        if (SceneRequest.SUCCESS.equals(request.outcome()) || SceneRequest.FAILURE.equals(request.outcome())) {
            Stat stat = request.choice() != null && request.choice().hasCheck()
                    ? Stat.fromLoose(request.choice().check().stat())
                    : null;
            return Prompts.checkOutcome(SceneRequest.SUCCESS.equals(request.outcome()),
                    stat == null ? "" : stat.display());
        }
        return Prompts.noCheckOutcome();
    }

    private String validate(SceneResponse r, SceneRequest request) {
        if (r == null) return "empty response";
        if (r.blocks() == null || r.blocks().isEmpty()) return "missing 'blocks' (a scene needs at least one)";
        boolean anyText = r.blocks().stream().anyMatch(b -> b != null && b.text() != null && !b.text().isBlank());
        if (!anyText) return "every block had empty 'text'";
        if (r.location() == null) return "missing 'location'";
        String locationId = r.location().id();
        if (locationId != null && !locationId.isBlank() && request.story().bible.location(locationId) == null
                && !AssetSpec.safeSubjectId(locationId)) {
            return "a new location needs a unique safe id of at most 48 characters that is unchanged by asset slugging; "
                    + "use lowercase letters, digits, underscores, hyphens or Chinese characters, without leading separators";
        }
        String npcProblem = validateIntroductions(r, request.story());
        if (npcProblem != null) return npcProblem;
        String repeated = repeatedLine(r, request.state());
        if (repeated != null) return repeated;
        if (normalizeChoices(r.choices()).isEmpty()) {
            // The final scene can complete the last beat itself. Judge its ending using the
            // same reducer as the eventual commit, without touching canonical/speculative state.
            GameState projected = request.state().deepCopy(mapper);
            reducer.apply(projected, request.story(), r.proposedStateDelta(), "scene_validation");
            if (projected.currentBeatId != null) {
                return "missing usable 'choices' -- the story is not finished after its validated state delta; "
                        + "provide options with non-empty text";
            }
        }
        return null;
    }

    /** Local, cheap repair of everything that does not need another model round trip. */
    private SceneBundle normalize(SceneResponse r, CompiledStory story, String sceneId,
                                  SceneRequest request, SceneMeta meta, AssetManifest manifest) {
        Map<String, NpcIntroduction> introductions = normalizeIntroductions(r, story);
        SceneLocation location = normalizeLocation(r.location(), story, request);
        String bgHint = r.location() == null ? null : r.location().backgroundAssetId();
        location = location.withBackgroundAssetId(
                assets.resolveBackground(manifest, location.id(), bgHint, request.outcome()));

        List<CharacterPresence> characters = new ArrayList<>();
        Set<String> onStage = new HashSet<>();
        if (r.characters() != null) {
            for (CharacterPresence c : r.characters()) {
                if (c == null || c.characterId() == null || c.characterId().isBlank()) continue;
                if (!PlayerCharacter.ID.equals(c.characterId())
                        && characters.stream().filter(p -> !PlayerCharacter.ID.equals(p.characterId())).count() >= MAX_ON_STAGE) continue;
                if (!onStage.add(c.characterId())) continue;
                NpcProfile profile = profile(story, introductions, c.characterId());
                String expression = PlayerCharacter.ID.equals(c.characterId()) && request.choice() != null
                        ? request.choice().playerExpression()
                        : EXPRESSIONS.contains(lower(c.expression())) ? lower(c.expression()) : "neutral";
                characters.add(new CharacterPresence(
                        c.characterId(),
                        PlayerCharacter.ID.equals(c.characterId()) ? request.state().player.name
                                : profile != null ? profile.name() : blankTo(c.name(), c.characterId()),
                        expression,
                        POSITIONS.contains(lower(c.position())) ? lower(c.position()) : "center",
                        profile != null ? profile.visualDescription() : c.visualDescription(),
                        assets.resolvePortrait(manifest, c.characterId(), expression, c.assetId(), request.outcome())));
            }
        }

        // Runtime records exactly the selected line, rather than trusting the model to invent PC speech.
        boolean playerSpoke = request.choice() != null && "dialogue".equals(request.choice().actionKind());
        List<Block> blocks = new ArrayList<>();
        if (playerSpoke) {
            blocks.add(Block.dialogue(PlayerCharacter.ID, request.state().player.name,
                    request.choice().text(), request.choice().playerExpression()));
        }
        if (request.choice() != null && !onStage.contains(PlayerCharacter.ID)) {
            onStage.add(PlayerCharacter.ID);
            var profile = story.playerVisual;
            String expression = request.choice().playerExpression();
            characters.add(new CharacterPresence(PlayerCharacter.ID, request.state().player.name, expression, "right",
                    profile == null ? request.state().player.visualDescription : profile.visualDescription(),
                    assets.resolvePortrait(manifest, PlayerCharacter.ID, expression, null, request.outcome())));
        }
        for (Block b : r.blocks()) {
            if (b == null || b.text() == null || b.text().isBlank()) continue;
            String text = b.text().trim();
            boolean isDialogue = "dialogue".equalsIgnoreCase(b.type());
            if (!isDialogue) {
                blocks.add(Block.narration(text));
                continue;
            }
            String speakerId = b.speakerId();
            // All permitted protagonist speech was inserted from the selected choice above.
            // Ignore unchosen speech, substitutions and repeated player blocks from the model.
            if (PlayerCharacter.ID.equals(speakerId)) continue;
            NpcProfile profile = profile(story, introductions, speakerId);
            String speakerName = profile != null ? profile.name() : b.speakerName();
            if (speakerName == null || speakerName.isBlank()) {
                // A line attributed to nobody reads better as narration than as a broken name plate.
                blocks.add(Block.narration(text));
                continue;
            }
            String expression = EXPRESSIONS.contains(lower(b.expression())) ? lower(b.expression()) : "neutral";
            blocks.add(Block.dialogue(speakerId, speakerName, text, expression));
            if (speakerId != null && !onStage.contains(speakerId)
                    && characters.stream().filter(c -> !PlayerCharacter.ID.equals(c.characterId())).count() < MAX_ON_STAGE) {
                // The model wrote a line for someone it forgot to put on stage: put them on stage.
                onStage.add(speakerId);
                characters.add(new CharacterPresence(speakerId, speakerName, expression, "center",
                        profile != null ? profile.visualDescription() : null,
                        assets.resolvePortrait(manifest, speakerId, expression, null, request.outcome())));
            }
        }
        if (blocks.isEmpty()) blocks.add(Block.narration("..."));

        List<Choice> choices = normalizeChoices(r.choices());

        StateDelta delta = r.proposedStateDelta() == null ? StateDelta.empty() : r.proposedStateDelta();

        // A named supporting figure from a lenient provider still gets a stable, transient identity.
        // It cannot enter the Bible without an explicit, validated recurring declaration.
        for (CharacterPresence character : characters) {
            String id = character.characterId();
            if (story.visualCharacter(id) == null && PreparedVisual.validId(id)
                    && !introductions.containsKey(id)) {
                introductions.put(id, new NpcIntroduction(new NpcProfile(id, character.name(),
                        "", "", List.of(), List.of(), "", "", character.visualDescription()), null, false));
            }
        }
        List<NpcIntroduction> presentIntroductions = introductions.values().stream()
                .filter(introduction -> onStage.contains(introduction.profile().id())).toList();

        return new SceneBundle(sceneId, request.state().currentBeatId, location, characters, blocks,
                choices, delta, r.storyProgressNote(), r.assetRequests(), presentIntroductions, meta);
    }

    /** Longest run of characters an NPC line may share with one of their recent lines. */
    private static final int MAX_SHARED_CJK = 11;
    private static final int MAX_SHARED_LATIN = 40;

    /**
     * A character who says the same thing again is a tic, not a person. Compare each NPC line
     * with that speaker's lines in the recent committed scenes; a long shared run (a repeated
     * sentence, not a repeated word) sends the scene back for repair.
     */
    private static String repeatedLine(SceneResponse response, GameState state) {
        if (response.blocks() == null || state.recentScenes == null || state.recentScenes.isEmpty()) return null;
        for (Block block : response.blocks()) {
            if (block == null || !"dialogue".equalsIgnoreCase(block.type()) || block.speakerId() == null
                    || PlayerCharacter.ID.equals(block.speakerId()) || block.text() == null) continue;
            String line = normalizeLine(block.text());
            if (line.isEmpty()) continue;
            int limit = mostlyCjk(line) ? MAX_SHARED_CJK : MAX_SHARED_LATIN;
            for (var scene : state.recentScenes) {
                for (Block earlier : scene.blocks()) {
                    if (earlier == null || !"dialogue".equalsIgnoreCase(earlier.type())
                            || !block.speakerId().equals(earlier.speakerId()) || earlier.text() == null) continue;
                    String previous = normalizeLine(earlier.text());
                    int shared = longestCommonRun(line, previous);
                    if (shared >= limit) {
                        String excerpt = earlier.text().trim();
                        if (excerpt.length() > 40) excerpt = excerpt.substring(0, 40) + "…";
                        return "'" + block.speakerId() + "' repeats a line they already said in " + scene.sceneId()
                                + " (\"" + excerpt + "\"); give them something new to say, a new stance, or silence";
                    }
                }
            }
        }
        return null;
    }

    private static String normalizeLine(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？…、“”‘’：；（）《》]+", "");
    }

    private static boolean mostlyCjk(String s) {
        int cjk = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= '\u4e00' && s.charAt(i) <= '\u9fff') cjk++;
        return cjk * 2 >= s.length();
    }

    /** Longest common substring length, by dynamic programming; lines are short. */
    private static int longestCommonRun(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[] previous = new int[b.length() + 1];
        int best = 0;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    current[j] = previous[j - 1] + 1;
                    if (current[j] > best) best = current[j];
                }
            }
            previous = current;
        }
        return best;
    }

    private String validateIntroductions(SceneResponse response, CompiledStory story) {
        Set<String> declared = new HashSet<>();
        if (response.newNpcs() != null) {
            if (response.newNpcs().size() > MAX_ON_STAGE) return "newNpcs may introduce at most three on-stage NPCs";
            for (NpcIntroduction introduction : response.newNpcs()) {
                NpcProfile npc = introduction == null ? null : introduction.profile();
                if (npc == null || !PreparedVisual.validId(npc.id())) return "newNpcs needs a valid NPC profile id";
                if (!declared.add(npc.id())) return "newNpcs ids must be unique";
                if (npc.name() == null || npc.name().isBlank()) return "newNpcs needs a name created for this scene";
                String designId = introduction.preparedVisualId();
                PreparedVisual design = story.preparedVisual(npc.id());
                if (designId != null && !designId.isBlank()) {
                    if (!designId.equals(npc.id())) return "when reusing a design, profile.id must equal preparedVisualId and all scene references must use that stable id";
                    if (story.preparedVisual(designId) == null) return "preparedVisualId is not an unassigned design; never assign a second identity to an already encountered person";
                } else if (design != null) {
                    return "newNpcs using an unassigned design id must explicitly set preparedVisualId to the same id";
                }
                if (story.visualCharacter(npc.id()) == null && design == null
                        && (npc.visualDescription() == null || npc.visualDescription().isBlank())) {
                    return "a newly drawn NPC needs profile.visualDescription";
                }
                if (story.visualCharacter(npc.id()) == null && design == null) {
                    // A look belongs to one person. Copying a spare design's appearance under a
                    // new id would put two people in the same picture.
                    String owner = appearanceOwner(story, npc.visualDescription());
                    if (owner != null) {
                        return "profile.visualDescription copies the appearance of " + owner
                                + "; to reuse an unassigned design set both profile.id and preparedVisualId to its id, "
                                + "otherwise give this new person a genuinely different appearance";
                    }
                }
                if (introduction.recurring() && story.visualCharacter(npc.id()) == null
                        && (npc.description() == null || npc.description().isBlank()
                            || npc.personality() == null || npc.personality().isBlank()
                            || npc.speakingStyle() == null || npc.speakingStyle().isBlank())) {
                    return "a recurring NPC needs a full profile: description, personality and speakingStyle";
                }
            }
        }
        Set<String> referenced = new HashSet<>();
        if (response.characters() != null) response.characters().stream().filter(java.util.Objects::nonNull)
                .forEach(c -> referenced.add(c.characterId()));
        if (response.blocks() != null) response.blocks().stream().filter(java.util.Objects::nonNull)
                .filter(b -> "dialogue".equalsIgnoreCase(b.type())).forEach(b -> referenced.add(b.speakerId()));
        for (String id : referenced) {
            if (story.preparedVisual(id) != null && !declared.contains(id)) {
                return "an unassigned visual design is not a person yet; supply newNpcs with the identity created for this scene";
            }
        }
        return null;
    }

    /** Which unassigned design or established person already wears this exact appearance, or null. */
    private static String appearanceOwner(CompiledStory story, String appearance) {
        String wanted = normalizeAppearance(appearance);
        if (wanted.isEmpty()) return null;
        for (PreparedVisual design : story.preparedVisuals) {
            if (wanted.equals(normalizeAppearance(design.visualDescription()))) return "unassigned design " + design.id();
        }
        for (NpcProfile npc : story.bible.characters()) {
            if (wanted.equals(normalizeAppearance(npc.visualDescription()))) return "established character " + npc.id();
        }
        for (NpcProfile npc : story.encounteredNpcs) {
            if (wanted.equals(normalizeAppearance(npc.visualDescription()))) return "encountered person " + npc.id();
        }
        return null;
    }

    private static String normalizeAppearance(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。；：、]+", " ").trim();
    }

    private Map<String, NpcIntroduction> normalizeIntroductions(SceneResponse response, CompiledStory story) {
        Map<String, NpcIntroduction> introductions = new LinkedHashMap<>();
        if (response.newNpcs() == null) return introductions;
        for (NpcIntroduction introduction : response.newNpcs()) {
            NpcProfile proposed = introduction.profile();
            NpcProfile established = story.visualCharacter(proposed.id());
            PreparedVisual design = story.preparedVisual(proposed.id());
            NpcProfile profile = established != null ? established : design == null ? proposed
                    : new NpcProfile(proposed.id(), proposed.name(), proposed.description(), proposed.personality(),
                            proposed.goals(), proposed.secrets(), proposed.speakingStyle(), proposed.relationshipToPlayer(),
                            design.visualDescription());
            introductions.put(profile.id(), new NpcIntroduction(profile, introduction.preparedVisualId(), introduction.recurring()));
        }
        return introductions;
    }

    private static NpcProfile profile(CompiledStory story, Map<String, NpcIntroduction> introductions, String id) {
        NpcProfile established = story.visualCharacter(id);
        NpcIntroduction introduction = introductions.get(id);
        return established != null ? established : introduction == null ? null : introduction.profile();
    }

    private List<Choice> normalizeChoices(List<Choice> proposed) {
        List<Choice> choices = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (proposed != null) {
            for (Choice c : proposed) {
                if (c == null || c.text() == null || c.text().isBlank()) continue;
                if (choices.size() >= MAX_CHOICES) break;
                String id = c.id() == null || !CHOICE_ID.matcher(c.id()).matches() || !ids.add(c.id())
                        ? uniqueId(ids, choices.size())
                        : c.id();
                choices.add(new Choice(id, c.text().trim(), blankTo(c.approach(), "action"),
                        normalizeCheck(c.check()), c.requirements(), c.actionKind(), c.playerExpression()));
            }
        }

        return choices;
    }

    private Check normalizeCheck(Check check) {
        if (check == null) return null;
        Stat stat = Stat.fromLoose(check.stat());
        if (stat == null) return null; // an unusable stat becomes an ordinary, checkless choice
        return new Check(stat.display(), CheckResolver.clampDc(check.dc()), check.description());
    }

    private SceneLocation normalizeLocation(SceneLocation location, CompiledStory story, SceneRequest request) {
        String id = location == null ? null : location.id();
        if (id == null || id.isBlank()) {
            id = request.state().currentLocationId;
        }
        var known = story.bible.location(id);
        String name = location == null ? null : location.name();
        String visual = location == null ? null : location.visualDescription();
        String prompt = location == null ? null : location.backgroundPrompt();
        if (known != null) {
            name = blankTo(name, known.name());
            visual = blankTo(visual, known.visualDescription());
        }
        return new SceneLocation(
                id == null ? "loc_unknown" : id,
                blankTo(name, "Somewhere"),
                blankTo(visual, ""),
                blankTo(prompt, visual),
                null);
    }

    private static String uniqueId(Set<String> ids, int index) {
        String candidate = "c" + (index + 1);
        int n = index + 1;
        while (!ids.add(candidate)) {
            candidate = "c" + (++n);
        }
        return candidate;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
