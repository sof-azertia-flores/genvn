package com.genvn.speculation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.config.GenvnProperties;
import com.genvn.dice.CheckResolver;
import com.genvn.dice.CheckResult;
import com.genvn.dice.DiceService;
import com.genvn.game.GameSession;
import com.genvn.game.GameState;
import com.genvn.game.SceneStateProjector;
import com.genvn.game.StateReducer;
import com.genvn.narrative.*;
import com.genvn.persistence.SceneTreeStore;
import com.genvn.story.CompiledStory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Parallel first round, followed by at most four probability-ranked second-round scenes.
 *
 * Dice are cast ahead: the current scene's dice already exist when the first round starts, so a
 * checked choice costs one branch (the outcome that will actually happen), never two. Every
 * branch casts the dice for the scene it produces, so the second round can do the same.
 */
@Component
public class SpeculativeGenerator {
    private static final Logger log = LoggerFactory.getLogger(SpeculativeGenerator.class);
    private static final int SECOND_ROUND_LIMIT = 4;
    private final SceneGenerator sceneGenerator;
    private final BranchCache cache;
    private final StateReducer reducer;
    private final ObjectMapper mapper;
    private final ChoiceProbabilityEstimator estimator;
    private final CheckResolver checkResolver;
    private final GenvnProperties properties;
    private final ThreadPoolExecutor executor;
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
    private final Map<String, Frontier> frontiers = new ConcurrentHashMap<>();
    private SceneTreeStore tree;

    /** Explicit single-round wiring remains useful for standalone callers. */
    public SpeculativeGenerator(SceneGenerator generator, BranchCache cache, StateReducer reducer,
                                ObjectMapper mapper, GenvnProperties properties) {
        this(generator, cache, reducer, mapper, properties, null, new CheckResolver(new DiceService()));
    }

    public SpeculativeGenerator(SceneGenerator generator, BranchCache cache, StateReducer reducer,
                                ObjectMapper mapper, GenvnProperties properties, CheckResolver checkResolver) {
        this(generator, cache, reducer, mapper, properties, null, checkResolver);
    }

    public SpeculativeGenerator(SceneGenerator generator, BranchCache cache, StateReducer reducer,
                                ObjectMapper mapper, GenvnProperties properties, ChoiceProbabilityEstimator estimator) {
        this(generator, cache, reducer, mapper, properties, estimator, new CheckResolver(new DiceService()));
    }

