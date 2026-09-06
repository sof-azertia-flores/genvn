package com.genvn.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.game.CreationMilestones;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GenerationProgressTest {
    private final ObjectMapper mapper = new ObjectMapper();
    record Payload(boolean valid) {}
    record Event(GenerationProgress.Stage stage, int attempt, int count) {}

    @Test void rejectedOutputReportsRepairWithoutClaimingValidationAndNeverMovesBackward() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = new LlmClient() {
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse("{\"valid\":" + (calls.incrementAndGet() == 2) + "}", "scripted", 1);
            }
            public String describe() { return "scripted"; }
            public boolean isMock() { return true; }
        };
        var events = new ArrayList<Event>();
        var milestones = new ArrayList<Integer>();
        var messages = new ArrayList<String>();
        var display = CreationMilestones.model((stage, percent, message) -> {
            milestones.add(percent); messages.add(message);
        }, false);
        var parsed = new StructuredLlm(client, mapper, new LlmCallLog()).call(
                LlmRequest.of(LlmPurpose.STORY_COMPILE, "system", "user", Map.of()), Payload.class,
                value -> value.valid() ? null : "bad payload",
                (stage, attempt, count) -> { events.add(new Event(stage, attempt, count)); display.report(stage, attempt, count); });

        assertTrue(parsed.value().valid());
        assertEquals(1, parsed.repairAttempts());
        assertEquals(List.of(
                new Event(GenerationProgress.Stage.REQUEST_STARTED, 1, 0),
                new Event(GenerationProgress.Stage.RESPONSE_RECEIVED, 1, 15),
                new Event(GenerationProgress.Stage.JSON_EXTRACTED, 1, 0),
                new Event(GenerationProgress.Stage.SCHEMA_PARSED, 1, 0),
                new Event(GenerationProgress.Stage.REPAIR_REQUESTED, 2, 0),
                new Event(GenerationProgress.Stage.REQUEST_STARTED, 2, 0),
                new Event(GenerationProgress.Stage.RESPONSE_RECEIVED, 2, 14),
                new Event(GenerationProgress.Stage.JSON_EXTRACTED, 2, 0),
                new Event(GenerationProgress.Stage.SCHEMA_PARSED, 2, 0),
                new Event(GenerationProgress.Stage.VALIDATED, 2, 0)), events);
        for (int i = 1; i < milestones.size(); i++) assertTrue(milestones.get(i) >= milestones.get(i - 1));
        assertTrue(messages.stream().anyMatch(message -> message.contains("第 2 次尝试")));
        assertTrue(messages.stream().noneMatch(message -> message.contains("bad payload")));
    }

    @Test void transportFailuresNeverReportReceivedOrValidatedContent() {
        LlmClient client = new LlmClient() {
            public LlmResponse complete(LlmRequest request) { throw new LlmException("private provider error"); }
            public String describe() { return "scripted"; }
            public boolean isMock() { return false; }
        };
        var events = new ArrayList<GenerationProgress.Stage>();
        assertThrows(LlmException.class, () -> new StructuredLlm(client, mapper, new LlmCallLog()).call(
                LlmRequest.of(LlmPurpose.STORY_COMPILE, "system", "user", Map.of()), Payload.class, value -> null,
                (stage, attempt, count) -> events.add(stage)));
        assertEquals(List.of(GenerationProgress.Stage.REQUEST_STARTED, GenerationProgress.Stage.REPAIR_REQUESTED,
                GenerationProgress.Stage.REQUEST_STARTED, GenerationProgress.Stage.REPAIR_REQUESTED,
                GenerationProgress.Stage.REQUEST_STARTED), events);
    }

    @Test void streamProgressUsesRealReceivedCharactersAndIsBounded() throws Exception {
        String content = "x".repeat(10000);
        String first = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("content", content.substring(0, 100))))));
        String second = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("content", content.substring(100))))));
        var counts = new ArrayList<Integer>();
        var stages = new ArrayList<GenerationProgress.Stage>();
        String actual = SseAssembler.assemble(new BufferedReader(new StringReader(
                ": keep-alive\n\ndata: " + first + "\n\ndata: " + second + "\n\ndata: [DONE]\n")),
                mapper, System.currentTimeMillis() + 10000, "test", (stage, attempt, count) -> {
                    stages.add(stage); counts.add(count);
                });
        assertEquals(content, actual);
        assertEquals(List.of(1, 2048, 4096, 8192), counts);
        assertTrue(stages.stream().allMatch(stage -> stage == GenerationProgress.Stage.CONTENT_RECEIVED));
    }
}
