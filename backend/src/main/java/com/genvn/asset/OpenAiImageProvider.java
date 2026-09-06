package com.genvn.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.config.ImageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.genvn.llm.IdleWatchdog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * OpenAI Images API adapter: POST /images/generations (JSON) and POST /images/edits
 * (multipart, with the base portrait as the reference).
 *
 * Model differences are handled here and nowhere else: gpt-image-* models return base64,
 * accept output_format/background and quality tokens like low/medium/high; dall-e-* models need
 * response_format=b64_json, do not accept output_format or a transparent background, and use
 * standard/hd. The configured model id is passed through untouched.
 */
public class OpenAiImageProvider implements ImageAssetProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageProvider.class);

    private final ImageProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiImageProvider(ImageProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    private boolean gptImageFamily() {
        return props.getModel() != null && props.getModel().toLowerCase(Locale.ROOT).startsWith("gpt-image");
    }

    @Override public boolean isEnabled() { return props.isEnabled() && props.hasCredentials(); }
    @Override public boolean supportsEdit() { return gptImageFamily(); }
    @Override public boolean supportsTransparentBackground() { return gptImageFamily(); }

    @Override
    public String describe() {
        return "openai-images / " + props.getModel() + " @ " + props.getBaseUrl()
                + (supportsEdit() ? " (edits: yes)" : " (edits: no)");
    }

    @Override
    public ImageResult generate(ImageRequest request) throws ImageProviderException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("prompt", outputPrompt(request.prompt(), request.transparentBackground() && gptImageFamily()));
        body.put("n", 1);
        body.put("size", request.width() + "x" + request.height());
        if (request.quality() != null && !request.quality().isBlank()) body.put("quality", request.quality());
        if (gptImageFamily()) {
            body.put("output_format", request.transparentBackground() ? "png" : request.format());
            body.put("background", request.transparentBackground() ? "transparent" : "opaque");
        } else {
            body.put("response_format", "b64_json");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(trim(props.getBaseUrl()) + "/images/generations"))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(httpRequest, request.transparentBackground() && gptImageFamily() ? "png" : request.format());
    }

    @Override
    public ImageResult edit(ImageEditRequest request) throws ImageProviderException {
        String boundary = "genvn-" + UUID.randomUUID();
        byte[] multipart;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            field(out, boundary, "model", props.getModel());
            // The reference-based edit gets the planner's prompt verbatim. Appending the output
            // block below made every transparent pose edit on a gpt-image route come back as an
            // opaque RGB file, while the same request without it came back RGBA (bisected 2026-09-05).
            field(out, boundary, "prompt", request.prompt());
            field(out, boundary, "n", "1");
            field(out, boundary, "size", request.width() + "x" + request.height());
            if (request.quality() != null && !request.quality().isBlank()) field(out, boundary, "quality", request.quality());
            if (gptImageFamily()) {
                field(out, boundary, "output_format", request.transparentBackground() ? "png" : request.format());
                field(out, boundary, "background", request.transparentBackground() ? "transparent" : "opaque");
            }
            String ext = AssetStore.extensionFor(request.referenceMime());
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"image\"; filename=\"reference." + ext + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + request.referenceMime() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(request.referenceImage());
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            multipart = out.toByteArray();
        } catch (Exception e) {
            throw new ImageProviderException("could not build edit request: " + e.getMessage(), e, false);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(trim(props.getBaseUrl()) + "/images/edits"))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                .build();
        return send(httpRequest, request.transparentBackground() && gptImageFamily() ? "png" : request.format());
    }

    /**
     * Generation only. Keeps the output contract last, because character/style prose may itself
     * mention a room or scenery. Not used for edits: see the note in {@link #edit}.
     */
    private static String outputPrompt(String prompt, boolean transparent) {
        if (!transparent) return prompt;
        return prompt + "\n\nFINAL OUTPUT REQUIREMENT: Deliver one isolated character cutout as an RGBA PNG "
                + "with a genuinely transparent background (alpha 0 outside the person). "
                + "Keep the character, clothing, hair and held objects visible. "
                + "Any locations, scenery, lighting environments or backgrounds mentioned above describe context only; "
                + "do not paint them behind the character. Preserve identity and the requested pose. "
                + "No solid-color backdrop, no painted checkerboard, no frame, no text. "
                + "When editing, preserve the reference's transparent background while changing the character pose.";
    }

    private ImageResult send(HttpRequest httpRequest, String format) throws ImageProviderException {
        HttpResponse<InputStream> response;
        String body;
        long started = System.currentTimeMillis();
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            body = new String(readAll(response.body(), "image response", started), StandardCharsets.UTF_8);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ImageProviderException("image request timed out", true, 408, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageProviderException("image request interrupted", false);
        } catch (ImageProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageProviderException("image request failed: " + e.getMessage(), e, true);
        }
        int status = response.statusCode();
        if (status / 100 != 2) {
            long retryAfter = response.headers().firstValue("retry-after")
                    .map(v -> { try { return Long.parseLong(v.trim()) * 1000L; } catch (NumberFormatException x) { return 0L; } })
                    .orElse(0L);
            throw new ImageProviderException("image API HTTP " + status + ": " + truncate(body),
                    ImageProviderException.retryableStatus(status), status, retryAfter);
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode first = root.path("data").path(0);
            byte[] bytes;
            if (first.hasNonNull("b64_json")) {
                bytes = Base64.getDecoder().decode(first.get("b64_json").asText());
            } else if (first.hasNonNull("url")) {
                bytes = download(first.get("url").asText());
            } else {
                throw new ImageProviderException("image API response had no image data: " + truncate(body), false);
            }
            String mime = "image/" + ("jpg".equals(format) ? "jpeg" : format);
            return new ImageResult(bytes, mime, 0, 0, props.getModel());
        } catch (ImageProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageProviderException("could not parse image API response: " + e.getMessage(), e, false);
        }
    }

    private byte[] download(String url) throws Exception {
        HttpRequest get = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds())).GET().build();
        long started = System.currentTimeMillis();
        HttpResponse<InputStream> r = http.send(get, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes = readAll(r.body(), "image download", started);
        if (r.statusCode() / 100 != 2) throw new ImageProviderException("image download HTTP " + r.statusCode(), true, r.statusCode(), 0);
        return bytes;
    }

    /**
     * The request timeout ends when headers arrive; this bounds the body read as well, so a
     * proxy that goes quiet mid-transfer costs one retryable failure, not a worker forever.
     * The deadline is {@code image.timeout-seconds} from the START of the request, headers and
     * body together: some proxies answer with headers at once and then trickle keep-alive bytes
     * for the whole generation, so the body phase is where the real waiting happens.
     */
    private byte[] readAll(InputStream in, String what, long startedAtMillis) throws IOException, ImageProviderException {
        long deadline = startedAtMillis + props.getTimeoutSeconds() * 1000L;
        IdleWatchdog watchdog = IdleWatchdog.guard(in, props.getIdleTimeoutSeconds() * 1000L, deadline);
        try (watchdog; in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                watchdog.touch();
                out.write(buffer, 0, n);
            }
            if (watchdog.tripped() != null) throw new ImageProviderException(what + " " + watchdog.tripped(), true, 408, 0);
            return out.toByteArray();
        } catch (IOException e) {
            if (watchdog.tripped() != null) throw new ImageProviderException(what + " " + watchdog.tripped(), true, 408, 0);
            throw e;
        }
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String trim(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
