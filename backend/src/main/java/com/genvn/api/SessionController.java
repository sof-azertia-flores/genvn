package com.genvn.api;

import com.genvn.config.GenvnProperties;
import com.genvn.config.ImageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import com.genvn.game.GameSession;
import com.genvn.game.PlayerCharacter;
import com.genvn.game.SessionService;
import com.genvn.game.Stat;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.StructuredLlm;
import com.genvn.persistence.GameSessionRepository;
import com.genvn.speculation.BranchCache;
import com.genvn.speculation.BranchKey;
import com.genvn.narrative.SceneRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The browser never owns game state: it posts a choice and is told what became true.
 */
@RestController
@RequestMapping("/api")
public class SessionController {

    private final SessionService sessions;
    private final BranchCache branchCache;
    private final LlmCallLog callLog;
    private final StructuredLlm llm;
    private final GenvnProperties properties;
    private final ImageProperties imageProperties;

    public SessionController(SessionService sessions, BranchCache branchCache, LlmCallLog callLog,
                             StructuredLlm llm, GenvnProperties properties) {
        this(sessions, branchCache, callLog, llm, properties, new ImageProperties());
    }

    @Autowired
    public SessionController(SessionService sessions, BranchCache branchCache, LlmCallLog callLog,
                             StructuredLlm llm, GenvnProperties properties, ImageProperties imageProperties) {
        this.sessions = sessions;
        this.branchCache = branchCache;
        this.callLog = callLog;
        this.llm = llm;
        this.properties = properties;
        this.imageProperties = imageProperties;
    }

    @GetMapping("/config")
    public Dtos.ConfigView config() {
        return new Dtos.ConfigView(llm.describeClient(), llm.usingMock(),
                properties.getSpeculation().isEnabled(), properties.getContinuation().isEnabled(),
                imageProperties.isEnabled());
    }

    @PostMapping("/sessions")
    public Dtos.SessionView create(@Valid @RequestBody Dtos.CreateSessionRequest request) {
        GameSession session = sessions.createWithArtStyle(request.storyOutline(), toPlayer(request.player()), request.artStyle());
        return view(session);
    }

    @GetMapping("/sessions")
    public List<GameSessionRepository.SessionSummary> list() {
        return sessions.list();
    }

    @GetMapping("/sessions/{id}")
    public Dtos.SessionView get(@PathVariable String id) {
        return view(sessions.require(id));
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("deleted", sessions.delete(id));
    }

    /**
     * Cast the die for a checked choice and return it immediately, before any scene exists.
     * Idempotent: calling it again for the same choice returns the same, persisted roll.
     */
    @PostMapping("/sessions/{id}/choices/{choiceId}/roll")
    public Dtos.RollResponse roll(@PathVariable String id, @PathVariable String choiceId,
                                  @Valid @RequestBody Dtos.ChooseRequest request) {
        SessionService.RollOutcome outcome = sessions.roll(id, choiceId,
                request.expectedSceneId(), request.expectedStateVersion());
        return new Dtos.RollResponse(outcome.session().id, outcome.choiceId(),
                Dtos.RollView.from(outcome.roll()), outcome.reused());
    }

    @PostMapping("/sessions/{id}/choices/{choiceId}")
    public Dtos.ChoiceView choose(@PathVariable String id, @PathVariable String choiceId,
                                 @Valid @RequestBody Dtos.ChooseRequest request) {
        SessionService.ChoiceOutcome outcome = sessions.choose(id, choiceId,
                request.expectedSceneId(), request.expectedStateVersion());
        GameSession session = outcome.session();
        return new Dtos.ChoiceView(
                session.id,
                outcome.chosenText(),
                Dtos.RollView.from(outcome.roll()),
                session.currentScene,
                session.state,
                session.story,
                session.finished,
                new Dtos.ChoiceMeta(outcome.fromSpeculativeCache(), outcome.discardedBranches(),
                        outcome.generationMillis(), outcome.rejectedOps(), outcome.reusedPendingRoll()),
                session.saveHealthy, session.continuationPending);
    }

    /**
     * Restore a previously visited scene as the head. Dice on that scene stay sealed; the player
     * changes the story by picking a different choice, not by rolling again.
     */
    @PostMapping("/sessions/{id}/nodes/{nodeId}/rewind")
    public Dtos.SessionView rewind(@PathVariable String id, @PathVariable String nodeId,
                                   @Valid @RequestBody Dtos.ChooseRequest request) {
        return view(sessions.rewind(id, nodeId, request.expectedSceneId(), request.expectedStateVersion()));
    }

