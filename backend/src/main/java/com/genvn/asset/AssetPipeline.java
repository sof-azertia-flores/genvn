package com.genvn.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.NotFoundException;
import com.genvn.config.ImageProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The image scheduler. Its own workers, its own queue, its own budget -- a slow picture can
 * never occupy a text worker, and nothing here ever touches GameState: a finished image only
 * changes the manifest, which has its own version counter.
 *
 * Rules it enforces:
 *  - one request per asset id, ever: every beat and branch that wants the same picture
 *    subscribes to the same task;
 *  - bounded concurrency and a bounded queue;
 *  - a per-arc attempt budget that counts retries and edits, persisted so a restart cannot
 *    reset it;
 *  - bounded backoff on retryable failures, no retry at all on non-retryable ones;
 *  - priority: what a committed scene needs now, then the coming beat, then the rest -- and a
 *    queued task can be moved forward when the story turns toward it;
 *  - late results for a forgotten session are dropped, never written.
 */
@Service
public class AssetPipeline implements AssetLookup {

    private static final Logger log = LoggerFactory.getLogger(AssetPipeline.class);
    private static final long DEPENDENCY_POLL_MILLIS = 500;
    private static final int MAX_DEPENDENCY_WAITS = 240; // 2 minutes of polling before giving up on the reference
    private static final int IDLE_RESERVED_ATTEMPTS = 4;

    private static final class Session {
        final AssetManifest manifest;
        final Object lock = new Object();
        boolean requeuedAfterLoad = false;
        String persistenceFailure;

        Session(AssetManifest manifest) {
            this.manifest = manifest;
        }
    }

    record Task(String sessionId, String assetId, int priority, long seq) implements Comparable<Task> {
        String key() {
            return sessionId + "/" + assetId;
        }

