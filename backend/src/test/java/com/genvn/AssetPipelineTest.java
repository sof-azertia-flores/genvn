package com.genvn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.asset.AssetKind;
import com.genvn.asset.AssetManifest;
import com.genvn.asset.AssetPipeline;
import com.genvn.asset.AssetRecord;
import com.genvn.asset.AssetSpec;
import com.genvn.asset.AssetStatus;
import com.genvn.asset.AssetStore;
import com.genvn.asset.ImageProviderException;
import com.genvn.asset.VisualPlanner;
import com.genvn.config.ImageProperties;
import com.genvn.support.Engine;
import com.genvn.support.FakeImageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scheduler on its own: files really land, one request per picture, bounded concurrency,
 * a budget that pauses rather than spends, retries that stop, and a restart that reuses.
 */
class AssetPipelineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Path dir;
    private FakeImageProvider fake;
    private AssetPipeline pipeline;
    private ImageProperties props;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("genvn-assets");
        fake = new FakeImageProvider();
        props = Engine.imageProperties();
        pipeline = new AssetPipeline(fake, new AssetStore(mapper, dir), props, mapper);
    }

    @AfterEach
    void tearDown() {
        pipeline.shutdown();
    }

    private static AssetSpec bg(String loc, int priority) {
        return new AssetSpec(AssetSpec.backgroundId(loc, "default"), AssetKind.BACKGROUND, loc, loc, "default",
                "background of " + loc, "k", "b1", priority, null, AssetSpec.GENERAL, true);
    }

    private static AssetSpec portrait(String npc) {
        return new AssetSpec(AssetSpec.portraitId(npc, "base"), AssetKind.PORTRAIT, npc, npc, "base",
                "portrait of " + npc, "k", "b1", 0, null, AssetSpec.GENERAL, false);
    }

    private static AssetSpec variant(String npc, String expr) {
        return new AssetSpec(AssetSpec.portraitId(npc, expr), AssetKind.PORTRAIT_VARIANT, npc, npc, expr,
                "same person, " + expr, "k", "b1", 5, AssetSpec.portraitId(npc, "base"), AssetSpec.GENERAL, false);
    }

    private VisualPlanner.Plan plan(AssetSpec... specs) {
        return new VisualPlanner.Plan("style", "k", List.of(specs));
    }

    private void settle(String sid) {
        for (int i = 0; i < 400; i++) {
            var st = pipeline.status(sid);
            if ((int) st.get("pending") == 0 && pipeline.activeCount() == 0) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { return; }
        }
    }

    private AssetRecord rec(String sid, String id) {
        return pipeline.snapshot(sid).orElseThrow().get(id);
    }

    @Test
    @DisplayName("a planned picture becomes a real, decodable file, READY in the manifest, with its own version")
    void filesReallyLand() throws Exception {
        pipeline.adopt("s1", plan(bg("loc_a", 0), portrait("npc_x")));
        settle("s1");
        AssetRecord r = rec("s1", "bg.loc_a.default");
        assertEquals(AssetStatus.READY, r.status);
        assertNotNull(r.readyAt);
        assertEquals(1, r.generationVersion);
        Path file = pipeline.readyFile("s1", "bg.loc_a.default").orElseThrow();
        assertTrue(Files.size(file) > 0);
        assertTrue(javax.imageio.ImageIO.read(file.toFile()).getWidth() > 0, "the published file decodes");
        assertEquals(AssetStatus.READY, rec("s1", "pt.npc_x.base").status);
        assertEquals(2, fake.calls.get());
        assertTrue(pipeline.snapshot("s1").orElseThrow().version > 0, "the manifest has its own version counter");
    }

    @Test
    @DisplayName("the same picture wanted twice is requested once; asking again after READY costs nothing")
    void onePictureOneRequest() {
        pipeline.adopt("s1", plan(bg("loc_a", 0)));
        pipeline.adopt("s1", plan(bg("loc_a", 0)));
        pipeline.ensureQueued("s1", List.of("bg.loc_a.default"), true);
        settle("s1");
        pipeline.ensureQueued("s1", List.of("bg.loc_a.default"), true);
        pipeline.adopt("s1", plan(bg("loc_a", 0)));
        settle("s1");
        assertEquals(1, fake.calls.get(), "one asset id, one provider call, ever");
    }

    @Test
    @DisplayName("independent pictures overlap up to the configured concurrency and never beyond it")
    void boundedRealConcurrency() throws Exception {
        fake.gate = new CountDownLatch(1);
        fake.entered = new CountDownLatch(2);
        pipeline.adopt("s1", plan(bg("a", 0), bg("b", 1), bg("c", 2), bg("d", 3)));
        assertTrue(fake.entered.await(5, TimeUnit.SECONDS), "two requests are inside the provider at once");
        Thread.sleep(150);
        assertEquals(2, fake.active.get(), "exactly concurrency=2 in flight, the other two wait");
        fake.gate.countDown();
        settle("s1");
        assertEquals(4, fake.calls.get());
        assertEquals(2, pipeline.highWaterConcurrency(), "never exceeded image.concurrency");
    }

    @Test
    @DisplayName("the arc budget pauses the rest instead of spending; a new arc resumes; a restart keeps the count")
    void budgetPausesRatherThanSpends() {
        props.setArcBudget(2);
        pipeline.adopt("s1", plan(bg("a", 0), bg("b", 1), bg("c", 2)));
        settle("s1");
        assertEquals(2, fake.calls.get());
        AssetManifest m = pipeline.snapshot("s1").orElseThrow();
        long ready = m.records.values().stream().filter(r -> r.status == AssetStatus.READY).count();
        long paused = m.records.values().stream().filter(r -> r.status == AssetStatus.PAUSED).count();
        assertEquals(2, ready);
        assertEquals(1, paused);
        assertTrue(m.budget.paused);
        assertTrue(m.budget.pauseReason.contains("budget"));

        // A restart must not reset what was already spent.
        AssetPipeline again = new AssetPipeline(fake, new AssetStore(mapper, dir), props, mapper);
        try {
            assertEquals(2, again.snapshot("s1").orElseThrow().budget.arcAttempts);
            again.ensureQueued("s1", List.of("bg.c.default"), true);
            settle("s1");
            assertEquals(2, fake.calls.get(), "still paused after restart: no call was made");

            again.beginArc("s1", 2);
            again.ensureQueued("s1", List.of("bg.c.default"), true);
            for (int i = 0; i < 200 && (int) again.status("s1").get("pending") > 0; i++) Thread.sleep(25);
            assertEquals(AssetStatus.READY, again.snapshot("s1").orElseThrow().get("bg.c.default").status);
            assertEquals(3, fake.calls.get());
        } catch (InterruptedException e) {
            fail(e);
        } finally {
            again.shutdown();
        }
    }

    @Test
    @DisplayName("retryable failures are retried a bounded number of times; non-retryable ones are not retried at all")
    void retriesAreBounded() throws Exception {
        props.setMaxAttempts(2);
        fake.failWith = prompt -> prompt.contains("flaky")
                ? new ImageProviderException("HTTP 429", true, 429, 0)
                : prompt.contains("bad") ? new ImageProviderException("HTTP 400", false, 400, 0) : null;
        AssetSpec flaky = new AssetSpec("bg.flaky.default", AssetKind.BACKGROUND, "flaky", "flaky", "default",
                "flaky one", "k", "b1", 0, null, AssetSpec.GENERAL, true);
        AssetSpec bad = new AssetSpec("bg.bad.default", AssetKind.BACKGROUND, "bad", "bad", "default",
                "bad one", "k", "b1", 0, null, AssetSpec.GENERAL, true);
        pipeline.adopt("s1", plan(flaky, bad));
        // first attempts fail immediately; the flaky retry is scheduled ~2s out
        for (int i = 0; i < 400; i++) {
            AssetRecord f = rec("s1", "bg.flaky.default");
            if (f.status == AssetStatus.FAILED && rec("s1", "bg.bad.default").status == AssetStatus.FAILED) break;
            Thread.sleep(25);
        }
        AssetRecord f = rec("s1", "bg.flaky.default");
        AssetRecord b = rec("s1", "bg.bad.default");
        assertEquals(AssetStatus.FAILED, f.status);
        assertEquals(2, f.attempts, "429 was retried exactly up to image.max-attempts");
        assertEquals(AssetStatus.FAILED, b.status);
        assertEquals(1, b.attempts, "400 was never retried");
        assertFalse(b.retryable);
        assertEquals(3, fake.calls.get());
    }

    @Test
    @DisplayName("after a restart a finished picture is reused with no provider call; a corrupt file is recovered once")
    void restartReusesAndRecovers() throws Exception {
        pipeline.adopt("s1", plan(bg("loc_a", 0)));
        settle("s1");
        assertEquals(1, fake.calls.get());
        pipeline.shutdown();

        AssetPipeline restarted = new AssetPipeline(fake, new AssetStore(mapper, dir), props, mapper);
        try {
            AssetRecord r = restarted.snapshot("s1").orElseThrow().get("bg.loc_a.default");
            assertEquals(AssetStatus.READY, r.status, "the file on disk is trusted after validation");
            assertEquals(1, fake.calls.get(), "no new provider call");
            assertTrue(restarted.readyFile("s1", "bg.loc_a.default").isPresent());
        } finally {
            restarted.shutdown();
        }

        // Corrupt the file: the next start must notice, not serve garbage, and repair within budget.
        Path file = pipeline.readyFile("s1", "bg.loc_a.default").orElseThrow();
        Files.write(file, new byte[]{1, 2, 3});
        AssetPipeline repaired = new AssetPipeline(fake, new AssetStore(mapper, dir), props, mapper);
        try {
            for (int i = 0; i < 400; i++) {
                AssetRecord r = repaired.snapshot("s1").orElseThrow().get("bg.loc_a.default");
                if (r.status == AssetStatus.READY && r.generationVersion == 2) break;
                Thread.sleep(25);
            }
            AssetRecord r = repaired.snapshot("s1").orElseThrow().get("bg.loc_a.default");
            assertEquals(AssetStatus.READY, r.status);
            assertEquals(2, r.generationVersion, "a fresh file was published under a new version");
            assertEquals(2, fake.calls.get(), "exactly one repair call");
            assertTrue(javax.imageio.ImageIO.read(repaired.readyFile("s1", "bg.loc_a.default").orElseThrow().toFile()).getWidth() > 0);
        } finally {
            repaired.shutdown();
        }
    }

    @Test
    @DisplayName("a forgotten session's late result is dropped: no file, no manifest, no resurrection")
    void lateResultsForForgottenSessionsAreDropped() throws Exception {
        fake.gate = new CountDownLatch(1);
        fake.entered = new CountDownLatch(1);
        pipeline.adopt("s1", plan(bg("loc_a", 0)));
        assertTrue(fake.entered.await(5, TimeUnit.SECONDS));
        pipeline.forget("s1");
        fake.gate.countDown();
        Thread.sleep(300);
        assertFalse(Files.exists(dir.resolve("s1").resolve("bg.loc_a.default.png")), "nothing written after forget");
        assertFalse(Files.exists(dir.resolve("s1").resolve("manifest.json")), "the manifest was not recreated");
        assertTrue(pipeline.snapshot("s1").isEmpty());
    }

    @Test
    @DisplayName("an expression variant waits for its base portrait and is produced as an edit of it")
    void variantsAreEditsOfTheBase() throws Exception {
        pipeline.adopt("s1", plan(variant("npc_x", "worried"), portrait("npc_x")));
        settle("s1");
        AssetRecord base = rec("s1", "pt.npc_x.base");
        AssetRecord v = rec("s1", "pt.npc_x.worried");
        assertEquals(AssetStatus.READY, base.status);
        assertEquals(AssetStatus.READY, v.status);
        assertEquals(1, fake.editCalls.get(), "the variant used the edit path with the base as reference");
        assertEquals(1, fake.generateCalls.get());
        assertTrue(v.startedAt.compareTo(base.readyAt) >= 0, "the variant did not start before its base was ready");
    }

    @Test
    @DisplayName("a disabled provider pauses everything with a named reason and never gets called")
    void disabledProviderPauses() {
        fake.enabled = false;
        pipeline.adopt("s1", plan(bg("loc_a", 0)));
        settle("s1");
        AssetRecord r = rec("s1", "bg.loc_a.default");
        assertEquals(AssetStatus.PAUSED, r.status);
        assertTrue(r.failureReason.contains("disabled"));
        assertEquals(0, fake.calls.get());
    }

    @Test
    @DisplayName("ids that could name a path are refused by the store")
    void pathSafety() {
        assertFalse(AssetStore.safeAssetId("../etc/passwd"));
        assertFalse(AssetStore.safeAssetId("a/b"));
        assertFalse(AssetStore.safeSessionId("../x"));
        assertTrue(AssetStore.safeAssetId("bg.loc_interior.default"));
        List<String> ok = new ArrayList<>();
        ok.add(AssetSpec.slug("Night / Rain!"));
        assertEquals("night_rain", ok.get(0));
    }
}
