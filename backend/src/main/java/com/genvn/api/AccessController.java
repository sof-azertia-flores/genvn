package com.genvn.api;

import com.genvn.config.GenvnProperties;
import com.genvn.config.UiLanguage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only endpoint reachable without the key: says whether one is needed and whether the one
 * the browser sent is right, so the front end can show the key screen before anything else.
 */
@RestController
@RequestMapping("/api")
public class AccessController {

    private final AccessGate gate;
    private final GenvnProperties properties;

    public AccessController(AccessGate gate) {
        this(gate, null);
    }

    @Autowired
    public AccessController(AccessGate gate, GenvnProperties properties) {
        this.gate = gate;
        this.properties = properties;
    }

    @GetMapping("/access")
    public Dtos.AccessView access(@RequestHeader(value = AccessGate.HEADER, required = false) String presented) {
        boolean required = gate.required();
        String language = properties == null ? UiLanguage.ZH : properties.getLanguage();
        return new Dtos.AccessView(required, !required || gate.matches(presented), language);
    }
}
