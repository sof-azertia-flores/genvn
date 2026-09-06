package com.genvn.llm;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Small ring buffer of recent LLM calls, for the dev inspector. */
@Component
public class LlmCallLog {

    public record Entry(
            String at,
            String purpose,
            String model,
            long durationMillis,
            boolean ok,
            String note,
            int responseChars
    ) {}

    private static final int MAX = 40;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public synchronized void record(LlmPurpose purpose, String model, long millis, boolean ok, String note, int chars) {
        entries.addFirst(new Entry(Instant.now().toString(), purpose == null ? "?" : purpose.name(),
                model, millis, ok, note, chars));
        while (entries.size() > MAX) entries.removeLast();
    }

    public synchronized List<Entry> recent() {
        return new ArrayList<>(entries);
    }
}
