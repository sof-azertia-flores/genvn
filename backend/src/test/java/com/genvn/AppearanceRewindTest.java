package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AppearanceIdentity;
import com.genvn.asset.AssetCoordinator;
import com.genvn.asset.AssetKind;
import com.genvn.asset.AssetManifest;
import com.genvn.asset.AssetPipeline;
import com.genvn.asset.AssetRecord;
import com.genvn.asset.AssetRequest;
import com.genvn.asset.AssetSpec;
import com.genvn.asset.AssetStatus;
import com.genvn.asset.AssetStore;
import com.genvn.asset.ImageProviderException;
import com.genvn.asset.VisualPlanner;
import com.genvn.config.ImageProperties;
import com.genvn.game.GameSession;
import com.genvn.game.GameState;
import com.genvn.narrative.Block;
import com.genvn.narrative.CharacterPresence;
import com.genvn.narrative.SceneBundle;
import com.genvn.narrative.SceneLocation;
import com.genvn.story.CompiledStory;
import com.genvn.story.LocationProfile;
import com.genvn.story.NpcProfile;
import com.genvn.story.PreparedVisual;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Rewind exercises real local PNG publication and scheduling, without a model or personal save. */
class AppearanceRewindTest {
    private static final String SID = "appearance_rewind";
    private static final String STYLE = "清晰线稿，低饱和水彩。";
    private static final String LOOK_A = "银色短发，墨绿色外套，左眼下有一颗痣。";
    private static final String LOOK_B = "红色长发，白色衬衫，圆框眼镜。";
    private static final String NPC = "npc_0";
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ImageProperties props;
    private FakeImageProvider fake;
    private AssetStore store;
    private AssetPipeline pipeline;
    private VisualPlanner planner;
    private AssetCoordinator coordinator;

    @BeforeEach void setUp() {
        props = Engine.imageProperties();
        props.setArcBudget(200);
        props.setFirstBatchBudget(20);
        props.getPlan().setLocations(0);
        props.getPlan().setCharacters(1);
        props.getPlan().setExpressions("worried");
        fake = new FakeImageProvider();
        store = new AssetStore(mapper, directory);
        planner = new VisualPlanner(props);
        start();
    }

    @AfterEach void tearDown() {
        if (pipeline != null) pipeline.shutdown();
    }

    private void start() {
        pipeline = new AssetPipeline(fake, store, props, mapper);
        coordinator = new AssetCoordinator(pipeline, planner, props);
    }

    private CompiledStory story(String look) {
        CompiledStory story = VisualPlannerTest.story(0, 0);
        story.artStyle = STYLE;
        story.playerVisual = npc("player", "玩家", "蓝色短发，黑色外套。");
        if (look != null) story.registerCharacter(npc(NPC, "林静", look));
        return story;
    }

    private static NpcProfile npc(String id, String name, String look) {
        return new NpcProfile(id, name, "一个过路人", "谨慎", List.of("找人"), List.of(),
                "轻声说话", "初次相遇", look);
    }

    private GameSession session(CompiledStory story) {
        GameSession session = new GameSession();
        session.id = SID;
        session.story = story;
        session.state = new GameState();
        session.state.sessionId = SID;
        session.state.player = Engine.alex();
        session.state.currentBeatId = "b1";
        session.currentScene = scene(NPC, "worried", "worried");
        return session;
    }

    private SceneBundle scene(String subject, String presencePose, String dialoguePose) {
        return new SceneBundle("scene_001", "b1", null,
                subject == null ? List.of() : List.of(new CharacterPresence(subject, "林静", presencePose,
                        "right", "", AssetSpec.portraitId(subject, presencePose))),
                subject == null ? List.of(Block.narration("窗外的雨停了。"))
                        : List.of(Block.dialogue(subject, "林静", "我们再等等。", dialoguePose)),
                List.of(), null, "", List.of(), null);
    }

