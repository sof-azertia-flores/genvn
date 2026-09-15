package com.genvn.story;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.CharacterState;
import com.genvn.game.GameState;
import com.genvn.llm.GenerationProgress;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.LlmResponse;
import com.genvn.llm.MockLlmClient;
import com.genvn.llm.StructuredLlm;
import com.genvn.prompt.ContextRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StoryRestructureContextTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void wholeBibleRoundTripReceivesEveryProfileIncludingEncounteredPeopleAndOldLocations() {
        List<NpcProfile> cast = new ArrayList<>();
        List<NpcProfile> supporting = new ArrayList<>();
        List<LocationProfile> locations = new ArrayList<>();
        GameState state = new GameState();
        state.sessionId = "restructure-roster";
        for (int i = 0; i < 45; i++) {
            String id = "npc_person_%02d".formatted(i);
            NpcProfile npc = new NpcProfile(id, "Name " + id, "Identity " + id,
                    "Personality " + id, List.of("Goal " + id), List.of("Secret " + id),
                    "Speech " + id, "Relationship " + id, "Appearance " + id);
            (i < 40 ? cast : supporting).add(npc);
            CharacterState character = new CharacterState(id, npc.name());
            character.met = true;
            state.characters.put(id, character);
        }
        for (int i = 0; i < 70; i++) {
            String id = "loc_place_%02d".formatted(i);
            locations.add(new LocationProfile(id, "Name " + id, "History " + id, "Appearance " + id));
            state.knownLocationIds.add(id);
        }
        state.currentLocationId = locations.getLast().id();
        StoryBible bible = new StoryBible("Premise", "Tone", List.of("Theme"), cast, locations,
                List.of("Object"), List.of("Mystery"), List.of("Hard canon"), List.of("Soft canon"));
        CompiledStory story = new CompiledStory(new AuthorCanon("Outline", List.of("Canon")), bible,
                new StorySpine("Arc", beats("old")));
        story.encounteredNpcs.addAll(supporting);
        state.currentBeatId = story.spine.beats().getFirst().id();
        List<NpcProfile> allCharacters = new ArrayList<>(cast);
        allCharacters.addAll(supporting);
        AtomicReference<String> prompt = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        MockLlmClient provider = new MockLlmClient(mapper) {
            @Override
            public LlmResponse complete(LlmRequest request) {
                assertEquals(LlmPurpose.STORY_RESTRUCTURE, request.purpose());
                calls.incrementAndGet();
                prompt.set(request.user());
                // Echo only profiles whose complete field values really reached the text provider.
                List<NpcProfile> visibleCharacters = allCharacters.stream()
                        .filter(npc -> request.user().contains(npc.description())
                                && request.user().contains(npc.visualDescription())
                                && request.user().contains(npc.goals().getFirst())).toList();
                List<LocationProfile> visibleLocations = locations.stream()
                        .filter(loc -> request.user().contains(loc.description())
                                && request.user().contains(loc.visualDescription())).toList();
                StoryBible echoed = new StoryBible("Revised premise", bible.tone(), bible.themes(),
                        visibleCharacters, visibleLocations, bible.importantObjects(), bible.mysteries(),
                        bible.hardCanon(), bible.softCanon());
                RestructureResponse response = new RestructureResponse(List.of("Revised canon"), echoed,
                        new StorySpine("New arc", beats("new")), List.of(), "Revised");
                try {
                    return new LlmResponse(mapper.writeValueAsString(response), "complete-roster-stub", 0);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        ContextRenderer renderer = new ContextRenderer();
        StoryRestructurePlanner planner = new StoryRestructurePlanner(
                new StructuredLlm(provider, mapper, new LlmCallLog()), renderer, mapper);

        RestructureResponse response = planner.plan(story, state, "Change the villain", GenerationProgress.NONE);

        assertEquals(1, calls.get(), "missing archived profiles must not spend repair rounds");
        assertEquals(allCharacters, response.bible().characters());
        assertEquals(locations, response.bible().locations());
        for (NpcProfile npc : allCharacters) {
            for (String field : List.of(npc.name(), npc.description(), npc.personality(), npc.goals().getFirst(),
                    npc.secrets().getFirst(), npc.speakingStyle(), npc.relationshipToPlayer(), npc.visualDescription())) {
                assertTrue(prompt.get().contains(field), field);
            }
        }
        assertFalse(prompt.get().contains("This briefing shows a relevant subset"));
        assertFalse(prompt.get().contains("This briefing prioritizes the current"));
        assertFalse(renderer.renderStoryFoundation(story, state).contains(cast.getLast().description()),
                "ordinary scene generation keeps its bounded context");
        assertFalse(renderer.renderStoryFoundation(story, state).contains(locations.getFirst().description()));
    }

    private static List<StoryBeat> beats(String prefix) {
        List<StoryBeat> beats = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            beats.add(new StoryBeat(prefix + "_" + i, "Beat " + i, "Purpose", "Condition", "critical", "Turn"));
        }
        return beats;
    }
}
