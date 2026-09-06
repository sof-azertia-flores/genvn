package com.genvn.speculation;

import com.genvn.dice.CheckResult;
import com.genvn.narrative.SceneBundle;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One in-flight or completed speculative candidate.
 *
 * {@code baseStateVersion} is the canonical state version this branch was forked from. If the
 * canonical state has moved on, the branch is stale and must not be used -- that check is what
 * makes it impossible for an unchosen branch to leak into canon.
 */
public record Branch(
        BranchKey key,
        CompletableFuture<SceneBundle> future,
        int baseStateVersion,
        long startedAtMillis,
        AtomicReference<String> provisionalSummary,
        FutureTask<Void> task,
        AtomicLong executionStartedAtMillis,
        /**
         * Dice cast for the checked choices of the scene this branch produced, keyed by choice id.
         * Set before the future completes. When the branch is committed these become the scene's
         * dice, which is what lets second-round candidates be written for one outcome only.
         */
        AtomicReference<Map<String, CheckResult>> nextDice
) {
    /** Kept for callers that seed an already completed candidate directly. */
    public Branch(BranchKey key, CompletableFuture<SceneBundle> future, int baseStateVersion,
                  long startedAtMillis, AtomicReference<String> provisionalSummary) {
        this(key, future, baseStateVersion, startedAtMillis, provisionalSummary, null,
                new AtomicLong(startedAtMillis), new AtomicReference<>(Map.of()));
    }

    public Branch(BranchKey key, CompletableFuture<SceneBundle> future, int baseStateVersion,
                  long startedAtMillis, AtomicReference<String> provisionalSummary, FutureTask<Void> task,
                  AtomicLong executionStartedAtMillis) {
        this(key, future, baseStateVersion, startedAtMillis, provisionalSummary, task,
                executionStartedAtMillis, new AtomicReference<>(Map.of()));
    }

    /** The dice this branch cast for its own scene's checked choices; empty until it is ready. */
    public Map<String, CheckResult> diceForNextScene() {
        Map<String, CheckResult> dice = nextDice.get();
        return dice == null ? Map.of() : dice;
    }

    public void cancel() {
        future.cancel(true);
        // CompletableFuture.cancel alone never interrupts the executor's actual worker.
        if (task != null) task.cancel(true);
    }

    public String provisional() {
        String s = provisionalSummary.get();
        return s == null ? "(pending)" : s;
    }

    public boolean isReady() {
        return future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled();
    }

    public String status() {
        if (future.isCancelled()) return "cancelled";
        if (future.isCompletedExceptionally()) return "failed";
        if (future.isDone()) return "ready";
        if (executionStartedAtMillis.get() == 0) return "queued";
        return "generating";
    }
}
