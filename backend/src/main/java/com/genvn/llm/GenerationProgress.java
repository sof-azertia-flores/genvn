package com.genvn.llm;

/** Content-free observations of actual work; no prompts or partial prose cross this boundary. */
@FunctionalInterface
public interface GenerationProgress {
    GenerationProgress NONE = (stage, attempt, receivedCharacters) -> {};

    enum Stage {
        REQUEST_STARTED, RESPONSE_STARTED, CONTENT_RECEIVED, RESPONSE_RECEIVED,
        JSON_EXTRACTED, SCHEMA_PARSED, VALIDATED, REPAIR_REQUESTED
    }

    void report(Stage stage, int attempt, int receivedCharacters);
}
