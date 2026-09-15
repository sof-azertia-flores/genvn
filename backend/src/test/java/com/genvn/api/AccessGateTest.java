package com.genvn.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetCoordinator;
import com.genvn.asset.AssetPipeline;
import com.genvn.asset.AssetStore;
import com.genvn.config.AccessConfig;
import com.genvn.config.GenvnProperties;
import com.genvn.persistence.GameSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The access key gate as the browser sees it: the same servlet filters the running app
 * registers, in the same order, in front of the real controllers.
 */
class AccessGateTest {

    private static final String KEY = "correct horse battery staple";
    private static final String CDN = "https://vn.example.com";

    private static MockMvc app(AccessGate gate) {
        var pipeline = mock(AssetPipeline.class);
        when(pipeline.status(anyString())).thenAnswer(call -> {
            Map<String, Object> row = new HashMap<>(Map.of("assetId", "bg.hall", "status", "READY", "generationVersion", 2));
            Map<String, Object> status = new HashMap<>();
            status.put("assets", new ArrayList<>(List.of(row)));
            return status;
        });
        when(pipeline.snapshot(anyString())).thenReturn(Optional.empty());
        var properties = new GenvnProperties();
        properties.setAllowedOrigins(List.of(CDN + "/"));
        var cors = new CorsFilter(AccessConfig.corsSource(properties.getAllowedOrigins()));
        return MockMvcBuilders.standaloneSetup(
                        new AccessController(gate),
                        new AssetController(pipeline, mock(AssetStore.class), mock(GameSessionRepository.class),
                                AssetCoordinator.disabled(), gate))
                .addFilters(cors, new AccessKeyFilter(gate, new ObjectMapper()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("with no key configured nothing changes: every call passes and picture URLs carry no token")
    void openGate() throws Exception {
        MockMvc mvc = app(AccessGate.open());
        mvc.perform(get("/api/access")).andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(false)).andExpect(jsonPath("$.granted").value(true))
                .andExpect(jsonPath("$.language").value("zh"));
        mvc.perform(get("/api/sessions/s1/assets")).andExpect(status().isOk())
                .andExpect(jsonPath("$.assets[0].url").value("/api/assets/s1/bg.hall"));
        // Picture bytes reach the controller (which has no manifest here) instead of being refused.
        mvc.perform(get("/api/assets/s1/bg.hall")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a locked server refuses calls without the key, with a JSON body and CORS headers the page can read")
    void lockedGateRefusesMissingAndWrongKeys() throws Exception {
        MockMvc mvc = app(new AccessGate(KEY));
        mvc.perform(get("/api/sessions/s1/assets").header("Origin", CDN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", CDN))
                .andExpect(jsonPath("$.error").value("access_key_required"));
        mvc.perform(get("/api/sessions/s1/assets").header(AccessGate.HEADER, "nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("access_key_invalid"));
        mvc.perform(post("/api/sessions").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        // An origin that is not on the list gets no CORS headers at all -- the browser blocks it.
        mvc.perform(get("/api/access").header("Origin", "https://elsewhere.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("the probe answers without a key and reports whether the presented one is right")
    void probeNeedsNoKey() throws Exception {
        MockMvc mvc = app(new AccessGate(KEY));
        mvc.perform(get("/api/access")).andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true)).andExpect(jsonPath("$.granted").value(false));
        mvc.perform(get("/api/access").header(AccessGate.HEADER, "nope")).andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(false));
        mvc.perform(get("/api/access").header(AccessGate.HEADER, " " + KEY + " ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true));
    }

    @Test
    @DisplayName("the right key passes, and picture URLs then carry the per-save token instead of the key")
    void rightKeyPassesAndSignsPictureUrls() throws Exception {
        AccessGate gate = new AccessGate(KEY);
        MockMvc mvc = app(gate);
        String token = gate.assetToken("s1");
        mvc.perform(get("/api/sessions/s1/assets").header(AccessGate.HEADER, KEY).header("Origin", CDN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", CDN))
                .andExpect(jsonPath("$.assets[0].url").value("/api/assets/s1/bg.hall?t=" + token))
                .andExpect(jsonPath("$.assets[0].url").value(not(containsString(KEY))));
    }

    @Test
    @DisplayName("picture bytes accept that save's token and nothing else")
    void pictureTokenIsScopedToOneSave() throws Exception {
        AccessGate gate = new AccessGate(KEY);
        MockMvc mvc = app(gate);
        // Through the filter: the controller answers (404 here, since the test has no manifest).
        mvc.perform(get("/api/assets/s1/bg.hall").param("t", gate.assetToken("s1"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/assets/s1/bg.hall").param("t", gate.assetToken("s2"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assets/s1/bg.hall").param("t", "deadbeef")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assets/s1/bg.hall")).andExpect(status().isUnauthorized());
        // The token opens pictures only, never the session API.
        mvc.perform(get("/api/sessions/s1/assets").param("t", gate.assetToken("s1"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assets/s1/bg.hall").param("t", gate.assetToken("s1"))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a CORS preflight from an allowed origin is answered without the key")
    void preflightNeedsNoKey() throws Exception {
        MockMvc mvc = app(new AccessGate(KEY));
        mvc.perform(options("/api/sessions/s1/choices/c1")
                        .header("Origin", CDN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", AccessGate.HEADER + ", Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", CDN))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString(AccessGate.HEADER)))
                .andExpect(header().string("Access-Control-Max-Age", "3600"));
        mvc.perform(options("/api/sessions")
                        .header("Origin", "https://elsewhere.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        // Loopback stays allowed without configuration, so the local dev server keeps working.
        mvc.perform(options("/api/sessions")
                        .header("Origin", "http://localhost:5180")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5180"));
    }

    @Test
    @DisplayName("tokens are stable per save, differ between saves, and never reveal the key")
    void tokens() {
        AccessGate gate = new AccessGate(KEY);
        String token = gate.assetToken("save-a");
        assertEquals(32, token.length());
        assertTrue(token.matches("[0-9a-f]+"));
        assertEquals(token, gate.assetToken("save-a"));
        assertNotEquals(token, gate.assetToken("save-b"));
        assertNotEquals(token, new AccessGate("another key").assetToken("save-a"));
        assertTrue(gate.assetTokenMatches("save-a", token));
        assertFalse(gate.assetTokenMatches("save-b", token));
        assertFalse(gate.assetTokenMatches("save-a", null));
        assertTrue(gate.matches(KEY));
        assertFalse(gate.matches(KEY + "x"));
        assertFalse(gate.matches(""));
        assertTrue(AccessGate.open().matches(null));
        assertEquals("", AccessGate.open().assetToken("save-a"));
        assertFalse(new AccessGate("   ").required(), "a blank key is no key");
    }

    @Test
    @DisplayName("configured origins are trimmed of trailing slashes and blanks are ignored")
    void originList() {
        var source = AccessConfig.corsSource(List.of(" https://vn.example.com/ ", "", "https://*.pages.dev"));
        var config = source.getCorsConfigurations().get("/api/**");
        assertEquals(List.of("http://localhost:*", "http://127.0.0.1:*", "https://vn.example.com", "https://*.pages.dev"),
                config.getAllowedOriginPatterns());
        assertNull(AccessConfig.corsSource(null).getCorsConfigurations().get("/api/**").getAllowedOrigins());
    }
}
