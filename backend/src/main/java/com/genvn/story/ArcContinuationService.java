package com.genvn.story;

import com.genvn.config.GenvnProperties;
import com.genvn.game.GameSession;
import com.genvn.game.GameState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.llm.LlmPurpose;
import com.genvn.llm.LlmRequest;
import com.genvn.llm.StructuredLlm;
import com.genvn.prompt.ContextRenderer;
import com.genvn.prompt.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * When the current spine is nearly used up, plan the next arc in the background so the story
 * can keep going instead of stopping. Purely additive: if it never finishes, the game simply
 * ends at the last beat.
 */
@Service
public class ArcContinuationService {

    private static final Logger log = LoggerFactory.getLogger(ArcContinuationService.class);

    private final StructuredLlm llm;
    private final ContextRenderer context;
    private final boolean enabled;
    private final double threshold;
    private final ObjectMapper snapshots = new ObjectMapper();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "genvn-arc-planner");
        t.setDaemon(true);
        return t;
    });

    public ArcContinuationService(StructuredLlm llm, ContextRenderer context, GenvnProperties properties) {
        this.llm = llm;
        this.context = context;
        this.enabled = properties.getContinuation().isEnabled();
        this.threshold = properties.getContinuation().getThreshold();
    }

    public void maybePlanNextArc(GameSession session) {
        maybePlanNextArc(session, () -> {});
    }

    public void maybePlanNextArc(GameSession session, Runnable onReady) {
        final LlmRequest request;
        final int sourceArc;
        synchronized (session) {
            if (!enabled || session.pendingArc != null || session.finished || session.deleted) return;
            if (session.state.storyProgress.fraction < threshold) return;
            if (!inFlight.add(session.id)) return;
            session.continuationPending = true;
            sourceArc = session.state.storyProgress.arcNumber;
            try {
                request = planningRequest(session);
            } catch (RuntimeException e) {
                inFlight.remove(session.id);
                session.continuationPending = false;
                log.warn("Could not snapshot session {} for arc planning", session.id, e);
                return;
            }
        }
        executor.submit(() -> {
            try {
                ArcOutline outline = llm.call(request, ArcOutline.class, ArcContinuationService::validate).value();
                synchronized (session) {
                    if (session.deleted || session.state.storyProgress.arcNumber != sourceArc || session.pendingArc != null) return;
                    session.pendingArc = outline;
                    onReady.run();
                    log.info("Session {}: next arc ready ('{}')", session.id, outline.arcTitle());
                }
            } catch (RuntimeException e) {
                log.info("Session {}: next-arc planning failed, story will end at the last beat ({})",
                        session.id, e.getMessage());
            } finally {
                synchronized (session) {
                    session.continuationPending = false;
                    inFlight.remove(session.id);
                }
            }
        });
    }

    public ArcOutline plan(GameSession session) {
        return llm.call(planningRequest(session), ArcOutline.class, ArcContinuationService::validate).value();
    }

    private LlmRequest planningRequest(GameSession session) {
        final CompiledStory story;
        final GameState state;
        synchronized (session) {
            story = snapshots.convertValue(session.story, CompiledStory.class);
            state = session.state.deepCopy(snapshots);
        }
        return LlmRequest.of(LlmPurpose.ARC_CONTINUE,
                Prompts.ARC_SYSTEM,
                Prompts.arcUser(context.renderStoryFoundation(story, state), context.renderGameState(state, story),
                        context.renderPlayedBeats(story)),
                Map.of("story", story, "state", state));
    }

    /** A continuation is asked for at least this many beats; the prompt asks for 7-10. */
    public static final int MIN_BEATS = 4;

    @jakarta.annotation.PreDestroy
    public void close() { executor.shutdownNow(); }

    static String validate(ArcOutline outline) {
        if (outline == null) return "empty response";
        if (outline.arcTitle() == null || outline.arcTitle().isBlank()) return "missing 'arcTitle'";
        if (outline.beats().isEmpty()) return "missing 'beats' (need at least one)";
        return StorySpine.validateBeats(outline.beats(), MIN_BEATS, true);
    }
}
