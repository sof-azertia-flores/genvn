package com.genvn.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetCoordinator;
import com.genvn.asset.AssetPipeline;
import com.genvn.asset.AssetResolver;
import com.genvn.asset.AssetStore;
import com.genvn.asset.VisualPlanner;
import com.genvn.config.ImageProperties;
import com.genvn.config.GenvnProperties;
import com.genvn.dice.CheckResolver;
import com.genvn.dice.DiceService;
import com.genvn.game.PlayerCharacter;
import com.genvn.game.SessionService;
import com.genvn.game.Stat;
import com.genvn.game.StateReducer;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.LlmClient;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.SceneGenerator;
import com.genvn.prompt.ContextRenderer;
import com.genvn.speculation.BranchCache;
import com.genvn.speculation.SpeculativeGenerator;
import com.genvn.story.ArcContinuationService;
import com.genvn.story.SpareDesignService;
import com.genvn.story.StoryCompiler;

/** Wires the real services by hand so tests exercise production code without a Spring context. */
public class Engine {

    public final ObjectMapper mapper = new ObjectMapper();
    public final BranchCache branchCache = new BranchCache();
    public final StateReducer reducer = new StateReducer();
    public final InMemoryGameSessionRepository repository = new InMemoryGameSessionRepository();
    public final SessionService sessions;
    public final SceneGenerator sceneGenerator;
    public final StructuredLlm llm;
    /** Present only when the engine was built with a picture pipeline. */
    public final AssetPipeline assets;
    public final FakeImageProvider images;
    /** Off by default in tests (target 0); a test opts in with {@code spareDesigns.setTarget(n)}. */
    public final SpareDesignService spareDesigns;

    public Engine(LlmClient client, ScriptedRandom random, boolean speculationEnabled, boolean continuationEnabled) {
        this(client, random, speculationEnabled, continuationEnabled, null, null, null);
    }

    /**
     * With pictures: a real AssetPipeline over {@code assetDir}, driven by the fake provider, so
     * tests exercise the production scheduler, store, resolver and coordinator end to end.
     */
    public Engine(LlmClient client, ScriptedRandom random, boolean speculationEnabled, boolean continuationEnabled,
                  FakeImageProvider images, ImageProperties imageProps, java.nio.file.Path assetDir) {
        this.llm = new StructuredLlm(client, mapper, new LlmCallLog());
        this.images = images;
        AssetCoordinator coordinator;
        if (images != null) {
            AssetStore store = new AssetStore(mapper, assetDir);
            this.assets = new AssetPipeline(images, store, imageProps, mapper);
            this.sceneGenerator = new SceneGenerator(llm, new ContextRenderer(), new AssetResolver(assets));
            coordinator = new AssetCoordinator(assets, new VisualPlanner(imageProps), imageProps);
        } else {
            this.assets = null;
            this.sceneGenerator = new SceneGenerator(llm, new ContextRenderer());
            coordinator = AssetCoordinator.disabled();
        }
        StoryCompiler compiler = new StoryCompiler(llm);
        CheckResolver checkResolver = new CheckResolver(new DiceService(random));
        GenvnProperties properties = properties(speculationEnabled, continuationEnabled);
        // The same scripted die feeds commit-time and branch-time casts.
        SpeculativeGenerator speculative = new SpeculativeGenerator(
                sceneGenerator, branchCache, reducer, mapper, properties, checkResolver);
        ArcContinuationService arcs = new ArcContinuationService(llm, new ContextRenderer(), properties);
        this.spareDesigns = new SpareDesignService(llm, properties, repository, coordinator, mapper);
        this.sessions = new SessionService(compiler, sceneGenerator, reducer, checkResolver,
                branchCache, speculative, arcs, repository, mapper, coordinator, spareDesigns);
    }

    /** Image settings for a test: enabled, small concurrency, generous budget unless overridden. */
    public static ImageProperties imageProperties() {
        ImageProperties p = new ImageProperties();
        p.setEnabled(true);
        p.setApiKey("test");
        p.setConcurrency(2);
        p.setMaxAttempts(2);
        p.setFirstBatchBudget(12);
        p.setArcBudget(24);
        return p;
    }

    /** Waits until no picture is queued or generating (or gives up after ~10s). */
    public void awaitAssets(String sessionId) {
        if (assets == null) return;
        for (int i = 0; i < 400; i++) {
            var st = assets.status(sessionId);
            if ((int) st.get("pending") == 0 && assets.activeCount() == 0) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Engine settings for a test: same defaults as the shipped config file, with a wider prefetch. */
    public static GenvnProperties properties(boolean speculationEnabled, boolean continuationEnabled) {
        GenvnProperties properties = new GenvnProperties();
        properties.setSpareDesigns(0); // background sketching is opt-in per test
        properties.getSpeculation().setEnabled(speculationEnabled);
        properties.getSpeculation().setMaxBranches(8);
        properties.getContinuation().setEnabled(continuationEnabled);
        return properties;
    }

    public static PlayerCharacter alex() {
        PlayerCharacter p = new PlayerCharacter();
        p.name = "Alex";
        p.background = "an archivist";
        p.stats.put(Stat.BODY, 2);
        p.stats.put(Stat.AGILITY, 2);
        p.stats.put(Stat.PERCEPTION, 4);
        p.stats.put(Stat.INTELLECT, 3);
        p.stats.put(Stat.WILL, 3);
        p.stats.put(Stat.PRESENCE, 1);
        p.hp = 10;
        p.maxHp = 10;
        return p;
    }

    public static final String OUTLINE = """
            I came to an old house that has stood empty for years, looking for the diary my
            grandfather left. Before he vanished he wrote that he had hidden something here that
            must not fall into anyone else's hands. The only neighbour warned me not to go up to
            the second floor after midnight, because every midnight there are footsteps up there.
            """;

    /** Waits until every speculative branch for a session has finished generating. */
    public void awaitBranches(String sessionId) {
        for (int i = 0; i < 200; i++) {
            var rows = branchCache.status(sessionId);
            if (!rows.isEmpty() && rows.stream().noneMatch(r -> "generating".equals(r.get("status"))
                    || "queued".equals(r.get("status")))) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
