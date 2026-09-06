package com.genvn.llm;

import java.util.Map;

/**
 * One structured-output request.
 *
 * {@code mockContext} carries already-structured domain data purely so {@link MockLlmClient}
 * can produce a coherent, state-aware fake response without re-parsing the prompt.
 * Real providers ignore it entirely -- it never leaves the process.
 */
public record LlmRequest(
        LlmPurpose purpose,
        String system,
        String user,
        Map<String, Object> mockContext,
        /** Null uses the provider's configured default; a number is an explicit override. */
        Double temperature
) {
    public LlmRequest {
        mockContext = mockContext == null ? Map.of() : mockContext;
    }

    public static LlmRequest of(LlmPurpose purpose, String system, String user, Map<String, Object> mockContext) {
        return new LlmRequest(purpose, system, user, mockContext, null);
    }
}
