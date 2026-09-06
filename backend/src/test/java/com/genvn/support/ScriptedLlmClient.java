package com.genvn.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.llm.LlmClient;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.LlmResponse;
import com.genvn.llm.MockLlmClient;
import com.genvn.narrative.Choice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Scene generation is scripted per test; story compilation is delegated to the real mock so
 * tests do not have to hand-author a whole Story Bible.
 */
public class ScriptedLlmClient implements LlmClient {

    private final MockLlmClient fallback;
    private final Function<LlmRequest, String> sceneScript;
    private final List<LlmRequest> requests = new ArrayList<>();

    public ScriptedLlmClient(ObjectMapper mapper, Function<LlmRequest, String> sceneScript) {
        this.fallback = new MockLlmClient(mapper);
        this.sceneScript = sceneScript;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        synchronized (this) {
            requests.add(request);
        }
        if (request.purpose() != LlmPurpose.SCENE_GENERATE) {
            return fallback.complete(request);
        }
        // Deliberately unsynchronized: a script may block on a latch so a test can prove two
        // branch generations are inside the "provider" at the same time.
        String json = sceneScript.apply(request);
        if (json == null) return fallback.complete(request);
        return new LlmResponse(json, "scripted", 1);
    }

    @Override
    public String describe() {
        return "scripted";
    }

    @Override
    public boolean isMock() {
        return true;
    }

    public synchronized int sceneCallCount() {
        return (int) requests.stream().filter(r -> r.purpose() == LlmPurpose.SCENE_GENERATE).count();
    }

    /** Every request of one purpose, in order; lets a test read what a background call was told. */
    public synchronized List<LlmRequest> requestsOf(LlmPurpose purpose) {
        return requests.stream().filter(r -> r.purpose() == purpose).toList();
    }

    /** The choice a given scene request is exploring, or null for an opening scene. */
    public static Choice choiceOf(LlmRequest request) {
        return (Choice) request.mockContext().get("choice");
    }

    public static String outcomeOf(LlmRequest request) {
        return String.valueOf(request.mockContext().getOrDefault("outcome", "NONE"));
    }
}
