package com.genvn.narrative;

import com.genvn.game.CharacterState;
import com.genvn.game.GameState;
import com.genvn.story.CompiledStory;
import com.genvn.story.NpcProfile;

import java.util.Set;
import java.util.stream.Collectors;

/** Pure in-memory cast transition. Call on the canonical session or on an isolated branch copy. */
public final class SceneCast {
    private SceneCast() {}

    public static void apply(CompiledStory story, GameState state, SceneBundle scene) {
        Set<String> present = scene.characters().stream().map(CharacterPresence::characterId).collect(Collectors.toSet());
        for (NpcIntroduction introduction : scene.newNpcs()) {
            if (introduction == null || introduction.profile() == null
                    || !present.contains(introduction.profile().id())) continue;
            story.encounterCharacter(introduction.profile(), introduction.recurring());
        }
        for (CharacterPresence character : scene.characters()) {
            if (com.genvn.game.PlayerCharacter.ID.equals(character.characterId())) continue;
            NpcProfile npc = story.visualCharacter(character.characterId());
            if (npc == null) continue;
            CharacterState value = state.characters.computeIfAbsent(npc.id(), id -> new CharacterState(id, npc.name()));
            value.met = true;
            value.lastSeenSceneId = scene.sceneId();
        }
        // Profiles own stable identities and image keys for the lifetime of this save.
        // ContextRenderer selects a bounded prompt view without deleting this canonical roster.
    }
}
