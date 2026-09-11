package com.genvn.api;

import com.genvn.config.RuntimeSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** In-app editor for backend/config/application.yml. Secrets are never returned. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final RuntimeSettings settings;

    public SettingsController(RuntimeSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public RuntimeSettings.View get() {
        return settings.snapshot();
    }

    @PutMapping
    public RuntimeSettings.View put(@RequestBody Map<String, Object> body) {
        Object values = body == null ? null : body.get("values");
        if (!(values instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Request must include a 'values' object.");
        }
        Map<String, Object> update = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) continue;
            update.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return settings.update(update);
    }
}
