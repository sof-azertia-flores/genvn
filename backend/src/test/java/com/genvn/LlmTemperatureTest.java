package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.LlmProperties;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.OpenAiCompatibleLlmClient;
import com.genvn.llm.StructuredLlm;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmTemperatureTest {
    @Test
    void configuredTemperatureReachesHttpBodyAndRepairsRetainExplicitOverrides() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Double> temperatures = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            temperatures.add(request.path("temperature").asDouble());
            String content = temperatures.size() == 2 ? "not JSON; request a repair" : "{\"ok\":true}";
            byte[] response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of(
                    "content", content))))).getBytes(StandardCharsets.UTF_8);
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
            props.setTemperature(0.23);
            StructuredLlm llm = new StructuredLlm(new OpenAiCompatibleLlmClient(props, mapper), mapper, new LlmCallLog());

            llm.call(LlmRequest.of(LlmPurpose.SCENE_GENERATE, "Test", "Test", Map.of()), Map.class, null);
            var overridden = llm.call(new LlmRequest(LlmPurpose.SCENE_GENERATE, "Test", "Test", Map.of(), 0.61),
                    Map.class, null);

            assertEquals(1, overridden.repairAttempts());
            assertEquals(List.of(0.23, 0.61, 0.61), temperatures,
                    "normal calls use config; explicit overrides survive the repair round trip");
        } finally {
            server.stop(0);
        }
    }
}
