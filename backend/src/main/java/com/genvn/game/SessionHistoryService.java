package com.genvn.game;

import com.genvn.api.SessionNotFoundException;
import com.genvn.narrative.Block;
import com.genvn.persistence.GameSessionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads only committed history and never moves the UI's reading boundary forward. */
@Service
public class SessionHistoryService {
    private final GameSessionRepository repository;

    public SessionHistoryService(GameSessionRepository repository) {
        this.repository = repository;
    }

    public record Entry(String sceneId, String beatId, String choiceText, String rollSummary,
                        List<Block> blocks, String at) {}

    public record Page(String sessionId, List<Entry> entries, String nextBeforeSceneId) {}

    public Page read(String sessionId, String throughSceneId, int throughBlockIndex,
                     String beforeSceneId, int limit) {
        if (throughSceneId == null || throughSceneId.isBlank()) {
            throw new IllegalArgumentException("请提供当前阅读场景");
        }
        if (throughBlockIndex < -1) throw new IllegalArgumentException("已读句子位置不能小于 -1");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("每页场景数量应为 1 至 50");
        GameSession session = repository.find(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("游戏不存在或已删除"));
        synchronized (session) {
            if (session.deleted) throw new SessionNotFoundException("游戏不存在或已删除");
            List<GameSession.HistoryEntry> history = session.history;
            int through = indexOf(history, throughSceneId);
            if (through < 0) throw new IllegalArgumentException("阅读场景不属于这次游戏的已发生情节");
            int end = through + 1;
            if (beforeSceneId != null && !beforeSceneId.isBlank()) {
                end = indexOf(history, beforeSceneId);
                if (end < 0 || end > through) throw new IllegalArgumentException("历史分页位置不在已读范围内");
            }
            int start = Math.max(0, end - limit);
            List<Entry> entries = new ArrayList<>();
            for (int i = start; i < end; i++) {
                GameSession.HistoryEntry entry = history.get(i);
                List<Block> blocks = entry.blocks == null ? List.of() : entry.blocks;
                int count = i == through
                        ? (int) Math.min(blocks.size(), (long) throughBlockIndex + 1) : blocks.size();
                entries.add(new Entry(entry.sceneId, entry.beatId, entry.choiceText, entry.rollSummary,
                        List.copyOf(blocks.subList(0, count)), entry.at));
            }
            return new Page(session.id, List.copyOf(entries), start > 0 ? history.get(start).sceneId : null);
        }
    }

    private static int indexOf(List<GameSession.HistoryEntry> history, String sceneId) {
        for (int i = 0; i < history.size(); i++) {
            if (Objects.equals(history.get(i).sceneId, sceneId)) return i;
        }
        return -1;
    }
}
