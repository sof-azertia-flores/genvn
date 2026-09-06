package com.genvn;

import com.genvn.config.GenvnProperties;
import com.genvn.llm.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config file replaced environment variables, which means a typo now fails *silently*:
 * a misspelled key binds to nothing and the default quietly applies. These tests make the
 * committed template (config/application.example.yml) prove that every key in it is real and
 * lands where it claims -- and, if the user's live config/application.yml exists, that it has
 * no typos either, without ever asserting anything about the secret it holds.
 */
class ConfigFileTest {

    /** The committed template. Must never contain a real key. */
    private static final String CONFIG = "config/application.example.yml";
    /** The user's live file. Gitignored, may hold a real key, may not exist on a fresh clone. */
    private static final String LIVE_CONFIG = "config/application.yml";

    /** Keys the config file is allowed to contain, i.e. the ones the app actually reads. */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "server.address",
            "server.port",
            "llm.api-key",
            "llm.base-url",
            "llm.model",
            "llm.temperature",
            "llm.timeout-seconds",
            "llm.idle-timeout-seconds",
            "llm.json-mode",
            "llm.stream",
            "llm.force-mock",
            "llm.reasoning.effort",
            "llm.reasoning.story-compile",
            "llm.reasoning.scene-generate",
            "llm.reasoning.arc-continue",
            "llm.reasoning.choice-probabilities",
            "llm.reasoning.spare-designs",
            "genvn.data-dir",
            "genvn.spare-designs",
            "genvn.access-key",
            "genvn.allowed-origins",
            "genvn.speculation.enabled",
            "genvn.speculation.max-branches",
            "genvn.speculation.threads",
            "genvn.continuation.enabled",
            "genvn.continuation.threshold",
            "image.enabled",
            "image.provider",
            "image.api-key",
            "image.base-url",
            "image.model",
            "image.background-size",
            "image.portrait-size",
            "image.quality",
            "image.output-format",
            "image.transparent-portraits",
            "image.timeout-seconds",
            "image.idle-timeout-seconds",
            "image.concurrency",
            "image.queue-capacity",
            "image.max-attempts",
            "image.first-batch-budget",
            "image.arc-budget",
            "image.plan.locations",
            "image.plan.characters",
            "image.plan.expressions");

    private Binder binder() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("genvn-config", new FileSystemResource(CONFIG));
        assertFalse(sources.isEmpty(), CONFIG + " could not be loaded");
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    private Set<String> keysInFile() throws IOException {
        return keysIn(CONFIG);
    }

    private static Set<String> keysIn(String path) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("genvn-config", new FileSystemResource(path));
        return sources.stream()
                .filter(EnumerablePropertySource.class::isInstance)
                .map(EnumerablePropertySource.class::cast)
                .flatMap(source -> Arrays.stream(source.getPropertyNames()))
                // A YAML list binds as key[0], key[1], ...; the list itself is the known key.
                .map(name -> name.replaceAll("\\[\\d+]", ""))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("every key in the committed template is one the app actually reads")
    void noTyposInConfigFile() throws IOException {
        Set<String> unknown = new TreeSet<>(keysInFile());
        unknown.removeAll(KNOWN_KEYS);
        assertTrue(unknown.isEmpty(),
                CONFIG + " contains key(s) nothing binds -- typo, or a setting that no longer exists: " + unknown);
    }

    @Test
    @DisplayName("if the user's live config exists, its keys are real too (its values are its own business)")
    void noTyposInLiveConfigIfPresent() throws IOException {
        if (!new java.io.File(LIVE_CONFIG).isFile()) {
            return; // fresh clone: nothing to check, defaults apply
        }
        Set<String> unknown = new TreeSet<>(keysIn(LIVE_CONFIG));
        unknown.removeAll(KNOWN_KEYS);
        assertTrue(unknown.isEmpty(),
                LIVE_CONFIG + " contains key(s) nothing binds -- a typo here silently falls back to the default: " + unknown);
    }

    @Test
    @DisplayName("the template binds to the engine settings with the documented values")
    void bindsGenvnProperties() throws IOException {
        GenvnProperties genvn = binder().bind("genvn", Bindable.of(GenvnProperties.class)).get();
        assertEquals("data", genvn.getDataDir());
        assertEquals(3, genvn.getSpareDesigns());
        assertEquals("", genvn.getAccessKey(), "the template ships with the gate open");
        assertTrue(genvn.getAllowedOrigins().isEmpty(), "the template allows loopback origins only");
        assertTrue(genvn.getSpeculation().isEnabled());
        assertEquals(6, genvn.getSpeculation().getMaxBranches());
        assertEquals(4, genvn.getSpeculation().getThreads());
        assertTrue(genvn.getContinuation().isEnabled());
        assertEquals(0.6, genvn.getContinuation().getThreshold(), 0.0001);
    }

    @Test
    @DisplayName("the committed template has no api key, so a fresh checkout runs in mock mode")
    void bindsLlmProperties() throws IOException {
        LlmProperties llm = binder().bind("llm", Bindable.of(LlmProperties.class)).get();
        assertFalse(llm.hasCredentials(), "the committed template must never contain a real api key");
        assertFalse(llm.isForceMock());
        assertEquals("https://api.openai.com/v1", llm.getBaseUrl());
        assertTrue(llm.isJsonMode());
        assertEquals(300, llm.getTimeoutSeconds());
        assertTrue(llm.isStream(), "streaming is on by default: it is what prevents gateway 504s on long generations");
        for (var purpose : com.genvn.llm.LlmPurpose.values()) {
            assertEquals("", llm.getReasoning().effortFor(purpose), "no reasoning_effort is sent unless configured");
        }
    }

    @Test
    @DisplayName("pictures ship OFF with no key, and the template's defaults match the code's")
    void bindsImageProperties() throws IOException {
        com.genvn.config.ImageProperties img = binder().bind("image",
                Bindable.of(com.genvn.config.ImageProperties.class)).get();
        assertFalse(img.isEnabled(), "a fresh checkout must never spend money on pictures");
        assertFalse(img.hasCredentials(), "the committed template must never contain an image api key");
        com.genvn.config.ImageProperties code = new com.genvn.config.ImageProperties();
        assertEquals(code.getConcurrency(), img.getConcurrency());
        assertEquals(code.getFirstBatchBudget(), img.getFirstBatchBudget());
        assertEquals(code.getArcBudget(), img.getArcBudget());
        assertEquals(code.getMaxAttempts(), img.getMaxAttempts());
        assertEquals(code.getPlan().getLocations(), img.getPlan().getLocations());
        assertEquals(code.getPlan().getCharacters(), img.getPlan().getCharacters());
        assertEquals(code.getPlan().expressionList(), img.getPlan().expressionList());
        assertEquals(java.util.List.of("worried", "suspicious"), img.getPlan().expressionList());
    }

    @Test
    @DisplayName("the committed template binds to loopback, not to every interface")
    void bindsLoopbackByDefault() throws IOException {
        String address = binder().bind("server.address", Bindable.of(String.class)).get();
        assertEquals("127.0.0.1", address,
                "the committed config must not expose the server beyond loopback by default");
    }

    @Test
    @DisplayName("class defaults match the template, so deleting the config changes nothing")
    void codeDefaultsMatchTheShippedFile() throws IOException {
        GenvnProperties fromFile = binder().bind("genvn", Bindable.of(GenvnProperties.class)).get();
        GenvnProperties fromCode = new GenvnProperties();
        assertEquals(fromCode.getDataDir(), fromFile.getDataDir());
        assertEquals(fromCode.getAccessKey(), fromFile.getAccessKey());
        assertEquals(fromCode.getAllowedOrigins(), fromFile.getAllowedOrigins());
        assertEquals(fromCode.getSpeculation().isEnabled(), fromFile.getSpeculation().isEnabled());
        assertEquals(fromCode.getSpeculation().getMaxBranches(), fromFile.getSpeculation().getMaxBranches());
        assertEquals(fromCode.getSpeculation().getThreads(), fromFile.getSpeculation().getThreads());
        assertEquals(fromCode.getContinuation().isEnabled(), fromFile.getContinuation().isEnabled());
        assertEquals(fromCode.getContinuation().getThreshold(), fromFile.getContinuation().getThreshold(), 0.0001);
    }
}