    /** Everything the dev inspector needs, in one call. */
    @GetMapping("/sessions/{id}/debug")
    public Map<String, Object> debug(@PathVariable String id) {
        GameSession session = sessions.copySession(sessions.require(id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.id);
        out.put("finished", session.finished);
        out.put("saveHealthy", session.saveHealthy);
        out.put("continuationPending", session.continuationPending);
        out.put("sceneCounter", session.sceneCounter);
        out.put("state", session.state);
        out.put("authorCanon", session.story.authorCanon);
        out.put("spine", session.story.spine);
        out.put("bible", session.story.bible);
        out.put("currentScene", session.currentScene);
        out.put("currentNodeId", session.currentNodeId);
        out.put("history", session.history);
        out.put("pendingArc", session.pendingArc);
        out.put("pendingRoll", session.pendingRoll);
        out.put("sceneDice", session.sceneDice);
        out.put("resolvingChoiceId", session.resolvingChoiceId);
        out.put("speculativeBranches", branchCache.status(session.id));
        out.put("branchCount", branchCache.size(session.id));
        out.put("llmCalls", callLog.recent());
        out.put("llmClient", llm.describeClient());
        return out;
    }

    /** A player-facing queue: only current choice labels and operational status, no spoilers. */
    @GetMapping("/sessions/{id}/tasks")
    public Dtos.SessionTasksView tasks(@PathVariable String id) {
        GameSession session = sessions.require(id);
        synchronized (session) {
            if (session.deleted) throw new SessionNotFoundException("This session has been deleted.");
            List<Dtos.SpeculationTaskView> rows = new ArrayList<>();
            var scene = session.currentScene;
            if (scene != null) {
                for (var choice : scene.choices()) {
                    // One branch per choice: the die is already cast, so only its outcome is being
                    // written. The row deliberately says nothing about which outcome that is.
                    String outcome = com.genvn.speculation.SpeculativeGenerator.outcomeFor(choice, session.sceneDice);
                    if (outcome == null) continue;
                    BranchKey key = new BranchKey(scene.sceneId(), choice.id(), outcome);
                    var branch = branchCache.get(id, key);
                    if (branch == null || branch.baseStateVersion() != session.state.stateVersion) continue;
                    String status = branch.status();
                    Long elapsed = "generating".equals(status)
                            ? Long.valueOf(Math.max(0, System.currentTimeMillis() - branch.executionStartedAtMillis().get()))
                            : "queued".equals(status) ? Long.valueOf(0) : null;
                    rows.add(new Dtos.SpeculationTaskView(scene.sceneId() + "::" + choice.id(), choice.id(), choice.text(),
                            choice.hasCheck(), status, elapsed,
                            "failed".equals(status) ? "这条预推演未完成，选择后会重新准备。" : null));
                }
            }
            return new Dtos.SessionTasksView(id, scene == null ? null : scene.sceneId(),
                    properties.getSpeculation().isEnabled(),
                    Math.max(1, properties.getSpeculation().getThreads()),
                    session.continuationPending, session.resolvingChoiceId, rows, sessions.secondRoundStatus(session));
        }
    }

    private Dtos.SessionView view(GameSession session) {
        GameSession copy = sessions.copySession(session);
        return new Dtos.SessionView(copy.id, copy.title, copy.finished,
                copy.story, copy.state, copy.currentScene, copy.saveHealthy, copy.continuationPending,
                Dtos.PendingRollView.from(copy.pendingRoll), copy.resolvingChoiceId);
    }

    /** A trait is a phrase, not an essay: it is repeated in every prompt and every save. */
    static final int MAX_TRAIT_CHARS = 80;

    static PlayerCharacter toPlayer(Dtos.PlayerInput input) {
        PlayerCharacter player = new PlayerCharacter();
        if (input == null) return player;
        if (input.name() != null && !input.name().isBlank()) player.name = input.name().trim();
        if (input.background() != null) player.background = input.background().trim();
        if (input.visualDescription() != null) player.visualDescription = input.visualDescription().trim();
        if (input.stats() != null) {
            for (Map.Entry<String, Integer> e : input.stats().entrySet()) {
                Stat stat = Stat.fromLoose(e.getKey());
                if (stat == null || e.getValue() == null) continue;
                player.stats.put(stat, Math.max(Stat.MIN, Math.min(Stat.MAX, e.getValue())));
            }
        }
        if (input.traits() != null) {
            input.traits().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(String::trim)
                    .map(t -> t.length() > MAX_TRAIT_CHARS ? t.substring(0, MAX_TRAIT_CHARS) : t)
                    .limit(6)
                    .forEach(player.traits::add);
        }
        int maxHp = input.maxHp() == null ? 10 : Math.max(1, Math.min(50, input.maxHp()));
        player.maxHp = maxHp;
        player.hp = maxHp;
        return player;
    }
}
