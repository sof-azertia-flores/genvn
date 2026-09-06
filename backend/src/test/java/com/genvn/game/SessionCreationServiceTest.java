package com.genvn.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvn.api.CreationQueueFullException;
import com.genvn.api.Dtos;
import com.genvn.api.NotFoundException;
import com.genvn.llm.LlmException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionCreationServiceTest {
    @Test void hundredPercentCannotBePublishedBeforeCreateReturnsAUsableSession() throws Exception {
        var sessions = mock(SessionService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(sessions.create(anyString(), any(), any())).thenAnswer(call -> {
            SessionService.CreationProgress progress = call.getArgument(2);
            progress.report("最后检查", 100, "仍在检查任务。");
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            var session = new GameSession(); session.id = "ready"; return session;
        });
        try (var jobs = new SessionCreationService(sessions)) {
            var job = jobs.submit("story", new PlayerCharacter());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var pending = jobs.require(job.id());
                assertEquals("RUNNING", pending.status());
                assertEquals(99, pending.progress());
                assertNull(pending.sessionId());
            } finally { release.countDown(); }
            var ready = terminal(jobs, job.id());
            assertEquals(100, ready.progress());
            assertEquals("READY", ready.status());
            assertEquals("ready", ready.sessionId());
        }
    }

    @Test void artStyleAndPlayerAppearanceAreForwardedAndIncludedInIdempotencyFingerprint() throws Exception {
        var sessions = mock(SessionService.class);
        when(sessions.create(anyString(), any(), anyString(), any())).thenAnswer(call -> {
            assertEquals("水彩线稿", call.getArgument(2));
            PlayerCharacter player = call.getArgument(1);
            assertEquals("银发，白色大衣", player.visualDescription);
            var session = new GameSession(); session.id = "styled"; return session;
        });
        try (var jobs = new SessionCreationService(sessions)) {
            String key = UUID.randomUUID().toString();
            var player = new PlayerCharacter(); player.visualDescription = "银发，白色大衣";
            jobs.submit("story", player, key, "水彩线稿");
            assertEquals("styled", terminal(jobs, key).sessionId());
            assertEquals("READY", jobs.submit("story", player, key, "水彩线稿").status());
            assertThrows(IllegalArgumentException.class, () -> jobs.submit("story", player, key, "像素艺术"));
            var other = new PlayerCharacter(); other.visualDescription = "黑发，蓝色夹克";
            assertThrows(IllegalArgumentException.class, () -> jobs.submit("story", other, key, "水彩线稿"));
            verify(sessions, times(1)).create(anyString(), any(), anyString(), any());
        }
    }

    @Test void lostPostResponseCanBeRecoveredByKnownIdOrReplayedWithoutCreatingAgain() throws Exception {
        var sessions = mock(SessionService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(sessions.create(anyString(), any(), any())).thenAnswer(call -> {
            SessionService.CreationProgress progress = call.getArgument(2);
            progress.report("编写正文", 55, "开场编写中。");
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            var session = new GameSession(); session.id = "single-session"; return session;
        });
        try (var jobs = new SessionCreationService(sessions)) {
            String key = UUID.randomUUID().toString();
            try {
                jobs.submit("private outline", new PlayerCharacter(), key); // intentionally ignore/loss of POST response
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var recovered = jobs.require(key);
                assertEquals(key, recovered.id()); assertEquals(55, recovered.progress());
                assertEquals(recovered, jobs.submit("private outline", new PlayerCharacter(), key));
                release.countDown();
                var ready = terminal(jobs, key);
                assertEquals("single-session", ready.sessionId());
                assertEquals(ready, jobs.submit("private outline", new PlayerCharacter(), key));
                assertFalse(new ObjectMapper().writeValueAsString(ready).contains("fingerprint"));
                verify(sessions, times(1)).create(anyString(), any(), any());
            } finally { release.countDown(); }
        }
    }

    @Test void concurrentPostsWithTheSameKeyOnlyEnqueueOneCreation() throws Exception {
        var sessions = mock(SessionService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(sessions.create(anyString(), any(), any())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            var session = new GameSession(); session.id = "one"; return session;
        });
        try (var jobs = new SessionCreationService(sessions, Clock.systemUTC(), Duration.ofHours(1), 1, 1, 2);
             var callers = Executors.newFixedThreadPool(8)) {
            String key = UUID.randomUUID().toString();
            CountDownLatch start = new CountDownLatch(1), waiting = new CountDownLatch(8);
            var responses = new ArrayList<java.util.concurrent.Future<Dtos.CreationJobView>>();
            try {
                for (int i = 0; i < 8; i++) responses.add(callers.submit(() -> {
                    waiting.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                    return jobs.submit("same story", new PlayerCharacter(), key);
                }));
                assertTrue(waiting.await(5, TimeUnit.SECONDS)); start.countDown();
                for (var response : responses) assertEquals(key, response.get(5, TimeUnit.SECONDS).id());
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                verify(sessions, times(1)).create(anyString(), any(), any());
            } finally { start.countDown(); release.countDown(); }
        }
    }

    @Test void malformedKeysAndReusingAKeyForDifferentInputAreRejected() throws Exception {
        var sessions = mock(SessionService.class);
        when(sessions.create(anyString(), any(), any())).thenAnswer(call -> {
            var session = new GameSession(); session.id = "one"; return session;
        });
        try (var jobs = new SessionCreationService(sessions)) {
            for (String key : new String[]{"", "job-one", "1-1-1-1-1", "../../sessions"}) {
                assertThrows(IllegalArgumentException.class, () -> jobs.submit("story", new PlayerCharacter(), key));
            }
            verifyNoInteractions(sessions);
            String key = UUID.randomUUID().toString();
            jobs.submit("story", new PlayerCharacter(), key); terminal(jobs, key);
            assertThrows(IllegalArgumentException.class, () -> jobs.submit("another story", new PlayerCharacter(), key));
            var anotherPlayer = new PlayerCharacter(); anotherPlayer.name = "Another character";
            assertThrows(IllegalArgumentException.class, () -> jobs.submit("story", anotherPlayer, key));
            verify(sessions, times(1)).create(anyString(), any(), any());
        }
    }

    @Test void progressOnlyMovesAtRealMilestonesAndPollingNeverResubmits() throws Exception {
        var sessions = mock(SessionService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(sessions.create(anyString(), any(), any())).thenAnswer(call -> {
            SessionService.CreationProgress progress = call.getArgument(2);
            progress.report("编写正文", 55, "框架已校验，正在编写开场。");
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            progress.report("保存故事", 95, "正在保存故事。");
            var session = new GameSession(); session.id = "ready-session";
            return session;
        });
        try (var jobs = new SessionCreationService(sessions)) {
            var submitted = jobs.submit("private outline", new PlayerCharacter());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var paused = jobs.require(submitted.id());
                assertEquals("RUNNING", paused.status());
                assertEquals(55, paused.progress());
                assertNull(paused.sessionId());
                for (int i = 0; i < 10; i++) {
                    assertEquals(paused, jobs.require(submitted.id()), "Polling must not invent progress or logs");
                }
                assertThrows(UnsupportedOperationException.class, () -> paused.logs().clear());
                assertFalse(new ObjectMapper().writeValueAsString(paused).contains("private outline"));
            } finally { release.countDown(); }
            var finished = terminal(jobs, submitted.id());
            assertEquals("READY", finished.status());
            assertEquals(100, finished.progress());
            assertEquals("ready-session", finished.sessionId());
            assertNull(finished.error());
            assertEquals(3, finished.logs().size());
            assertEquals(1, finished.logs().getFirst().id());
            Instant.parse(finished.logs().getFirst().time());
            verify(sessions, times(1)).create(anyString(), any(), any());
        }
    }

    @Test void failedJobsNeverExposeProviderErrorBodiesOrStoryInput() throws Exception {
        var sessions = mock(SessionService.class);
        when(sessions.create(anyString(), any(), any())).thenThrow(
                new LlmException("Authorization Bearer sk-secret; prompt PRIVATE_STORY"));
        try (var jobs = new SessionCreationService(sessions)) {
            var submitted = jobs.submit("PRIVATE_STORY", new PlayerCharacter());
            var failed = terminal(jobs, submitted.id());
            assertEquals("FAILED", failed.status());
            assertNull(failed.sessionId());
            assertTrue(failed.progress() < 100);
            assertTrue(failed.error().contains("未能完成"));
            String json = new ObjectMapper().writeValueAsString(failed);
            assertFalse(json.contains("sk-secret"));
            assertFalse(json.contains("PRIVATE_STORY"));
            assertFalse(json.contains("Authorization"));
        }
    }

    @Test void queueAndRetainedRecordsAreBoundedAndCompletedJobsExpire() throws Exception {
        var sessions = mock(SessionService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(sessions.create(anyString(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            var session = new GameSession(); session.id = "saved";
            return session;
        });
        var clock = new MutableClock();
        try (var jobs = new SessionCreationService(sessions, clock, Duration.ofMinutes(10), 1, 1, 2)) {
            var first = jobs.submit("one", new PlayerCharacter());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second = jobs.submit("two", new PlayerCharacter());
                assertEquals("QUEUED", jobs.require(second.id()).status());
                assertThrows(CreationQueueFullException.class, () -> jobs.submit("three", new PlayerCharacter()));
                release.countDown();
                terminal(jobs, first.id()); terminal(jobs, second.id());
                var third = jobs.submit("three", new PlayerCharacter());
                assertThrows(NotFoundException.class, () -> jobs.require(first.id()), "Oldest terminal record is evicted");
                terminal(jobs, third.id());
                clock.now = clock.now.plus(Duration.ofMinutes(10));
                assertThrows(NotFoundException.class, () -> jobs.require(second.id()));
                assertThrows(NotFoundException.class, () -> jobs.require(third.id()));
                verify(sessions, times(3)).create(anyString(), any(), any());
            } finally { release.countDown(); }
        }
    }

    @Test void workerQueueSaturationRejectsWithoutCreatingAnUnreachableJob() throws Exception {
        var sessions = mock(SessionService.class);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(sessions.create(anyString(), any(), any())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            var session = new GameSession(); session.id = "saved"; return session;
        });
        try (var jobs = new SessionCreationService(sessions, Clock.systemUTC(), Duration.ofHours(1), 1, 1, 10)) {
            var first = jobs.submit("one", new PlayerCharacter());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second = jobs.submit("two", new PlayerCharacter());
                assertThrows(CreationQueueFullException.class, () -> jobs.submit("three", new PlayerCharacter()));
                assertEquals("RUNNING", jobs.require(first.id()).status());
                assertEquals("QUEUED", jobs.require(second.id()).status());
                release.countDown(); terminal(jobs, first.id()); terminal(jobs, second.id());
                verify(sessions, times(2)).create(anyString(), any(), any());
            } finally { release.countDown(); }
        }
    }

    private static Dtos.CreationJobView terminal(SessionCreationService jobs, String id) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            var result = jobs.require(id);
            if ("READY".equals(result.status()) || "FAILED".equals(result.status())) return result;
            Thread.sleep(10);
        }
        return fail("Creation job did not finish");
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-05T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
