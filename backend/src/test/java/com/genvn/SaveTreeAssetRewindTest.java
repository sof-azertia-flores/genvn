package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetCoordinator;
import com.genvn.asset.AssetPipeline;
import com.genvn.asset.AssetResolver;
import com.genvn.asset.AssetStatus;
import com.genvn.asset.AssetStore;
import com.genvn.asset.VisualPlanner;
import com.genvn.dice.CheckResolver;
import com.genvn.dice.DiceService;
import com.genvn.game.SessionService;
import com.genvn.game.StateReducer;
import com.genvn.llm.LlmCallLog;
import com.genvn.llm.StructuredLlm;
import com.genvn.narrative.SceneGenerator;
import com.genvn.persistence.FileGameSessionRepository;
import com.genvn.persistence.SceneTreeStore;
import com.genvn.prompt.ContextRenderer;
import com.genvn.speculation.BranchCache;
import com.genvn.speculation.SpeculativeGenerator;
import com.genvn.story.ArcContinuationService;
import com.genvn.story.StoryCompiler;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import com.genvn.support.SceneJson;
import com.genvn.support.ScriptedLlmClient;
import com.genvn.support.ScriptedRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the public save/rewind hooks as well as the lower-level coordinator regressions. */
class SaveTreeAssetRewindTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private record Stack(SessionService sessions, AssetPipeline assets, SpeculativeGenerator speculation)
            implements AutoCloseable {
        @Override public void close() { speculation.close(); assets.shutdown(); }
    }

    private Stack stack(FakeImageProvider fake) {
        var client = new ScriptedLlmClient(mapper, request -> SceneJson.scene("雨停之前，他仍站在原处。")
                .at("loc_threshold").withCharacter("npc_witness", "邻居", "worried", null)
                .choice("wait", "再等一会儿", "social").build());
        var properties = Engine.properties(false, false);
        properties.setDataDir(directory.toString());
        var imageProps = Engine.imageProperties();
        imageProps.setFirstBatchBudget(30);
        imageProps.setArcBudget(100);
        imageProps.getPlan().setExpressions("worried");
        var assets = new AssetPipeline(fake, new AssetStore(mapper, directory.resolve("assets")), imageProps, mapper);
        var coordinator = new AssetCoordinator(assets, new VisualPlanner(imageProps), imageProps);
        var llm = new StructuredLlm(client, mapper, new LlmCallLog());
        var generator = new SceneGenerator(llm, new ContextRenderer(), new AssetResolver(assets));
        var reducer = new StateReducer();
        var cache = new BranchCache();
        var dice = new CheckResolver(new DiceService(new ScriptedRandom(10)));
        var speculative = new SpeculativeGenerator(generator, cache, reducer, mapper, properties, dice);
        var repository = new FileGameSessionRepository(mapper, properties);
        var tree = new SceneTreeStore(mapper, directory.resolve("sessions"));
        speculative.setSceneTree(tree);
        var sessions = new SessionService(new StoryCompiler(llm), generator, reducer, dice, cache, speculative,
                new ArcContinuationService(llm, new ContextRenderer(), properties), repository, mapper,
                coordinator, null, tree);
        return new Stack(sessions, assets, speculative);
    }

    private static void settle(Stack stack, String id) {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            while ((int) stack.assets.status(id).get("pending") > 0 || stack.assets.activeCount() > 0) Thread.sleep(20);
        });
    }

    private static Map<String, Path> art(Stack stack, String id) {
        Map<String, Path> result = new LinkedHashMap<>();
        var manifest = stack.assets.snapshot(id).orElseThrow();
        for (String assetId : new String[]{"pt.player.base", "card.player.default", "pt.player.talking", "pt.player.action",
                "pt.npc_witness.base", "card.npc_witness.default", "pt.npc_witness.worried"}) {
            assertEquals(AssetStatus.READY, manifest.get(assetId).status, assetId);
            result.put(assetId, stack.assets.readyFile(id, assetId).orElseThrow());
        }
        return result;
    }

    @Test void actualSaveTreeRewindAndLaterReloadKeepTheNpcAndPlayerArt() {
        var fake = new FakeImageProvider();
        String sessionId;
        Map<String, Path> files;
        int calls;
        try (Stack stack = stack(fake)) {
            var session = stack.sessions.createWithArtStyle(Engine.OUTLINE, Engine.alex(), "水彩插画，淡蓝色调。");
            sessionId = session.id;
            String opening = session.currentScene.sceneId();
            settle(stack, sessionId);
            files = art(stack, sessionId);
            var after = stack.sessions.choose(sessionId, "wait").session();
            settle(stack, sessionId);
            calls = fake.calls.get();

            var rewound = stack.sessions.rewind(sessionId, opening, after.currentScene.sceneId(), after.state.stateVersion);
            settle(stack, sessionId);
            assertEquals(opening, rewound.currentScene.sceneId());
            assertEquals(files, art(stack, sessionId));
            assertEquals(calls, fake.calls.get(), "the actual rewind path must not redraw established faces");
        }

        try (Stack restarted = stack(fake)) {
            restarted.sessions.require(sessionId);
            restarted.sessions.require(sessionId);
            settle(restarted, sessionId);
            assertEquals(files, art(restarted, sessionId));
            assertEquals(calls, fake.calls.get(), "opening the saved rewind after restart must reuse its artwork");
            files.values().forEach(file -> assertTrue(Files.isRegularFile(file), file.toString()));
        }
    }
}
