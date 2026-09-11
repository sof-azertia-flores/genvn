package com.genvn;

import com.genvn.config.GenvnProperties;
import com.genvn.config.ImageProperties;
import com.genvn.config.RuntimeSettings;
import com.genvn.llm.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSettingsTest {

    @Test
    @DisplayName("hot-reloadable keys update the live beans and the yaml; bind address stays pending restart")
    void applyUpdatesLiveBeansAndWritesFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("application.yml");
        LlmProperties llm = new LlmProperties();
        GenvnProperties genvn = new GenvnProperties();
        ImageProperties image = new ImageProperties();
        RuntimeSettings settings = new RuntimeSettings(llm, genvn, image, null, file,
                null, null, null, null, null);

        RuntimeSettings.View view = settings.update(Map.of(
                "llm.model", "gpt-4o",
                "llm.force-mock", true,
                "genvn.speculation.enabled", false,
                "image.enabled", true,
                "server.port", 9090
        ));
        assertEquals("gpt-4o", llm.getModel());
        assertTrue(llm.isForceMock());
        assertFalse(genvn.getSpeculation().isEnabled());
        assertTrue(image.isEnabled());
        assertTrue(view.restartPending().contains("server.port"));
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("model: gpt-4o"));
        assertTrue(yaml.contains("port: 9090"));
        assertFalse(yaml.contains("sk-"));
        for (RuntimeSettings.Field field : settings.snapshot().fields()) {
            if (field.kind() == RuntimeSettings.Kind.SECRET) {
                assertNull(field.value(), "secrets must not be returned");
            }
        }
    }
}
