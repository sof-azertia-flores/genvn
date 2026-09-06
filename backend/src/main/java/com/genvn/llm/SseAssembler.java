package com.genvn.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reassembles an OpenAI-style server-sent-events stream into the final message text.
 *
 * Streaming exists here for one reason: long structured generations (the story compiler
 * writes several thousand tokens) can exceed the idle timeout of the gateway in front of a
 * provider, which then answers 504 even though the model was working. With streaming, bytes
 * flow the whole time and the gateway never sees an idle origin.
 *
 * Tolerant by design: comment lines, blank keep-alives, chunks with null content, a missing
 * [DONE], and a provider that ignored {@code stream:true} and sent one JSON body are all handled.
 */
final class SseAssembler {

    private static final Logger log = LoggerFactory.getLogger(SseAssembler.class);
    /** How often to report progress while a long generation streams in. */
    private static final long PROGRESS_EVERY_MILLIS = 5_000;

    private SseAssembler() {}

    static String assemble(BufferedReader reader, ObjectMapper mapper, long deadlineAtMillis, String label)
            throws IOException {
        return assemble(reader, mapper, deadlineAtMillis, label, GenerationProgress.NONE);
    }

    static String assemble(BufferedReader reader, ObjectMapper mapper, long deadlineAtMillis, String label,
                           GenerationProgress progress) throws IOException {
        return assemble(reader, mapper, deadlineAtMillis, label, progress, () -> {});
    }

    /** @param onActivity called for every line received, so an idle watchdog can tell silence from work. */
    static String assemble(BufferedReader reader, ObjectMapper mapper, long deadlineAtMillis, String label,
                           GenerationProgress progress, Runnable onActivity) throws IOException {
        StringBuilder text = new StringBuilder();
        List<String> rawLines = new ArrayList<>();
        boolean sawData = false;
        boolean done = false;
        long started = System.currentTimeMillis();
        long lastReport = started;
        int chunks = 0;
        int nextCheckpoint = 0;
        // Bounded observations of received content, not an estimate of total output length.
        int[] contentCheckpoints = {1, 2048, 4096, 8192};
        String line;

        while (!done && (line = reader.readLine()) != null) {
            onActivity.run();
            long now = System.currentTimeMillis();
            if (now - lastReport >= PROGRESS_EVERY_MILLIS) {
                log.info("{}: streaming... {} chars, {} chunks, {}s", label, text.length(), chunks, (now - started) / 1000);
                lastReport = now;
            }
            if (System.currentTimeMillis() > deadlineAtMillis) {
                throw new LlmException("LLM stream exceeded the configured timeout while still producing output");
            }
            if (Thread.currentThread().isInterrupted()) {
                LlmCancellation.check();
            }
            rawLines.add(line);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:") || trimmed.startsWith("id:")) {
                continue;
            }
            if (!trimmed.startsWith("data:")) {
                continue; // not an SSE field we understand; maybe a plain JSON body, decided below
            }
            sawData = true;
            String payload = trimmed.substring("data:".length()).trim();
            if (payload.equals("[DONE]")) {
                done = true;
                continue;
            }
            JsonNode chunk;
            try {
                chunk = mapper.readTree(payload);
            } catch (IOException e) {
                continue; // a torn or non-JSON chunk is not fatal; the next one usually is fine
            }
            JsonNode error = chunk.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new LlmException("LLM stream error: " + (error.isTextual() ? error.asText() : error.toString()));
            }
            JsonNode choice = chunk.path("choices").path(0);
            JsonNode content = choice.path("delta").path("content");
            if (content.isMissingNode() || content.isNull()) {
                content = choice.path("message").path("content"); // some shims send full messages
            }
            if (content.isTextual()) {
                text.append(content.asText());
                chunks++;
                while (nextCheckpoint < contentCheckpoints.length && text.length() >= contentCheckpoints[nextCheckpoint]) {
                    progress.report(GenerationProgress.Stage.CONTENT_RECEIVED, 1, contentCheckpoints[nextCheckpoint++]);
                }
            }
        }

        if (!sawData) {
            // The provider ignored stream:true and sent a normal completion body. Use it.
            String body = String.join("\n", rawLines).trim();
            if (!body.isEmpty()) {
                try {
                    JsonNode root = mapper.readTree(body);
                    JsonNode content = root.path("choices").path(0).path("message").path("content");
                    if (content.isTextual()) return content.asText();
                    JsonNode error = root.path("error");
                    if (!error.isMissingNode() && !error.isNull()) {
                        throw new LlmException("LLM error: " + error.toString());
                    }
                } catch (IOException ignored) {
                    // fall through to the empty-response error below
                }
            }
        }

        if (text.isEmpty()) {
            throw new LlmException("LLM stream ended without any message content");
        }
        return text.toString();
    }
}
