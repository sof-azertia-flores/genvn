package com.genvn.speculation;

import com.genvn.narrative.SceneBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Holds the one-step-ahead candidates for each session.
 *
 * Prefetch is a performance optimisation and nothing more: a miss, a failure or a stale entry
 * simply falls back to live generation. Nothing in the cache can become canon without passing
 * through the same commit path as a freshly generated scene.
 */
@Component
public class BranchCache {

    private static final Logger log = LoggerFactory.getLogger(BranchCache.class);

    private final Map<String, Map<String, Branch>> bySession = new ConcurrentHashMap<>();

    public void put(String sessionId, Branch branch) {
        Branch previous = bySession.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(branch.key().asString(), branch);
        if (previous != null && previous != branch) previous.cancel();
    }

    public Branch get(String sessionId, BranchKey key) {
        Map<String, Branch> branches = bySession.get(sessionId);
        return branches == null ? null : branches.get(key.asString());
    }

    /** Called with the session monitor held after the player has committed to this choice. */
    public int discardExcept(String sessionId, BranchKey selected) {
        Map<String, Branch> branches = bySession.get(sessionId);
        if (branches == null) return 0;
        int discarded = 0;
        for (Branch branch : branches.values()) {
            if (!branch.key().equals(selected) && branches.remove(branch.key().asString(), branch)) {
                branch.cancel();
                discarded++;
            }
        }
        return discarded;
    }

    /** @return a usable candidate, or null when there is no fresh, finished branch for this key. */
    public SceneBundle takeIfFresh(String sessionId, BranchKey key, int currentStateVersion) {
        Map<String, Branch> branches = bySession.get(sessionId);
        if (branches == null) return null;
        Branch branch = branches.get(key.asString());
        if (branch == null) return null;
        if (branch.baseStateVersion() != currentStateVersion) {
            log.info("Discarding stale branch {} (forked at v{}, canonical is v{})",
                    key.asString(), branch.baseStateVersion(), currentStateVersion);
            return null;
        }
        if (!branch.isReady()) return null;
        try {
            return branch.future().getNow(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A miss is very often a branch that is still being written. Waiting for it costs the
     * remaining seconds; regenerating from scratch costs the whole generation again and leaves
     * the half-finished branch burning tokens for nothing.
     *
     * @return the finished candidate, or null if there was nothing in flight for this key, it was
     *         forked from an older state, it failed, or it did not finish within {@code maxWaitMillis}.
     */
    public SceneBundle awaitIfInFlight(String sessionId, BranchKey key, int currentStateVersion, long maxWaitMillis) {
        Map<String, Branch> branches = bySession.get(sessionId);
        if (branches == null) return null;
        Branch branch = branches.get(key.asString());
        if (branch == null || branch.baseStateVersion() != currentStateVersion) return null;
        if (branch.future().isDone()) return takeIfFresh(sessionId, key, currentStateVersion);
        long started = System.currentTimeMillis();
        try {
            SceneBundle bundle = branch.future().get(maxWaitMillis, TimeUnit.MILLISECONDS);
            log.info("Waited {}ms for in-flight branch {} instead of regenerating it",
                    System.currentTimeMillis() - started, key.asString());
            return bundle;
        } catch (TimeoutException e) {
            branch.cancel();
            log.info("In-flight branch {} did not finish within {}ms; generating live", key.asString(), maxWaitMillis);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for a speculative branch");
        } catch (CancellationException e) {
            throw e; // deleting/discarding this request must never start a replacement model call
        } catch (ExecutionException e) {
            return null; // the branch failed; the caller falls back to live generation
        }
    }

    /** Cancels and drops every branch for a session. Called the moment a choice is committed. */
    public int discardAll(String sessionId) {
        Map<String, Branch> branches = bySession.remove(sessionId);
        if (branches == null) return 0;
        int n = 0;
        for (Branch b : branches.values()) {
            if (!b.future().isDone()) {
                b.cancel();
            }
            n++;
        }
        return n;
    }

    public List<Map<String, Object>> status(String sessionId) {
        Map<String, Branch> branches = bySession.get(sessionId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (branches == null) return out;
        long now = System.currentTimeMillis();
        for (Branch b : branches.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", b.key().asString());
            row.put("choiceId", b.key().choiceId());
            row.put("outcome", b.key().outcome());
            row.put("status", b.status());
            row.put("baseStateVersion", b.baseStateVersion());
            row.put("ageMillis", now - b.startedAtMillis());
            row.put("executionStartedAtMillis", b.executionStartedAtMillis().get());
            row.put("provisional", b.provisional());
            out.add(row);
        }
        out.sort((a, c) -> String.valueOf(a.get("key")).compareTo(String.valueOf(c.get("key"))));
        return out;
    }

    public int size(String sessionId) {
        Map<String, Branch> branches = bySession.get(sessionId);
        return branches == null ? 0 : branches.size();
    }

    /** Snapshot of whatever is currently cached; used to persist finished unused candidates. */
    public List<Branch> list(String sessionId) {
        Map<String, Branch> branches = bySession.get(sessionId);
        return branches == null ? List.of() : List.copyOf(branches.values());
    }
}
