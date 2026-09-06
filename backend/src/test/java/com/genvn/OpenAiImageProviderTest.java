package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.ImageEditRequest;
import com.genvn.asset.ImageProviderException;
import com.genvn.asset.ImageRequest;
import com.genvn.asset.ImageResult;
import com.genvn.asset.OpenAiImageProvider;
import com.genvn.config.ImageProperties;
import com.genvn.support.FakeImageProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real adapter against a local HTTP double that speaks the OpenAI Images API. This is the
 * "simulated HTTP verification": request shape, both response shapes, multipart edits, and the
 * retryable / non-retryable classification -- with no network and no key.
 */
class OpenAiImageProviderTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseJson;
    private final byte[] png = FakeImageProvider.png("x", 8, 8);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", ex -> respond(ex, "generations"));
        server.createContext("/v1/images/edits", ex -> respond(ex, "edits"));
        server.createContext("/file.png", ex -> {
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
            ex.close();
        });
        server.start();
        responseJson = "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png) + "\"}]}";
    }

    private void respond(com.sun.net.httpserver.HttpExchange ex, String which) throws IOException {
        lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastContentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
        if (status == 429) ex.getResponseHeaders().add("Retry-After", "7");
        byte[] out = (status == 200 ? responseJson : "{\"error\":{\"message\":\"nope " + which + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiImageProvider provider(String model) {
        ImageProperties p = new ImageProperties();
        p.setEnabled(true);
        p.setApiKey("test-key");
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        p.setModel(model);
        p.setTimeoutSeconds(5);
        return new OpenAiImageProvider(p, new ObjectMapper());
    }

    @Test
    @DisplayName("gpt-image: sends output_format and transparent background, reads base64, keeps the model id")
    void gptImageGeneration() throws Exception {
        ImageResult r = provider("gpt-image-1").generate(new ImageRequest("a hall", 1536, 1024, "png", true, "low"));
        assertArrayEquals(png, r.bytes());
        assertEquals("image/png", r.mimeType());
        assertEquals("gpt-image-1", r.model());
        String body = lastBody.get();
        assertTrue(body.contains("\"model\":\"gpt-image-1\""));
        assertTrue(body.contains("\"size\":\"1536x1024\""));
        assertTrue(body.contains("\"output_format\":\"png\""));
        assertTrue(body.contains("\"background\":\"transparent\""));
        assertTrue(body.contains("\"quality\":\"low\""));
        assertFalse(body.contains("response_format"), "gpt-image models reject response_format");
    }

    @Test
    void gptImage2KeepsNativeTransparencyOnBothEndpointsAndOverridesConflictingSceneProse() throws Exception {
        var provider = provider("gpt-image-2");
        assertTrue(provider.supportsTransparentBackground());
        ImageResult generated = provider.generate(new ImageRequest("Character standing in a factory background", 1024, 1536, "jpeg", true, "medium"));
        var body = new ObjectMapper().readTree(lastBody.get());
        assertEquals("gpt-image-2", body.path("model").asText());
        assertEquals("transparent", body.path("background").asText());
        assertEquals("png", body.path("output_format").asText());
        assertEquals("image/png", generated.mimeType());
        assertTrue(body.path("prompt").asText().indexOf("FINAL OUTPUT REQUIREMENT") > body.path("prompt").asText().indexOf("factory background"));
        provider.edit(new ImageEditRequest("same person, suspicious", png, "image/png", 1024, 1536, "jpeg", true, "medium"));
        assertTrue(lastBody.get().contains("name=\"background\"\r\n\r\ntransparent"));
        assertTrue(lastBody.get().contains("name=\"output_format\"\r\n\r\npng"));
        // An edit sends the planner's prompt verbatim: the appended output block made a real
        // gpt-image route return opaque RGB poses (bisected against the live endpoint, 2026-09-05).
        assertTrue(lastBody.get().contains("name=\"prompt\"\r\n\r\nsame person, suspicious\r\n"));
        assertFalse(lastBody.get().contains("FINAL OUTPUT REQUIREMENT"));
    }

    @Test
    @DisplayName("dall-e: asks for b64_json, never sends output_format or transparency, and can follow a url")
    void dalleGeneration() throws Exception {
        provider("dall-e-3").generate(new ImageRequest("a hall", 1792, 1024, "png", true, ""));
        String body = lastBody.get();
        assertTrue(body.contains("\"response_format\":\"b64_json\""));
        assertFalse(body.contains("output_format"));
        assertFalse(body.contains("transparent"));
        assertFalse(body.contains("quality"), "blank quality sends nothing");

        responseJson = "{\"data\":[{\"url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/file.png\"}]}";
        ImageResult r = provider("dall-e-3").generate(new ImageRequest("a hall", 1024, 1024, "png", false, ""));
        assertArrayEquals(png, r.bytes(), "a url response is downloaded, not stored as a link");
    }

    @Test
    @DisplayName("an edit is a multipart request carrying the reference image and the prompt")
    void editIsMultipart() throws Exception {
        OpenAiImageProvider p = provider("gpt-image-1");
        assertTrue(p.supportsEdit());
        ImageResult r = p.edit(new ImageEditRequest("same person, worried", png, "image/png", 1024, 1536, "png", true, ""));
        assertArrayEquals(png, r.bytes());
        assertTrue(lastContentType.get().startsWith("multipart/form-data; boundary="));
        String body = lastBody.get();
        assertTrue(body.contains("name=\"image\"; filename=\"reference.png\""));
        assertTrue(body.contains("name=\"prompt\"\r\n\r\nsame person, worried"));
        assertTrue(body.contains("name=\"background\"\r\n\r\ntransparent"));
        assertFalse(provider("dall-e-3").supportsEdit(), "dall-e has no edit-from-reference path here");
    }

    @Test
    void characterCardEditExplicitlyAddsAnOpaqueBackground() throws Exception {
        provider("gpt-image-1").edit(new ImageEditRequest("permanent character card", png,
                "image/png", 1024, 1536, "png", false, ""));
        assertTrue(lastBody.get().contains("name=\"background\"\r\n\r\nopaque"));
        assertTrue(lastBody.get().contains("name=\"image\"; filename=\"reference.png\""));
    }

    @Test
    @DisplayName("429 with Retry-After is retryable and carries the delay; 400 is not retryable")
    void classification() {
        status = 429;
        ImageProviderException tooMany = assertThrows(ImageProviderException.class,
                () -> provider("gpt-image-1").generate(new ImageRequest("x", 1024, 1024, "png", false, "")));
        assertTrue(tooMany.isRetryable());
        assertEquals(429, tooMany.statusCode());
        assertEquals(7_000, tooMany.retryAfterMillis());

        status = 400;
        ImageProviderException bad = assertThrows(ImageProviderException.class,
                () -> provider("gpt-image-1").generate(new ImageRequest("x", 1024, 1024, "png", false, "")));
        assertFalse(bad.isRetryable(), "bad parameters must not burn budget on retries");
        assertTrue(bad.getMessage().contains("400"));

        status = 200;
        responseJson = "{\"data\":[]}";
        ImageProviderException empty = assertThrows(ImageProviderException.class,
                () -> provider("gpt-image-1").generate(new ImageRequest("x", 1024, 1024, "png", false, "")));
        assertFalse(empty.isRetryable());
    }
}
