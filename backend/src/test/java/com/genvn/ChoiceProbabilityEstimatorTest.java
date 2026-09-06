package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.LlmClient;
import com.genvn.llm.LlmException;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.LlmResponse;
import com.genvn.llm.MockLlmClient;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.Choice;
import com.genvn.speculation.ChoiceProbabilityEstimator;
import com.genvn.speculation.ChoiceProbabilityEstimator.BranchOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ChoiceProbabilityEstimatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Choice> choices = List.of(choice("ask", "询问邻居", "social"), choice("look", "检查门锁", "investigation"));

    @Test void oneStructuredCallEstimatesBothDepthsAndNormalizesEachBranchIndependently() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<LlmRequest> captured = new AtomicReference<>();
        var estimator = scripted(request -> {
            calls.incrementAndGet(); captured.set(request);
            return """
                    {"currentChoices":{"ask":3,"look":1},"nextChoices":{
                     "session:v1:ask:SUCCESS":{"ask":1,"look":9},
                     "session:v1:ask:FAILURE":{"ask":4,"look":1}}}
                    """;
        });
        var result = estimator.estimate(List.of("询问过去发生的事"), choices,
                List.of(new BranchOptions("session:v1:ask:SUCCESS", choices), new BranchOptions("session:v1:ask:FAILURE", choices)));
        assertEquals(1, calls.get());
        assertEquals(LlmPurpose.CHOICE_PROBABILITIES, captured.get().purpose());
        assertEquals(0.75, result.currentChoices().get("ask"), 1e-9);
        assertEquals(0.1, result.nextChoices().get("session:v1:ask:SUCCESS").get("ask"), 1e-9);
        assertEquals(0.8, result.nextChoices().get("session:v1:ask:FAILURE").get("ask"), 1e-9);
        assertThrows(UnsupportedOperationException.class, () -> result.currentChoices().put("ask", 0.0));
        assertThrows(UnsupportedOperationException.class, () -> result.nextChoices().get("session:v1:ask:SUCCESS").clear());
    }

    @Test void promptUsesOnlyRecentChosenHistoryAndAtMostSixBranches() throws Exception {
        var actualMock = new MockLlmClient(mapper);
        AtomicReference<LlmRequest> captured = new AtomicReference<>();
        var estimator = scripted(request -> { captured.set(request); return actualMock.complete(request).text(); });
        var history = IntStream.range(0, 20).mapToObj(index -> "adopted-" + index).toList();
        var branches = IntStream.range(0, 8).mapToObj(index -> new BranchOptions("branch-" + index, choices)).toList();
        var result = estimator.estimate(history, choices, branches);
        var payload = mapper.readTree(captured.get().user());
        assertEquals(12, payload.path("previousChoices").size());
        assertEquals("adopted-8", payload.path("previousChoices").get(0).asText());
        assertEquals(6, payload.path("branches").size());
        assertEquals(6, result.nextChoices().size());
        assertFalse(result.nextChoices().containsKey("branch-6"));
        assertFalse(result.nextChoices().containsKey("branch-7"));
    }

    @Test void unsupportedOrInvalidWeightsNeverProduceInventedFallbackProbabilities() {
        for (String probabilities : List.of(
                "{\"ask\":1}", "{\"ask\":1,\"invented\":2}",
                "{\"ask\":-1,\"look\":2}", "{\"ask\":0,\"look\":0}",
                "{\"ask\":null,\"look\":2}", "{\"ask\":\"NaN\",\"look\":2}")) {
            AtomicInteger calls = new AtomicInteger();
            var estimator = scripted(request -> {
                calls.incrementAndGet();
                return "{\"currentChoices\":" + probabilities + ",\"nextChoices\":{}}";
            });
            assertThrows(LlmException.class, () -> estimator.estimate(List.of(), choices, List.of()));
            assertEquals(3, calls.get(), "Structured repair is bounded and must ultimately fail closed");
        }
        var wrongBranch = scripted(request -> "{\"currentChoices\":{\"ask\":1,\"look\":1},\"nextChoices\":{\"invented\":{\"ask\":1,\"look\":1}}}");
        assertThrows(LlmException.class, () -> wrongBranch.estimate(List.of(), choices, List.of(new BranchOptions("real", choices))));
    }

    @Test void finiteExtremeWeightsNormalizeWithoutOverflowAndZeroRemainsZero() {
        var estimator = scripted(request -> "{\"currentChoices\":{\"ask\":1e308,\"look\":1e308},\"nextChoices\":{\"branch\":{\"ask\":0,\"look\":1e308}}}");
        var result = estimator.estimate(List.of(), choices, List.of(new BranchOptions("branch", choices)));
        assertEquals(0.5, result.currentChoices().get("ask"));
        assertEquals(0.5, result.currentChoices().get("look"));
        assertEquals(0.0, result.nextChoices().get("branch").get("ask"));
        assertEquals(1.0, result.nextChoices().get("branch").get("look"));
    }

    @Test void mockIsUniformWithoutHistoryAndReflectsOnlyActuallyChosenApproaches() {
        var estimator = new ChoiceProbabilityEstimator(new StructuredLlm(new MockLlmClient(mapper), mapper, new LlmCallLog()));
        var noHistory = estimator.estimate(List.of(), choices, List.of(new BranchOptions("branch", choices)));
        assertEquals(0.5, noHistory.currentChoices().get("ask"));
        assertEquals(0.5, noHistory.nextChoices().get("branch").get("ask"));
        var socialHistory = estimator.estimate(List.of("安慰受惊的邻居", "询问他看见了什么"), choices,
                List.of(new BranchOptions("branch", choices)));
        assertEquals(0.75, socialHistory.currentChoices().get("ask"), 1e-9);
        assertEquals(0.75, socialHistory.nextChoices().get("branch").get("ask"), 1e-9);
    }

    @Test void ambiguousOrUnboundedChoiceGroupsAreRejectedBeforeCallingTheModel() {
        AtomicInteger calls = new AtomicInteger();
        var estimator = scripted(request -> { calls.incrementAndGet(); return "{}"; });
        assertThrows(IllegalArgumentException.class, () -> estimator.estimate(List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> estimator.estimate(List.of(), List.of(choices.getFirst(), choices.getFirst()), List.of()));
        var five = IntStream.range(0, 5).mapToObj(index -> choice("c" + index, "选项", "social")).toList();
        assertThrows(IllegalArgumentException.class, () -> estimator.estimate(List.of(), five, List.of()));
        assertThrows(IllegalArgumentException.class, () -> estimator.estimate(List.of(), choices,
                List.of(new BranchOptions("duplicate", choices), new BranchOptions("duplicate", choices))));
        assertEquals(0, calls.get());
    }

    private ChoiceProbabilityEstimator scripted(Function<LlmRequest, String> response) {
        LlmClient client = new LlmClient() {
            public LlmResponse complete(LlmRequest request) { return new LlmResponse(response.apply(request), "scripted", 1); }
            public String describe() { return "scripted"; }
            public boolean isMock() { return false; }
        };
        return new ChoiceProbabilityEstimator(new StructuredLlm(client, mapper, new LlmCallLog()));
    }

    private static Choice choice(String id, String text, String approach) {
        return new Choice(id, text, approach, null, List.of());
    }
}
