package com.genvn.story;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetSpec;
import com.genvn.config.GenvnProperties;
import com.genvn.config.UiLanguage;
import com.genvn.game.GameState;
import com.genvn.game.PlayerCharacter;
import com.genvn.llm.GenerationProgress;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.StructuredLlm;
import com.genvn.prompt.ContextRenderer;
import com.genvn.prompt.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-plans the framework of a story that is already being played, from the player's own words.
 *
 * This is the only place the compiled framework may be rewritten mid-arc. An arc continuation
 * (see {@link ArcContinuationService}) grows the story forward and may never touch author canon;
 * a restructure is the opposite: the player has rejected where the plot went, so the canon is
 * exactly the thing that gives way. What it may NOT do is unmake what has already been played --
 * completed beats are kept verbatim, and everyone the player has met stays in the bible.
 *
 * The player's instruction reaches the model and nothing else. It is never returned, logged or
 * persisted: the only trace a restructure leaves is the revised framework itself.
 */
@Service
public class StoryRestructurePlanner {

    private static final Logger log = LoggerFactory.getLogger(StoryRestructurePlanner.class);

    /** The prompt asks for 5-10 beats; fewer than this is not a plot. */
    public static final int MIN_BEATS = 4;
    /** A restructure instruction is one paragraph of intent, not a second story outline. */
    public static final int MAX_INSTRUCTION_CHARS = 2000;

    private final StructuredLlm llm;
    private final ContextRenderer context;
    private final ObjectMapper mapper;
    @Autowired(required = false)
    private GenvnProperties properties;

    public StoryRestructurePlanner(StructuredLlm llm, ContextRenderer context) {
        this(llm, context, new ObjectMapper());
    }

    @Autowired
    public StoryRestructurePlanner(StructuredLlm llm, ContextRenderer context, ObjectMapper mapper) {
        this.llm = llm;
        this.context = context;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    private String language() {
        return properties == null ? UiLanguage.ZH : properties.getLanguage();
    }

    /** Trim and cap the player's own words, and neutralise the delimiters the prompt uses. */
    public static String sanitize(String instruction) {
        if (instruction == null) return "";
        String trimmed = instruction.trim();
        if (trimmed.length() > MAX_INSTRUCTION_CHARS) trimmed = trimmed.substring(0, MAX_INSTRUCTION_CHARS);
        return trimmed.replace("<<<", "< < <").replace(">>>", "> > >");
    }

    /**
     * One model call against snapshots the caller owns. Never touches the live session.
     *
     * @param story the story as it stands, already a snapshot
     * @param state the state the rewritten scene will be written against (the parent scene's state)
     */
    public RestructureResponse plan(CompiledStory story, GameState state, String instruction,
                                    GenerationProgress progress) {
        String clean = sanitize(instruction);
        if (clean.isBlank()) throw new IllegalArgumentException("请写下你希望这段剧情怎么改。");

        Map<String, Object> mockContext = new LinkedHashMap<>();
        mockContext.put("story", story);
        mockContext.put("state", state);
        mockContext.put("instruction", clean);
        mockContext.put("language", language());

        LlmRequest request = LlmRequest.of(LlmPurpose.STORY_RESTRUCTURE,
                Prompts.restructureSystem(language()),
                Prompts.restructureUser(
                        context.renderRestructureFoundation(story, state),
                        context.renderGameState(state, story),
                        context.renderCompletedBeats(story, state),
                        clean),
                mockContext);

        RestructureResponse response = llm.call(request, RestructureResponse.class,
                r -> validate(r, story, state), progress).value();
        log.info("Session {}: framework revised -- {} canon facts, {} characters, {} locations, {} beats ahead",
                state.sessionId, response.authorCanonFacts().size(), response.bible().characters().size(),
                response.bible().locations().size(), response.spine().beats().size());
        return response;
    }

    /**
     * Domain validation. The two rules worth failing a round trip over are the ones the engine
     * cannot repair afterwards: a beat tail that reuses a completed beat's id (which would make
     * {@code completedBeats} ambiguous) and a bible that drops someone the player has already met.
     */
    static String validate(RestructureResponse r, CompiledStory story, GameState state) {
        if (r == null) return "empty response";
        if (r.spine() == null || r.spine().beats() == null || r.spine().beats().isEmpty()) {
            return "missing 'spine.beats' (the beats still to come)";
        }
        String bibleProblem = StoryCompiler.validateBibleShape(r.bible());
        if (bibleProblem != null) return bibleProblem;
        String beatProblem = StorySpine.validateBeats(r.spine().beats(), MIN_BEATS, true);
        if (beatProblem != null) return beatProblem;
        if (r.authorCanonFacts().isEmpty()) {
            return "'authorCanonFacts' must restate the complete corrected law of this story, not an empty list";
        }

        Set<String> completed = new HashSet<>(state.completedBeats == null ? List.of() : state.completedBeats);
        for (StoryBeat beat : r.spine().beats()) {
            if (completed.contains(beat.id())) {
                return "beat id '" + beat.id() + "' belongs to a beat that has already been completed; "
                        + "the beats still to come need new ids";
            }
        }

        Set<String> keptCharacters = new HashSet<>();
        for (NpcProfile npc : r.bible().characters()) keptCharacters.add(npc.id());
        for (String id : requiredCharacterIds(story, state)) {
            if (!keptCharacters.contains(id)) {
                return "character '" + id + "' has already appeared in this play-through and must stay in "
                        + "bible.characters with the same id; you may change who they are, not delete them";
            }
        }

        Set<String> keptLocations = new HashSet<>();
        for (LocationProfile loc : r.bible().locations()) keptLocations.add(loc.id());
        for (String id : requiredLocationIds(state)) {
            if (!keptLocations.contains(id)) {
                return "location '" + id + "' has already been visited and must stay in bible.locations "
                        + "with the same id";
            }
        }
        return null;
    }

    /**
     * Build the revised story. Completed beats are spliced in front of the model's tail, art
     * identity is carried over untouched, and any established person or place the model still
     * managed to lose is merged back in rather than failing the player's request over it.
     */
    public CompiledStory apply(CompiledStory story, GameState state, RestructureResponse response) {
        CompiledStory revised = mapper.convertValue(story, CompiledStory.class);

        // Author canon: the request is binding, so the corrected facts replace the old ones.
        // originalOutline is provenance -- what the player originally asked for -- and is never
        // rendered into a prompt, so it survives verbatim.
        revised.authorCanon = new AuthorCanon(story.authorCanon.originalOutline(), response.authorCanonFacts());
        revised.bible = mergeBible(story, state, response.bible());

        List<StoryBeat> beats = new ArrayList<>(keptBeats(story, state));
        beats.addAll(response.spine().beats());
        String arcTitle = response.spine().arcTitle() == null || response.spine().arcTitle().isBlank()
                ? story.spine.arcTitle() : response.spine().arcTitle();
        revised.spine = new StorySpine(arcTitle, beats);
        return revised;
    }

    /** The first beat the rewritten scene belongs to: the head of the model's new tail. */
    public static String firstNewBeatId(RestructureResponse response) {
        return response.spine().beats().get(0).id();
    }

    /** Completed beats, in the order the spine had them, so completedBeats keeps resolving. */
    static List<StoryBeat> keptBeats(CompiledStory story, GameState state) {
        List<String> completed = state.completedBeats == null ? List.of() : state.completedBeats;
        List<StoryBeat> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StoryBeat beat : story.spine.beats()) {
            if (completed.contains(beat.id()) && seen.add(beat.id())) kept.add(beat);
        }
        return kept;
    }

