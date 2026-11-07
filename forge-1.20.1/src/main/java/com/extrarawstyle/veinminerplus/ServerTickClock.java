package com.extrarawstyle.veinminerplus;

/**
 * Tick timing that actually includes the chain's own work.
 *
 * {@code MinecraftServer.tickServer} stores the tick's duration and updates the field behind
 * {@code getAverageTickTime} <em>before</em> it calls {@code ForgeEventFactory.onPostServerTick}.
 * This mod's chain jobs run from {@code ServerTickEvent.END}, which is fired from that call, so the
 * server's own average tick time is complete for everything except the part that matters here: a
 * tick that spends 90 ms breaking blocks is recorded as a few milliseconds, and the derived TPS reads
 * a healthy 20 while the server is clearly falling behind.
 *
 * That makes {@code getAverageTickTime} unusable both for the diagnostics and for the overload guard.
 * This clock measures what it cannot: the interval between successive END callbacks, which spans the
 * whole tick including the previous tick's chain work. A smoothed value is kept for the guard so one
 * slow tick does not pause a job by itself.
 *
 * The clock is updated unconditionally, outside any diagnostic flag: the guard depends on it, and a
 * guard that only works when logging is enabled would be worse than no guard.
 */
final class ServerTickClock {

    /** Sanity bound for the smoothing window, in ticks. */
    private static final int WINDOW = 20;

    private static long lastNanos = -1L;
    // Rolling sum of the last WINDOW intervals, in nanoseconds.
    private static long[] samples = new long[WINDOW];
    private static int sampleCount;
    private static int sampleIndex;

    private ServerTickClock() {
    }

    /** Called once per server tick from the END handler, before any chain work runs. */
    static void observe(long nowNanos) {
        if (lastNanos > 0) {
            long interval = nowNanos - lastNanos;
            // A negative or absurd value means the clock was reset (nothing sensible to average).
            if (interval > 0 && interval < 60_000_000_000L) {
                samples[sampleIndex] = interval;
                sampleIndex = (sampleIndex + 1) % WINDOW;
                if (sampleCount < WINDOW) {
                    sampleCount++;
                }
            }
        }
        lastNanos = nowNanos;
    }

    /**
     * Smoothed tick interval in milliseconds, or -1 before two ticks have been seen.
     *
     * This is the interval that includes chain work, so a sustained 90 ms of mining shows up as
     * roughly 90 ms here rather than as the server's own few-millisecond figure.
     */
    static double smoothedIntervalMillis() {
        if (sampleCount == 0) {
            return -1.0D;
        }
        long total = 0L;
        for (int i = 0; i < sampleCount; i++) {
            total += samples[i];
        }
        return total / (double) sampleCount / 1_000_000.0D;
    }

    /**
     * TPS derived from the smoothed interval, capped at 20.
     *
     * The cap is the same one the job's guard has always applied, so a value read here and a value
     * read by the guard cannot disagree. Returns 20 before any measurement exists, matching the old
     * "assume healthy until proven otherwise" behaviour rather than inventing a stall.
     */
    static double tps() {
        double millis = smoothedIntervalMillis();
        if (millis <= 0.0D) {
            return 20.0D;
        }
        return Math.min(20.0D, 1_000.0D / millis);
    }
}
