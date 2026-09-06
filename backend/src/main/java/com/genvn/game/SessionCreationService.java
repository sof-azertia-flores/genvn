package com.genvn.game;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.genvn.api.CreationQueueFullException;
import com.genvn.api.Dtos;
import com.genvn.api.NotFoundException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, process-local jobs. Only fixed milestone messages are retained, never story input. */
@Service
public class SessionCreationService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SessionCreationService.class);
    private static final int MAX_LOGS = 80;
    private static final ObjectMapper FINGERPRINT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final SessionService sessions;
    private final ThreadPoolExecutor executor;
    private final Clock clock;
    private final Duration retention;
    private final int maxRecords;
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    @Autowired
    public SessionCreationService(SessionService sessions) {
        this(sessions, Clock.systemUTC(), Duration.ofHours(1), 2, 8, 128);
    }

    /** Smaller bounds and a controlled clock allow deterministic queue/expiry tests. */
    SessionCreationService(SessionService sessions, Clock clock, Duration retention,
                           int workers, int queueCapacity, int maxRecords) {
        this.sessions = sessions;
        this.clock = clock;
        this.retention = retention;
        this.maxRecords = maxRecords;
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), work -> {
                    Thread thread = new Thread(work, "genvn-story-creation");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public Dtos.CreationJobView submit(String outline, PlayerCharacter player) {
        return submit(outline, player, null);
    }

    /** A caller can retain this UUID before POST, and recover a lost response by GET alone. */
    public Dtos.CreationJobView submit(String outline, PlayerCharacter player, String idempotencyKey) {
        return submit(outline, player, idempotencyKey, "");
    }

    public Dtos.CreationJobView submit(String outline, PlayerCharacter player, String idempotencyKey, String artStyle) {
        String id = idempotencyKey == null ? UUID.randomUUID().toString() : validKey(idempotencyKey);
        String fingerprint = fingerprint(outline, player, artStyle);
        synchronized (jobs) {
            prune();
            Job existing = jobs.get(id);
            if (existing != null) {
                if (!existing.fingerprint.equals(fingerprint)) {
                    throw new IllegalArgumentException("此开局编号已用于另一份故事或角色，请为新的故事使用新的请求编号。");
                }
                return existing.view();
            }
            if (jobs.size() >= maxRecords) {
                Iterator<Job> iterator = jobs.values().iterator();
                while (iterator.hasNext()) {
                    if (iterator.next().terminal()) { iterator.remove(); break; }
                }
            }
            if (jobs.size() >= maxRecords || executor.isShutdown()) throw new CreationQueueFullException();
            Job job = new Job(id, fingerprint);
            job.addLog("故事请求已接收，正在等待开局任务开始。");
            jobs.put(job.id, job);
            try {
                executor.execute(() -> run(job, outline, player, artStyle));
            } catch (RejectedExecutionException full) {
                jobs.remove(job.id);
                throw new CreationQueueFullException();
            }
            return job.view();
        }
    }

    private static String validKey(String key) {
        try {
            // UUID.fromString also accepts abbreviated forms; require the full canonical shape.
            if (key.length() == 36 && UUID.fromString(key).toString().equalsIgnoreCase(key)) return key;
        } catch (IllegalArgumentException ignored) { }
        throw new IllegalArgumentException("开局请求编号必须是有效的 UUID。");
    }

    private static String fingerprint(String outline, PlayerCharacter player, String artStyle) {
        try {
            byte[] payload = FINGERPRINT_MAPPER.writeValueAsBytes(new FingerprintInput(outline, player, artStyle == null ? "" : artStyle.trim()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("无法识别这份故事或角色，请检查输入后重试。");
        }
    }

    private record FingerprintInput(String outline, PlayerCharacter player, String artStyle) {}

    public Dtos.CreationJobView require(String id) {
        synchronized (jobs) {
            prune();
            Job job = jobs.get(id);
            if (job == null) throw new NotFoundException("开局任务已过期或服务已重启，请查看存档列表；若没有新存档，可重新开始。");
            return job.view();
        }
    }

    private void run(Job job, String outline, PlayerCharacter player, String artStyle) {
        synchronized (job) { job.status = "RUNNING"; }
        try {
            GameSession session = artStyle == null || artStyle.isBlank()
                    ? sessions.create(outline, player, job::report)
                    : sessions.create(outline, player, artStyle, job::report);
            synchronized (job) {
                job.sessionId = session.id;
                job.progress = 100;
                job.stage = "故事已就绪";
                job.status = "READY";
                job.completedAt = clock.instant();
            }
        } catch (Throwable failure) {
            // Provider exceptions can contain request/prompt fragments or credentials. They
            // must never be returned in this player-facing log, even for a local deployment.
            synchronized (job) {
                job.status = "FAILED";
                job.error = Thread.currentThread().isInterrupted()
                        ? "故事准备已中断，请稍后重试。"
                        : "故事准备未能完成，请检查文字模型和本地保存设置后重试。";
                job.addLog(job.error);
                job.completedAt = clock.instant();
            }
            log.warn("Story creation job {} failed ({})", job.id, failure.getClass().getSimpleName());
            if (failure instanceof Error error) throw error;
        }
    }

    /** Called while holding the registry lock; polling never schedules generation. */
    private void prune() {
        Instant oldest = clock.instant().minus(retention);
        jobs.values().removeIf(job -> {
            synchronized (job) {
                return job.completedAt != null && !job.completedAt.isAfter(oldest);
            }
        });
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private final class Job {
        private final String id;
        /** In-memory one-way digest only; never retained in logs or API responses. */
        private final String fingerprint;
        private String status = "QUEUED";
        private String stage = "等待开始";
        private int progress;
        private int nextLogId = 1;
        private final List<Dtos.CreationLog> logs = new ArrayList<>();
        private String sessionId;
        private String error;
        private Instant completedAt;

        private Job(String id, String fingerprint) { this.id = id; this.fingerprint = fingerprint; }

        private synchronized boolean terminal() { return completedAt != null; }

        private synchronized void report(String stage, int progress, String message) {
            this.stage = stage;
            // Only run() publishes 100 atomically with READY and a usable session id.
            this.progress = Math.max(this.progress, Math.min(99, Math.max(0, progress)));
            addLog(message);
        }

        private synchronized void addLog(String message) {
            logs.add(new Dtos.CreationLog(nextLogId++, clock.instant().toString(), message));
            if (logs.size() > MAX_LOGS) logs.removeFirst();
        }

        private synchronized Dtos.CreationJobView view() {
            return new Dtos.CreationJobView(id, status, stage, progress, List.copyOf(logs), sessionId, error);
        }
    }
}
