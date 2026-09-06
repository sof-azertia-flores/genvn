package com.genvn.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.genvn.dice.CheckResult;
import com.genvn.game.GameSession;
import com.genvn.game.GameState;
import com.genvn.narrative.SceneBundle;
import com.genvn.story.CompiledStory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.Map;

/** Request and response shapes for the REST API. */
public final class Dtos {

    private Dtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayerInput(
            @NotBlank @Size(max = 60) String name,
            @Size(max = 400) String background,
            /** Keys are stat names ("Perception", "perception", "PERCEPTION"); values 0-5. */
            Map<String, Integer> stats,
            List<String> traits,
            Integer maxHp,
            @Size(max = 2000) String visualDescription
    ) {
        public PlayerInput(String name, String background, Map<String, Integer> stats, List<String> traits, Integer maxHp) {
            this(name, background, stats, traits, maxHp, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateSessionRequest(
            @NotBlank @Size(max = 6000) String storyOutline,
            @Valid PlayerInput player,
            @Size(max = 6000) String artStyle
    ) {
        public CreateSessionRequest(String storyOutline, PlayerInput player) { this(storyOutline, player, null); }
    }

    public record ChooseRequest(
            @NotBlank String expectedSceneId,
            @NotNull @Min(0) Integer expectedStateVersion
    ) {}

    /** A die already cast on the current scene; the client resumes from it. */
    public record PendingRollView(String choiceId, RollView roll) {
        public static PendingRollView from(GameSession.PendingRoll p) {
            return p == null ? null : new PendingRollView(p.choiceId, RollView.from(p.roll));
        }
    }

    public record RollResponse(String sessionId, String choiceId, RollView roll, boolean reused) {}

    public record SessionView(
            String sessionId,
            String title,
            boolean finished,
            CompiledStory story,
            GameState state,
            SceneBundle scene,
            boolean saveHealthy,
            boolean continuationPending,
            PendingRollView pendingRoll,
            /** Process-local status only; never restored from a save after a restart. */
            String resolvingChoiceId
    ) {}

    public record RollView(
            String stat,
            int d20,
            int modifier,
            int total,
            int dc,
            boolean success,
            boolean critical,
            boolean fumble,
            String summary
    ) {
        public static RollView from(CheckResult r) {
            if (r == null) return null;
            return new RollView(r.statDisplay(), r.d20(), r.statModifier(), r.total(), r.dc(),
                    r.success(), r.critical(), r.fumble(), r.summary());
        }
    }

    public record ChoiceMeta(
            boolean fromSpeculativeCache,
            int discardedBranches,
            long generationMillis,
            List<String> rejectedOps,
            boolean reusedPendingRoll
    ) {}

    public record ChoiceView(
            String sessionId,
            String chosenText,
            RollView roll,
            SceneBundle scene,
            GameState state,
            CompiledStory story,
            boolean finished,
            ChoiceMeta meta,
            boolean saveHealthy,
            boolean continuationPending
    ) {}

    public record ConfigView(
            String llm,
            boolean mockMode,
            boolean speculationEnabled,
            boolean continuationEnabled,
            boolean imageEnabled
    ) {
        public ConfigView(String llm, boolean mockMode, boolean speculationEnabled, boolean continuationEnabled) {
            this(llm, mockMode, speculationEnabled, continuationEnabled, false);
        }
    }

    public record CreationLog(int id, String time, String message) {}

    public record CreationJobView(String id, String status, String stage, int progress,
                                  List<CreationLog> logs, String sessionId, String error) {}

    /**
     * Safe, small queue view: no generated branch prose, prompts or model error bodies, and no
     * hint of how a pre-cast die landed -- only whether the choice involves one.
     */
    public record SpeculationTaskView(String id, String choiceId, String choiceText, boolean check,
                                      String status, Long elapsedMillis, String error) {}

    public record SessionTasksView(String sessionId, String sceneId, boolean speculationEnabled, int speculationConcurrency,
                                   boolean continuationPending, String resolvingChoiceId,
                                   List<SpeculationTaskView> branches,
                                   com.genvn.speculation.SpeculativeGenerator.SecondRoundView secondRound) {}

    public record ErrorView(String error, String message) {}

    /** Whether the server wants an access key, and whether the one sent with this request is right. */
    public record AccessView(boolean required, boolean granted) {}
}
