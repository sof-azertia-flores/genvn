package com.genvn.api;

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

    public AccessController(AccessGate gate) {
        this.gate = gate;
    }

    @GetMapping("/access")
    public Dtos.AccessView access(@RequestHeader(value = AccessGate.HEADER, required = false) String presented) {
        boolean required = gate.required();
        return new Dtos.AccessView(required, !required || gate.matches(presented));
    }
}
