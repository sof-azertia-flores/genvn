package com.genvn.llm;

import java.util.concurrent.CancellationException;

/** Cancellation is terminal, never another transport or structured-output repair attempt. */
final class LlmCancellation {
    private LlmCancellation() {}

    static void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("LLM call was cancelled");
    }

    static void propagate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException cancelled) throw cancelled;
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                CancellationException cancelled = new CancellationException("LLM call was interrupted");
                cancelled.initCause(failure);
                throw cancelled;
            }
        }
        check();
    }
}
