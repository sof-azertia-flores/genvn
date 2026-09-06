package com.genvn.game;

public class StoryProgress {
    public int arcNumber = 1;
    public int scenesPlayed = 0;
    public int beatsCompleted = 0;
    public int totalBeats = 0;
    /** 0..1, used by the UI progress bar and by the arc-continuation trigger. */
    public double fraction = 0.0;

    public void recompute() {
        fraction = totalBeats <= 0 ? 0.0 : Math.min(1.0, (double) beatsCompleted / totalBeats);
    }
}
