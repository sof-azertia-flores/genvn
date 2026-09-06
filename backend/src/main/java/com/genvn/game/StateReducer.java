package com.genvn.game;

import com.genvn.story.CompiledStory;
import com.genvn.story.StoryBeat;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates and applies a proposed {@link StateDelta}.
 *
 * This is the boundary the LLM cannot cross. Only the ops in {@link #ALLOWED} exist; every
 * numeric change is clamped; every reference to a beat, thread, character or location must
 * resolve. Anything else is dropped and recorded, never applied, and never fatal.
 */
@Service
public class StateReducer {

    public static final Set<String> ALLOWED = Set.of(
            DeltaOp.ADD_INVENTORY, DeltaOp.REMOVE_INVENTORY, DeltaOp.SET_FLAG,
            DeltaOp.RELATIONSHIP_DELTA, DeltaOp.HP_DELTA, DeltaOp.ADD_CONDITION,
            DeltaOp.REMOVE_CONDITION, DeltaOp.CHANGE_LOCATION, DeltaOp.COMPLETE_BEAT,
            DeltaOp.ADD_THREAD, DeltaOp.RESOLVE_THREAD, DeltaOp.MEET_CHARACTER);

    private static final int MAX_HP_SWING = 20;
    private static final int MAX_RELATIONSHIP_SWING = 3;
    private static final int RELATIONSHIP_FLOOR = -10;
    private static final int RELATIONSHIP_CEILING = 10;
    private static final int MAX_INVENTORY = 30;
    private static final int MAX_FLAGS = 60;
    private static final int MAX_CONDITIONS = 8;
    private static final int MAX_THREADS = 24;
    private static final int MAX_NEW_THREADS_PER_SCENE = 2;
    /** The current beat may only be completed once at least this many scenes have played in it. */
    public static final int MIN_SCENES_PER_BEAT = 2;

    public record Applied(List<DeltaOp> applied, List<String> rejected) {}

    /**
     * Mutates {@code state} in place. The caller must own it: either the canonical state during
     * a commit, or a forked copy inside a speculative branch.
     */
    public Applied apply(GameState state, CompiledStory story, StateDelta delta, String sceneId) {
        List<DeltaOp> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        if (delta == null || delta.isEmpty()) {
            return new Applied(applied, rejected);
        }

        int newThreads = 0;
        for (DeltaOp op : delta.ops()) {
            if (op == null || op.op() == null || op.op().isBlank()) {
                rejected.add("op with no name");
                continue;
            }
            String name = op.op().trim();
            if (!ALLOWED.contains(name)) {
                rejected.add("forbidden op '" + name + "' (target=" + op.target() + ") -- the model may not write state directly");
                continue;
            }
            if (DeltaOp.ADD_THREAD.equals(name) && ++newThreads > MAX_NEW_THREADS_PER_SCENE) {
                rejected.add("addThread: too many new threads in one scene");
                continue;
            }
            try {
                String problem = applyOne(state, story, name, op, sceneId);
                if (problem == null) {
                    applied.add(op);
                } else {
                    rejected.add(name + ": " + problem);
                }
            } catch (RuntimeException e) {
                rejected.add(name + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }

        if (!applied.isEmpty()) {
            state.stateVersion++;
        }
        for (String r : rejected) {
            state.rejectedOpsLog.add("[scene " + sceneId + "] " + r);
        }
        while (state.rejectedOpsLog.size() > 50) state.rejectedOpsLog.remove(0);
        return new Applied(applied, rejected);
    }

    /** @return null when applied, otherwise a human-readable reason it was refused. */
    private String applyOne(GameState state, CompiledStory story, String name, DeltaOp op, String sceneId) {
        switch (name) {
            case DeltaOp.ADD_INVENTORY -> {
                String item = clean(op.target(), 60);
                if (item.isEmpty()) return "no item name";
                if (state.inventory.size() >= MAX_INVENTORY) return "inventory full";
                if (state.hasItem(item)) return "already carrying '" + item + "'";
                state.inventory.add(new InventoryItem(item, clean(op.value(), 200), sceneId));
            }
            case DeltaOp.REMOVE_INVENTORY -> {
                String item = clean(op.target(), 60);
                boolean removed = state.inventory.removeIf(i -> i.name != null && i.name.equalsIgnoreCase(item));
                if (!removed) return "player is not carrying '" + item + "'";
            }
            case DeltaOp.SET_FLAG -> {
                String key = flagKey(op.target());
                if (key.isEmpty()) return "no flag key";
                if (!state.flags.containsKey(key) && state.flags.size() >= MAX_FLAGS) return "flag table full";
                state.flags.put(key, clean(op.value() == null ? "true" : op.value(), 200));
            }
            case DeltaOp.RELATIONSHIP_DELTA -> {
                String id = clean(op.target(), 60);
                CharacterState cs = state.characters.get(id);
                if (cs == null) return "unknown character '" + id + "'";
                int amount = clamp(nz(op.amount()), -MAX_RELATIONSHIP_SWING, MAX_RELATIONSHIP_SWING);
                cs.relationship = clamp(cs.relationship + amount, RELATIONSHIP_FLOOR, RELATIONSHIP_CEILING);
                cs.lastSeenSceneId = sceneId;
            }
            case DeltaOp.HP_DELTA -> {
                int amount = clamp(nz(op.amount()), -MAX_HP_SWING, MAX_HP_SWING);
                state.player.hp = clamp(state.player.hp + amount, 0, state.player.maxHp);
            }
            case DeltaOp.ADD_CONDITION -> {
                String c = clean(op.target(), 40);
                if (c.isEmpty()) return "no condition name";
                if (state.player.conditions.size() >= MAX_CONDITIONS) return "condition list full";
                if (state.player.conditions.stream().anyMatch(x -> x.equalsIgnoreCase(c))) return "already has '" + c + "'";
                state.player.conditions.add(c);
            }
            case DeltaOp.REMOVE_CONDITION -> {
                String c = clean(op.target(), 40);
                if (!state.player.conditions.removeIf(x -> x.equalsIgnoreCase(c))) return "does not have '" + c + "'";
            }
            case DeltaOp.CHANGE_LOCATION -> {
                String id = clean(op.target(), 60);
                if (story.bible.location(id) == null) return "unknown location '" + id + "'";
                state.currentLocationId = id;
                if (!state.knownLocationIds.contains(id)) state.knownLocationIds.add(id);
            }
            case DeltaOp.COMPLETE_BEAT -> {
                String id = clean(op.target(), 60);
                StoryBeat beat = story.spine.beat(id);
                if (beat == null) return "unknown beat '" + id + "'";
                if (state.completedBeats.contains(id)) return "beat '" + id + "' already completed";
                if (id.equals(state.currentBeatId) && state.scenesInCurrentBeat < MIN_SCENES_PER_BEAT - 1) {
                    // A beat is a phase, not a checkbox: it cannot open and close in the same scene.
                    return "beat '" + id + "' cannot end in its own first scene; let it develop before its turn";
                }
                state.completedBeats.add(id);
                state.storyProgress.beatsCompleted = state.completedBeats.size();
                state.storyProgress.totalBeats = story.spine.beats().size();
                state.storyProgress.recompute();
                if (id.equals(state.currentBeatId)) {
                    StoryBeat next = story.spine.nextIncompleteAfter(id, state.completedBeats);
                    state.currentBeatId = next == null ? null : next.id();
                }
            }
            case DeltaOp.ADD_THREAD -> {
                String description = clean(op.value() != null ? op.value() : op.target(), 240);
                if (description.isEmpty()) return "no thread description";
                if (state.continuityLedger.size() >= MAX_THREADS) return "ledger full";
                boolean duplicate = state.continuityLedger.stream()
                        .anyMatch(e -> e.description != null && e.description.equalsIgnoreCase(description));
                if (duplicate) return "thread already tracked";
                state.continuityLedger.add(new ContinuityEntry(nextThreadId(state), description, sceneId));
            }
            case DeltaOp.RESOLVE_THREAD -> {
                String id = clean(op.target(), 20);
                ContinuityEntry entry = state.continuityLedger.stream()
                        .filter(e -> e.id.equalsIgnoreCase(id)).findFirst().orElse(null);
                if (entry == null) return "unknown thread '" + id + "'";
                entry.status = ContinuityEntry.RESOLVED;
                entry.resolvedAtScene = sceneId;
            }
            case DeltaOp.MEET_CHARACTER -> {
                String id = clean(op.target(), 60);
                CharacterState cs = state.characters.get(id);
                if (cs == null) return "unknown character '" + id + "'";
                cs.met = true;
                cs.lastSeenSceneId = sceneId;
            }
            default -> {
                return "unhandled op";
            }
        }
        return null;
    }

    public static String nextThreadId(GameState state) {
        int max = 0;
        for (ContinuityEntry e : state.continuityLedger) {
            if (e.id != null && e.id.length() > 1 && e.id.charAt(0) == 'P') {
                try {
                    max = Math.max(max, Integer.parseInt(e.id.substring(1)));
                } catch (NumberFormatException ignored) {
                    // ids that are not P-numbers simply do not participate in the sequence
                }
            }
        }
        return String.format("P%03d", max + 1);
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String flagKey(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\u4e00-\\u9fff]+", "_");
        t = t.replaceAll("^_+|_+$", "");
        return t.length() > 64 ? t.substring(0, 64) : t;
    }
}
