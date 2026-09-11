package com.genvn.persistence;

import com.genvn.game.GameSession;

import java.util.List;
import java.util.Optional;

public interface GameSessionRepository {

    void save(GameSession session);

    Optional<GameSession> find(String id);

    List<SessionSummary> list();

    boolean delete(String id);

    /**
     * True when this save is still stored in an older on-disk layout, so re-saving it would
     * migrate it. Implementations without an on-disk layout have nothing to migrate.
     */
    default boolean hasLegacyLayout(String id) {
        return false;
    }

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
