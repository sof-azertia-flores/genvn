package com.genvn.llm;

/**
 * The single seam between the domain and any LLM provider.
 * Nothing outside the llm package may know about HTTP, providers or vendor payload shapes.
 */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);

    /** Providers without streaming report only the completed response at the structured layer. */
    default LlmResponse complete(LlmRequest request, GenerationProgress progress) {
        return complete(request);
    }

    /** Human-readable description for the dev inspector, e.g. "mock" or "openai-compatible / gpt-4o-mini". */
    String describe();

    boolean isMock();
}