    /**
     * The revised bible, with anyone and anywhere the play-through has already established put
     * back if the model dropped them. Their old profile is used: a person the player has met has
     * a {@code CharacterState} and possibly pictures keyed to that id.
     */
    private StoryBible mergeBible(CompiledStory story, GameState state, StoryBible revised) {
        List<NpcProfile> characters = new ArrayList<>(revised.characters());
        Set<String> present = new HashSet<>();
        for (NpcProfile npc : characters) present.add(npc.id());
        for (String id : requiredCharacterIds(story, state)) {
            if (present.add(id)) {
                NpcProfile established = story.bible.character(id);
                if (established == null) established = findEncountered(story, id);
                if (established != null) {
                    characters.add(established);
                    log.warn("Session {}: restructure dropped established character '{}'; kept the old profile",
                            state.sessionId, id);
                }
            }
        }

        List<LocationProfile> locations = new ArrayList<>(revised.locations());
        Set<String> places = new HashSet<>();
        for (LocationProfile loc : locations) places.add(loc.id());
        for (String id : requiredLocationIds(state)) {
            if (places.add(id)) {
                LocationProfile established = story.bible.location(id);
                if (established != null) {
                    locations.add(established);
                    log.warn("Session {}: restructure dropped visited location '{}'; kept the old profile",
                            state.sessionId, id);
                }
            }
        }

        return new StoryBible(revised.premise(), revised.tone(), revised.themes(), characters, locations,
                revised.importantObjects(), revised.mysteries(), revised.hardCanon(), revised.softCanon());
    }

    private static NpcProfile findEncountered(CompiledStory story, String id) {
        for (NpcProfile npc : story.encounteredNpcs) {
            if (npc.id().equals(id)) return npc;
        }
        return null;
    }

    /**
     * Everyone the play-through has established: the bible people the player has a relationship
     * with, and anyone met along the way. Deleting one of these would orphan their CharacterState
     * and their pictures.
     */
    static Set<String> requiredCharacterIds(CompiledStory story, GameState state) {
        Set<String> ids = new LinkedHashSet<>();
        if (state.characters != null) {
            for (String id : state.characters.keySet()) {
                if (id != null && !PlayerCharacter.ID.equals(id) && AssetSpec.safeSubjectId(id)) ids.add(id);
            }
        }
        for (NpcProfile npc : story.encounteredNpcs) {
            if (npc.id() != null && AssetSpec.safeSubjectId(npc.id())) ids.add(npc.id());
        }
        return ids;
    }

    /** Everywhere the player has been, plus where they are standing right now. */
    static Set<String> requiredLocationIds(GameState state) {
        Set<String> ids = new LinkedHashSet<>();
        if (state.knownLocationIds != null) {
            for (String id : state.knownLocationIds) {
                if (id != null && AssetSpec.safeSubjectId(id)) ids.add(id);
            }
        }
        if (state.currentLocationId != null && AssetSpec.safeSubjectId(state.currentLocationId)) {
            ids.add(state.currentLocationId);
        }
        return ids;
    }
}
