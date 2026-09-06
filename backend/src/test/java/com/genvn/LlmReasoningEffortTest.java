package com.genvn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.LlmProperties;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.OpenAiCompatibleLlmClient;
import com.genvn.llm.StructuredLlm;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** llm.reasoning.* reaches the request body as reasoning_effort, per purpose, and never by accident. */
class LlmReasoningEffortTest {

    @Test
    @DisplayName("nothing is sent by default; a configured default and per-purpose overrides land in the body")
    void reasoningEffortIsSentPerPurposeOnlyWhenConfigured() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(mapper.readTree(exchange.getRequestBody()));
            byte[] response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of(
                    "content", "{\"ok\":true}"))))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            LlmProperties props = new LlmProperties();
            props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            props.setApiKey("dummy-local-test-key");
            props.setStream(false);
            StructuredLlm llm = new StructuredLlm(new OpenAiCompatibleLlmClient(props, mapper), mapper, new LlmCallLog());

            llm.call(LlmRequest.of(LlmPurpose.SCENE_GENERATE, "Test", "Test", Map.of()), Map.class, null);
            assertFalse(bodies.get(0).has("reasoning_effort"), "unset means the field is absent, not empty");

            props.getReasoning().setEffort(" Medium ");
            props.getReasoning().setSceneGenerate("low");
            props.getReasoning().setChoiceProbabilities("minimal");
            llm.call(LlmRequest.of(LlmPurpose.STORY_COMPILE, "Test", "Test", Map.of()), Map.class, null);
            llm.call(LlmRequest.of(LlmPurpose.SCENE_GENERATE, "Test", "Test", Map.of()), Map.class, null);
            llm.call(LlmRequest.of(LlmPurpose.CHOICE_PROBABILITIES, "Test", "Test", Map.of()), Map.class, null);
            llm.call(LlmRequest.of(LlmPurpose.ARC_CONTINUE, "Test", "Test", Map.of()), Map.class, null);

            assertEquals("medium", bodies.get(1).path("reasoning_effort").asText(), "the default, normalised");
            assertEquals("low", bodies.get(2).path("reasoning_effort").asText(), "scene override wins");
            assertEquals("minimal", bodies.get(3).path("reasoning_effort").asText());
            assertEquals("medium", bodies.get(4).path("reasoning_effort").asText(), "no override falls back to the default");
            assertTrue(new OpenAiCompatibleLlmClient(props, mapper).describe().contains("reasoning medium"));
        } finally {
            server.stop(0);
        }
    }
}