        @Override
        public int compareTo(Task o) {
            int c = Integer.compare(priority, o.priority);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    private final ImageAssetProvider provider;
    private final AssetStore store;
    private final ImageProperties props;
    private final ObjectMapper mapper;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Set<String> forgotten = ConcurrentHashMap.newKeySet();
    private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
    private final Map<String, Task> queued = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<AssetRecord>> futures = new ConcurrentHashMap<>();
    private final Map<String, Integer> dependencyWaits = new ConcurrentHashMap<>();
    /** Sessions whose manifest could not be read, with the reason; they play without pictures. */
    private final Map<String, String> unreadable = new ConcurrentHashMap<>();
    private final List<Thread> workers = new ArrayList<>();
    private final ScheduledExecutorService delayed;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger highWater = new AtomicInteger();
    private final Object idleSchedulingLock = new Object();
    private final AtomicLong seq = new AtomicLong();
    private volatile boolean running = true;

    public AssetPipeline(ImageAssetProvider provider, AssetStore store, ImageProperties props, ObjectMapper mapper) {
        this.provider = provider;
        this.store = store;
        this.props = props;
        this.mapper = mapper;
        this.delayed = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "genvn-image-backoff");
            t.setDaemon(true);
            return t;
        });
        syncWorkers();
    }

    /** Grow or shrink the worker pool to match {@code image.concurrency}. Extra workers exit on their next idle poll. */
    public void syncWorkers() {
        synchronized (workers) {
            workers.removeIf(t -> !t.isAlive());
            int want = Math.max(1, props.getConcurrency());
            int have = (int) workers.stream().filter(Thread::isAlive).count();
            for (int i = have; i < want; i++) {
                Thread t = new Thread(this::workerLoop, "genvn-image-" + (workers.size() + 1));
                t.setDaemon(true);
                t.start();
                workers.add(t);
            }
        }
    }

    // ------------------------------------------------------------------ planning

    /** Merge a plan into the session and auto-queue the first batch, within budget. */
    public int adopt(String sessionId, VisualPlanner.Plan plan) {
        if (forgotten.contains(sessionId)) return 0;
        Session s = session(sessionId);
        int queuedNow = 0;
        synchronized (s.lock) {
            if (!live(s)) return 0;
            if (s.persistenceFailure != null && !persistLocked(s)) return 0;
            AssetManifest m = s.manifest;
            if (m.style == null || m.style.isBlank()) {
                m.style = plan.style();
                m.styleKey = plan.styleKey();
            }
            for (AssetSpec spec : plan.specs()) {
                AssetRecord existing = m.records.get(spec.assetId());
                if (existing == null) {
                    m.records.put(spec.assetId(), new AssetRecord(spec));
                } else {
                    refreshPromptLocked(existing, spec);
                }
            }
            Set<String> plannedNow = new java.util.HashSet<>();
            for (AssetSpec spec : plan.specs()) plannedNow.add(spec.assetId());
            List<AssetRecord> candidates = m.records.values().stream()
                    .filter(r -> r.status == AssetStatus.PLANNED && !r.spec.idlePreparation())
                    .sorted(Comparator.<AssetRecord>comparingInt(r -> plannedNow.contains(r.spec.assetId()) ? 0 : 1)
                            .thenComparingInt(r -> r.spec.priority()))
                    .toList();
            for (AssetRecord r : candidates) {
                if (m.budget.firstBatchQueued >= props.getFirstBatchBudget()) break;
                if (enqueueLocked(s, r, r.spec.priority())) {
                    m.budget.firstBatchQueued++;
                    queuedNow++;
                }
            }
            m.touch();
            persistLocked(s);
        }
        log.info("Assets {}: plan has {} picture(s); {} queued in the first batch ({} budget), provider {}",
                sessionId, plan.specs().size(), queuedNow, props.getFirstBatchBudget(), provider.describe());
        return queuedNow;
    }

    /** Add pictures a committed scene asked for and queue them now (arc budget permitting). */
    public int adoptSpecs(String sessionId, List<AssetSpec> specs) {
        if (forgotten.contains(sessionId) || specs.isEmpty()) return 0;
        Session s = session(sessionId);
        int queuedNow = 0;
        synchronized (s.lock) {
            if (!live(s)) return 0;
            if (s.persistenceFailure != null && !persistLocked(s)) return 0;
            for (AssetSpec spec : specs) {
                AssetRecord existing = s.manifest.records.get(spec.assetId());
                if (existing != null && appearanceDiverged(existing, spec)) {
                    dropRecordLocked(s, spec.assetId());
                }
                AssetRecord r = s.manifest.records.computeIfAbsent(spec.assetId(), id -> new AssetRecord(spec));
                if (r.spec.idlePreparation() && !spec.idlePreparation()) {
                    // The appearance is now assigned to a canonically introduced person. Keep the
                    // original image prompt and file; a name/role is not permission to redraw them.
                    r.spec = r.spec.assignedTo(spec.subjectName(), spec.beatId(), spec.priority());
                    if (r.status == AssetStatus.QUEUED) reprioritize(sessionId, spec.assetId(), spec.priority());
                }
                refreshPromptLocked(r, spec);
                if (r.status == AssetStatus.PLANNED || r.status == AssetStatus.MISSING) {
                    if (enqueueLocked(s, r, spec.priority())) queuedNow++;
                }
            }
            s.manifest.touch();
            persistLocked(s);
        }
        return queuedNow;
    }

    private static boolean promptRefreshable(AssetRecord r) {
        return r.status == AssetStatus.PLANNED || r.status == AssetStatus.FAILED
                || r.status == AssetStatus.PAUSED || r.status == AssetStatus.MISSING;
    }

    /**
     * The planner's wording improved since this picture was planned or failed. A READY file keeps
     * the prompt that produced it; anything else takes the new text so a later automatic or
     * manual retry does not resend the old one. Returns true when the record changed.
     */
    private static boolean refreshPromptLocked(AssetRecord r, AssetSpec spec) {
        if (!promptRefreshable(r) || r.spec.kind() != spec.kind() || !r.spec.styleKey().equals(spec.styleKey())
                || r.spec.prompt().equals(spec.prompt())) return false;
        r.spec = r.spec.withPrompt(spec.prompt());
        return true;
    }

    /** Update wording only; queues nothing and spends nothing. Used before a manual retry. */
    public int refreshPrompts(String sessionId, List<AssetSpec> specs) {
        if (forgotten.contains(sessionId) || specs.isEmpty()) return 0;
        Session s = sessions.get(sessionId);
        if (s == null) return 0;
        int changed = 0;
        synchronized (s.lock) {
            if (!live(s)) return 0;
            for (AssetSpec spec : specs) {
                AssetRecord r = s.manifest.get(spec.assetId());
                if (r != null && refreshPromptLocked(r, spec)) changed++;
            }
            if (changed > 0) {
                s.manifest.touch();
                persistLocked(s);
            }
        }
        return changed;
    }

    /** Make sure these are on their way; urgent ones jump the queue. Idempotent. */
    public void ensureQueued(String sessionId, Collection<String> assetIds, boolean urgent) {
        if (forgotten.contains(sessionId) || assetIds.isEmpty()) return;
        Session s = session(sessionId);
        boolean changed = false;
        synchronized (s.lock) {
            if (!live(s)) return;
            if (s.persistenceFailure != null && !persistLocked(s)) return;
            for (String id : assetIds) {
                AssetRecord r = s.manifest.get(id);
                if (r == null) continue;
                int priority = urgent ? 0 : r.spec.priority();
                switch (r.status) {
                    case PLANNED, MISSING -> changed |= enqueueLocked(s, r, priority);
                    case PAUSED -> {
                        if (budgetAvailableLocked(s)) {
                            changed |= enqueueLocked(s, r, priority);
                        }
                    }
                    case QUEUED -> {
                        if (urgent) reprioritize(sessionId, id, 0);
                    }
                    default -> {
                        // READY, GENERATING, FAILED: nothing to do
                    }
                }
            }
            if (changed) {
                s.manifest.touch();
                persistLocked(s);
            }
        }
    }

    /**
     * Explicit user retry grants one call, not a fresh automatic retry cycle. All historical
     * attempt counters remain cumulative and every call still passes the durable budget gate.
     * Repeated clicks while queued/generating, or on a READY picture, cost nothing.
     */
    public Map<String, Object> manualRetry(String sessionId, String assetId) {
        if (!AssetStore.safeSessionId(sessionId) || !AssetStore.safeAssetId(assetId)
                || forgotten.contains(sessionId) || snapshot(sessionId).isEmpty()) {
            throw new NotFoundException("图片不存在或游戏已删除");
        }
        Session s = sessions.get(sessionId);
        if (s == null) throw new NotFoundException("图片不存在或游戏已删除");
        synchronized (s.lock) {
            if (!live(s)) throw new NotFoundException("图片不存在或游戏已删除");
            AssetRecord r = s.manifest.get(assetId);
            if (r == null) throw new NotFoundException("图片不存在");
            if (r.status == AssetStatus.READY || r.isPendingWork()) return status(sessionId);
            if (r.status != AssetStatus.FAILED && r.status != AssetStatus.PAUSED
                    && r.status != AssetStatus.MISSING) {
                throw new IllegalArgumentException("这张图片尚未生成失败，请等待生成队列");
            }
            if (s.persistenceFailure != null && !persistLocked(s)) return status(sessionId);
            // Setting this before enqueue ensures even an immediate failure cannot trigger a
            // second automatic provider call. It also survives a process restart mid-request.
            r.manualAttemptLimit = r.attempts + 1;
            dependencyWaits.remove(sessionId + "/" + assetId);
            enqueueLocked(s, r, 0);
            s.manifest.touch();
            persistLocked(s);
            return status(sessionId);
        }
    }

    /** A committed scene referenced these pictures: record when they were first needed. */
    public void markNeeded(String sessionId, Collection<String> assetIds, String sceneId) {
        if (forgotten.contains(sessionId) || assetIds.isEmpty()) return;
        Session s = session(sessionId);
        synchronized (s.lock) {
            if (!live(s)) return;
            boolean changed = false;
            for (String id : assetIds) {
                AssetRecord r = s.manifest.get(id);
                if (r == null) continue;
                if (r.firstNeededAt == null) r.firstNeededAt = Instant.now().toString();
                r.reuseCount++;
                changed = true;
            }
            if (changed) {
                s.manifest.touch();
                persistLocked(s);
            }
        }
    }

    /** A new arc: reset the per-arc budget and let budget-paused pictures be planned again. */
    public void beginArc(String sessionId, int arcNumber) {
        if (forgotten.contains(sessionId)) return;
        Session s = session(sessionId);
        synchronized (s.lock) {
            if (!live(s)) return;
            AssetManifest.Budget b = s.manifest.budget;
            if (s.persistenceFailure != null && !persistLocked(s)) return;
            if (arcNumber <= b.arcNumber) return; // Repeated/late arc notifications cannot refill a budget.
            b.arcNumber = arcNumber;
            b.arcAttempts = 0;
            b.firstBatchQueued = 0;
            b.paused = false;
            b.pauseReason = null;
            for (AssetRecord r : s.manifest.records.values()) {
                if (r.status == AssetStatus.PAUSED) r.status = AssetStatus.PLANNED;
            }
            s.manifest.touch();
            persistLocked(s);
        }
    }

    public void forget(String sessionId) {
        forgotten.add(sessionId);
        Session s = sessions.get(sessionId);
        if (s != null) {
            // Publication and deletion use the same lock. A save already in progress finishes
            // before removal; all later success/failure callbacks see the forgotten marker.
            synchronized (s.lock) {
                sessions.remove(sessionId, s);
                store.deleteSession(sessionId);
            }
        } else {
            store.deleteSession(sessionId);
        }
        queued.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(sessionId + "/")) {
                queue.remove(e.getValue());
                return true;
            }
            return false;
        });
        futures.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith(sessionId + "/")) return false;
            e.getValue().cancel(false);
            return true;
        });
        dependencyWaits.keySet().removeIf(key -> key.startsWith(sessionId + "/"));
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Optional<AssetManifest> snapshot(String sessionId) {
        if (sessionId == null || forgotten.contains(sessionId)) return Optional.empty();
        Session s = sessions.get(sessionId);
        if (s == null) {
            // A damaged manifest must not take the story down with it: prose generation and the
            // status endpoint see "no pictures" plus a reason, and nothing is silently rewritten.
            try {
                if (store.readManifest(sessionId).isEmpty()) return Optional.empty();
                s = session(sessionId);
            } catch (UncheckedIOException e) {
                if (unreadable.put(sessionId, e.getMessage()) == null) {
                    log.warn("Assets {}: manifest unreadable; playing without pictures until it is repaired or removed ({})",
                            sessionId, e.getCause() == null ? e.getMessage() : e.getCause().toString());
                }
                return Optional.empty();
            }
            unreadable.remove(sessionId);
        }
        synchronized (s.lock) {
            if (!live(s)) return Optional.empty();
            return Optional.of(mapper.convertValue(s.manifest, AssetManifest.class));
        }
    }

    public CompletableFuture<AssetRecord> subscribe(String sessionId, String assetId) {
        String key = sessionId + "/" + assetId;
        CompletableFuture<AssetRecord> future = futures.computeIfAbsent(key, k -> new CompletableFuture<>());
        if (forgotten.contains(sessionId)) {
            futures.remove(key, future);
            future.cancel(false);
        }
        return future;
    }

    public Map<String, Object> status(String sessionId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<AssetManifest> snap = snapshot(sessionId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AssetStatus st : AssetStatus.values()) counts.put(st.name().toLowerCase(), 0);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (snap.isPresent()) {
            AssetManifest m = snap.get();
            for (AssetRecord r : m.records.values()) {
                counts.merge(r.status.name().toLowerCase(), 1, Integer::sum);
                rows.add(row(r));
            }
            rows.sort(Comparator.comparingInt(x -> (int) x.get("priority")));
            out.put("version", m.version);
            out.put("style", m.style);
            Map<String, Object> budget = new LinkedHashMap<>();
            budget.put("arcNumber", m.budget.arcNumber);
            budget.put("arcAttempts", m.budget.arcAttempts);
            budget.put("arcBudget", props.getArcBudget());
            budget.put("attemptsTotal", m.budget.attemptsTotal);
            budget.put("firstBatchQueued", m.budget.firstBatchQueued);
            budget.put("firstBatchBudget", props.getFirstBatchBudget());
            budget.put("paused", m.budget.paused);
            budget.put("pauseReason", m.budget.pauseReason);
            out.put("budget", budget);
        } else {
            out.put("version", 0);
        }
        out.put("counts", counts);
        out.put("pending", counts.get("queued") + counts.get("generating"));
        out.put("assets", rows);
        out.put("provider", provider.describe());
        out.put("enabled", provider.isEnabled());
        out.put("manifestError", unreadable.get(sessionId));
        out.put("concurrency", props.getConcurrency());
        out.put("active", active.get());
        out.put("highWaterConcurrency", highWater.get());
        out.put("queueDepth", queue.size());
        out.put("queueCapacity", props.getQueueCapacity());
        return out;
    }

    private static Map<String, Object> row(AssetRecord r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("assetId", r.spec.assetId());
        row.put("kind", r.spec.kind().name());
        row.put("subjectId", r.spec.subjectId());
        row.put("subjectName", r.spec.subjectName());
        row.put("variant", r.spec.variant());
        row.put("status", r.status.name());
        row.put("fileName", r.fileName);
        row.put("width", r.width);
        row.put("height", r.height);
        row.put("beatId", r.spec.beatId());
        row.put("priority", r.spec.priority());
        row.put("dependsOn", r.spec.dependsOn());
        row.put("applicability", r.spec.applicability());
        row.put("attempts", r.attempts);
        row.put("failureReason", r.failureReason);
        row.put("generationVersion", r.generationVersion);
        row.put("plannedAt", r.plannedAt);
        row.put("queuedAt", r.queuedAt);
        row.put("startedAt", r.startedAt);
        row.put("readyAt", r.readyAt);
        row.put("firstNeededAt", r.firstNeededAt);
        row.put("reuseCount", r.reuseCount);
        Boolean inTime = null;
        if (r.readyAt != null && r.firstNeededAt != null) inTime = r.readyAt.compareTo(r.firstNeededAt) <= 0;
        row.put("readyBeforeNeeded", inTime);
        return row;
    }

    public int highWaterConcurrency() { return highWater.get(); }
    public int activeCount() { return active.get(); }
    public int queueDepth() { return queue.size(); }
    public boolean enabled() { return provider.isEnabled(); }
    public String providerDescription() { return provider.describe(); }

    /** Test/inspection helper: the READY file for an asset, validated. */
    public Optional<java.nio.file.Path> readyFile(String sessionId, String assetId) {
        Optional<AssetManifest> snap = snapshot(sessionId);
        if (snap.isEmpty()) return Optional.empty();
        AssetRecord r = snap.get().get(assetId);
        if (r == null || r.status != AssetStatus.READY || r.fileName == null) return Optional.empty();
        return store.imagePath(sessionId, r.fileName);
    }

    // ------------------------------------------------------------------ internals

    private Session session(String sessionId) {
        Session s = sessions.computeIfAbsent(sessionId, id -> {
            AssetManifest m = store.readManifest(id).orElseGet(() -> {
                AssetManifest fresh = new AssetManifest();
                fresh.sessionId = id;
                return fresh;
            });
            recover(id, m);
            return new Session(m);
        });
        synchronized (s.lock) {
            if (!live(s)) return s;
            if (!s.requeuedAfterLoad) {
                s.requeuedAfterLoad = true;
                List<AssetRecord> waiting = new ArrayList<>();
                boolean changed = false;
                for (AssetRecord r : s.manifest.records.values()) {
                    if (r.status == AssetStatus.QUEUED || r.status == AssetStatus.MISSING) {
                        r.status = AssetStatus.PLANNED; // enqueueLocked flips it back to QUEUED
                        if (!enqueueLocked(s, r, r.spec.priority()) && r.status == AssetStatus.PLANNED) {
                            r.status = AssetStatus.QUEUED;
                            if (!queued.containsKey(sessionId + "/" + r.spec.assetId())) waiting.add(r);
                        }
                        changed = true;
                    }
                }
                if (changed) {
                    s.manifest.touch();
                    if (persistLocked(s)) {
                        for (AssetRecord r : waiting) {
                            requeueLater(sessionId, r.spec.assetId(), r.spec.priority(), DEPENDENCY_POLL_MILLIS);
                        }
                    }
                }
            }
        }
        return s;
    }

    /** Restart policy: trust only files that still decode; never resend an unknown-outcome call unboundedly. */
    private void recover(String sessionId, AssetManifest m) {
        for (AssetRecord r : m.records.values()) {
            if (r.spec == null) continue;
            switch (r.status) {
                case READY -> {
                    if (r.fileName == null || !store.validate(sessionId, r.fileName)) {
                        r.status = AssetStatus.MISSING;
                        r.failureReason = "file missing or unreadable after restart";
                    }
                }
                case GENERATING -> {
                    // The process died mid-call. The attempt is already counted; retry only within bounds.
                    if (r.attempts < attemptLimit(r)) {
                        r.status = AssetStatus.QUEUED;
                        r.failureReason = "interrupted by restart; retrying";
                    } else {
                        r.status = AssetStatus.FAILED;
                        r.retryable = false;
                        r.failureReason = "interrupted by restart; attempts exhausted";
                    }
                }
                default -> {
                    // PLANNED / QUEUED / FAILED / PAUSED / MISSING carry over as they are
                }
            }
        }
    }

    private boolean budgetAvailableLocked(Session s) {
        return live(s) && s.persistenceFailure == null && provider.isEnabled() && !budgetExhaustedLocked(s);
    }

    /** image.arc-budget 0 means no per-arc cap; concurrency, dedup and bounded retries still apply. */
    private boolean budgetExhaustedLocked(Session s) {
        return props.isArcBudgetLimited() && s.manifest.budget.arcAttempts >= props.getArcBudget();
    }

    private int attemptLimit(AssetRecord record) {
        return record.manualAttemptLimit > 0 ? record.manualAttemptLimit : props.getMaxAttempts();
    }

    private String capabilityProblem(AssetSpec spec) {
        if (spec.kind().transparentSprite() && !provider.supportsTransparentBackground()) {
            return "当前图片模型不支持透明背景；请使用支持透明 PNG 的图片模型";
        }
        if (spec.dependsOn() != null && !provider.supportsEdit()) {
            return "当前图片模型不支持参考图编辑，无法生成一致的人物姿势或角色卡";
        }
        return null;
    }

    /** Caller holds s.lock. Returns true if a task was actually put on the queue. */
    private boolean enqueueLocked(Session s, AssetRecord r, int priority) {
        if (!live(s)) return false;
        if (s.persistenceFailure != null) {
            r.status = AssetStatus.PAUSED;
            r.failureReason = s.persistenceFailure;
            return false;
        }
        String key = s.manifest.sessionId + "/" + r.spec.assetId();
        if (queued.containsKey(key)) return false;
        if (!provider.isEnabled()) {
            r.status = AssetStatus.PAUSED;
            r.failureReason = "image provider disabled: " + provider.describe();
            return false;
        }
        if (props.outputFormatProblem() != null) {
            r.status = AssetStatus.PAUSED;
            r.failureReason = props.outputFormatProblem();
            return false;
        }
        String capability = capabilityProblem(r.spec);
        if (capability != null) {
            r.status = AssetStatus.PAUSED;
            r.failureReason = capability;
            return false;
        }
        if (budgetExhaustedLocked(s)) {
            r.status = AssetStatus.PAUSED;
            r.failureReason = "arc budget exhausted (" + s.manifest.budget.arcAttempts + "/" + props.getArcBudget() + ")";
            s.manifest.budget.paused = true;
            s.manifest.budget.pauseReason = r.failureReason;
            return false;
        }
        if (queue.size() >= props.getQueueCapacity()) {
            r.failureReason = "image queue full (" + props.getQueueCapacity() + "); will be queued when a scene needs it";
            log.info("Assets {}: queue full, {} stays planned", s.manifest.sessionId, r.spec.assetId());
            return false;
        }
        Task task = new Task(s.manifest.sessionId, r.spec.assetId(), priority, seq.incrementAndGet());
        queued.put(key, task);
        queue.add(task);
        r.status = AssetStatus.QUEUED;
        r.referenceUnavailable = false;
        r.failureReason = null;
        if (r.queuedAt == null) r.queuedAt = Instant.now().toString();
        futures.compute(key, (k, f) -> f == null || f.isDone() ? new CompletableFuture<>() : f);
        return true;
    }

    private void reprioritize(String sessionId, String assetId, int priority) {
        String key = sessionId + "/" + assetId;
        Task old = queued.get(key);
        if (old == null || old.priority() <= priority) return;
        if (queue.remove(old)) {
            Task fresh = new Task(sessionId, assetId, priority, seq.incrementAndGet());
            queued.put(key, fresh);
            queue.add(fresh);
        }
    }

    private boolean live(Session s) {
        String sid = s.manifest.sessionId;
        return !forgotten.contains(sid) && sessions.get(sid) == s;
    }

    /** A provider attempt may start only after its reservation was durably published. */
    private boolean persistLocked(Session s) {
        if (!live(s)) return false;
        String previousFailure = s.persistenceFailure;
        if (previousFailure != null) {
            if (previousFailure.equals(s.manifest.budget.pauseReason)) {
                s.manifest.budget.paused = false;
                s.manifest.budget.pauseReason = null;
            }
            for (AssetRecord r : s.manifest.records.values()) {
                if (r.status == AssetStatus.PAUSED && previousFailure.equals(r.failureReason)) {
                    r.status = AssetStatus.PLANNED;
                    r.failureReason = null;
                }
            }
        }
        try {
            store.writeManifest(s.manifest);
            s.persistenceFailure = null;
            return true;
        } catch (UncheckedIOException e) {
            s.persistenceFailure = "asset persistence failed; fix local storage before resuming image generation";
            s.manifest.budget.paused = true;
            s.manifest.budget.pauseReason = s.persistenceFailure;
            for (AssetRecord r : s.manifest.records.values()) {
                if (r.status == AssetStatus.QUEUED || r.status == AssetStatus.PLANNED) {
                    r.status = AssetStatus.PAUSED;
                    r.failureReason = s.persistenceFailure;
                    complete(s.manifest.sessionId + "/" + r.spec.assetId(), r);
                }
            }
            log.warn("Assets {}: generation paused because the budget could not be persisted: {}",
                    s.manifest.sessionId, e.toString());
            return false;
        }
    }

    private void complete(String key, AssetRecord r) {
        CompletableFuture<AssetRecord> f = futures.get(key);
        if (f != null) f.complete(r);
    }

    private void requeueLater(String sessionId, String assetId, int priority, long delayMillis) {
        if (!running || forgotten.contains(sessionId)) return;
        try {
            delayed.schedule(() -> {
                if (!running || forgotten.contains(sessionId)) return;
                Session s = sessions.get(sessionId);
                if (s == null) return;
                synchronized (s.lock) {
                    if (!live(s)) return;
                    AssetRecord r = s.manifest.get(assetId);
                    if (r == null || r.status != AssetStatus.QUEUED) return;
                    r.status = AssetStatus.PLANNED;
                    boolean enqueued = enqueueLocked(s, r, priority);
                    boolean waitForCapacity = !enqueued && r.status == AssetStatus.PLANNED
                            && !queued.containsKey(sessionId + "/" + assetId);
                    if (!enqueued && r.status == AssetStatus.PLANNED) {
                        // A full queue delays admission, not the provider retry itself. Keep this
                        // single retry pending and durable until a slot opens; no attempt is spent.
                        r.status = AssetStatus.QUEUED;
                    }
                    s.manifest.touch();
                    if (persistLocked(s) && waitForCapacity) {
                        requeueLater(sessionId, assetId, priority, DEPENDENCY_POLL_MILLIS);
                    }
                }
            }, Math.max(1, delayMillis), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Shutdown preserves QUEUED on disk for the next process, without sending a call.
            if (running) throw e;
        }
    }

    // ------------------------------------------------------------------ workers

    private void workerLoop() {
        while (running) {
            if (shouldRetireExtraWorker()) return;
            Task task;
            try {
                task = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (task == null) {
                scheduleIdlePreparation();
                continue;
            }
            queued.remove(task.key(), task);
            try {
                process(task);
            } catch (Throwable e) {
                // A worker is a long-lived resource: an Error from one picture (a decoder running
                // out of memory, say) must not silently retire it for the rest of the process.
                log.error("Assets {}: worker error on {}: {}", task.sessionId(), task.assetId(), e.toString());
            }
        }
    }

    private boolean shouldRetireExtraWorker() {
        int cap = Math.max(1, props.getConcurrency());
        synchronized (workers) {
            workers.removeIf(t -> !t.isAlive() && t != Thread.currentThread());
            if (workers.isEmpty() || Thread.currentThread() == workers.get(0)) return false;
            long alive = workers.stream().filter(t -> t.isAlive() || t == Thread.currentThread()).count();
            if (alive <= cap) return false;
            workers.remove(Thread.currentThread());
            return true;
        }
    }

    /**
     * Drop character pictures whose appearance no longer matches the restored story, so a later
     * route that reuses an NPC id does not keep showing the previous route's face.
     */
    public void dropDivergedCharacterArt(String sessionId, Map<String, String> appearanceBySubject) {
        if (forgotten.contains(sessionId) || appearanceBySubject == null) return;
        Session s = sessions.get(sessionId);
        if (s == null) return;
        synchronized (s.lock) {
            if (!live(s)) return;
            boolean changed = false;
            for (String assetId : List.copyOf(s.manifest.records.keySet())) {
                AssetRecord r = s.manifest.get(assetId);
                if (r == null || r.spec == null || r.spec.kind() == AssetKind.BACKGROUND) continue;
                String subject = r.spec.subjectId();
                if (com.genvn.game.PlayerCharacter.ID.equals(subject)) continue;
                String appearance = appearanceBySubject.get(subject);
                if (appearance == null) {
                    dropRecordLocked(s, assetId);
                    changed = true;
                    continue;
                }
                if (!appearance.isBlank() && r.spec.prompt() != null && !r.spec.prompt().contains(appearance)) {
                    dropRecordLocked(s, assetId);
                    changed = true;
                }
            }
            if (changed) {
                s.manifest.touch();
                persistLocked(s);
            }
        }
    }

    private static boolean appearanceDiverged(AssetRecord existing, AssetSpec spec) {
        if (existing.spec == null || spec == null) return false;
        if (existing.spec.kind() == AssetKind.BACKGROUND || spec.kind() == AssetKind.BACKGROUND) return false;
        // Spare assignment keeps the drawing; a name/role is not permission to redraw them.
        if (existing.spec.idlePreparation()) return false;
        if (existing.status != AssetStatus.READY && !existing.isPendingWork()) return false;
        String oldPrompt = existing.spec.prompt();
        String nextPrompt = spec.prompt();
        return oldPrompt != null && nextPrompt != null && !oldPrompt.equals(nextPrompt);
    }

    private void dropRecordLocked(Session s, String assetId) {
        AssetRecord r = s.manifest.records.remove(assetId);
        if (r == null) return;
        String key = s.manifest.sessionId + "/" + assetId;
        Task queuedTask = queued.remove(key);
        if (queuedTask != null) queue.remove(queuedTask);
        CompletableFuture<AssetRecord> future = futures.remove(key);
        if (future != null) future.cancel(false);
        dependencyWaits.remove(key);
        if (r.fileName != null) store.deleteFile(s.manifest.sessionId, r.fileName);
    }

    /** Fill genuinely spare workers with bounded visual-only reserves, keeping calls for play. */
    private void scheduleIdlePreparation() {
        synchronized (idleSchedulingLock) {
            if (!running || !queue.isEmpty() || !provider.isEnabled()) return;
            for (Session session : sessions.values()) {
                synchronized (session.lock) {
                    if (!queue.isEmpty()) return;
                    if (!budgetAvailableLocked(session)) continue;
                    long waiting = session.manifest.records.values().stream()
                            .filter(record -> record.status == AssetStatus.QUEUED).count();
                    // In-flight calls already count in arcAttempts; queued calls do not yet.
                    // Leave four unspent calls for immediate scenes and bounded recovery.
                    if (props.isArcBudgetLimited()
                            && props.getArcBudget() - session.manifest.budget.arcAttempts - waiting <= IDLE_RESERVED_ATTEMPTS) continue;
                    AssetRecord next = session.manifest.records.values().stream()
                            .filter(record -> record.status == AssetStatus.PLANNED && record.spec.idlePreparation())
                            .filter(record -> record.spec.dependsOn() == null
                                    || Optional.ofNullable(session.manifest.get(record.spec.dependsOn()))
                                            .map(base -> base.status == AssetStatus.READY).orElse(false))
                            .min(Comparator.comparingInt(record -> record.spec.priority())).orElse(null);
                    if (next != null && enqueueLocked(session, next, next.spec.priority())) {
                        session.manifest.touch();
                        persistLocked(session);
                        return;
                    }
                }
            }
        }
    }

    private void process(Task task) {
        String sid = task.sessionId();
        if (forgotten.contains(sid)) return;
        Session s = sessions.get(sid);
        if (s == null) return;
        String key = task.key();

        AssetSpec spec;
        synchronized (s.lock) {
            if (!live(s)) return;
            AssetRecord r = s.manifest.get(task.assetId());
            if (r == null || r.status != AssetStatus.QUEUED) return;
            spec = r.spec;
        }

        // Dependency: every variant/card is edited from its base sprite. Never block a worker on a
        // base that has not started; poll instead, so two variants cannot starve their base.
        byte[] reference = null;
        String referenceMime = null;
        if (spec.dependsOn() != null) {
            AssetRecord base;
            boolean referenceWasReady;
            synchronized (s.lock) {
                base = s.manifest.get(spec.dependsOn());
                referenceWasReady = base != null && base.status == AssetStatus.READY && base.fileName != null;
            }
            if (referenceWasReady) {
                try {
                    Optional<java.nio.file.Path> p = store.imagePath(sid, base.fileName);
                    if (p.isPresent()) {
                        reference = Files.readAllBytes(p.get());
                        referenceMime = AssetStore.mimeFor(base.fileName);
                    }
                } catch (IOException e) {
                    log.info("Assets {}: could not read reference {} ({}); pausing {}",
                            sid, base.fileName, e.getMessage(), spec.assetId());
                }
            } else if (base != null && (base.status == AssetStatus.QUEUED || base.status == AssetStatus.GENERATING
                    || base.status == AssetStatus.PLANNED || base.status == AssetStatus.MISSING)) {
                ensureQueued(sid, List.of(spec.dependsOn()), true);
                int waits = dependencyWaits.merge(key, 1, Integer::sum);
                int maxWaits = Math.max(MAX_DEPENDENCY_WAITS,
                        (props.getTimeoutSeconds() * props.getMaxAttempts() + 60) * 2);
                if (waits <= maxWaits) {
                    synchronized (s.lock) {
                        AssetRecord r = s.manifest.get(task.assetId());
                        if (r != null) r.failureReason = "等待基础透明立绘完成：" + spec.dependsOn();
                    }
                    requeueLater(sid, task.assetId(), task.priority(), DEPENDENCY_POLL_MILLIS);
                    return;
                }
            }
            if (reference == null) {
                pauseDependency(s, key, spec, "基础透明立绘尚不可用，角色卡与姿势不会脱离参考图单独生成", !referenceWasReady);
                dependencyWaits.remove(key);
                return;
            }
        }
        dependencyWaits.remove(key);

        // Budget gate and the transition to GENERATING, atomically with the attempt count.
        synchronized (s.lock) {
            if (!live(s)) return;
            AssetRecord r = s.manifest.get(task.assetId());
            if (r == null || r.status != AssetStatus.QUEUED) return;
            if (r.manualAttemptLimit > 0 && r.attempts >= r.manualAttemptLimit) {
                r.status = AssetStatus.FAILED;
                r.failureReason = "本次手动重新生成已尝试一次；如需继续请再次点击重新生成";
                s.manifest.touch();
                persistLocked(s);
                complete(key, r);
                return;
            }
            if (s.persistenceFailure != null) {
                r.status = AssetStatus.PAUSED;
                r.failureReason = s.persistenceFailure;
                complete(key, r);
                return;
            }
            if (!provider.isEnabled()) {
                r.status = AssetStatus.PAUSED;
                r.failureReason = "image provider disabled: " + provider.describe();
                s.manifest.touch();
                persistLocked(s);
                complete(key, r);
                return;
            }
            if (props.outputFormatProblem() != null) {
                r.status = AssetStatus.PAUSED;
                r.failureReason = props.outputFormatProblem();
                s.manifest.touch();
                persistLocked(s);
                complete(key, r);
                return;
            }
            if (budgetExhaustedLocked(s)) {
                r.status = AssetStatus.PAUSED;
                r.failureReason = "arc budget exhausted (" + s.manifest.budget.arcAttempts + "/" + props.getArcBudget() + ")";
                s.manifest.budget.paused = true;
                s.manifest.budget.pauseReason = r.failureReason;
                s.manifest.touch();
                persistLocked(s);
                complete(key, r);
                return;
            }
            if (r.spec.idlePreparation() && task.priority() >= AssetSpec.IDLE_PRIORITY && r.firstNeededAt == null
                    && props.isArcBudgetLimited()
                    && props.getArcBudget() - s.manifest.budget.arcAttempts <= IDLE_RESERVED_ATTEMPTS) {
                // Automatic retries and restored queued work pass this boundary too. A scheduling
                // check alone cannot protect the reserve once a spare image starts retrying.
                r.status = AssetStatus.PAUSED;
                r.failureReason = "预备绘图暂缓，为当前剧情与重试保留 " + IDLE_RESERVED_ATTEMPTS + " 次图片生成额度";
                s.manifest.touch();
                persistLocked(s);
                complete(key, r);
                return;
            }
            String capability = capabilityProblem(spec);
            if (capability != null) {
                r.status = AssetStatus.PAUSED;
                r.failureReason = capability;
                s.manifest.touch();
                persistLocked(s);
                complete(key, r);
                return;
            }
            s.manifest.budget.arcAttempts++;
            s.manifest.budget.attemptsTotal++;
            r.attempts++;
            r.status = AssetStatus.GENERATING;
            r.startedAt = Instant.now().toString();
            r.failureReason = null;
            s.manifest.touch();
            if (!persistLocked(s)) {
                // This call was never sent; it is safe to release only this reservation.
                s.manifest.budget.arcAttempts--;
                s.manifest.budget.attemptsTotal--;
                r.attempts--;
                r.status = AssetStatus.PAUSED;
                r.failureReason = s.persistenceFailure;
                complete(key, r);
                return;
            }
        }

        int now = active.incrementAndGet();
        highWater.accumulateAndGet(now, Math::max);
        long started = System.currentTimeMillis();
        try {
            int[] size = props.parseSize(spec.landscape() ? props.getBackgroundSize() : props.getPortraitSize(),
                    spec.landscape() ? new int[]{1536, 1024} : new int[]{1024, 1536});
            boolean transparent = spec.kind().transparentSprite();
            String format = props.getOutputFormat() == null ? "png" : props.getOutputFormat().toLowerCase();
            if (transparent) format = "png";

            ImageResult result;
            if (reference != null && provider.supportsEdit()) {
                result = provider.edit(new ImageEditRequest(spec.prompt(), reference, referenceMime,
                        size[0], size[1], format, transparent, props.getQuality()));
            } else {
                result = provider.generate(new ImageRequest(spec.prompt(), size[0], size[1], format, transparent,
                        props.getQuality()));
            }
            if (transparent) {
                String problem = AssetStore.transparencyProblem(result.bytes());
                if (problem != null) {
                    // A valid API call can yield an unusable stochastic output. Retry through the
                    // scheduler so every attempt still consumes the durable arc budget.
                    throw new ImageProviderException(problem + "；已请求透明 PNG，可重新生成", true);
                }
            }
            synchronized (s.lock) {
                if (!live(s)) {
                    log.info("Assets {}: dropping late result for {} (session forgotten)", sid, spec.assetId());
                    return;
                }
                AssetStore.Stored stored = store.save(sid, spec.assetId(), result.bytes(), result.mimeType());
                AssetRecord r = s.manifest.get(task.assetId());
                if (r == null) return;
                r.fileName = stored.fileName();
                r.mimeType = stored.mimeType();
                r.width = stored.width();
                r.height = stored.height();
                r.bytes = stored.bytes();
                r.contentHash = stored.contentHash();
                r.model = result.model();
                r.provider = provider.describe();
                r.generationVersion++;
                r.status = AssetStatus.READY;
                r.readyAt = Instant.now().toString();
                r.failureReason = null;
                s.manifest.touch();
                boolean published = persistLocked(s);
                log.info("Assets {}: {} READY ({}x{}, {} bytes, {}s, attempt {}, {} via {})", sid, spec.assetId(),
                        r.width, r.height, r.bytes, (System.currentTimeMillis() - started) / 1000, r.attempts,
                        reference != null && provider.supportsEdit() ? "edit" : "generate", provider.describe());
                complete(key, r);
                if (published) resumeReferenceDependentsLocked(s, spec.assetId());
            }
        } catch (ImageProviderException e) {
            fail(s, key, task, e.getMessage(), e.isRetryable(), e.retryAfterMillis());
        } catch (IOException e) {
            fail(s, key, task, "unusable image payload: " + e.getMessage(), true, 0);
        } catch (Throwable e) {
            fail(s, key, task, "unexpected: " + e, false, 0);
        } finally {
            active.decrementAndGet();
        }
    }

    private void pauseDependency(Session s, String key, AssetSpec spec, String reason, boolean recheckReady) {
        synchronized (s.lock) {
            if (!live(s)) return;
            AssetRecord r = s.manifest.get(spec.assetId());
            if (r == null) return;
            AssetRecord reference = s.manifest.get(spec.dependsOn());
            if (recheckReady && reference != null && reference.status == AssetStatus.READY) {
                // The base retry may have completed between our earlier check and acquiring
                // this monitor. Requeue now so its wake-up cannot be lost.
                r.status = AssetStatus.PLANNED;
                enqueueLocked(s, r, spec.priority());
                s.manifest.touch();
                persistLocked(s);
                return;
            }
            r.status = AssetStatus.PAUSED;
            r.referenceUnavailable = true;
            r.failureReason = reason;
            s.manifest.touch();
            persistLocked(s);
            complete(key, r);
        }
    }

    /** A successful base retry unblocks its cards/poses without reopening independently failed jobs. */
    private void resumeReferenceDependentsLocked(Session session, String referenceId) {
        boolean changed = false;
        for (AssetRecord dependent : session.manifest.records.values()) {
            if (dependent.status == AssetStatus.PAUSED && dependent.referenceUnavailable
                    && referenceId.equals(dependent.spec.dependsOn())) {
                enqueueLocked(session, dependent, dependent.spec.priority());
                changed = true; // Budget/capability pauses also deserve a persisted explanation.
            }
        }
        if (changed) {
            session.manifest.touch();
            persistLocked(session);
        }
    }

    private void fail(Session s, String key, Task task, String reason, boolean retryable, long retryAfterMillis) {
        synchronized (s.lock) {
            if (!live(s)) return;
            AssetRecord r = s.manifest.get(task.assetId());
            if (r == null) return;
            if (s.persistenceFailure != null) {
                r.status = AssetStatus.PAUSED;
                r.failureReason = s.persistenceFailure;
                complete(key, r);
                return;
            }
            boolean again = retryable && r.attempts < attemptLimit(r);
            if (again) {
                long backoff = Math.min(30_000L, 2_000L * (1L << Math.max(0, r.attempts - 1)));
                long delay = Math.max(backoff, retryAfterMillis);
                r.status = AssetStatus.QUEUED;
                r.failureReason = "attempt " + r.attempts + " failed (" + reason + "); retrying in " + delay / 1000 + "s";
                log.info("Assets {}: {} failed, retry {} of {} in {}ms: {}", s.manifest.sessionId, task.assetId(),
                        r.attempts + 1, props.getMaxAttempts(), delay, reason);
                s.manifest.touch();
                if (persistLocked(s)) requeueLater(s.manifest.sessionId, task.assetId(), task.priority(), delay);
            } else {
                r.status = AssetStatus.FAILED;
                r.retryable = retryable;
                r.failureReason = reason + (retryable ? " (attempts exhausted)" : " (not retryable)");
                log.warn("Assets {}: {} FAILED after {} attempt(s): {}", s.manifest.sessionId, task.assetId(),
                        r.attempts, r.failureReason);
                s.manifest.touch();
                persistLocked(s);
                complete(key, r);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        delayed.shutdownNow();
        for (Thread t : workers) t.interrupt();
    }
}
