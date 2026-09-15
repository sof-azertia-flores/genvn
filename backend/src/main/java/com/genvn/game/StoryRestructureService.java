package com.genvn.game;

import com.genvn.api.CreationQueueFullException;
import com.genvn.api.Dtos;
import com.genvn.api.NotFoundException;
import com.genvn.api.RestructureInProgressException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, process-local restructure jobs, shaped like {@link SessionCreationService}: two model
 * calls take far too long for one request, so the player watches a progress bar instead.
 *
 * Two things differ from story creation. A restructure belongs to a save, so only ONE may be in
 * flight per save -- a second is refused rather than queued, because the first is already
 * rewriting the story the second would be planned against. And the player's instruction is never
 * part of a job's identity or its view: the fingerprint is over the save and the anchor only, so
 * the text exists in memory for the length of the call and nowhere else.
 */
@Service
public class StoryRestructureService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(StoryRestructureService.class);
    private static final int MAX_LOGS = 80;

    private final SessionService sessions;
    private final ThreadPoolExecutor executor;
    private final Clock clock;
    private final Duration retention;
    private final int maxRecords;
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    @Autowired
    public StoryRestructureService(SessionService sessions) {
        this(sessions, Clock.systemUTC(), Duration.ofHours(1), 2, 8, 128);
    }

    /** Smaller bounds and a controlled clock allow deterministic queue/expiry tests. */
    StoryRestructureService(SessionService sessions, Clock clock, Duration retention,
                            int workers, int queueCapacity, int maxRecords) {
        this.sessions = sessions;
        this.clock = clock;
        this.retention = retention;
        this.maxRecords = maxRecords;
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), work -> {
                    Thread thread = new Thread(work, "genvn-story-restructure");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * @param idempotencyKey a UUID the caller retained before POSTing, so a lost response is
     *                       recoverable by GET alone and a replay never rewrites twice
     */
    public Dtos.CreationJobView submit(String sessionId, String nodeId, String instruction,
                                       String expectedSceneId, int expectedStateVersion, String idempotencyKey) {
        String id = idempotencyKey == null ? UUID.randomUUID().toString() : validKey(idempotencyKey);
        synchronized (jobs) {
            prune();
            Job existing = jobs.get(id);
            if (existing != null) {
                if (!existing.matches(sessionId, nodeId, expectedStateVersion)) {
                    throw new IllegalArgumentException("这个重塑编号已经用在另一段剧情上，请为新的重塑使用新的编号。");
                }
                return existing.view();
            }
            for (Job job : jobs.values()) {
                if (!job.terminal() && sessionId.equals(job.sessionId)) throw new RestructureInProgressException();
            }
            if (jobs.size() >= maxRecords) {
                Iterator<Job> iterator = jobs.values().iterator();
                while (iterator.hasNext()) {
                    if (iterator.next().terminal()) { iterator.remove(); break; }
                }
            }
            if (jobs.size() >= maxRecords || executor.isShutdown()) throw new CreationQueueFullException();
            Job job = new Job(id, sessionId, nodeId, expectedStateVersion);
            job.addLog("重塑请求已接收，正在等待改写任务开始。");
            jobs.put(job.id, job);
            try {
                executor.execute(() -> run(job, instruction, expectedSceneId));
            } catch (RejectedExecutionException full) {
                jobs.remove(job.id);
                throw new CreationQueueFullException();
            }
            return job.view();
        }
    }

    public Dtos.CreationJobView require(String id) {
        synchronized (jobs) {
            prune();
            Job job = jobs.get(id);
            if (job == null) throw new NotFoundException("重塑任务已过期或服务已重启，请重新打开这个存档看看。");
            return job.view();
        }
    }

    private static String validKey(String key) {
        try {
            // UUID.fromString also accepts abbreviated forms; require the full canonical shape.
            if (key.length() == 36 && UUID.fromString(key).toString().equalsIgnoreCase(key)) return key;
        } catch (IllegalArgumentException ignored) { }
        throw new IllegalArgumentException("重塑请求编号必须是有效的 UUID。");
    }

    private void run(Job job, String instruction, String expectedSceneId) {
        synchronized (job) { job.status = "RUNNING"; }
        try {
            sessions.restructure(job.sessionId, job.nodeId, instruction, expectedSceneId,
                    job.expectedStateVersion, job.id, job::report);
            synchronized (job) {
                job.progress = 100;
                job.stage = "剧情已重塑";
                job.status = "READY";
                job.completedAt = clock.instant();
            }
        } catch (Throwable failure) {
            // Provider exceptions can carry prompt fragments -- which here would include the
            // player's own instruction -- or credentials. Nothing from them reaches the view.
            synchronized (job) {
                job.status = "FAILED";
                job.error = message(failure);
                job.addLog(job.error);
                job.completedAt = clock.instant();
            }
            log.warn("Restructure job {} failed ({})", job.id, failure.getClass().getSimpleName());
            if (failure instanceof Error error) throw error;
        }
    }

    /**
     * Fixed, player-facing text. The two cases worth telling apart are the ones the player can
     * act on: the save moved under them, or the model could not produce a usable framework.
     */
    private static String message(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return "重塑已中断，剧情没有改动，可以稍后重试。";
        if (failure instanceof com.genvn.api.SceneConflictException
                || failure instanceof com.genvn.api.ChoiceResolvingException) {
            return "这个存档在重塑过程中发生了变化，剧情没有改动。请重新打开这一幕再试。";
        }
        if (failure instanceof IllegalArgumentException || failure instanceof NotFoundException) {
            return "这一幕无法重塑，剧情没有改动。";
        }
        return "重塑未能完成，剧情没有改动。请检查文字模型设置后重试。";
    }

    /** Called while holding the registry lock; polling never schedules work. */
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
        private final String sessionId;
        private final String nodeId;
        private final int expectedStateVersion;
        private String status = "QUEUED";
        private String stage = "等待开始";
        private int progress;
        private int nextLogId = 1;
        private final List<Dtos.CreationLog> logs = new ArrayList<>();
        private String error;
        private Instant completedAt;

        private Job(String id, String sessionId, String nodeId, int expectedStateVersion) {
            this.id = id;
            this.sessionId = sessionId;
            this.nodeId = nodeId;
            this.expectedStateVersion = expectedStateVersion;
        }

        /** Identity is the save and the anchor, deliberately never the player's own words. */
        private synchronized boolean matches(String sessionId, String nodeId, int expectedStateVersion) {
            return Objects.equals(this.sessionId, sessionId) && Objects.equals(this.nodeId, nodeId)
                    && this.expectedStateVersion == expectedStateVersion;
        }

        private synchronized boolean terminal() { return completedAt != null; }

        private synchronized void report(String stage, int progress, String message) {
            this.stage = stage;
            // Only run() publishes 100, atomically with READY and a save that is already written.
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
