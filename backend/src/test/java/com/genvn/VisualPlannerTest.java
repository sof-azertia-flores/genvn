package com.genvn;

import com.genvn.asset.AssetKind;
import com.genvn.asset.AssetRequest;
import com.genvn.asset.AssetSpec;
import com.genvn.asset.VisualPlanner;
import com.genvn.config.ImageProperties;
import com.genvn.game.GameState;
import com.genvn.story.AuthorCanon;
import com.genvn.story.CompiledStory;
import com.genvn.story.LocationProfile;
import com.genvn.story.NpcProfile;
import com.genvn.story.StoryBeat;
import com.genvn.story.StoryBible;
import com.genvn.story.StorySpine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VisualPlannerTest {

    static CompiledStory story(int locations, int npcs) {
        List<LocationProfile> locs = new java.util.ArrayList<>();
        for (int i = 0; i < locations; i++) locs.add(new LocationProfile("loc_" + i, "Place " + i, "desc", "visual " + i));
        List<NpcProfile> chars = new java.util.ArrayList<>();
        for (int i = 0; i < npcs; i++) {
            chars.add(new NpcProfile("npc_" + i, "Person " + i, "d", "p", List.of(), List.of(), "s", "r", "portrait " + i));
        }
        StoryBible bible = new StoryBible("premise", "quiet dread", List.of("memory"), chars, locs,
                List.of(), List.of(), List.of(), List.of());
        StorySpine spine = new StorySpine("Arc", List.of(
                new StoryBeat("b1", "Arrival", "p", "c", "critical"),
                new StoryBeat("b2", "Search", "p", "c", "major"),
                new StoryBeat("b3", "End", "p", "c", "critical")));
        return new CompiledStory(new AuthorCanon("outline", List.of("fact")), bible, spine);
    }

    static GameState stateAt(String location) {
        GameState s = new GameState();
        s.sessionId = "s1";
        s.currentLocationId = location;
        return s;
    }

    @Test
    @DisplayName("the plan is deterministic, bounded by config, and puts the opening location first")
    void deterministicAndBounded() {
        ImageProperties props = new ImageProperties();
        props.getPlan().setLocations(3);
        props.getPlan().setCharacters(2);
        props.getPlan().setExpressions("worried,suspicious");
        VisualPlanner planner = new VisualPlanner(props);
        CompiledStory story = story(5, 4);

        VisualPlanner.Plan a = planner.plan(story, stateAt("loc_3"), null);
        VisualPlanner.Plan b = planner.plan(story, stateAt("loc_3"), null);
        assertEquals(a.specs().stream().map(AssetSpec::assetId).toList(),
                b.specs().stream().map(AssetSpec::assetId).toList(), "same story, same ids, every time");

        List<AssetSpec> bgs = a.specs().stream().filter(s -> s.kind() == AssetKind.BACKGROUND).toList();
        assertEquals(3, bgs.size(), "capped by image.plan.locations even though the bible has 5");
        assertEquals("bg.loc_3.default", bgs.get(0).assetId(), "the opening location comes first");
        assertEquals(0, bgs.get(0).priority());

        List<AssetSpec> bases = a.specs().stream().filter(s -> s.kind() == AssetKind.PORTRAIT).toList();
        assertEquals(2, bases.size(), "capped by image.plan.characters");
        List<AssetSpec> variants = a.specs().stream().filter(s -> s.kind() == AssetKind.PORTRAIT_VARIANT).toList();
        assertEquals(4, variants.size(), "two expressions for each of two characters");
        for (AssetSpec v : variants) {
            assertEquals(AssetSpec.portraitId(v.subjectId(), "base"), v.dependsOn(), "a variant is edited from its base");
            assertTrue(v.priority() > bases.get(0).priority(), "bases run before variants so no worker waits on itself");
        }
        assertTrue(a.specs().stream().allMatch(s -> s.applicability().equals(AssetSpec.GENERAL)));
        assertTrue(a.specs().stream().noneMatch(s -> s.subjectId().equals("npc_invented")),
                "the planner never invents subjects the compiler did not establish");
    }

    @Test
    @DisplayName("a scene's picture request is validated against the bible and its variant sanitised")
    void requestsAreValidated() {
        ImageProperties props = new ImageProperties();
        VisualPlanner planner = new VisualPlanner(props);
        CompiledStory story = story(2, 1);
        assertNull(planner.fromRequest(story, new AssetRequest("background", "loc_nope", "night", ""), "st", "k", "b1"),
                "an unknown location is refused, not drawn");
        AssetSpec night = planner.fromRequest(story, new AssetRequest("background", "loc_1", "Night / Rain!", "storm"), "st", "k", "b2");
        assertNotNull(night);
        assertEquals("bg.loc_1.night_rain", night.assetId());
        assertEquals("b2", night.beatId());
        AssetSpec afraid = planner.fromRequest(story, new AssetRequest("portrait", "npc_0", "afraid", ""), "st", "k", "b1");
        assertEquals(AssetKind.PORTRAIT_VARIANT, afraid.kind());
        assertEquals("pt.npc_0.base", afraid.dependsOn());
        assertNull(planner.fromRequest(story, new AssetRequest("sculpture", "loc_1", "x", ""), "st", "k", "b1"));
    }
}
