package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.llm.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LlmCancellationTest {
    @ParameterizedTest(name = "cancelled real HTTP transport, stream={0}")
    @ValueSource(booleans = {false, true})
    void interruptAbortsHttpOrSseBodyAndNeverRetries(boolean stream) throws Exception {
        CountDownLatch responseBlocked = new CountDownLatch(1), releaseResponse = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Thread> caller = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (stream) {
                // Headers arrive and the assembler is blocked waiting for the next SSE line.
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            }
            responseBlocked.countDown();
            try { releaseResponse.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var worker = Executors.newSingleThreadExecutor();
        try {
            ObjectMapper mapper = new ObjectMapper();
            LlmProperties props = new LlmProperties();
            props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            props.setApiKey("dummy-local-test-key");
            props.setStream(stream);
            var llm = new StructuredLlm(new OpenAiCompatibleLlmClient(props, mapper), mapper, new LlmCallLog());
            var result = worker.submit(() -> {
                caller.set(Thread.currentThread());
                return llm.call(LlmRequest.of(LlmPurpose.SCENE_GENERATE, "Test", "Test", Map.of()), Map.class, null);
            });
            assertTrue(responseBlocked.await(5, TimeUnit.SECONDS));
            if (stream) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean assembling = false;
                while (!assembling && System.nanoTime() < deadline) {
                    assembling = java.util.Arrays.stream(caller.get().getStackTrace())
                            .anyMatch(frame -> frame.getClassName().equals("com.genvn.llm.SseAssembler"));
                    if (!assembling) Thread.sleep(5);
                }
                assertTrue(assembling, "the cancellation must interrupt SSE body reading, not just wait-for-headers");
            }
            caller.get().interrupt(); // cancel the actual worker, not merely its result future
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(1, requests.get(), "a cancelled transport never re-enters the repair loop");
        } finally {
            releaseResponse.countDown();
            worker.shutdownNow();
            server.stop(0);
            handlers.shutdownNow();
        }
    }
}