    @Autowired
    public SpeculativeGenerator(SceneGenerator generator, BranchCache cache, StateReducer reducer,
                                ObjectMapper mapper, GenvnProperties properties, ChoiceProbabilityEstimator estimator,
                                CheckResolver checkResolver) {
        this.sceneGenerator = generator;
        this.cache = cache;
        this.reducer = reducer;
        this.mapper = mapper;
        this.estimator = estimator;
        this.checkResolver = checkResolver == null ? new CheckResolver(new DiceService()) : checkResolver;
        this.properties = properties;
        int threads = Math.max(1, properties.getSpeculation().getThreads());
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, queue, r -> {
            Thread t = new Thread(r, "genvn-speculation");
            t.setDaemon(true);
            return t;
        });
    }

    @Autowired(required = false)
    public void setSceneTree(SceneTreeStore tree) {
        this.tree = tree;
    }

    public void reconfigurePool() {
        int threads = Math.max(1, properties.getSpeculation().getThreads());
        executor.setCorePoolSize(threads);
        executor.setMaximumPoolSize(threads);
    }

    private boolean speculationEnabled() {
        return properties.getSpeculation().isEnabled();
    }

    private int maxBranches() {
        return Math.max(1, properties.getSpeculation().getMaxBranches());
    }

    private static final class Frontier {
        final GameSession session;
        final SceneBundle root;
        final int version;
        final int nextIndex;
        final CompiledStory story;
        final GameState state;
        final List<String> history;
        /** The current scene's pre-cast dice, so each checked choice gets exactly one branch. */
        final Map<String, CheckResult> dice;
        final Map<BranchKey, Branch> first = new LinkedHashMap<>();
        final List<RankedBranch> second = new ArrayList<>();
        FutureTask<Void> ranking;
        BranchKey selected;
        boolean closed;
        String phase = "waiting_first_round";
        Frontier(GameSession session, ObjectMapper mapper) {
            this.session = session;
            root = session.currentScene;
            version = session.state.stateVersion;
            nextIndex = session.sceneCounter;
            story = mapper.convertValue(session.story, CompiledStory.class);
            state = session.state.deepCopy(mapper);
            history = session.history.stream().map(h -> h.choiceText).filter(Objects::nonNull).toList();
            dice = session.sceneDice == null ? Map.of() : Map.copyOf(session.sceneDice);
        }
    }
    private record Projection(CompiledStory story, GameState state) {}
    private record Candidate(BranchKey parent, Choice choice, String outcome, double probability) {}
    private record RankedBranch(BranchKey parent, Branch branch, double probability, Projection projection) {}
    /** Player-facing: names the choice pair and whether a die is involved, never how it landed. */
    public record SecondRoundCandidate(String key, String parentChoiceId, String choiceId, boolean check,
                                       double probability, String status) {}
    public record SecondRoundView(String status, List<SecondRoundCandidate> candidates, int limit) {}

    public List<String> prefetch(GameSession session) {
        synchronized (session) {
            Frontier previous = frontiers.get(session.id);
            if (previous != null && !previous.closed && previous.version == session.state.stateVersion
                    && session.currentScene != null && previous.root.sceneId().equals(session.currentScene.sceneId())) {
                return previous.first.keySet().stream().map(BranchKey::asString).toList();
            }
            Map<BranchKey, Branch> promoted = promote(session, previous);
            cache.discardAll(session.id);
            if (!speculationEnabled() || session.deleted || session.currentScene == null || session.finished
                    || session.currentScene.choices().isEmpty()) {
                promoted.values().forEach(Branch::cancel);
                return List.of();
            }
            Frontier frontier = new Frontier(session, mapper);
            frontiers.put(session.id, frontier);
            outer: for (Choice choice : frontier.root.choices()) {
                for (String outcome : outcomes(choice, frontier.dice)) {
                    if (frontier.first.size() >= maxBranches()) break outer;
                    if (tree != null && tree.findChild(session.id, frontier.root.sceneId(), choice.id(), outcome).isPresent()) {
                        continue;
                    }
                    BranchKey key = new BranchKey(frontier.root.sceneId(), choice.id(), outcome);
                    Branch branch = promoted.remove(key);
                    boolean created = branch == null;
                    if (branch == null) branch = branch(session.id, key, frontier.version,
                            copy(frontier.story), frontier.state.deepCopy(mapper), choice, outcome, frontier.nextIndex);
                    frontier.first.put(key, branch);
                    cache.put(session.id, branch);
                    if (created) executor.execute(branch.task());
                }
            }
            promoted.values().forEach(Branch::cancel);
            if (estimator != null) {
                CompletableFuture<?>[] done = frontier.first.values().stream()
                        .map(b -> b.future().handle((v, e) -> null)).toArray(CompletableFuture[]::new);
                CompletableFuture.allOf(done).thenRun(() -> scheduleRanking(frontier));
            } else frontier.phase = "skipped";
            log.info("Session {}: prefetching {} branches from {} (state v{})", session.id,
                    frontier.first.size(), frontier.root.sceneId(), frontier.version);
            return frontier.first.keySet().stream().map(BranchKey::asString).toList();
        }
    }

    /** All accesses to a frontier's mutable fields use its session monitor. No model call holds it. */
    private void scheduleRanking(Frontier frontier) {
        synchronized (frontier.session) {
            if (!live(frontier) || frontier.selected != null || frontier.ranking != null) return;
            frontier.ranking = new FutureTask<>(() -> {
                try { rankAndGenerate(frontier); }
                catch (Exception failure) {
                    synchronized (frontier.session) {
                        if (live(frontier)) frontier.phase = "skipped";
                    }
                    log.info("Session {}: second-round ranking unavailable: {}", frontier.session.id, rootMessage(failure));
                }
                return null;
            });
            executor.execute(frontier.ranking);
        }
    }

    private void rankAndGenerate(Frontier frontier) {
        List<ChoiceProbabilityEstimator.BranchOptions> options = new ArrayList<>();
        synchronized (frontier.session) {
            if (!live(frontier) || frontier.selected != null) return;
            frontier.phase = "estimating";
            for (Branch branch : frontier.first.values()) {
                if (!branch.isReady()) continue;
                SceneBundle scene = branch.future().getNow(null);
                if (!scene.choices().isEmpty()) options.add(new ChoiceProbabilityEstimator.BranchOptions(
                        branch.key().asString(), scene.choices()));
            }
        }
        if (options.isEmpty()) {
            synchronized (frontier.session) { if (live(frontier)) frontier.phase = "skipped"; }
            return;
        }
        var probabilities = estimator.estimate(frontier.history, frontier.root.choices(), options);
        List<Candidate> candidates = new ArrayList<>();
        for (Branch parent : frontier.first.values()) {
            if (!parent.isReady()) continue;
            var conditional = probabilities.nextChoices().get(parent.key().asString());
            if (conditional == null) continue;
            for (Choice choice : parent.future().getNow(null).choices()) {
                double score = probabilities.currentChoices().get(parent.key().choiceId()) * conditional.get(choice.id());
                // The parent branch already cast the dice for its own scene, so a checked choice
                // has exactly one job here: the outcome those dice will produce.
                for (String outcome : outcomes(choice, parent.diceForNextScene())) {
                    candidates.add(new Candidate(parent.key(), choice, outcome, score));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::probability).reversed()
                .thenComparing(c -> c.parent().asString()).thenComparing(c -> c.choice().id()).thenComparing(Candidate::outcome));
        synchronized (frontier.session) {
            if (!live(frontier) || frontier.selected != null || Thread.currentThread().isInterrupted()) return;
            Map<BranchKey, Projection> projected = new HashMap<>();
            for (Candidate candidate : candidates) {
                if (frontier.second.size() >= SECOND_ROUND_LIMIT) break;
                Projection projection = projected.computeIfAbsent(candidate.parent(), key -> project(frontier, key));
                // A future arc is not yet canon; leave its continuation to normal live planning.
                if (projection.state.currentBeatId == null) continue;
                SceneBundle parentScene = frontier.first.get(candidate.parent()).future().getNow(null);
                BranchKey key = new BranchKey(parentScene.sceneId(), candidate.choice().id(), candidate.outcome());
                Branch branch = branch(frontier.session.id, key, projection.state.stateVersion,
                        copy(projection.story), projection.state.deepCopy(mapper), candidate.choice(),
                        candidate.outcome(), frontier.nextIndex + 1);
                frontier.second.add(new RankedBranch(candidate.parent(), branch, candidate.probability(), projection));
                executor.execute(branch.task());
            }
            frontier.phase = frontier.second.isEmpty() ? "skipped" : "generating";
        }
    }

    private Projection project(Frontier frontier, BranchKey parent) {
        CompiledStory story = copy(frontier.story);
        GameState state = frontier.state.deepCopy(mapper);
        SceneBundle scene = frontier.first.get(parent).future().getNow(null);
        SceneStateProjector.register(story, state, scene);
        SceneStateProjector.apply(reducer, story, state, scene, frontier.root.choice(parent.choiceId()), null);
        return new Projection(story, state);
    }

    private Map<BranchKey, Branch> promote(GameSession session, Frontier previous) {
        Map<BranchKey, Branch> reusable = new HashMap<>();
        if (previous == null) return reusable;
        previous.closed = true;
        frontiers.remove(session.id, previous);
        if (previous.ranking != null) previous.ranking.cancel(true);
        for (RankedBranch candidate : previous.second) {
            Branch branch = candidate.branch;
            if (!session.deleted && candidate.parent.equals(previous.selected)
                    && !branch.future().isCompletedExceptionally()
                    && branch.key().sceneId().equals(session.state.currentSceneId)
                    && branch.baseStateVersion() == session.state.stateVersion
                    && sameState(candidate.projection.state, session.state)
                    && sameStoryIgnoringSpares(mapper, candidate.projection.story, session.story)) {
                reusable.put(branch.key(), branch);
            } else branch.cancel();
        }
        executor.purge();
        return reusable;
    }

    /**
     * Spare appearance-only designs are sketched in the background and are not plot: a candidate
     * written before a new design existed is still exactly right, so they are left out of the
     * comparison that decides whether a prepared successor may be promoted.
     */
    static boolean sameStoryIgnoringSpares(ObjectMapper mapper, CompiledStory projected, CompiledStory canonical) {
        ObjectNode left = mapper.valueToTree(projected);
        ObjectNode right = mapper.valueToTree(canonical);
        left.remove("preparedVisuals");
        right.remove("preparedVisuals");
        return left.equals(right);
    }

    private boolean sameState(GameState projected, GameState canonical) {
        // The engine's exact die is unknown until the click. It changes only the log suffix;
        // every gameplay fact, scene memory and outcome-derived state must still match exactly.
        ObjectNode left = mapper.valueToTree(projected);
        ObjectNode right = mapper.valueToTree(canonical);
        left.set("recentEvents", mapper.valueToTree(normalizedEvents(projected)));
        right.set("recentEvents", mapper.valueToTree(normalizedEvents(canonical)));
        return left.equals(right);
    }
    private List<String> normalizedEvents(GameState state) {
        return state.recentEvents.stream().map(event -> event.replaceFirst("  \\[engine roll: .*]$", "")).toList();
    }
    private boolean live(Frontier frontier) {
        return !frontier.closed && !frontier.session.deleted && frontiers.get(frontier.session.id) == frontier
                && frontier.session.state.stateVersion == frontier.version
                && frontier.session.currentScene != null
                && frontier.root.sceneId().equals(frontier.session.currentScene.sceneId());
    }

    public int prioritize(GameSession session, BranchKey key) {
        synchronized (session) {
            if (session.deleted) throw new com.genvn.api.SessionNotFoundException("This session has been deleted.");
            Frontier frontier = frontiers.get(session.id);
            if (frontier != null) {
                frontier.selected = key;
                if (frontier.ranking != null) frontier.ranking.cancel(true);
                for (RankedBranch candidate : frontier.second) if (!candidate.parent.equals(key)) candidate.branch.cancel();
            }
            int discarded = cache.discardExcept(session.id, key);
            Branch branch = cache.get(session.id, key);
            if (branch != null && branch.baseStateVersion() != session.state.stateVersion) {
                discarded += cache.discardAll(session.id);
                branch = null;
            }
            executor.purge();
            if (branch != null && branch.task() != null && queue.remove(branch.task())) queue.addFirst(branch.task());
            return discarded;
        }
    }

    public SecondRoundView secondRoundStatus(GameSession session) {
        synchronized (session) {
            Frontier frontier = frontiers.get(session.id);
            if (frontier == null || !live(frontier)) return new SecondRoundView("skipped", List.of(), SECOND_ROUND_LIMIT);
            List<SecondRoundCandidate> rows = frontier.second.stream().map(candidate -> new SecondRoundCandidate(
                    candidate.parent.choiceId() + "=>" + candidate.branch.key().choiceId(), candidate.parent.choiceId(),
                    candidate.branch.key().choiceId(), !SceneRequest.NONE.equals(candidate.branch.key().outcome()),
                    candidate.probability, candidate.branch.status())).toList();
            String status = frontier.phase;
            if ("generating".equals(status) && frontier.second.stream().allMatch(c -> c.branch.future().isDone())) {
                status = frontier.second.stream().allMatch(c -> c.branch.isReady()) ? "ready" : "completed_with_failures";
            }
            return new SecondRoundView(status, rows, SECOND_ROUND_LIMIT);
        }
    }

    public void cancelSession(String sessionId) {
        Frontier frontier = frontiers.remove(sessionId);
        if (frontier == null) return;
        synchronized (frontier.session) {
            frontier.closed = true;
            if (frontier.ranking != null) frontier.ranking.cancel(true);
            frontier.first.values().forEach(Branch::cancel);
            frontier.second.forEach(candidate -> candidate.branch.cancel());
        }
        executor.purge();
    }

    private Branch branch(String sessionId, BranchKey key, int version, CompiledStory story, GameState fork,
                          Choice choice, String outcome, int index) {
        CompletableFuture<SceneBundle> future = new CompletableFuture<>();
        AtomicReference<String> summary = new AtomicReference<>();
        AtomicReference<Map<String, CheckResult>> nextDice = new AtomicReference<>(Map.of());
        AtomicLong started = new AtomicLong();
        FutureTask<Void> task = new FutureTask<>(() -> {
            started.set(System.currentTimeMillis());
            try { future.complete(generateBranch(sessionId, story, fork, choice, outcome, index, summary, nextDice)); }
            catch (CancellationException failure) { future.cancel(false); }
            catch (Throwable failure) { future.completeExceptionally(failure); }
            return null;
        });
        future.whenComplete((value, failure) -> { if (future.isCancelled()) task.cancel(true); });
        return new Branch(key, future, version, System.currentTimeMillis(), summary, task, started, nextDice);
    }

    private SceneBundle generateBranch(String sessionId, CompiledStory story, GameState fork, Choice choice,
                                       String outcome, int sceneIndex, AtomicReference<String> provisional,
                                       AtomicReference<Map<String, CheckResult>> nextDice) {
        SceneBundle candidate = sceneGenerator.generate(new SceneRequest(story, fork, choice, outcome, null, sceneIndex, true));
        SceneStateProjector.register(story, fork, candidate);
        var applied = reducer.apply(fork, story, candidate.proposedStateDelta(), candidate.sceneId());
        provisional.set(applied.applied().isEmpty() ? "no state change" : applied.applied().stream()
                .map(o -> o.op() + (o.target() == null ? "" : ":" + o.target())).collect(Collectors.joining(", ")));
        // Cast the candidate scene's dice now, on the fork: if this branch is committed they become
        // the scene's dice, and until then they only exist here. The fork's player has the same
        // modifiers as canon; stats never change in play.
        nextDice.set(castDice(candidate, fork));
        log.info("Session {}: branch {}/{} ready", sessionId, choice.id(), outcome);
        return candidate.withMeta(candidate.meta().withCacheHit(true));
    }

    /** One die per checked choice, in choice order; the same rule {@code SessionService} uses at commit. */
    public Map<String, CheckResult> castDice(SceneBundle scene, GameState state) {
        Map<String, CheckResult> dice = new LinkedHashMap<>();
        for (Choice choice : scene.choices()) {
            if (choice.hasCheck()) dice.put(choice.id(), checkResolver.resolve(choice.check(), state.player));
        }
        return dice;
    }

    private CompiledStory copy(CompiledStory story) { return mapper.convertValue(story, CompiledStory.class); }

    /** The one outcome a choice can have given the dice already cast; a checked choice without a die gets no branch. */
    private static List<String> outcomes(Choice choice, Map<String, CheckResult> dice) {
        if (!choice.hasCheck()) return List.of(SceneRequest.NONE);
        CheckResult die = dice.get(choice.id());
        if (die == null) return List.of();
        return List.of(die.success() ? SceneRequest.SUCCESS : SceneRequest.FAILURE);
    }

    /** Which outcome branch a choice resolves to, or null for a checked choice whose die is unknown. */
    public static String outcomeFor(Choice choice, Map<String, CheckResult> dice) {
        List<String> outcomes = outcomes(choice, dice == null ? Map.of() : dice);
        return outcomes.isEmpty() ? null : outcomes.get(0);
    }
    private static String rootMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
    @jakarta.annotation.PreDestroy
    public void close() {
        List.copyOf(frontiers.keySet()).forEach(this::cancelSession);
        executor.shutdownNow();
    }
}
