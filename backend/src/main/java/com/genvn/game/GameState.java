package com.genvn.game;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Canonical Game State. This is the single source of truth about what is actually true
 * in the player's game. The LLM never writes to it directly: it may only propose a
 * {@link StateDelta}, which {@link StateValidator} filters and {@link StateReducer} applies.
 */
public class GameState {
    public String sessionId;
    /** Bumped on every committed scene or applied delta. Also guards choices from stale pages. */
    public int stateVersion = 0;

    public PlayerCharacter player = new PlayerCharacter();
    public Map<String, CharacterState> characters = new LinkedHashMap<>();
    public List<InventoryItem> inventory = new ArrayList<>();
    public Map<String, String> flags = new LinkedHashMap<>();

    public String currentLocationId;
    public List<String> knownLocationIds = new ArrayList<>();

    public String currentArcTitle;
    public String currentBeatId;
    /** Scenes committed since the current beat became current. Drives beat pacing. */
    public int scenesInCurrentBeat = 0;
    public List<String> completedBeats = new ArrayList<>();

    public List<ContinuityEntry> continuityLedger = new ArrayList<>();
    public String currentSceneId;
    /** Most recent first; capped so prompts stay bounded. */
    public List<String> recentEvents = new ArrayList<>();
    /** Full text of the last four committed scenes; older text remains in session history. */
    public List<SceneMemory> recentScenes = new ArrayList<>();
    public StoryProgress storyProgress = new StoryProgress();

    /** Ops the validator refused or clamped, newest last. Surfaced in the dev inspector. */
    public List<String> rejectedOpsLog = new ArrayList<>();

    public boolean hasItem(String name) {
        if (name == null) return false;
        return inventory.stream().anyMatch(i -> i.name != null && i.name.equalsIgnoreCase(name.trim()));
    }

    public void addRecentEvent(String event) {
        if (event == null || event.isBlank()) return;
        recentEvents.add(0, event.trim());
        while (recentEvents.size() > 12) recentEvents.remove(recentEvents.size() - 1);
    }

    public void rememberScene(String sceneId, List<com.genvn.narrative.Block> blocks) {
        if (recentScenes == null) recentScenes = new ArrayList<>();
        recentScenes.removeIf(s -> java.util.Objects.equals(s.sceneId(), sceneId));
        recentScenes.add(new SceneMemory(sceneId, blocks));
        while (recentScenes.size() > 4) recentScenes.remove(0);
    }

    /**
     * Deep copy via JSON round-trip. Boring, but it makes branch forking obviously correct:
     * a forked branch physically cannot alias any canonical collection.
     */
    public GameState deepCopy(ObjectMapper mapper) {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(this), GameState.class);
        } catch (Exception e) {
            throw new IllegalStateException("GameState must always be serializable", e);
        }
    }
}
