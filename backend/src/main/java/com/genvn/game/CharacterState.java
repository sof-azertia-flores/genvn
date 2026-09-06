package com.genvn.game;

import java.util.ArrayList;
import java.util.List;

/** Runtime, per-session state about an NPC. The static profile lives in the StoryBible. */
public class CharacterState {
    public String id;
    public String name;
    public boolean met = false;
    /** Clamped to [-10, 10]. Negative is hostile, positive is warm. */
    public int relationship = 0;
    public String lastSeenSceneId;
    /** What this NPC has actually learned in play. Used to stop the LLM giving NPCs omniscience. */
    public List<String> knowledge = new ArrayList<>();

    public CharacterState() {}

    public CharacterState(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
