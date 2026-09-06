package com.genvn.persistence;

import com.genvn.game.GameSession;

import java.util.List;
import java.util.Optional;

public interface GameSessionRepository {

    void save(GameSession session);

    Optional<GameSession> find(String id);

    List<SessionSummary> list();

    boolean delete(String id);

    record SessionSummary(
            String id,
            String title,
            String createdAt,
            String updatedAt,
            String playerName,
            int scenesPlayed,
            boolean finished
    ) {}
}
