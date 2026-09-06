package com.genvn.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** The skeleton, not the script: a handful of beats the play-through should move through. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StorySpine(String arcTitle, List<StoryBeat> beats) {
    public StorySpine {
        beats = beats == null ? List.of() : List.copyOf(beats);
    }

    public StoryBeat beat(String id) {
        if (id == null) return null;
        return beats.stream().filter(b -> id.equals(b.id())).findFirst().orElse(null);
    }

    public StoryBeat nextAfter(String beatId) {
        return nextIncompleteAfter(beatId, Set.of());
    }

    /**
     * Scan forward once, treating the first occurrence of each id as authoritative. Old saves
     * may contain duplicate ids; repeatedly looking up their successors can otherwise cycle.
     */
    public StoryBeat nextIncompleteAfter(String beatId, java.util.Collection<String> completed) {
        boolean afterCurrent = beatId == null || beat(beatId) == null;
        Set<String> seen = new HashSet<>();
        for (StoryBeat beat : beats) {
            if (beat.id() == null || beat.id().isBlank() || !seen.add(beat.id())) continue;
            if (afterCurrent && !completed.contains(beat.id())) return beat;
            if (beat.id().equals(beatId)) afterCurrent = true;
        }
        return null;
    }

    /** Shared compiler/continuation validation, before generated ids can enter a save. */
    static String validateBeats(List<StoryBeat> beats) {
        return validateBeats(beats, 1, false);
    }

    /**
     * @param minimum     fewest beats that count as a story rather than a sketch
     * @param requireTurn every beat must say what changes when it lands
     */
    static String validateBeats(List<StoryBeat> beats, int minimum, boolean requireTurn) {
        if (beats == null || beats.size() < minimum) {
            return "need at least " + minimum + " beats with real turns; " + (beats == null ? 0 : beats.size())
                    + " is a sketch, not a story";
        }
        Set<String> ids = new HashSet<>();
        for (StoryBeat beat : beats) {
            if (beat == null || beat.id() == null || beat.id().isBlank()) return "a beat is missing its 'id'";
            if (!ids.add(beat.id())) return "duplicate beat id '" + beat.id() + "' -- each beat needs a unique id";
            if (beat.title() == null || beat.title().isBlank()) return "beat '" + beat.id() + "' is missing its 'title'";
            if (requireTurn && (beat.turn() == null || beat.turn().isBlank())) {
                return "beat '" + beat.id() + "' is missing its 'turn' -- say what is irreversibly different once it lands";
            }
        }
        return null;
    }
}
