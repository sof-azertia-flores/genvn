package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.llm.LlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming reassembler is what stands between a long story-compile and a gateway 504,
 * so it is exercised offline against every shape of stream we have seen in the wild.
 */
class SseAssemblerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String assemble(String body) throws Exception {
        return assemble(body, System.currentTimeMillis() + 60_000);
    }

    private String assemble(String body, long deadline) throws Exception {
        Class<?> cls = Class.forName("com.genvn.llm.SseAssembler");
        Method m = cls.getDeclaredMethod("assemble", BufferedReader.class, ObjectMapper.class, long.class, String.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(null, new BufferedReader(new StringReader(body)), mapper, deadline, "TEST");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static String chunk(String content) {
        String c = content == null ? "null" : "\"" + content.replace("\"", "\\\"") + "\"";
        return "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":" + c + "},\"finish_reason\":null}]}\n\n";
    }

    @Test
    @DisplayName("deltas are concatenated in order and [DONE] ends the stream")
    void concatenatesDeltas() throws Exception {
        String body = chunk("{\"ok\"") + chunk(":") + chunk("true}") + "data: [DONE]\n\n" + chunk("IGNORED AFTER DONE");
        assertEquals("{\"ok\":true}", assemble(body));
    }

    @Test
    @DisplayName("comments, blank keep-alives, role-only and null-content chunks are skipped")
    void skipsNoise() throws Exception {
        String body = ": keep-alive\n\n"
                + "event: message\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}\n\n"
                + chunk(null)
                + chunk("a")
                + "\n\n"
                + chunk("b")
                + "data: [DONE]\n";
        assertEquals("ab", assemble(body));
    }

    @Test
    @DisplayName("a stream that simply ends without [DONE] still yields what was received")
    void toleratesMissingDone() throws Exception {
        assertEquals("hello", assemble(chunk("hel") + chunk("lo")));
    }

    @Test
    @DisplayName("a provider that ignored stream:true and sent one JSON body is still understood")
    void fallsBackToPlainJsonBody() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"plain\\\":1}\"}}]}";
        assertEquals("{\"plain\":1}", assemble(body));
    }

    @Test
    @DisplayName("an error chunk mid-stream surfaces as a clean LlmException")
    void surfacesErrorChunk() {
        String body = chunk("partial") + "data: {\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit\"}}\n\n";
        LlmException e = assertThrows(LlmException.class, () -> assemble(body));
        assertTrue(e.getMessage().contains("rate limited"));
    }

    @Test
    @DisplayName("an empty stream is an error, not an empty scene")
    void emptyStreamIsAnError() {
        assertThrows(LlmException.class, () -> assemble(": keep-alive\n\ndata: [DONE]\n"));
    }

    @Test
    @DisplayName("a torn chunk is skipped rather than aborting the whole generation")
    void tornChunkIsSkipped() throws Exception {
        String body = chunk("a") + "data: {\"choices\":[{\"delta\":{\"content\":\"BROKEN\n\n" + chunk("b") + "data: [DONE]\n";
        assertEquals("ab", assemble(body));
    }

    @Test
    @DisplayName("a stream that outlives the deadline fails instead of hanging a game thread")
    void deadlineIsEnforced() {
        assertThrows(LlmException.class, () -> assemble(chunk("a") + chunk("b"), System.currentTimeMillis() - 1));
    }
}
