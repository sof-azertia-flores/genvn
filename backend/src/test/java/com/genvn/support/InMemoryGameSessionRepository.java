package com.genvn.support;

import com.genvn.game.GameSession;
import com.genvn.persistence.GameSessionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Proves the repository abstraction holds: the engine never touches the filesystem in tests. */
public class InMemoryGameSessionRepository implements GameSessionRepository {

    private final Map<String, GameSession> store = new ConcurrentHashMap<>();

    @Override
    public void save(GameSession session) {
        session.touch();
        store.put(session.id, session);
    }

    @Override
    public Optional<GameSession> find(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<SessionSummary> list() {
        List<SessionSummary> out = new ArrayList<>();
        store.values().forEach(s -> out.add(new SessionSummary(s.id, s.title, s.createdAt, s.updatedAt,
                s.state.player.name, s.state.storyProgress.scenesPlayed, s.finished)));
        return out;
    }

    @Override
    public boolean delete(String id) {
        return store.remove(id) != null;
    }
}
