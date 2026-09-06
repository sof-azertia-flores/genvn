package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CompiledStory {
    public AuthorCanon authorCanon;
    /** The user's visual direction, retained across scenes and later arcs. */
    public String artStyle = "";
    /** The protagonist's art identity is separate from NPC relations and knowledge. */
    public NpcProfile playerVisual;
    /** Unassigned appearance sketches, never advance-written NPC identities or plot facts. */
    public List<PreparedVisual> preparedVisuals = new ArrayList<>();
    /** Canonically met supporting people whose identities were not promoted to the story bible. */
    public List<NpcProfile> encounteredNpcs = new ArrayList<>();
    public StoryBible bible;
    public StorySpine spine;
    /** Arc 2+ outlines appended by the continuation service. */
    public List<ArcOutline> laterArcs = new ArrayList<>();

    public CompiledStory() {}

    public CompiledStory(AuthorCanon authorCanon, StoryBible bible, StorySpine spine) {
        this.authorCanon = authorCanon;
        this.bible = bible;
        this.spine = spine;
    }

    /**
     * A scene may legitimately move to a room the compiler never named. Register it so that
     * location ids stay resolvable. Author canon and existing entries are never touched.
     */
    public void registerLocation(LocationProfile loc) {
        if (loc == null || loc.id() == null || bible.location(loc.id()) != null) return;
        List<LocationProfile> locations = new ArrayList<>(bible.locations());
        locations.add(loc);
        bible = new StoryBible(bible.premise(), bible.tone(), bible.themes(), bible.characters(),
                locations, bible.importantObjects(), bible.mysteries(), bible.hardCanon(), bible.softCanon());
    }

    public void registerCharacter(NpcProfile npc) {
        if (npc != null && npc.id() != null && com.genvn.game.PlayerCharacter.ID.equalsIgnoreCase(npc.id().trim())) return;
        if (npc == null || npc.id() == null || bible.character(npc.id()) != null) return;
        List<NpcProfile> characters = new ArrayList<>(bible.characters());
        characters.add(npc);
        bible = new StoryBible(bible.premise(), bible.tone(), bible.themes(), characters,
                bible.locations(), bible.importantObjects(), bible.mysteries(), bible.hardCanon(), bible.softCanon());
    }

    public NpcProfile visualCharacter(String id) {
        if (com.genvn.game.PlayerCharacter.ID.equals(id)) return playerVisual;
        NpcProfile established = bible.character(id);
        if (established != null) return established;
        return encounteredNpcs.stream().filter(npc -> npc.id().equals(id)).findFirst().orElse(null);
    }

    public PreparedVisual preparedVisual(String id) {
        if (id == null) return null;
        return preparedVisuals.stream().filter(visual -> id.equals(visual.id())).findFirst().orElse(null);
    }

    /** Called under the session lock after a scene is selected. Existing identities cannot be rewritten. */
    public void encounterCharacter(NpcProfile proposed, boolean recurring) {
        if (proposed == null || !PreparedVisual.validId(proposed.id())) return;
        NpcProfile established = visualCharacter(proposed.id());
        NpcProfile npc = established == null ? proposed : established;
        preparedVisuals = new ArrayList<>(preparedVisuals.stream().filter(v -> !v.id().equals(npc.id())).toList());
        if (recurring) {
            registerCharacter(npc);
            encounteredNpcs = new ArrayList<>(encounteredNpcs.stream().filter(n -> !n.id().equals(npc.id())).toList());
        } else if (established == null) {
            encounteredNpcs.add(npc);
        }
    }

    /** Replaces the active spine when a new arc begins. Author canon is preserved. */
    public void beginArc(ArcOutline outline) {
        laterArcs.add(outline);
        spine = new StorySpine(outline.arcTitle(), outline.beats());
    }
}
