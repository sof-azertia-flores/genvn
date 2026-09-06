package com.genvn.llm;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The JDK HttpClient's request timeout ends when the response headers arrive; nothing bounds
 * how long a body read may block afterwards. A gateway that keeps the connection open without
 * sending bytes would therefore hang the reading thread forever. This watchdog closes the body
 * stream when no bytes have arrived for {@code idleMillis}, or when the overall deadline passes,
 * which turns the blocked read into an IOException the caller can report and retry.
 */
public final class IdleWatchdog implements AutoCloseable {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "genvn-io-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final Closeable stream;
    private final long idleMillis;
    private final long deadlineAtMillis;
    private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
    private final ScheduledFuture<?> ticks;
    private volatile String tripped;

    private IdleWatchdog(Closeable stream, long idleMillis, long deadlineAtMillis) {
        this.stream = stream;
        this.idleMillis = idleMillis;
        this.deadlineAtMillis = deadlineAtMillis;
        long period = idleMillis > 0 ? Math.max(50, Math.min(1000, idleMillis / 4)) : 1000;
        this.ticks = TIMER.scheduleAtFixedRate(this::check, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * @param idleMillis       longest silence tolerated between bytes; 0 or less disables the idle rule
     * @param deadlineAtMillis absolute wall-clock deadline for the whole read; 0 disables it
     */
    public static IdleWatchdog guard(Closeable stream, long idleMillis, long deadlineAtMillis) {
        return new IdleWatchdog(stream, idleMillis, deadlineAtMillis);
    }

    /** Call whenever bytes arrive. */
    public void touch() {
        lastActivity.set(System.currentTimeMillis());
    }

    /** Why the stream was closed by the watchdog, or null if it was not. */
    public String tripped() {
        return tripped;
    }

    private void check() {
        if (tripped != null) return;
        long now = System.currentTimeMillis();
        if (deadlineAtMillis > 0 && now > deadlineAtMillis) {
            trip("exceeded the total read deadline");
        } else if (idleMillis > 0 && now - lastActivity.get() > idleMillis) {
            trip("sent no bytes for " + (idleMillis / 1000) + "s");
        }
    }

    private void trip(String reason) {
        tripped = reason;
        ticks.cancel(false);
        try {
            stream.close();
        } catch (IOException ignored) {
            // the reader will observe the closed stream
        }
    }

    @Override
    public void close() {
        ticks.cancel(false);
    }
}
