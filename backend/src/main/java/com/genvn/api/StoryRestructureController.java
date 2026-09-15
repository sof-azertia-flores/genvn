package com.genvn.api;

import com.genvn.game.StoryRestructureService;
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

/**
 * Rewriting the story from one scene onward takes two model calls, so it is a job the client
 * watches, exactly like story creation. The POST hangs off the save (it mutates one) while the
 * poll hangs off the job, which outlives any one scene.
 */
@RestController
@RequestMapping("/api")
public class StoryRestructureController {

    private final StoryRestructureService restructures;

    public StoryRestructureController(StoryRestructureService restructures) {
        this.restructures = restructures;
    }

    @PostMapping("/sessions/{id}/nodes/{nodeId}/restructure")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Dtos.CreationJobView start(@PathVariable String id,
                                      @PathVariable String nodeId,
                                      @Valid @RequestBody Dtos.RestructureRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return restructures.submit(id, nodeId, request.instruction(),
                request.expectedSceneId(), request.expectedStateVersion(), key);
    }

    @GetMapping("/session-restructures/{jobId}")
    public Dtos.CreationJobView get(@PathVariable String jobId) {
        return restructures.require(jobId);
    }
}
