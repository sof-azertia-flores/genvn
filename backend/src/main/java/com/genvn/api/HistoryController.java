package com.genvn.api;

import com.genvn.game.SessionHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class HistoryController {
    private final SessionHistoryService history;

    public HistoryController(SessionHistoryService history) {
        this.history = history;
    }

    @GetMapping("/{id}/history")
    public SessionHistoryService.Page history(@PathVariable String id,
            @RequestParam(defaultValue = "") String throughSceneId,
            @RequestParam(defaultValue = "-1") int throughBlockIndex,
            @RequestParam(required = false) String beforeSceneId,
            @RequestParam(defaultValue = "20") int limit) {
        return history.read(id, throughSceneId, throughBlockIndex, beforeSceneId, limit);
    }
}
