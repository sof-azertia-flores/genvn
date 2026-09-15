package com.genvn.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetCoordinator;
import com.genvn.config.GenvnProperties;
import com.genvn.dice.CheckResolver;
import com.genvn.dice.DiceService;
import com.genvn.game.SessionService;
import com.genvn.game.StateReducer;
import com.genvn.game.StoryRestructureService;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.LlmClient;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.SceneGenerator;
import com.genvn.persistence.FileGameSessionRepository;
import com.genvn.persistence.SceneTreeStore;
import com.genvn.prompt.ContextRenderer;
import com.genvn.speculation.BranchCache;
import com.genvn.speculation.SpeculativeGenerator;
import com.genvn.story.ArcContinuationService;
import com.genvn.story.StoryCompiler;
import com.genvn.story.StoryRestructurePlanner;

import java.nio.file.Path;
import java.util.random.RandomGenerator;

/**
 * {@link Engine} with the parts a scene tree needs: real files on disk, a {@link SceneTreeStore},
 * and the restructure planner and job registry. Everything else is wired the same way.
 */
public class TreeEngine implements AutoCloseable {

    public final ObjectMapper mapper = new ObjectMapper();
    public final SessionService sessions;
    public final FileGameSessionRepository repository;
    public final SceneTreeStore tree;
    public final SpeculativeGenerator speculative;
    public final StoryRestructureService restructures;
    public final StoryRestructurePlanner planner;
    public final BranchCache cache = new BranchCache();
    public final LlmClient client;
    public final Path dataDir;

    public TreeEngine(Path dataDir, LlmClient client) {
        this(dataDir, client, new ScriptedRandom(), false, false);
    }

    public TreeEngine(Path dataDir, LlmClient client, RandomGenerator dice,
                      boolean speculation, boolean continuation) {
        this.dataDir = dataDir;
        this.client = client;
        GenvnProperties properties = Engine.properties(speculation, continuation);
        properties.setDataDir(dataDir.toString());
        StructuredLlm llm = new StructuredLlm(client, mapper, new LlmCallLog());
        SceneGenerator generator = new SceneGenerator(llm, new ContextRenderer());
        StateReducer reducer = new StateReducer();
        CheckResolver resolver = new CheckResolver(new DiceService(dice));
        this.speculative = new SpeculativeGenerator(generator, cache, reducer, mapper, properties, resolver);
        this.repository = new FileGameSessionRepository(mapper, properties);
        this.tree = new SceneTreeStore(mapper, dataDir.resolve("sessions"));
        this.speculative.setSceneTree(tree);
        this.planner = new StoryRestructurePlanner(llm, new ContextRenderer(), mapper);
        this.sessions = new SessionService(new StoryCompiler(llm), generator, reducer, resolver, cache, speculative,
                new ArcContinuationService(llm, new ContextRenderer(), properties), repository, mapper,
                AssetCoordinator.disabled(), null, tree, planner);
        this.restructures = new StoryRestructureService(sessions);
    }

    @Override
    public void close() {
        speculative.close();
        restructures.close();
    }
}
