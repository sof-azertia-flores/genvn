package com.genvn.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.NotFoundException;
import com.genvn.api.ChoiceResolvingException;
import com.genvn.asset.AssetCoordinator;
import com.genvn.api.PendingRollException;
import com.genvn.api.RestructureInProgressException;
import com.genvn.api.SceneConflictException;
import com.genvn.api.SessionNotFoundException;
import com.genvn.dice.CheckResolver;
import com.genvn.dice.CheckResult;
import com.genvn.narrative.Choice;
import com.genvn.narrative.SceneBundle;
import com.genvn.narrative.SceneGenerator;
import com.genvn.narrative.SceneRequest;
import com.genvn.persistence.GameSessionRepository;
import com.genvn.persistence.SceneNode;
import com.genvn.persistence.SceneTreeStore;
import com.genvn.speculation.BranchCache;
import com.genvn.speculation.BranchKey;
import com.genvn.speculation.SpeculativeGenerator;
import com.genvn.story.ArcContinuationService;
import com.genvn.story.CompiledStory;
import com.genvn.story.StoryCompiler;
import com.genvn.story.StoryRestructurePlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the canonical loop: compile -> scene -> choice -> roll -> commit -> prefetch.
 *
 * Every mutation of canonical state happens in {@link #commitScene}, and only after a scene has
 * been generated and validated successfully. A failed generation leaves the session exactly as
 * it was.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final StoryCompiler compiler;
    private final SceneGenerator sceneGenerator;
    private final StateReducer reducer;
    private final CheckResolver checkResolver;
    private final BranchCache branchCache;
    private final SpeculativeGenerator speculative;
    private final ArcContinuationService arcs;
    private final GameSessionRepository repository;
    private final ObjectMapper mapper;
    private final AssetCoordinator assets;
    private final com.genvn.story.SpareDesignService spareDesigns;
    private final SceneTreeStore tree;
    /** Null when nothing wired one: restructuring is then simply unavailable. */
    private final StoryRestructurePlanner restructurePlanner;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.genvn.config.GenvnProperties genvnProperties;

    private String language() {
        return genvnProperties == null ? com.genvn.config.UiLanguage.ZH : genvnProperties.getLanguage();
    }

    private void note(CreationProgress progress, int value, String zhStage, String enStage, String zh, String en) {
        progress.report(com.genvn.config.UiLanguage.text(language(), zhStage, enStage), value,
                com.genvn.config.UiLanguage.text(language(), zh, en));
    }

    /** Wiring without a picture pipeline: every asset hook is a no-op. */
    public SessionService(StoryCompiler compiler, SceneGenerator sceneGenerator, StateReducer reducer,
                          CheckResolver checkResolver, BranchCache branchCache,
                          SpeculativeGenerator speculative, ArcContinuationService arcs,
                          GameSessionRepository repository, ObjectMapper mapper) {
        this(compiler, sceneGenerator, reducer, checkResolver, branchCache, speculative, arcs, repository, mapper,
                AssetCoordinator.disabled());
    }

    public SessionService(StoryCompiler compiler, SceneGenerator sceneGenerator, StateReducer reducer,
                          CheckResolver checkResolver, BranchCache branchCache,
                          SpeculativeGenerator speculative, ArcContinuationService arcs,
                          GameSessionRepository repository, ObjectMapper mapper, AssetCoordinator assets) {
        this(compiler, sceneGenerator, reducer, checkResolver, branchCache, speculative, arcs, repository, mapper, assets, null);
    }

    public SessionService(StoryCompiler compiler, SceneGenerator sceneGenerator, StateReducer reducer,
                          CheckResolver checkResolver, BranchCache branchCache,
                          SpeculativeGenerator speculative, ArcContinuationService arcs,
                          GameSessionRepository repository, ObjectMapper mapper, AssetCoordinator assets,
                          com.genvn.story.SpareDesignService spareDesigns) {
        this(compiler, sceneGenerator, reducer, checkResolver, branchCache, speculative, arcs, repository, mapper,
                assets, spareDesigns, null);
    }

    public SessionService(StoryCompiler compiler, SceneGenerator sceneGenerator, StateReducer reducer,
                          CheckResolver checkResolver, BranchCache branchCache,
                          SpeculativeGenerator speculative, ArcContinuationService arcs,
                          GameSessionRepository repository, ObjectMapper mapper, AssetCoordinator assets,
                          com.genvn.story.SpareDesignService spareDesigns,
                          com.genvn.persistence.SceneTreeStore tree) {
        this(compiler, sceneGenerator, reducer, checkResolver, branchCache, speculative, arcs, repository, mapper,
                assets, spareDesigns, tree, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SessionService(StoryCompiler compiler, SceneGenerator sceneGenerator, StateReducer reducer,
                          CheckResolver checkResolver, BranchCache branchCache,
                          SpeculativeGenerator speculative, ArcContinuationService arcs,
                          GameSessionRepository repository, ObjectMapper mapper, AssetCoordinator assets,
                          com.genvn.story.SpareDesignService spareDesigns,
                          com.genvn.persistence.SceneTreeStore tree,
                          StoryRestructurePlanner restructurePlanner) {
        this.spareDesigns = spareDesigns;
        this.tree = tree;
        this.restructurePlanner = restructurePlanner;
        this.assets = assets == null ? AssetCoordinator.disabled() : assets;
        this.compiler = compiler;
        this.sceneGenerator = sceneGenerator;
        this.reducer = reducer;
        this.checkResolver = checkResolver;
        this.branchCache = branchCache;
        this.speculative = speculative;
        this.arcs = arcs;
        this.repository = repository;
        this.mapper = mapper;
    }

    public record ChoiceOutcome(
            GameSession session,
            CheckResult roll,
            String chosenText,
            boolean fromSpeculativeCache,
            int discardedBranches,
            List<String> rejectedOps,
            long generationMillis,
            /** True when the die came from a prior roll() call rather than being cast here. */
            boolean reusedPendingRoll
    ) {}

    public record RollOutcome(GameSession session, CheckResult roll, String choiceId, boolean reused) {}

    /**
     * Upper bound on waiting for a branch that is mid-generation. The branch itself is already
     * bounded by llm.timeout-seconds, so this is only a safety net against a stuck future.
     */
    private static final long IN_FLIGHT_WAIT_MILLIS = 10L * 60 * 1000;

    // ------------------------------------------------------------------ create

    /** Milestones describe completed work, never a timer or an estimate of model tokens. */
    @FunctionalInterface
    public interface CreationProgress {
        void report(String stage, int progress, String message);
    }

    public GameSession create(String outline, PlayerCharacter player) {
        return create(outline, player, (stage, progress, message) -> {});
    }

    public GameSession create(String outline, PlayerCharacter player, CreationProgress progress) {
        return create(outline, player, "", progress);
    }

    public GameSession createWithArtStyle(String outline, PlayerCharacter player, String artStyle) {
        return create(outline, player, artStyle, (stage, progress, message) -> {});
    }

    public GameSession create(String outline, PlayerCharacter player, String artStyle, CreationProgress progress) {
        String id = uniqueSessionId(candidate -> repository.find(candidate).isPresent() || assets.hasPictures(candidate));
        note(progress, 2, "开始准备故事", "Starting the story",
                "开局任务已开始，正在读取故事与玩家资料。",
                "The opening job has started; reading the story and player notes.");
        StoryCompiler.Compiled compiled = compiler.compile(id, outline, player, artStyle, progress);

        GameSession session = new GameSession();
        session.id = id;
        session.story = compiled.story();
        session.state = compiled.state();
        session.title = compiled.story().spine.arcTitle();

        try {
            note(progress, 53, "故事框架已建立", "Story frame ready",
                    "故事框架和初始状态已就绪，开始安排美术素材。",
                    "The story frame and opening state are ready; planning pictures next.");
            // Pictures start from the frame while opening prose is still being written.
            note(progress, 55, "规划人物与场景素材", "Planning people and places",
                    "根据故事框架安排素材，符合生成设置和预算的图片将进入后台队列。",
                    "Pictures that fit the settings and budget will join the background queue.");
            assets.planForSession(session);
            note(progress, 58, "美术任务已安排", "Picture jobs queued",
                    "素材规划阶段已结束；图片由后台队列独立处理。",
                    "Picture planning is done; the background queue handles images on its own.");
            note(progress, 60, "编写开场正文", "Writing the opening",
                    "已准备开场地点、角色与可用素材，开始编写对白和选项。",
                    "Opening place, people and available art are ready; writing dialogue and choices.");
            SceneBundle opening = sceneGenerator.generate(new SceneRequest(
                    session.story, session.state, null, SceneRequest.NONE, null, 0, false),
                    CreationMilestones.model(progress, true, language()));
            note(progress, 88, "整理开场场景", "Assembling the opening scene",
                    "开场对白、选项和人物位置已整理，准备写入游戏状态。",
                    "Opening dialogue, choices and positions are assembled; writing them into game state.");
            commitScene(session, opening, null, null, Map.of());
            note(progress, 91, "绑定开场状态与素材", "Binding opening state and art",
                    "开场已写入剧情历史，人物、地点和素材引用已绑定。",
                    "The opening is in story history; people, places and art references are bound.");
            persistSession(session);
            note(progress, 94, "本地保存阶段完成", "Local save finished",
                    session.saveHealthy ? "开场故事已保存到本地。" : "开场已建立，但本地写入未成功；进入故事后请留意存档提示。",
                    session.saveHealthy ? "The opening story is saved locally."
                            : "The opening exists, but the local write did not succeed; watch the save notice after you enter.");
            note(progress, 97, "准备后续分支", "Preparing later branches",
                    "正在按设置启动后续选项的预推演。图片无需全部完成即可进入故事。",
                    "Prefetch of later choices is starting. You can enter before every picture is ready.");
            speculative.prefetch(session);
            note(progress, 99, "等待进入故事", "Ready to enter",
                    session.saveHealthy
                            ? "故事已保存，可以开始游玩。尚未完成的图片和后续分支会继续在后台准备。"
                            : "故事已就绪，但存档暂未写入磁盘；进入后请留意存档提示。",
                    session.saveHealthy
                            ? "The story is saved and ready to play. Unfinished pictures and later branches keep preparing."
                            : "The story is ready, but the save is not on disk yet; watch the save notice after you enter.");
            log.info("Created session {} ('{}') with {} beats", id, session.title, session.story.spine.beats().size());
            return session;
        } catch (RuntimeException | Error failure) {
            // A failed opening has no playable session. Stop its early picture work as well,
            // otherwise retries would leave invisible, paid generation queues behind.
            synchronized (session) {
                branchCache.discardAll(id);
                speculative.cancelSession(id);
                assets.onSessionDeleted(id);
                repository.delete(id);
                session.deleted = true;
            }
            throw failure;
        }
    }

    /**
     * Eight hex characters are plenty for one player's shelf of saves, but nothing used to stop
     * a repeat from overwriting an older save and adopting its picture library. A candidate that
     * already names a save or an asset directory is skipped.
     */
    public static String uniqueSessionId(java.util.function.Predicate<String> taken) {
        for (int i = 0; i < 32; i++) {
            String candidate = UUID.randomUUID().toString().substring(0, 8);
            if (!taken.test(candidate)) return candidate;
        }
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ roll

    public RollOutcome roll(String sessionId, String choiceId) {
        GameSession session = require(sessionId);
        String sceneId;
        int version;
        synchronized (session) {
            sceneId = session.currentScene.sceneId();
            version = session.state.stateVersion;
        }
        return roll(sessionId, choiceId, sceneId, version);
    }

    /**
     * Casts the die for a checked choice and returns it at once -- BEFORE any scene is
     * generated -- so the player sees the real number while the scene is still being written.
     *
     * The roll is persisted on the session. A retry after a failed generation, a page refresh
     * or a backend restart all reuse it; nothing ever rolls twice for the same choice. Once
     * seen, a die is binding: rolling or resolving a different choice on this scene is refused.
     */
    public RollOutcome roll(String sessionId, String choiceId, String expectedSceneId, int expectedStateVersion) {
        GameSession session = require(sessionId);
        synchronized (session) {
            SceneBundle current = session.currentScene;
            if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
            if (current == null) throw new NotFoundException("Session " + sessionId + " has no current scene");
            if (!Objects.equals(expectedSceneId, current.sceneId()) || expectedStateVersion != session.state.stateVersion) {
                throw new SceneConflictException("This scene has already changed. Reload it and choose again.");
            }
            if (session.restructuringJobId != null) throw new RestructureInProgressException();
            Choice choice = current.choice(choiceId);
            if (choice == null) {
                throw new SceneConflictException("This choice is no longer offered. Reload the current scene.");
            }
            if (!choice.hasCheck()) {
                throw new IllegalArgumentException("Choice '" + choiceId + "' has no check; there is nothing to roll.");
            }

            GameSession.PendingRoll pending = session.pendingRoll;
            if (session.resolvingChoiceId != null
                    && !(choiceId.equals(session.resolvingChoiceId) && pending != null
                    && current.sceneId().equals(pending.sceneId) && choiceId.equals(pending.choiceId))) {
                throw new ChoiceResolvingException(session.resolvingChoiceId);
            }
            if (pending != null && current.sceneId().equals(pending.sceneId)) {
                if (choiceId.equals(pending.choiceId)) {
                    return new RollOutcome(copySession(session), pending.roll, choiceId, true);
                }
                throw new PendingRollException(pending.choiceId);
            }

            CheckResult result = dieFor(session, choice);
            session.pendingRoll = new GameSession.PendingRoll(current.sceneId(), choiceId, result);
            persistSession(session);
            log.info("Session {}: revealed the die for '{}' -- {} (persisted, binding until resolved)",
                    sessionId, choiceId, result.summary());
            return new RollOutcome(copySession(session), result, choiceId, false);
        }
    }

    // ------------------------------------------------------------------ play

    public ChoiceOutcome choose(String sessionId, String choiceId) {
        // Trusted in-process callers (tests/tools) choose the currently observed scene.
        // HTTP clients must use the explicit expected-scene overload below.
        // Only the observation is taken under the monitor; the call itself must run outside it,
        // or this overload would quietly hold the session for the whole generation again.
        GameSession session = require(sessionId);
        String sceneId;
        int version;
        synchronized (session) {
            sceneId = session.currentScene.sceneId();
            version = session.state.stateVersion;
        }
        return choose(sessionId, choiceId, sceneId, version);
    }

    /**
     * Three phases, so the session monitor is never held across a model call:
     *  1. locked, milliseconds: validate, decide (and persist) the die, snapshot the base state;
     *  2. unlocked: obtain the next scene from the cache, an in-flight branch, or a live
     *     generation -- against the snapshot only, exactly like a speculative branch;
     *  3. locked: prove the scene and state version are still the ones we built on, then commit.
     * A second click while phase 2 is running gets an immediate conflict rather than a second
     * generation; a page that is behind gets a conflict at phase 1 or 3, never a double commit.
     */
    public ChoiceOutcome choose(String sessionId, String choiceId, String expectedSceneId, int expectedStateVersion) {
        GameSession session = require(sessionId);

        final Choice choice;
        final CheckResult roll;
        final boolean reused;
        final String outcome;
        final String baseSceneId;
        final int baseVersion;
        final int sceneIndex;
        final GameState snapshot;
        final CompiledStory storySnapshot;
        synchronized (session) {
            SceneBundle current = session.currentScene;
            if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
            if (current == null) throw new NotFoundException("Session " + sessionId + " has no current scene");
            if (!Objects.equals(expectedSceneId, current.sceneId()) || expectedStateVersion != session.state.stateVersion) {
                throw new SceneConflictException("This scene has already changed. Reload it and choose again.");
            }
            if (session.restructuringJobId != null) throw new RestructureInProgressException();
            if (session.resolvingChoiceId != null) {
                throw new ChoiceResolvingException(session.resolvingChoiceId);
            }
            choice = current.choice(choiceId);
            if (choice == null) {
                throw new SceneConflictException("This choice is no longer offered. Reload the current scene.");
            }

            // The engine rolls; the model has never seen a die. A die from roll() is reused, never
            // re-rolled. A die cast HERE is persisted at once so a retry after a failed generation
            // replays it too -- choose() without a prior roll() must not re-roll either.
            GameSession.PendingRoll pending = session.pendingRoll;
            boolean pendingOnThisScene = pending != null && current.sceneId().equals(pending.sceneId);
            if (pendingOnThisScene && !choiceId.equals(pending.choiceId)) {
                throw new PendingRollException(pending.choiceId);
            }
            reused = pendingOnThisScene && choice.hasCheck();
            if (!choice.hasCheck()) {
                roll = null;
            } else if (reused) {
                roll = pending.roll;
            } else {
                roll = dieFor(session, choice);
                session.pendingRoll = new GameSession.PendingRoll(current.sceneId(), choiceId, roll);
                persistSession(session);
            }
            outcome = roll == null ? SceneRequest.NONE
                    : (roll.success() ? SceneRequest.SUCCESS : SceneRequest.FAILURE);
            persistReadySiblings(session, new BranchKey(current.sceneId(), choice.id(), outcome));
            baseSceneId = current.sceneId();
            baseVersion = session.state.stateVersion;
            sceneIndex = session.sceneCounter;
            snapshot = session.state.deepCopy(mapper);
            storySnapshot = mapper.convertValue(session.story, CompiledStory.class);
            session.resolvingChoiceId = choiceId;
        }

        SceneBundle next;
        boolean cacheHit;
        boolean waitedForBranch = false;
        boolean restoredVisitedChild = false;
        int discardedBeforeGeneration;
        Map<String, CheckResult> carriedDice = Map.of();
        long started = System.currentTimeMillis();
        BranchKey key = new BranchKey(baseSceneId, choice.id(), outcome);
        try {
            // Use the matching pre-generated candidate if one is ready AND forked from the state
            // we are still on; else finish waiting for it; else a retained tree node; else generate
            // now. Prefetch is never load-bearing, and none of this touches the session.
            // The choice (including its actual die) is now binding. Stop unused requests now,
            // then move the selected queued candidate ahead of background work without duplicating it.
            discardedBeforeGeneration = speculative.prioritize(session, key);
            SceneNode retained = retainedChild(sessionId, baseSceneId, choice.id(), outcome);
            if (retained != null && retained.restorable()) {
                restoredVisitedChild = true;
                next = retained.scene;
                cacheHit = true;
            } else if (retained != null && retained.scene != null) {
                next = retained.scene;
                cacheHit = true;
                if (retained.sceneDice != null) carriedDice = retained.sceneDice;
            } else {
                next = branchCache.takeIfFresh(sessionId, key, baseVersion);
                cacheHit = next != null;
                if (!cacheHit) {
                    next = branchCache.awaitIfInFlight(sessionId, key, baseVersion, IN_FLIGHT_WAIT_MILLIS);
                    waitedForBranch = next != null;
                    cacheHit = waitedForBranch;
                }
            }
            if (!cacheHit) {
                synchronized (session) {
                    if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
                    if (session.currentScene == null || !baseSceneId.equals(session.currentScene.sceneId())
                            || session.state.stateVersion != baseVersion) {
                        throw new SceneConflictException("This scene changed before generation could begin. Reload it.");
                    }
                }
                next = sceneGenerator.generate(new SceneRequest(
                        storySnapshot, snapshot, choice, outcome, roll, sceneIndex, false));
            } else if (!restoredVisitedChild && SceneNode.sequentialSceneId(next.sceneId())) {
                next = next.withSceneId("scene_%03d".formatted(sceneIndex));
                var branch = branchCache.get(sessionId, key);
                if (branch != null) carriedDice = branch.diceForNextScene();
            } else if (!restoredVisitedChild) {
                var branch = branchCache.get(sessionId, key);
                if (branch != null && carriedDice.isEmpty()) carriedDice = branch.diceForNextScene();
            }
        } catch (RuntimeException | Error e) {
            // Whatever went wrong, the scene is no longer being resolved: leaving the marker set
            // would refuse every later choice on this save until a restart.
            synchronized (session) {
                session.resolvingChoiceId = null;
                if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
            }
            throw e;
        }
        long generationMillis = System.currentTimeMillis() - started;
        log.info("Session {}: chose '{}' ({}){}{} -> {} ({}ms)", sessionId, choice.id(), choice.text(),
                roll == null ? "" : " | " + roll.summary(),
                reused ? " [die reused from roll()]" : "",
                waitedForBranch ? "waited for in-flight branch"
                        : cacheHit ? "speculative cache HIT" : "cache miss, generated live",
                generationMillis);

        synchronized (session) {
            try {
                if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
                if (session.currentScene == null || !baseSceneId.equals(session.currentScene.sceneId())
                        || session.state.stateVersion != baseVersion) {
                    throw new SceneConflictException(
                            "This scene changed while your choice was being resolved. Reload and choose again.");
                }
                GameSession.PendingRoll pending = session.pendingRoll;
                if (pending != null && baseSceneId.equals(pending.sceneId)
                        && (!choice.id().equals(pending.choiceId) || !Objects.equals(roll, pending.roll))) {
                    throw new PendingRollException(pending.choiceId);
                }
                // Finished unused siblings were written to the tree before prioritize cancelled
                // them. Drop whatever is still in memory.
                int discarded = discardedBeforeGeneration + branchCache.discardAll(sessionId);

                StateReducer.Applied applied;
                if (restoredVisitedChild) {
                    SceneNode visited = retainedChild(sessionId, baseSceneId, choice.id(), outcome);
                    if (visited == null || !visited.restorable()) {
                        throw new SceneConflictException("This path is no longer available. Reload and choose again.");
                    }
                    restoreNode(session, visited);
                    applied = new StateReducer.Applied(List.of(), List.of());
                } else {
                    applied = commitScene(session, next, choice, roll, carriedDice);
                }
                session.pendingRoll = null; // the die has been played; nothing is outstanding
                persistSession(session);

                // Slide the frontier forward.
                speculative.prefetch(session);
                arcs.maybePlanNextArc(session, () -> {
                    if (session.deleted) return;
                    if (session.state.currentBeatId == null && activatePendingArc(session)) {
                        session.state.stateVersion++;
                        speculative.prefetch(session);
                    }
                    persistCurrentNodeMutation(session);
                    persistSession(session);
                });

                // On a hit one of the discarded entries was the branch we used; on a miss all were waste.
                session.resolvingChoiceId = null;
                return new ChoiceOutcome(copySession(session), roll, choice.text(), cacheHit,
                        cacheHit ? Math.max(0, discarded - 1) : discarded, applied.rejected(), generationMillis, reused);
            } finally {
                session.resolvingChoiceId = null;
            }
        }
    }

    // ------------------------------------------------------------------ commit

    /**
     * The single place canonical state changes. {@code carriedDice} are dice a speculative branch
     * already cast for this scene's choices; any checked choice without one gets a fresh die here.
     */
    private StateReducer.Applied commitScene(GameSession session, SceneBundle scene, Choice choice, CheckResult roll,
                                             Map<String, CheckResult> carriedDice) {
        GameState state = session.state;
        int versionBeforeCommit = state.stateVersion;
        String parentNodeId = session.currentNodeId;
        // The opening's "before" state exists only here; see SceneNode.preState.
        GameState preState = parentNodeId == null ? state.deepCopy(mapper) : null;

        SceneStateProjector.register(session.story, state, scene);
        scene = assets.prepareSceneAssets(session, scene);
        StateReducer.Applied applied = SceneStateProjector.apply(reducer, session.story, state, scene,
                choice, roll == null ? null : roll.summary());

        session.currentScene = scene;
        session.sceneDice = castSceneDice(scene, state, carriedDice);
        GameSession.HistoryEntry entry = new GameSession.HistoryEntry(scene.sceneId(), scene.beatId(),
                choice == null ? null : choice.text(),
                roll == null ? null : roll.summary(),
                scene.blocks().isEmpty() ? null : truncate(scene.blocks().get(0).text(), 120));
        entry.blocks = scene.blocks();
        session.history.add(entry);
        session.sceneCounter++;

        if (state.currentBeatId == null && session.pendingArc != null) {
            activatePendingArc(session);
        }
        session.finished = session.currentScene.choices().isEmpty();
        // Location, text and scene counters also change when the model proposes no delta.
        state.stateVersion = Math.max(state.stateVersion, versionBeforeCommit + 1);
        persistCommittedNode(session, parentNodeId, session.currentScene, choice, roll, outcomeOf(roll), preState);
        entry.sceneId = session.currentScene.sceneId();

        log.info("Session {}: committed {} -- state v{}, beat {}, {} op(s) applied, {} rejected, hp {}/{}, {} item(s)",
                session.id, scene.sceneId(), state.stateVersion, state.currentBeatId,
                applied.applied().size(), applied.rejected().size(), state.player.hp, state.player.maxHp,
                state.inventory.size());
        if (!applied.rejected().isEmpty()) {
            log.info("Session {} scene {}: rejected {} proposed op(s): {}",
                    session.id, scene.sceneId(), applied.rejected().size(), applied.rejected());
        }
        assets.onSceneCommitted(session, scene);
        // A design this scene turned into a person has left the pool; sketch a replacement.
        if (spareDesigns != null) spareDesigns.maybeReplenish(session);
        return applied;
    }

    private void beginNextArc(GameSession session) {
        var outline = session.pendingArc;
        session.pendingArc = null;
        session.story.beginArc(outline);
        GameState state = session.state;
        state.completedBeats.clear();
        state.currentArcTitle = outline.arcTitle();
        state.currentBeatId = session.story.spine.beats().isEmpty() ? null : session.story.spine.beats().get(0).id();
        state.storyProgress.arcNumber++;
        state.scenesInCurrentBeat = 0;
        state.storyProgress.beatsCompleted = 0;
        state.storyProgress.totalBeats = session.story.spine.beats().size();
        state.storyProgress.recompute();
        state.addRecentEvent("A new arc begins: " + outline.arcTitle());
        assets.onArcBegan(session);
        log.info("Session {} moved into arc {} ('{}')", session.id, state.storyProgress.arcNumber, outline.arcTitle());
    }

    /** Called only with the session monitor held, including a late planner callback. */
    private boolean activatePendingArc(GameSession session) {
        if (session.pendingArc == null || session.state.currentBeatId != null) return false;
        beginNextArc(session);
        SceneBundle scene = session.currentScene;
        if (scene.choices().isEmpty()) {
            session.currentScene = new SceneBundle(scene.sceneId(), scene.beatId(), scene.location(),
                    scene.characters(), scene.blocks(),
                    List.of(new Choice("continue_arc",
                            com.genvn.config.UiLanguage.text(language(), "继续下一章", "Continue the story"),
                            "action", null, null)),
                    scene.proposedStateDelta(), scene.storyProgressNote(), scene.assetRequests(), scene.newNpcs(), scene.meta());
        }
        session.finished = false;
        return true;
    }

    // ------------------------------------------------------------------ dice

    /**
     * Dice are cast the moment a scene becomes current, one per checked choice, so the prefetch
     * can write only the outcome that will happen. A die from a committed branch is kept; the
     * rest are cast now. Nothing is revealed until the player picks that choice.
     */
    private Map<String, CheckResult> castSceneDice(SceneBundle scene, GameState state, Map<String, CheckResult> carried) {
        Map<String, CheckResult> dice = new LinkedHashMap<>();
        for (Choice choice : scene.choices()) {
            if (!choice.hasCheck()) continue;
            CheckResult die = carried == null ? null : carried.get(choice.id());
            dice.put(choice.id(), die != null ? die : checkResolver.resolve(choice.check(), state.player));
        }
        return dice;
    }

    /** The pre-cast die for this choice; an older save without one gets it cast (and kept) now. */
    private CheckResult dieFor(GameSession session, Choice choice) {
        if (session.sceneDice == null) session.sceneDice = new LinkedHashMap<>();
        CheckResult die = session.sceneDice.get(choice.id());
        if (die == null) {
            die = checkResolver.resolve(choice.check(), session.state.player);
            session.sceneDice.put(choice.id(), die);
        }
        return die;
    }

    /**
     * Called with the monitor held: a save written before dice were cast ahead gets dice for its
     * current scene once. A die the player already saw ({@link GameSession#pendingRoll}) is kept
     * as that choice's die, so the reveal and the story still agree. Returns true if anything changed.
     */
    private boolean ensureSceneDice(GameSession session) {
        if (session.currentScene == null) return false;
        if (session.sceneDice == null) session.sceneDice = new LinkedHashMap<>();
        boolean changed = false;
        GameSession.PendingRoll pending = session.pendingRoll;
        for (Choice choice : session.currentScene.choices()) {
            if (!choice.hasCheck() || session.sceneDice.containsKey(choice.id())) continue;
            CheckResult die = pending != null && pending.roll != null
                    && session.currentScene.sceneId().equals(pending.sceneId) && choice.id().equals(pending.choiceId)
                    ? pending.roll
                    : checkResolver.resolve(choice.check(), session.state.player);
            session.sceneDice.put(choice.id(), die);
            changed = true;
        }
        return changed;
    }

    // ------------------------------------------------------------------ scene tree

    /**
     * Pointer move, not a commit: restore a previously visited node and its sealed dice.
     * {@code stateVersion} still moves forward so a page that is still on the later scene 409s.
     */
    public GameSession rewind(String sessionId, String nodeId, String expectedSceneId, int expectedStateVersion) {
        GameSession session = require(sessionId, false);
        synchronized (session) {
            if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
            if (session.currentScene == null) throw new NotFoundException("Session " + sessionId + " has no current scene");
            if (!Objects.equals(expectedSceneId, session.currentScene.sceneId())
                    || expectedStateVersion != session.state.stateVersion) {
                throw new SceneConflictException("This scene has already changed. Reload it and choose again.");
            }
            if (session.restructuringJobId != null) throw new RestructureInProgressException();
            if (session.resolvingChoiceId != null) {
                throw new ChoiceResolvingException(session.resolvingChoiceId);
            }
            if (tree == null) {
                throw new IllegalArgumentException("This save cannot rewind: the scene tree is not available.");
            }
            SceneNode node = tree.readNode(sessionId, nodeId)
                    .orElseThrow(() -> new NotFoundException("没有可以回到的这一幕"));
            if (!node.restorable()) {
                throw new IllegalArgumentException("这一幕还没有成为走过的情节，不能从这里重新选择。");
            }
            if (nodeId.equals(session.currentNodeId)) {
                return copySession(session);
            }
            restoreNode(session, node);
            persistSession(session);
            speculative.prefetch(session);
            log.info("Session {}: rewound to {} (state v{})", sessionId, nodeId, session.state.stateVersion);
            return copySession(session);
        }
    }

    /**
     * Rewrite the story from one scene onward, in the player's own words.
     *
     * A rewind changes which path is current; this changes what the story IS. The framework --
     * author canon, bible, and every beat still ahead -- is re-planned around the player's
     * request (their request wins over canon, which is the whole point), the scene they rejected
     * is written again against the revised framework, and everything after it follows from the
     * new beats as they play. Completed beats, the people they have met and the places they have
     * been are kept: those already happened.
     *
     * Same three phases as {@link #choose}: the monitor is never held across a model call, and
     * {@code restructuringJobId} makes every other mutation conflict while the story underneath
     * them is being rewritten.
     *
     * The instruction reaches the two model calls and nothing else -- it is never written to the
     * save, the tree, or any log.
     */
    public GameSession restructure(String sessionId, String nodeId, String instruction,
                                   String expectedSceneId, int expectedStateVersion,
                                   String jobId, CreationProgress progress) {
        String clean = StoryRestructurePlanner.sanitize(instruction);
        if (clean.isBlank()) throw new IllegalArgumentException("请写下你希望这段剧情怎么改。");
        GameSession session = require(sessionId, false);

        final SceneNode target;
        final SceneNode parent;
        final GameState baseState;
        final CompiledStory storySnapshot;
        final Choice choice;
        final CheckResult roll;
        final String outcome;
        final int sceneIndex;
        final String baseSceneId;
        final int baseVersion;
        synchronized (session) {
            if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
            if (session.currentScene == null) throw new NotFoundException("Session " + sessionId + " has no current scene");
            if (!Objects.equals(expectedSceneId, session.currentScene.sceneId())
                    || expectedStateVersion != session.state.stateVersion) {
                throw new SceneConflictException("This scene has already changed. Reload it and try again.");
            }
            if (session.restructuringJobId != null) throw new RestructureInProgressException();
            if (session.resolvingChoiceId != null) throw new ChoiceResolvingException(session.resolvingChoiceId);
            if (tree == null || restructurePlanner == null) {
                throw new IllegalArgumentException("这个存档无法重塑剧情：场景树不可用。");
            }

            target = tree.readNode(sessionId, nodeId)
                    .orElseThrow(() -> new NotFoundException("没有可以重塑的这一幕"));
            if (!target.restorable()) {
                throw new IllegalArgumentException("这一幕还没有成为走过的情节，不能从这里重塑。");
            }

            if (target.parentNodeId == null) {
                // The opening. Its "before" state is the only one the tree stores explicitly.
                parent = null;
                if (target.preState == null) {
                    throw new IllegalArgumentException(
                            "这个存档是更早的版本建立的，没有保存开场之前的状态，开场无法重塑；可以从后面任意一幕重塑。");
                }
                baseState = target.preState.deepCopy(mapper);
                choice = null;
                roll = null;
                outcome = SceneRequest.NONE;
            } else {
                parent = tree.readNode(sessionId, target.parentNodeId).orElse(null);
                if (parent == null || !parent.restorable() || parent.scene == null) {
                    throw new IllegalArgumentException("这一幕之前的情节已经读不到了，无法从这里重塑。");
                }
                Choice source = parent.scene.choice(target.fromChoiceId);
                if (source == null) {
                    throw new IllegalArgumentException("这一幕之前的选项已经不存在了，无法从这里重塑。");
                }
                baseState = parent.state.deepCopy(mapper);
                choice = source;
                // The die the player already saw stays binding: a rewrite changes what happens,
                // never whether they succeeded.
                roll = target.roll;
                outcome = target.outcome == null ? outcomeOf(target.roll) : target.outcome;
            }
            storySnapshot = storyAt(sessionId, parent == null ? target : parent, session.story);
            sceneIndex = session.sceneCounter;
            baseSceneId = session.currentScene.sceneId();
            baseVersion = session.state.stateVersion;
            session.restructuringJobId = jobId;
        }

        final CompiledStory revisedStory;
        final String newBeatId;
        final List<String> newThreads;
        final SceneBundle newScene;
        try {
            var plan = restructurePlanner.plan(storySnapshot, baseState, clean,
                    CreationMilestones.model(progress, 10, 55, "故事框架", "story frame", language()));
            revisedStory = restructurePlanner.apply(storySnapshot, baseState, plan);
            newBeatId = StoryRestructurePlanner.firstNewBeatId(plan);
            newThreads = plan.newThreads();

            // The scene is written as if the revised framework had always been the plan: the beat
            // it belongs to is the first of the new ones, and it is that beat's first scene.
            GameState writingState = baseState.deepCopy(mapper);
            writingState.currentBeatId = newBeatId;
            writingState.scenesInCurrentBeat = 0;
            writingState.currentArcTitle = revisedStory.spine.arcTitle();
            newScene = sceneGenerator.generate(
                    new SceneRequest(revisedStory, writingState, choice, outcome, roll, sceneIndex, false),
                    CreationMilestones.model(progress, 60, 92, "重写的这一幕", "rewritten scene", language()),
                    clean);
        } catch (RuntimeException | Error e) {
            synchronized (session) {
                session.restructuringJobId = null;
                if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
            }
            throw e;
        }

        synchronized (session) {
            try {
                if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
                if (session.currentScene == null || !baseSceneId.equals(session.currentScene.sceneId())
                        || session.state.stateVersion != baseVersion) {
                    throw new SceneConflictException(
                            "This save changed while the story was being rewritten. Reload and try again.");
                }

                // Back to the moment before the rejected scene. This is also what erases it from
                // the model's memory: recentScenes and recentEvents come back as they were.
                if (parent != null) {
                    restoreNode(session, parent);
                } else {
                    restoreBeforeOpening(session, target);
                }
                // AFTER the restore: restoreNode reloads the story from the node's hash.
                session.story = revisedStory;
                adoptRevisedFramework(session, newBeatId, newThreads);

                // Every prefetched branch and every next-arc plan belongs to a story that no
                // longer exists.
                session.pendingArc = null;
                branchCache.discardAll(sessionId);
                speculative.cancelSession(sessionId);
                assets.planForSession(session);

                commitScene(session, newScene, choice, roll, Map.of());
                session.pendingRoll = null;
                persistSession(session);
                speculative.prefetch(session);
                log.info("Session {}: restructured from {} -- new head {} on beat {} ({} beats ahead, state v{})",
                        sessionId, nodeId, session.currentScene.sceneId(), newBeatId,
                        session.story.spine.beats().size(), session.state.stateVersion);
                return copySession(session);
            } finally {
                session.restructuringJobId = null;
            }
        }
    }

    /** The compiled story as of one node, falling back to the live one when the blob is gone. */
    private CompiledStory storyAt(String sessionId, SceneNode node, CompiledStory fallback) {
        if (tree != null && node != null && node.storyHash != null) {
            var stored = tree.readStory(sessionId, node.storyHash);
            if (stored.isPresent()) return stored.get();
        }
        return mapper.convertValue(fallback, CompiledStory.class);
    }

    /**
     * The one restore with no node to land on: back to before the opening was ever written.
     * Mirrors {@link #restoreNode} minus the scene, which is about to be generated.
     */
    private void restoreBeforeOpening(GameSession session, SceneNode root) {
        GameState restored = root.preState.deepCopy(mapper);
        restored.stateVersion = Math.max(session.state.stateVersion, restored.stateVersion) + 1;
        session.state = restored;
        session.currentScene = null;
        session.sceneDice = new LinkedHashMap<>();
        session.currentNodeId = null;
        session.pendingRoll = null;
        session.pendingArc = null;
        session.continuationEpoch++;
        session.continuationPending = false;
        session.finished = false;
        session.history = new ArrayList<>();
    }

    /**
     * Point the state at the revised spine. The completed beats are still in it by construction,
     * so {@code completedBeats} keeps resolving; the scene about to commit opens the first of the
     * beats that are new.
     */
    private void adoptRevisedFramework(GameSession session, String newBeatId, List<String> newThreads) {
        GameState state = session.state;
        CompiledStory story = session.story;
        state.currentBeatId = newBeatId;
        state.scenesInCurrentBeat = 0;
        state.currentArcTitle = story.spine.arcTitle();
        state.storyProgress.totalBeats = story.spine.beats().size();
        state.storyProgress.beatsCompleted = state.completedBeats.size();
        state.storyProgress.recompute();
        state.addRecentEvent("The story was rewritten from here: " + story.spine.arcTitle());

        // Questions the revision opened. Ids continue the ledger's own sequence.
        int next = state.continuityLedger.size() + 1;
        int added = 0;
        for (String thread : newThreads) {
            if (thread == null || thread.isBlank() || added >= 3) continue;
            state.continuityLedger.add(new ContinuityEntry(
                    String.format("P%03d", next++), thread.trim(), state.currentSceneId));
            added++;
        }
    }

    private SceneNode retainedChild(String sessionId, String parentNodeId, String choiceId, String outcome) {
        if (tree == null) return null;
        return tree.findChild(sessionId, parentNodeId, choiceId, outcome).orElse(null);
    }

    /**
     * Keep finished unused candidates as unvisited children. In-flight ones are still cancelled:
     * they would keep spending tokens for a rewind that may never happen.
     */
    private void persistReadySiblings(GameSession session, BranchKey selected) {
        if (tree == null || session.currentScene == null) return;
        String storyHash = writeStoryQuiet(session);
        if (storyHash == null) return;
        String parentId = session.currentScene.sceneId();
        for (var branch : branchCache.list(session.id)) {
            if (branch.key().equals(selected) || !branch.isReady()) continue;
            if (!parentId.equals(branch.key().sceneId())) continue;
            SceneBundle scene = branch.future().getNow(null);
            if (scene == null) continue;
            String nodeId = SceneNode.preparedId(parentId, branch.key().choiceId(), branch.key().outcome());
            if (tree.readNode(session.id, nodeId).isPresent()) continue;
            Choice sibling = session.currentScene.choice(branch.key().choiceId());
            CheckResult die = sibling != null && sibling.hasCheck() ? session.sceneDice.get(sibling.id()) : null;
            SceneBundle named = scene.withSceneId(nodeId);
            SceneNode node = SceneNode.prepared(parentId, named, branch.key().choiceId(),
                    sibling == null ? null : sibling.text(), die, branch.key().outcome(), storyHash);
            if (branch.diceForNextScene() != null && !branch.diceForNextScene().isEmpty()) {
                node.sceneDice = new LinkedHashMap<>(branch.diceForNextScene());
            }
            try {
                tree.writeNode(session.id, node);
            } catch (RuntimeException e) {
                log.warn("Session {}: could not keep unused branch {}: {}", session.id, nodeId, e.toString());
            }
        }
    }

    private void persistCommittedNode(GameSession session, String parentNodeId, SceneBundle scene,
                                      Choice choice, CheckResult roll, String outcome, GameState preState) {
        scene = uniqueVisitedScene(session, scene);
        session.currentScene = scene;
        session.currentNodeId = scene.sceneId();
        if (tree == null) return;
        String storyHash = writeStoryQuiet(session);
        if (storyHash == null) {
            session.saveHealthy = false;
            return;
        }
        try {
            SceneNode node = SceneNode.visited(parentNodeId, scene,
                    choice == null ? null : choice.id(),
                    choice == null ? null : choice.text(),
                    roll, outcome, session.state.deepCopy(mapper), session.sceneDice, storyHash);
            node.pendingArc = session.pendingArc;
            // Only the root: every other node rewrites itself from its parent's state, but the
            // opening has no parent, so without this it could never be rewritten.
            if (parentNodeId == null) node.preState = preState;
            tree.writeNode(session.id, node);
        } catch (RuntimeException e) {
            session.saveHealthy = false;
            log.warn("Session {}: could not persist scene node {}: {}", session.id, scene.sceneId(), e.toString());
        }
    }

    /**
     * A node id is never reused for a different visit. Crash recovery can leave an orphan
     * {@code scene_NNN} whose counter was not yet saved; minting the same id again would
     * overwrite that node, and the new parent can be the node itself.
     */
    private SceneBundle uniqueVisitedScene(GameSession session, SceneBundle scene) {
        if (tree == null || scene == null || scene.sceneId() == null) return scene;
        SceneNode existing = tree.readNode(session.id, scene.sceneId()).orElse(null);
        if (existing == null || !existing.restorable()) return scene;
        int index = Math.max(session.sceneCounter, 0);
        while (true) {
            String candidate = "scene_%03d".formatted(index);
            SceneNode occupant = tree.readNode(session.id, candidate).orElse(null);
            if (occupant == null || !occupant.restorable()) {
                session.sceneCounter = index + 1;
                log.warn("Session {}: avoided overwriting visited node {} by minting {}",
                        session.id, scene.sceneId(), candidate);
                return scene.withSceneId(candidate);
            }
            index++;
        }
    }

    private void restoreNode(GameSession session, SceneNode node) {
        if (node.storyHash != null && tree != null) {
            tree.readStory(session.id, node.storyHash).ifPresent(story -> session.story = story);
        }
        GameState restored = node.state.deepCopy(mapper);
        int floor = Math.max(session.state.stateVersion, restored.stateVersion);
        restored.stateVersion = floor + 1;
        session.state = restored;
        session.currentScene = node.scene;
        session.sceneDice = node.sceneDice == null ? new LinkedHashMap<>() : new LinkedHashMap<>(node.sceneDice);
        session.currentNodeId = node.nodeId;
        session.pendingRoll = null;
        session.pendingArc = node.pendingArc;
        session.continuationEpoch++;
        session.continuationPending = false;
        session.finished = node.scene == null || node.scene.choices().isEmpty();
        session.history = historyAlong(session.id, node);
        assets.reconcileAfterRestore(session, () -> historicalStories(session.id));
    }

    /** Only consulted for unattributed legacy pictures in the save the player has opened. */
    private List<CompiledStory> historicalStories(String sessionId) {
        if (tree == null) return List.of();
        Set<String> hashes = new HashSet<>();
        List<CompiledStory> stories = new ArrayList<>();
        for (SceneNode node : tree.listNodes(sessionId)) {
            if (node.storyHash != null && hashes.add(node.storyHash)) {
                tree.readStory(sessionId, node.storyHash).ifPresent(stories::add);
            }
        }
        return stories;
    }

    private List<GameSession.HistoryEntry> historyAlong(String sessionId, SceneNode leaf) {
        List<SceneNode> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        SceneNode current = leaf;
        while (current != null && seen.add(current.nodeId)) {
            chain.add(current);
            current = current.parentNodeId == null || tree == null ? null
                    : tree.readNode(sessionId, current.parentNodeId).orElse(null);
        }
        Collections.reverse(chain);
        List<GameSession.HistoryEntry> history = new ArrayList<>();
        for (SceneNode node : chain) {
            GameSession.HistoryEntry entry = new GameSession.HistoryEntry(
                    node.nodeId, node.beatId, node.fromChoiceText,
                    node.roll == null ? null : node.roll.summary(),
                    node.scene == null || node.scene.blocks().isEmpty() ? null
                            : truncate(node.scene.blocks().get(0).text(), 120));
            entry.blocks = node.scene == null ? List.of() : node.scene.blocks();
            if (node.visitedAt != null) entry.at = node.visitedAt;
            else if (node.createdAt != null) entry.at = node.createdAt;
            history.add(entry);
        }
        return history;
    }

    private String writeStoryQuiet(GameSession session) {
        try {
            return tree.writeStory(session.id, session.story);
        } catch (RuntimeException e) {
            log.warn("Session {}: could not persist compiled story: {}", session.id, e.toString());
            return null;
        }
    }

    private static String outcomeOf(CheckResult roll) {
        if (roll == null) return SceneRequest.NONE;
        return roll.success() ? SceneRequest.SUCCESS : SceneRequest.FAILURE;
    }

    private boolean ensureCurrentNode(GameSession session) {
        if (session.currentScene == null) return false;
        if (session.currentNodeId != null) {
            return retryMissingCurrentNode(session);
        }
        if (tree != null) {
            SceneNode existing = tree.readNode(session.id, session.currentScene.sceneId()).orElse(null);
            if (existing != null && existing.restorable()) {
                session.currentNodeId = existing.nodeId;
                return true;
            }
        }
        persistCommittedNode(session, null, session.currentScene, null, null, SceneRequest.NONE, null);
        return true;
    }

    /** Node write failed after currentNodeId was assigned: try again so rewind is not permanently missing. */
    private boolean retryMissingCurrentNode(GameSession session) {
        if (tree == null || session.currentNodeId == null) return false;
        if (tree.readNode(session.id, session.currentNodeId).isPresent()) return false;
        persistCommittedNode(session, null, session.currentScene, null, null, SceneRequest.NONE, null);
        return true;
    }

    /**
     * Raise the counter past every {@code scene_NNN} already on disk. A crash after writing a
     * node and before writing session.json would otherwise mint the same id again.
     */
    private boolean syncSceneCounterFromTree(GameSession session) {
        if (tree == null) return false;
        int floor = session.sceneCounter;
        for (SceneNode node : tree.listNodes(session.id)) {
            Integer index = SceneNode.sequentialIndex(node.nodeId);
            if (index != null) floor = Math.max(floor, index + 1);
        }
        if (floor == session.sceneCounter) return false;
        session.sceneCounter = floor;
        return true;
    }

    /** Write session.json, then keep saveHealthy false when the current rewind point is missing. */
    private void persistSession(GameSession session) {
        repository.save(session);
        if (tree != null && session.currentNodeId != null && tree.readNode(session.id, session.currentNodeId).isEmpty()) {
            session.saveHealthy = false;
        }
    }

    /** Late continuation (or any in-place scene edit) must update the visited node, not only session.json. */
    private void persistCurrentNodeMutation(GameSession session) {
        if (tree == null || session.currentNodeId == null || session.currentScene == null) return;
        SceneNode node = tree.readNode(session.id, session.currentNodeId).orElse(null);
        if (node == null || !node.restorable()) return;
        try {
            node.scene = session.currentScene;
            node.state = session.state.deepCopy(mapper);
            node.sceneDice = session.sceneDice == null ? new LinkedHashMap<>() : new LinkedHashMap<>(session.sceneDice);
            node.pendingArc = session.pendingArc;
            String storyHash = writeStoryQuiet(session);
            if (storyHash != null) node.storyHash = storyHash;
            tree.writeNode(session.id, node);
        } catch (RuntimeException e) {
            session.saveHealthy = false;
            log.warn("Session {}: could not update scene node {}: {}", session.id, session.currentNodeId, e.toString());
        }
    }

    /**
     * Bring one save up to the current dice format and write it back. Run for every save at
     * startup and again, cheaply, whenever a save is first touched; a save that already carries
     * its dice is left alone.
     */
    public boolean migrateSave(String id) {
        GameSession session = require(id, false);
        synchronized (session) {
            boolean diceReady = session.sceneDice != null && !session.sceneDice.isEmpty()
                    || session.currentScene == null || session.currentScene.choices().stream().noneMatch(Choice::hasCheck);
            return diceReady && (session.currentNodeId != null || session.currentScene == null);
        }
    }

    // ------------------------------------------------------------------ lookups

    public GameSession require(String id) {
        return require(id, true);
    }

    private GameSession require(String id, boolean ensurePictures) {
        GameSession session = repository.find(id)
                .orElseThrow(() -> new SessionNotFoundException("No session with id '" + id + "'"));
        synchronized (session) {
            if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
            // Old saves have no scene memory; their current scene still contains recoverable text.
            if ((session.state.recentScenes == null || session.state.recentScenes.isEmpty()) && session.currentScene != null) {
                session.state.rememberScene(session.currentScene.sceneId(), session.currentScene.blocks());
            }
            if (ensureSceneDice(session) | ensureCurrentNode(session) | syncSceneCounterFromTree(session)) {
                // The dice and the current node are now part of the save: write them so a restart
                // cannot recast, reuse a node id, or lose the rewind point.
                persistSession(session);
                log.info("Session {}: brought an older save up to the current scene-tree format", id);
            }
            if (ensurePictures) assets.ensureCurrentSceneAssets(session, () -> historicalStories(session.id));
        }
        return session;
    }

    /** Detach an API response while holding the same lock as commit and file persistence. */
    public GameSession copySession(GameSession session) {
        synchronized (session) {
            GameSession copy = mapper.convertValue(session, GameSession.class);
            copy.saveHealthy = session.saveHealthy;
            copy.continuationPending = session.continuationPending;
            copy.deleted = session.deleted;
            copy.resolvingChoiceId = session.resolvingChoiceId;
            return copy;
        }
    }

    public List<GameSessionRepository.SessionSummary> list() {
        return repository.list();
    }

    public boolean delete(String id) {
        GameSession session = repository.find(id).orElse(null);
        if (session == null) {
            boolean deleted = repository.delete(id); // also supports deleting an unreadable save
            if (deleted) {
                branchCache.discardAll(id);
                speculative.cancelSession(id);
                assets.onSessionDeleted(id);
            }
            return deleted;
        }
        // Commit, branch scheduling and deletion share the same monitor. Delete the repository
        // entry first; if disk deletion fails, the original playable session remains intact.
        synchronized (session) {
            if (session.deleted || !repository.delete(id)) return false;
            session.deleted = true;
            session.resolvingChoiceId = null;
            branchCache.discardAll(id);
            speculative.cancelSession(id);
            assets.onSessionDeleted(id);
            return true;
        }
    }

    public com.genvn.speculation.SpeculativeGenerator.SecondRoundView secondRoundStatus(GameSession session) {
        return speculative.secondRoundStatus(session);
    }

    /** Exposed for tests and the inspector: a snapshot copy that callers may freely mutate. */
    public GameState snapshot(GameSession session) {
        synchronized (session) {
            return session.state.deepCopy(mapper);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
