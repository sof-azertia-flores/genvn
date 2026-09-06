package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.ImageProviderException;
import com.genvn.asset.ImageRequest;
import com.genvn.asset.OpenAiImageProvider;
import com.genvn.config.ImageProperties;
import com.genvn.support.FakeImageProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A proxy that answers with headers at once and trickles keep-alive bytes while the model works
 * spends the whole generation in the body phase. image.timeout-seconds is the budget for the
 * call as a whole, counted from the request, so a picture that arrives inside it succeeds.
 */
class ImageBodyDeadlineTest {

    private final byte[] png = FakeImageProvider.png("x", 8, 8);

    private HttpServer trickling(long trickleMillis) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/images/generations", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            long until = System.currentTimeMillis() + trickleMillis;
            try {
                while (System.currentTimeMillis() < until) {
                    out.write(' ');
                    out.flush();
                    Thread.sleep(100);
                }
                out.write(("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png) + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException closed) {
                // the client gave up; nothing to do
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private OpenAiImageProvider provider(HttpServer server, int timeoutSeconds) {
        ImageProperties p = new ImageProperties();
        p.setEnabled(true);
        p.setApiKey("dummy-local-test-key");
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        p.setModel("gpt-image-2");
        p.setTimeoutSeconds(timeoutSeconds);
        p.setIdleTimeoutSeconds(5);
        return new OpenAiImageProvider(p, new ObjectMapper());
    }

    @Test
    @DisplayName("a picture that arrives within the total budget succeeds even if the body took most of it")
    void slowBodyInsideTheBudgetSucceeds() throws Exception {
        HttpServer server = trickling(1500);
        try {
            var result = provider(server, 10).generate(new ImageRequest("a hall", 64, 64, "png", false, ""));
            assertArrayEquals(png, result.bytes());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("the budget counts from the request, and running out is a retryable failure that names the deadline")
    void budgetIsMeasuredFromTheRequestStart() throws Exception {
        HttpServer server = trickling(4000);
        try {
            long started = System.nanoTime();
            ImageProviderException failure = assertThrows(ImageProviderException.class,
                    () -> provider(server, 1).generate(new ImageRequest("a hall", 64, 64, "png", false, "")));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            assertTrue(failure.getMessage().contains("total read deadline"), failure.getMessage());
            assertTrue(failure.isRetryable());
            assertTrue(elapsedMillis < 3000, "gave up close to the 1s budget, not after the trickle ended: " + elapsedMillis + "ms");
        } finally {
            server.stop(0);
        }
    }
}
