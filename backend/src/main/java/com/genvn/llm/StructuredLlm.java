package com.genvn.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.prompt.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/**
 * Wraps every structured-output call with: extract JSON -> deserialize -> domain-validate,
 * and on failure a bounded repair loop. If it still fails, it throws a clean exception --
 * it never returns half-parsed data, and it never mutates game state.
 */
@Service
public class StructuredLlm {

    private static final Logger log = LoggerFactory.getLogger(StructuredLlm.class);
    private static final int MAX_ATTEMPTS = 3; // 1 original + 2 repairs

    private final LlmClient client;
    private final ObjectMapper mapper;
    private final LlmCallLog callLog;

    public StructuredLlm(LlmClient client, ObjectMapper mapper, LlmCallLog callLog) {
        this.client = client;
        this.mapper = mapper;
        this.callLog = callLog;
    }

    public record Parsed<T>(T value, int repairAttempts, long totalMillis, String model) {}

    /**
     * @param validate returns null when the parsed value is usable, otherwise the reason it is not.
     */
    public <T> Parsed<T> call(LlmRequest request, Class<T> type, Function<T, String> validate) {
        return call(request, type, validate, GenerationProgress.NONE);
    }

    public <T> Parsed<T> call(LlmRequest request, Class<T> type, Function<T, String> validate,
                              GenerationProgress progress) {
        long started = System.currentTimeMillis();
        String userPrompt = request.user();
        String lastError = "unknown";
        String lastRaw = "";

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            LlmCancellation.check();
            int round = attempt + 1;
            if (attempt > 0) progress.report(GenerationProgress.Stage.REPAIR_REQUESTED, round, 0);
            progress.report(GenerationProgress.Stage.REQUEST_STARTED, round, 0);
            log.info("{}: attempt {}/{}{} via {}", request.purpose(), attempt + 1, MAX_ATTEMPTS,
                    attempt == 0 ? "" : " (repair)", client.describe());
            LlmRequest attemptRequest = new LlmRequest(request.purpose(), request.system(), userPrompt,
                    request.mockContext(), request.temperature());
            String raw;
            try {
                LlmResponse response = client.complete(attemptRequest,
                        (stage, ignored, characters) -> progress.report(stage, round, characters));
                LlmCancellation.check();
                raw = response.text();
                progress.report(GenerationProgress.Stage.RESPONSE_RECEIVED, round, raw == null ? 0 : raw.length());
                callLog.record(request.purpose(), response.model(), response.durationMillis(), true,
                        attempt == 0 ? "ok" : "repair attempt " + attempt, raw == null ? 0 : raw.length());
            } catch (RuntimeException e) {
                LlmCancellation.propagate(e);
                lastError = e.getMessage();
                lastRaw = "";
                callLog.record(request.purpose(), client.describe(), System.currentTimeMillis() - started, false,
                        "transport: " + lastError, 0);
                boolean retryable = !(e instanceof LlmException failure) || failure.isRetryable();
                if (!retryable) {
                    // A 400/401/403/404 will not change on repetition; say so at once instead of
                    // spending two more timeouts on it.
                    log.warn("LLM request rejected; not retrying: {}", lastError);
                    throw new LlmException("The model endpoint rejected the request: " + lastError, e,
                            ((LlmException) e).statusCode(), false);
                }
                log.warn("LLM transport failure ({} of {}): {}", attempt + 1, MAX_ATTEMPTS, lastError);
                // The model never saw a rejected answer, so the prompt is resent unchanged, after a
                // short pause that gives a rate limit or a flapping gateway room to recover.
                userPrompt = request.user();
                if (attempt < MAX_ATTEMPTS - 1) pauseBeforeRetry(round);
                continue;
            }

            lastRaw = raw;
            String json = JsonExtractor.extractObject(raw);
            if (json == null) {
                lastError = "response contained no JSON object";
            } else {
                progress.report(GenerationProgress.Stage.JSON_EXTRACTED, round, 0);
                try {
                    T value = mapper.readValue(json, type);
                    progress.report(GenerationProgress.Stage.SCHEMA_PARSED, round, 0);
                    String problem = validate == null ? null : validate.apply(value);
                    if (problem == null) {
                        progress.report(GenerationProgress.Stage.VALIDATED, round, 0);
                        long total = System.currentTimeMillis() - started;
                        log.info("{}: accepted as {} after {}s{}", request.purpose(), type.getSimpleName(),
                                total / 1000, attempt == 0 ? "" : " (" + attempt + " repair round(s))");
                        return new Parsed<>(value, attempt, total, client.describe());
                    }
                    lastError = problem;
                } catch (Exception e) {
                    LlmCancellation.propagate(e);
                    lastError = "could not deserialize: " + e.getMessage();
                }
            }
            log.warn("LLM structured output rejected ({} of {}): {}", attempt + 1, MAX_ATTEMPTS, lastError);
            callLog.record(request.purpose(), client.describe(), 0, false, "rejected: " + lastError, 0);
            userPrompt = Prompts.repairUser(request.user(), lastRaw, lastError);
        }

        throw new LlmException("Model could not produce valid output after " + MAX_ATTEMPTS
                + " attempts. Last problem: " + lastError);
    }

    private static void pauseBeforeRetry(int round) {
        try {
            Thread.sleep(Math.min(3000L, 500L * round));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("LLM call was cancelled while waiting to retry");
        }
    }

    public boolean usingMock() {
        return client.isMock();
    }

    public String describeClient() {
        return client.describe();
    }

}
