package com.genvn.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Talks to any OpenAI-compatible /chat/completions endpoint (OpenAI, Together, Groq,
 * llama.cpp server, LM Studio, Ollama's OpenAI shim, ...).
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiCompatibleLlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return complete(request, GenerationProgress.NONE);
    }

    @Override
    public LlmResponse complete(LlmRequest request, GenerationProgress progress) {
        LlmCancellation.check();
        long started = System.currentTimeMillis();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.getModel());
            body.put("temperature", request.temperature() == null ? props.getTemperature() : request.temperature());
            ArrayNode messages = body.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", request.system());
            ObjectNode usr = messages.addObject();
            usr.put("role", "user");
            usr.put("content", request.user());
            if (props.isJsonMode()) {
                body.putObject("response_format").put("type", "json_object");
            }
            if (props.isStream()) {
                body.put("stream", true);
            }
            String effort = props.getReasoning().effortFor(request.purpose());
            if (!effort.isBlank()) {
                body.put("reasoning_effort", effort);
            }

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(props.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            String label = request.purpose() == null ? "LLM" : request.purpose().name();
            int promptChars = (request.system() == null ? 0 : request.system().length())
                    + (request.user() == null ? 0 : request.user().length());
            log.info("{}: sending to {} ({} chars of prompt, {}{})", label, props.getModel(), promptChars,
                    props.isStream() ? "streaming" : "non-streaming",
                    effort.isBlank() ? "" : ", reasoning " + effort);

            if (props.isStream()) {
                return streamed(httpRequest, started, label, progress);
            }

            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            String responseBody = readBody(response.body(), started, label);
            long millis = System.currentTimeMillis() - started;
            log.info("{}: response {} after {}s ({} chars)", label, response.statusCode(), millis / 1000, responseBody.length());
            if (response.statusCode() / 100 != 2) {
                throw new LlmException("LLM HTTP " + response.statusCode() + ": " + truncate(responseBody), response.statusCode());
            }
            JsonNode root = mapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new LlmException("LLM response had no message content: " + truncate(responseBody), 0);
            }
            return new LlmResponse(content.asText(), props.getModel(), millis);
        } catch (LlmException e) {
            LlmCancellation.propagate(e);
            throw e;
        } catch (Exception e) {
            LlmCancellation.propagate(e);
            throw new LlmException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Streaming path. The request timeout above bounds time-to-first-byte; an {@link IdleWatchdog}
     * bounds the silence between chunks and the total time, so a stalled stream fails cleanly
     * (and retryably) instead of hanging a game thread until the gateway drops the connection.
     */
    private LlmResponse streamed(HttpRequest httpRequest, long started, String label,
                                 GenerationProgress progress) throws Exception {
        HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        log.info("{}: first byte after {}ms (HTTP {})", label, System.currentTimeMillis() - started, response.statusCode());
        if (response.statusCode() / 100 != 2) {
            String errorBody = readBody(response.body(), started, label);
            throw new LlmException("LLM HTTP " + response.statusCode() + ": " + truncate(errorBody), response.statusCode());
        }
        progress.report(GenerationProgress.Stage.RESPONSE_STARTED, 1, 0);
        long deadline = started + props.getTimeoutSeconds() * 1000L;
        InputStream body = response.body();
        IdleWatchdog watchdog = IdleWatchdog.guard(body, props.getIdleTimeoutSeconds() * 1000L, deadline);
        try (watchdog; BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String text = SseAssembler.assemble(reader, mapper, deadline, label, progress, watchdog::touch);
            if (watchdog.tripped() != null) {
                throw new LlmException("LLM stream " + watchdog.tripped() + "; the connection was closed", null, 0, true);
            }
            long millis = System.currentTimeMillis() - started;
            log.info("{}: stream complete, {} chars in {}s", label, text.length(), millis / 1000);
            return new LlmResponse(text, props.getModel(), millis);
        } catch (IOException e) {
            LlmCancellation.propagate(e);
            if (watchdog.tripped() != null) {
                throw new LlmException("LLM stream " + watchdog.tripped() + "; the connection was closed", e, 0, true);
            }
            throw e;
        }
    }

    /** Reads a whole body under the same idle/total watchdog as the streaming path. */
    private String readBody(InputStream body, long started, String label) throws IOException {
        long deadline = started + props.getTimeoutSeconds() * 1000L;
        IdleWatchdog watchdog = IdleWatchdog.guard(body, props.getIdleTimeoutSeconds() * 1000L, deadline);
        try (watchdog; InputStream in = body) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                watchdog.touch();
                out.write(buffer, 0, n);
            }
            if (watchdog.tripped() != null) {
                throw new LlmException("LLM response body " + watchdog.tripped() + "; the connection was closed", null, 0, true);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            LlmCancellation.propagate(e);
            if (watchdog.tripped() != null) {
                throw new LlmException("LLM response body " + watchdog.tripped() + "; the connection was closed", e, 0, true);
            }
            throw e;
        }
    }

    @Override
    public String describe() {
        String effort = props.getReasoning().effortFor(null);
        return "openai-compatible / " + props.getModel() + " @ " + props.getBaseUrl()
                + (props.isStream() ? " (streaming)" : "")
                + (effort.isBlank() ? "" : " (reasoning " + effort + ")");
    }

    @Override
    public boolean isMock() {
        return false;
    }

    private static String trimTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
