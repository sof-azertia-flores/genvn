package com.genvn.api;

import com.genvn.game.SessionCreationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Async companion to the original, synchronous POST /api/sessions API. */
@RestController
@RequestMapping("/api/session-creations")
public class SessionCreationController {
    private final SessionCreationService creations;

    public SessionCreationController(SessionCreationService creations) {
        this.creations = creations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Dtos.CreationJobView create(@Valid @RequestBody Dtos.CreateSessionRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var player = SessionController.toPlayer(request.player());
        return creations.submit(request.storyOutline(), player, key, request.artStyle());
    }

    @GetMapping("/{id}")
    public Dtos.CreationJobView get(@PathVariable String id) {
        return creations.require(id);
    }
}