    private void settle() {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
            while ((int) pipeline.status(SID).get("pending") > 0 || pipeline.activeCount() > 0) {
                Thread.sleep(20);
            }
        });
    }

    private AssetManifest manifest() { return pipeline.snapshot(SID).orElseThrow(); }

    private Map<String, String> readyNames() {
        Map<String, String> names = new LinkedHashMap<>();
        manifest().records.forEach((id, r) -> {
            assertEquals(AssetStatus.READY, r.status, id);
            assertNotNull(r.spec.appearanceKey(), id);
            names.put(id, r.fileName);
        });
        return names;
    }

    private void assertFilesRemain(Map<String, String> names) {
        names.forEach((id, name) -> assertTrue(store.validate(SID, name), id + " original PNG must remain"));
    }

    @Test void unchangedAppearanceSurvivesRewindAndNarrativeRewordingWithoutCalls() {
        GameSession session = session(story(LOOK_A));
        coordinator.ensureCurrentSceneAssets(session);
        settle();
        Map<String, String> before = readyNames();
        assertTrue(before.keySet().containsAll(List.of("pt.npc_0.base", "card.npc_0.default", "pt.npc_0.worried",
                "pt.player.base", "card.player.default")));
        int calls = fake.calls.get();

        CompiledStory changedText = story(null);
        changedText.registerCharacter(new NpcProfile(NPC, "新的称呼", "后来成为侦探", "开朗", List.of("调查"),
                List.of("新的秘密"), "直率", "成为朋友", LOOK_A));
        session.story = changedText;
        coordinator.reconcileAfterRestore(session);
        coordinator.ensureCurrentSceneAssets(session);
        coordinator.ensureCurrentSceneAssets(session);
        settle();

        assertEquals(before, readyNames(), "card and terse reference-based pose prompts keep their original files");
        assertFilesRemain(before);
        assertEquals(calls, fake.calls.get(), "name, personality, story and repeated checks do not redraw");
    }

    @Test void bothAppearancesReturnToTheirOwnFilesAcrossRewindsAndRestart() {
        CompiledStory a = story(LOOK_A);
        CompiledStory b = story(LOOK_B);
        GameSession session = session(a);
        coordinator.ensureCurrentSceneAssets(session);
        settle();
        Map<String, String> aFiles = readyNames();
        String aKey = manifest().get("pt.npc_0.base").spec.appearanceKey();

        session.story = b;
        coordinator.reconcileAfterRestore(session);
        settle();
        Map<String, String> bFiles = readyNames();
        assertNotEquals(aKey, manifest().get("pt.npc_0.base").spec.appearanceKey());
        for (String id : List.of("pt.npc_0.base", "card.npc_0.default", "pt.npc_0.worried")) {
            assertNotEquals(aFiles.get(id), bFiles.get(id), "the second appearance has a separate PNG: " + id);
        }
        assertEquals(aFiles.get("card.player.default"), bFiles.get("card.player.default"));
        assertFilesRemain(aFiles);
        assertFilesRemain(bFiles);
        int calls = fake.calls.get();

        pipeline.shutdown();
        start();
        for (CompiledStory next : List.of(a, b, a)) {
            session.story = next;
            coordinator.reconcileAfterRestore(session);
            settle();
            assertEquals(next == a ? aFiles : bFiles, readyNames());
            assertEquals(calls, fake.calls.get(), "switching to an already published appearance costs no request");
        }
        assertFilesRemain(aFiles);
        assertFilesRemain(bFiles);
    }

    @Test void assigningPreparedAppearanceToNamedNpcPreservesBothPictures() {
        CompiledStory story = story(null);
        story.preparedVisuals.add(new PreparedVisual("spare_1", LOOK_A));
        GameSession session = session(story);
        session.currentScene = scene(null, "neutral", "neutral");
        coordinator.planForSession(session);
        pipeline.ensureQueued(SID, List.of("pt.spare_1.base", "card.spare_1.default"), true);
        settle();
        AssetRecord base = manifest().get("pt.spare_1.base");
        AssetRecord card = manifest().get("card.spare_1.default");
        assertEquals(AssetStatus.READY, base.status);
        assertEquals(AssetStatus.READY, card.status);
        String baseFile = base.fileName;
        String cardFile = card.fileName;
        String key = base.spec.appearanceKey();
        int calls = fake.calls.get();

        story.encounterCharacter(npc("spare_1", "书店老板", LOOK_A), true);
        session.currentScene = scene("spare_1", "neutral", "neutral");
        coordinator.ensureCurrentSceneAssets(session);
        settle();

        assertEquals(baseFile, manifest().get("pt.spare_1.base").fileName);
        assertEquals(cardFile, manifest().get("card.spare_1.default").fileName);
        assertEquals(key, manifest().get("card.spare_1.default").spec.appearanceKey());
        assertEquals(calls, fake.calls.get(), "naming an already drawn appearance must not re-render its card");
    }

    @Test void legacyReferencePicturesInheritVerifiedBaseInsteadOfRequiringAppearanceInTheirPrompt() throws Exception {
        GameSession session = session(story(LOOK_A));
        Map<String, String> legacyFiles = seedLegacy(session.story);
        int priorAttempts = legacyFiles.size();
        assertFalse(store.readManifest(SID).orElseThrow().get("pt.npc_0.worried").spec.prompt().contains(LOOK_A));

        coordinator.ensureCurrentSceneAssets(session);
        settle();

        assertEquals(legacyFiles, readyNames());
        String baseKey = manifest().get("pt.npc_0.base").spec.appearanceKey();
        assertEquals(baseKey, manifest().get("card.npc_0.default").spec.appearanceKey());
        assertEquals(baseKey, manifest().get("pt.npc_0.worried").spec.appearanceKey());
        assertEquals(priorAttempts, manifest().budget.attemptsTotal);
        assertEquals(0, fake.calls.get());
        assertFilesRemain(legacyFiles);
    }

    @Test void historicalSnapshotCanAttributeLegacyFilesForALaterReturnToThatRoute() throws Exception {
        CompiledStory a = story(LOOK_A);
        CompiledStory b = story(LOOK_B);
        Map<String, String> aFiles = seedLegacy(a);
        GameSession session = session(b);
        AtomicInteger historyReads = new AtomicInteger();
        coordinator.ensureCurrentSceneAssets(session, () -> {
            historyReads.incrementAndGet();
            return List.of(a);
        });
        settle();
        assertTrue(historyReads.get() > 0, "an unattributed base consults historical story snapshots");
        int calls = fake.calls.get();
        assertTrue(calls > 0, "the current route needs its genuinely different appearance");

        session.story = a;
        coordinator.reconcileAfterRestore(session);
        settle();
        assertEquals(aFiles, readyNames());
        assertEquals(calls, fake.calls.get(), "verified old-route files are reusable, including their dependents");
        assertFilesRemain(aFiles);
    }

    @Test void missingCurrentCardAndDialogueOnlyPoseArePreparedExactlyOnce() {
        GameSession session = session(story(LOOK_A));
        session.currentScene = scene(NPC, "worried", "relieved");
        coordinator.ensureCurrentSceneAssets(session);
        coordinator.ensureCurrentSceneAssets(session);
        settle();
        assertEquals(AssetStatus.READY, manifest().get("card.npc_0.default").status);
        assertEquals(AssetStatus.READY, manifest().get("pt.npc_0.worried").status);
        assertEquals(AssetStatus.READY, manifest().get("pt.npc_0.relieved").status,
                "a later dialogue expression is needed even when the stage presence has another pose");
        int calls = fake.calls.get();
        coordinator.ensureCurrentSceneAssets(session);
        settle();
        assertEquals(calls, fake.calls.get());
        assertTrue(manifest().records.values().stream().allMatch(r -> r.attempts == 1));
    }

    @Test void anExistingNonDefaultBackgroundDoesNotTriggerAnUnusedDefaultOnLoad() {
        CompiledStory story = story(null);
        story.playerVisual = null;
        story.registerLocation(new LocationProfile("loc_room", "房间", "一间卧室", "窗外夜色"));
        String nightId = "bg.loc_room.night";
        AssetSpec night = planner.fromRequest(story, new AssetRequest("background", "loc_room", "night", null),
                STYLE, VisualPlanner.styleKey(STYLE), "b1");
        pipeline.adopt(SID, new VisualPlanner.Plan(STYLE, VisualPlanner.styleKey(STYLE), List.of(night)));
        settle();
        String nightFile = manifest().get(nightId).fileName;
        assertEquals(1, fake.calls.get());
        GameSession session = session(story);
        session.currentScene = new SceneBundle("scene_night", "b1",
                new SceneLocation("loc_room", "房间", "窗外夜色", "", nightId), List.of(),
                List.of(Block.narration("夜已深。")), List.of(), null, "", List.of(), null);

        coordinator.ensureCurrentSceneAssets(session);
        coordinator.ensureCurrentSceneAssets(session);
        settle();

        assertEquals(nightId, session.currentScene.location().backgroundAssetId());
        assertEquals(nightFile, manifest().get(nightId).fileName);
        assertNull(manifest().get("bg.loc_room.default"));
        assertEquals(1, fake.calls.get(), "loading a scene with its ready night background spends no extra call");
    }

    @Test void unverifiableLegacyArtIsKeptAndOnlyTheCurrentSubjectsAreRebuilt() throws Exception {
        CompiledStory old = story(LOOK_A);
        old.registerCharacter(npc("npc_absent", "远处的人", "黄色雨衣。"));
        props.getPlan().setCharacters(2);
        Map<String, String> legacyFiles = seedLegacy(old);
        pipeline.shutdown();
        AssetManifest legacy = store.readManifest(SID).orElseThrow();
        // Simulate a base made by an unknown historical prompt template: it is not evidence
        // that the current face matches, even though the generic dependent prompts do match.
        legacy.get("pt.npc_0.base").spec = legacy.get("pt.npc_0.base").spec.withPrompt("unverifiable old drawing request");
        store.writeManifest(legacy);
        start();
        GameSession current = session(story(LOOK_A));
        coordinator.ensureCurrentSceneAssets(current);
        settle();

        assertNotEquals(legacyFiles.get("pt.npc_0.base"), manifest().get("pt.npc_0.base").fileName);
        assertNotEquals(legacyFiles.get("card.npc_0.default"), manifest().get("card.npc_0.default").fileName);
        assertEquals(3, fake.calls.get(), "only the visible NPC's base, card and used pose are rebuilt");
        assertNull(manifest().get("pt.npc_absent.base"), "an absent historical NPC is not regenerated");
        assertFilesRemain(legacyFiles);
    }

    @Test void deletingACurrentPngRepairsOnlyThatPictureAndKeepsItsBudgetHistory() throws Exception {
        GameSession session = session(story(LOOK_A));
        coordinator.ensureCurrentSceneAssets(session);
        settle();
        String id = "card.npc_0.default";
        Map<String, String> before = readyNames();
        Files.delete(pipeline.readyFile(SID, id).orElseThrow());
        int calls = fake.calls.get();
        int attempts = manifest().budget.attemptsTotal;

        coordinator.ensureCurrentSceneAssets(session);
        coordinator.ensureCurrentSceneAssets(session);
        settle();

        assertEquals(AssetStatus.READY, manifest().get(id).status);
        assertNotEquals(before.get(id), manifest().get(id).fileName, "replacement gets a fresh immutable file");
        assertEquals(calls + 1, fake.calls.get());
        assertEquals(attempts + 1, manifest().budget.attemptsTotal);
        for (var entry : before.entrySet()) {
            if (!entry.getKey().equals(id)) assertEquals(entry.getValue(), manifest().get(entry.getKey()).fileName);
        }
    }

    @Test void disabledImageProviderDoesNotSpendOnMissingCurrentArt() {
        fake.enabled = false;
        props.setEnabled(false);
        GameSession session = session(story(LOOK_A));
        coordinator.ensureCurrentSceneAssets(session);
        coordinator.reconcileAfterRestore(session);
        assertEquals(0, fake.calls.get());
        assertEquals(0, pipeline.activeCount());
    }

    @Test void repeatedMissingChecksKeepTerminalFailureUntilUserRetries() {
        fake.failWith = prompt -> prompt.contains("worried expression")
                ? new ImageProviderException("test image rejected", false) : null;
        GameSession session = session(story(LOOK_A));
        coordinator.ensureCurrentSceneAssets(session);
        settle();
        String pose = "pt.npc_0.worried";
        assertEquals(AssetStatus.FAILED, manifest().get(pose).status);
        int calls = fake.calls.get();
        for (int i = 0; i < 4; i++) coordinator.ensureCurrentSceneAssets(session);
        settle();
        assertEquals(calls, fake.calls.get(), "a page poll cannot reopen a failed generation's attempt allowance");
        assertEquals(1, manifest().get(pose).attempts);

        fake.failWith = null;
        pipeline.manualRetry(SID, pose);
        settle();
        assertEquals(AssetStatus.READY, manifest().get(pose).status);
        assertEquals(calls + 1, fake.calls.get());
        assertEquals(2, manifest().get(pose).attempts);
    }

    @Test void appearanceIdentityNormalizesTextButSeparatesSubjectAndStyle() {
        assertEquals(AppearanceIdentity.key("npc", " cafe\u0301\r\ncoat ", " blue  ink "),
                AppearanceIdentity.key("npc", "café coat", "blue ink"));
        assertNotEquals(AppearanceIdentity.key("npc_a", LOOK_A, STYLE), AppearanceIdentity.key("npc_b", LOOK_A, STYLE));
        assertNotEquals(AppearanceIdentity.key(NPC, LOOK_A, STYLE), AppearanceIdentity.key(NPC, LOOK_A, "油画"));
        assertNotEquals(AppearanceIdentity.key(NPC, LOOK_A, STYLE), AppearanceIdentity.key(NPC, LOOK_B, STYLE));
    }

    private Map<String, String> seedLegacy(CompiledStory story) throws Exception {
        pipeline.shutdown();
        AssetManifest legacy = new AssetManifest();
        legacy.sessionId = SID;
        legacy.style = STYLE;
        legacy.styleKey = VisualPlanner.styleKey(STYLE);
        GameState state = new GameState();
        state.currentBeatId = "b1";
        Map<String, String> files = new LinkedHashMap<>();
        for (AssetSpec spec : planner.plan(story, state, STYLE).specs()) {
            AssetRecord record = new AssetRecord(spec.withAppearanceKey(null));
            record.recordVersionId = null;
            byte[] bytes = FakeImageProvider.png(spec.assetId(), 16, 24, spec.kind() != AssetKind.CHARACTER_CARD);
            AssetStore.Stored saved = store.save(SID, spec.assetId(), bytes, "image/png");
            record.status = AssetStatus.READY;
            record.attempts = 1;
            record.generationVersion = 1;
            record.fileName = saved.fileName();
            record.mimeType = saved.mimeType();
            record.width = saved.width();
            record.height = saved.height();
            record.bytes = saved.bytes();
            record.contentHash = saved.contentHash();
            record.model = "legacy-test-model";
            record.readyAt = "2026-09-01T00:00:00Z";
            legacy.records.put(spec.assetId(), record);
            files.put(spec.assetId(), saved.fileName());
        }
        legacy.budget.attemptsTotal = files.size();
        legacy.budget.arcAttempts = files.size();
        legacy.budget.firstBatchQueued = files.size();
        store.writeManifest(legacy);
        start();
        return files;
    }
}
