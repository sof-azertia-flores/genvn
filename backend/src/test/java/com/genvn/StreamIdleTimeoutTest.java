package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.llm.LlmException;
import com.genvn.llm.LlmProperties;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.OpenAiCompatibleLlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The JDK request timeout ends when headers arrive. A gateway that then holds the connection
 * open without sending bytes used to block the reading thread -- and with it the session --
 * until the connection finally dropped. The idle watchdog turns that into a bounded, retryable
 * failure on both the streaming and the plain path.
 */
class StreamIdleTimeoutTest {

    @ParameterizedTest(name = "a body that goes quiet is abandoned, stream={0}")
    @ValueSource(booleans = {true, false})
    void stalledBodyFailsRetryablyInsteadOfHanging(boolean stream) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            // Headers and a first fragment arrive; then nothing, for longer than the idle limit.
            out.write((stream
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"{\"}}]}\n\n"
                    : "{\"choices\":[{\"message\":{\"content\":\"{").getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            LlmProperties props = new LlmProperties();
            props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            props.setApiKey("dummy-local-test-key");
            props.setStream(stream);
            props.setIdleTimeoutSeconds(1);
            props.setTimeoutSeconds(30);
            var client = new OpenAiCompatibleLlmClient(props, new ObjectMapper());

            long started = System.nanoTime();
            LlmException failure = assertThrows(LlmException.class, () -> client.complete(
                    LlmRequest.of(LlmPurpose.SCENE_GENERATE, "Test", "Test", Map.of())));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsed < 8000, "gave up only after " + elapsed + "ms");
            assertTrue(failure.isRetryable(), "a quiet gateway is worth another attempt");
            assertTrue(failure.getMessage().contains("no bytes"), failure.getMessage());
            assertEquals(1, requests.get(), "the client itself never re-sends");
        } finally {
            release.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }
}
