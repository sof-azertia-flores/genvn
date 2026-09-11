package com.genvn.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Switches between the mock and a live OpenAI-compatible client when credentials or
 * {@code llm.force-mock} change. Model, URL, timeouts and keys are read from {@link LlmProperties}
 * on every call, so those take effect without rebuilding the client.
 */
public final class ReloadableLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ReloadableLlmClient.class);

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private volatile LlmClient delegate;
    private volatile String signature;

    public ReloadableLlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        reload();
    }

    public synchronized void reload() {
        String next = signature();
        if (next.equals(signature) && delegate != null) return;
        LlmClient created = create(props, mapper);
        delegate = created;
        signature = next;
        log.info("LLM: using {}", created.describe());
    }

    static LlmClient create(LlmProperties props, ObjectMapper mapper) {
        if (props.isForceMock() || !props.hasCredentials()) {
            String reason = props.isForceMock() ? "forced by llm.force-mock" : "llm.api-key is empty";
            return new MockLlmClient(mapper, reason);
        }
        return new OpenAiCompatibleLlmClient(props, mapper);
    }

    private String signature() {
        return (props.isForceMock() || !props.hasCredentials() ? "mock" : "live") + "|" + props.isForceMock();
    }

    private LlmClient current() {
        LlmClient client = delegate;
        if (client == null) reload();
        return delegate;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return current().complete(request);
    }

    @Override
    public LlmResponse complete(LlmRequest request, GenerationProgress progress) {
        return current().complete(request, progress);
    }

    @Override
    public String describe() {
        return current().describe();
    }

    @Override
    public boolean isMock() {
        return current().isMock();
    }
}
