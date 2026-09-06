package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genvn.api.Dtos;
import com.genvn.api.SessionController;
import com.genvn.asset.*;
import com.genvn.config.GenvnProperties;
import com.genvn.game.GameSession;
import com.genvn.game.PlayerCharacter;
import com.genvn.llm.LlmCallLog;
import com.genvn.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerVisualStyleTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final String artStyle = "日系赛璐璐风格，清晰黑色线稿、蓝紫色调、平涂阴影。";
    final String appearance = "银色短发，深绿色眼睛，左眼下有一颗痣，白色风衣与黑色手套。";

    private String script(boolean following) {
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(SceneJson.scene("房间里传来脚步声。")
                    .at("loc_threshold").choice("speak", "请告诉我钥匙在哪里。", "social").build());
            var choice = (ObjectNode) node.withArray("choices").get(0);
            choice.put("actionKind", "dialogue");
            choice.put("playerExpression", "talking");
            ObjectNode invented = node.withArray("blocks").addObject();
            invented.put("type", "dialogue").put("speakerId", "player").put("speakerName", "Wrong name")
                    .put("text", following ? "我决定永远相信你。" : "我同意交出全部财产。");
            return node.toString();
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }

    @Test void suppliedAppearanceAndStyleReachBothGenerationAndReferenceEditsAndSurviveReload() throws Exception {
        var client = new ScriptedLlmClient(mapper, r -> script(ScriptedLlmClient.choiceOf(r) != null));
        var props = Engine.imageProperties();
        props.getPlan().setCharacters(0);
        props.getPlan().setLocations(0);
        props.setFirstBatchBudget(4);
        var fake = new FakeImageProvider();
        var engine = new Engine(client, new ScriptedRandom(10), false, false, fake, props, directory);
        try {
            var player = Engine.alex(); player.visualDescription = appearance;
            var session = engine.sessions.createWithArtStyle(Engine.OUTLINE, player, artStyle);
            engine.awaitAssets(session.id);
            var manifest = engine.assets.snapshot(session.id).orElseThrow();
            var playerRecords = manifest.records.values().stream().filter(r -> "player".equals(r.spec.subjectId())).toList();
            assertEquals(List.of("pt.player.base", "card.player.default", "pt.player.talking", "pt.player.action"),
                    playerRecords.stream().map(r -> r.spec.assetId()).toList());
            assertEquals(4, playerRecords.stream().mapToInt(r -> r.attempts).sum());
            var playerPrompts = playerRecords.stream().map(r -> r.spec.prompt()).collect(java.util.stream.Collectors.toSet());
            List<ImageRequest> playerGenerations;
            List<ImageEditRequest> playerEdits;
            synchronized (fake.generations) { playerGenerations = fake.generations.stream().filter(r -> playerPrompts.contains(r.prompt())).toList(); }
            synchronized (fake.edits) { playerEdits = fake.edits.stream().filter(r -> playerPrompts.contains(r.prompt())).toList(); }
            assertEquals(1, playerGenerations.size());
            assertEquals(3, playerEdits.size());
            assertFalse(session.state.characters.containsKey("player"));
            assertNull(session.story.bible.character("player"));
            assertEquals("Alex", session.story.playerVisual.name());
            assertEquals(appearance, session.state.player.visualDescription);
            assertTrue(playerGenerations.get(0).prompt().contains(appearance));
            assertTrue(playerGenerations.get(0).prompt().contains(artStyle));
            assertFalse(playerGenerations.get(0).prompt().contains("painterly digital art"));
            byte[] base = Files.readAllBytes(engine.assets.readyFile(session.id, "pt.player.base").orElseThrow());
            assertTrue(AssetStore.hasTransparentContent(base));
            for (var edit : playerEdits) {
                boolean card = edit.prompt().contains("permanent character card");
                // The card is painted with the art direction; a transparent pose edit deliberately
                // carries neither the style text nor the appearance: the reference already does, and
                // a long art-direction text made a gpt-image route return opaque RGB poses.
                assertEquals(card, edit.prompt().contains(artStyle));
                if (!card) assertFalse(edit.prompt().contains(appearance));
                assertArrayEquals(base, edit.referenceImage());
                assertEquals(!card, edit.transparentBackground());
                if (!card) assertTrue(edit.prompt().startsWith("Same character as the reference;"));
            }
            var restored = mapper.readValue(mapper.writeValueAsBytes(session), GameSession.class);
            assertEquals(artStyle, restored.story.artStyle);
            assertEquals(appearance, restored.state.player.visualDescription);
            var planner = new VisualPlanner(props);
            var later = planner.plan(restored.story, restored.state, manifest.style);
            assertTrue(later.specs().stream().filter(s -> s.kind() != AssetKind.PORTRAIT_VARIANT)
                    .allMatch(s -> s.prompt().contains(artStyle)));
            assertTrue(later.specs().stream().filter(s -> s.kind() == AssetKind.PORTRAIT_VARIANT)
                    .noneMatch(s -> s.prompt().contains(artStyle)));
            var dynamic = planner.fromRequest(restored.story,
                    new AssetRequest("portrait", "player", "examining", "查看钥匙"), later.style(), later.styleKey(), "next");
            assertEquals("pt.player.base", dynamic.dependsOn());
            assertTrue(dynamic.prompt().contains("examining"));
            assertFalse(dynamic.prompt().contains(artStyle), "a pose edit relies on its reference for the look");
        } finally { engine.assets.shutdown(); }
    }

    @Test void playerBaseAndPermanentCardHavePriorityEvenWithOnlyTwoFirstBatchSlots() throws Exception {
        var props = Engine.imageProperties();
        props.setFirstBatchBudget(2);
        var compilerEngine = new Engine(new com.genvn.llm.MockLlmClient(mapper), new ScriptedRandom(10), false, false);
        var session = compilerEngine.sessions.createWithArtStyle(Engine.OUTLINE, Engine.alex(), artStyle);
        var fake = new FakeImageProvider();
        var pipeline = new AssetPipeline(fake, new AssetStore(mapper, directory), props, mapper);
        try {
            pipeline.adopt(session.id, new VisualPlanner(props).plan(session.story, session.state, null));
            for (int i = 0; i < 200 && (pipeline.activeCount() > 0 || (int) pipeline.status(session.id).get("pending") > 0); i++) Thread.sleep(25);
            var manifest = pipeline.snapshot(session.id).orElseThrow();
            assertEquals(AssetStatus.READY, manifest.get("pt.player.base").status);
            assertEquals(AssetStatus.READY, manifest.get("card.player.default").status);
            assertEquals(AssetStatus.PLANNED, manifest.get("pt.player.talking").status);
            assertEquals(2, manifest.records.values().stream().filter(r -> !r.spec.idlePreparation()).mapToInt(r -> r.attempts).sum());
            assertEquals(2, manifest.budget.firstBatchQueued);
        } finally { pipeline.shutdown(); }
    }

    @Test void runtimeOnlySpeaksTheChosenPlayerLineAndNeverInventsTheirDecision() {
        var client = new ScriptedLlmClient(mapper, r -> script(ScriptedLlmClient.choiceOf(r) != null));
        var engine = new Engine(client, new ScriptedRandom(10), false, false);
        var session = engine.sessions.createWithArtStyle(Engine.OUTLINE, Engine.alex(), artStyle);
        assertTrue(session.currentScene.blocks().stream().noneMatch(b -> "player".equals(b.speakerId())));
        var choice = session.currentScene.choices().get(0);
        assertEquals("dialogue", choice.actionKind());
        var after = engine.sessions.choose(session.id, choice.id(), session.currentScene.sceneId(), session.state.stateVersion).session();
        var spoken = after.currentScene.blocks().stream().filter(b -> "player".equals(b.speakerId())).toList();
        assertEquals(1, spoken.size());
        assertEquals(choice.text(), spoken.get(0).text());
        assertEquals("Alex", spoken.get(0).speakerName());
        assertTrue(after.currentScene.blocks().stream().noneMatch(b -> b.text().contains("永远相信")));
        assertEquals("talking", after.currentScene.characters().stream().filter(c -> "player".equals(c.characterId())).findFirst().orElseThrow().expression());
        assertFalse(after.state.characters.containsKey("player"));
    }

    @Test void syncApiPersistsUserVisualFieldsAndReportsImageSettingWithoutKeys() {
        var engine = new Engine(new ScriptedLlmClient(mapper, r -> script(false)), new ScriptedRandom(10), false, false);
        var props = Engine.imageProperties();
        var controller = new SessionController(engine.sessions, engine.branchCache, new LlmCallLog(), engine.llm, new GenvnProperties(), props);
        assertTrue(controller.config().imageEnabled());
        var input = new Dtos.PlayerInput("Alex", "档案员", Map.of(), List.of("谨慎"), 10, appearance);
        var view = controller.create(new Dtos.CreateSessionRequest(Engine.OUTLINE, input, artStyle));
        assertEquals(artStyle, view.story().artStyle);
        assertEquals(appearance, view.state().player.visualDescription);
        assertEquals(PlayerCharacter.ID, view.state().player.id);
    }

    @Test void actionChoicePreservesItsPoseWithoutBeingConvertedToSpeech() {
        var client = new ScriptedLlmClient(mapper, r -> SceneJson.scene("你查看了房门。")
                .choice("go", "检查锁孔", "investigation").build()
                .replace("\"check\":null", "\"check\":null,\"actionKind\":\"action\",\"playerExpression\":\"examining\""));
        var props = Engine.imageProperties(); props.getPlan().setCharacters(0); props.getPlan().setLocations(0);
        var fake = new FakeImageProvider();
        var engine = new Engine(client, new ScriptedRandom(10), false, false, fake, props, directory);
        try {
            var session = engine.sessions.createWithArtStyle(Engine.OUTLINE, Engine.alex(), artStyle);
            var result = engine.sessions.choose(session.id, "go", session.currentScene.sceneId(), session.state.stateVersion).session();
            engine.awaitAssets(session.id);
            var player = result.currentScene.characters().stream().filter(c -> "player".equals(c.characterId())).findFirst().orElseThrow();
            assertEquals("pt.player.examining", player.assetId());
            assertTrue(result.currentScene.blocks().stream().noneMatch(b -> "player".equals(b.speakerId())));
            var manifest = engine.assets.snapshot(session.id).orElseThrow();
            assertEquals(AssetStatus.READY, manifest.get(player.assetId()).status);
            assertEquals(1, manifest.records.values().stream().filter(r -> r.spec.kind() == AssetKind.CHARACTER_CARD
                    && "player".equals(r.spec.subjectId())).count());
        } finally { engine.assets.shutdown(); }
    }
}
