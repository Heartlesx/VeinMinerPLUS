/**
 * Scenario harness for ServerTickClock.
 *
 * The smoothing logic is copied from ServerTickClock.java with a fixed sample window so the
 * behaviour can be checked without a running server. Two questions matter: does a sustained slow
 * tick show up at all (the server's own average does not see END work), and does one slow tick avoid
 * pausing a job by itself.
 *
 * Run: javac ClockHarness.java && java ClockHarness
 */
public final class ClockHarness {
    private static int failures;
    private static final int WINDOW = 20;
    private static final long MS = 1_000_000L;

    public static void main(String[] args) {
        healthyTicksRead20();
        sustained90msShowsUp();
        oneSlowTickDoesNotPanic();
        capIsTwenty();
        unmeasuredBeforeTwoTicks();

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("all clock scenarios passed");
    }

    // ==== state machine copied from ServerTickClock ====

    static final class Clock {
        long lastNanos = -1L;
        long[] samples = new long[WINDOW];
        int sampleCount;
        int sampleIndex;

        void observe(long nowNanos) {
            if (lastNanos > 0) {
                long interval = nowNanos - lastNanos;
                if (interval > 0 && interval < 60_000_000_000L) {
                    samples[sampleIndex] = interval;
                    sampleIndex = (sampleIndex + 1) % WINDOW;
                    if (sampleCount < WINDOW) sampleCount++;
                }
            }
            lastNanos = nowNanos;
        }

        double smoothedIntervalMillis() {
            if (sampleCount == 0) return -1.0D;
            long total = 0L;
            for (int i = 0; i < sampleCount; i++) total += samples[i];
            return total / (double) sampleCount / 1_000_000.0D;
        }

        double tps() {
            double millis = smoothedIntervalMillis();
            if (millis <= 0.0D) return 20.0D;
            return Math.min(20.0D, 1_000.0D / millis);
        }
    }

    // ==== scenarios ====

    // 50 ms ticks are exactly 20 TPS: the cap must not invent more.
    static void healthyTicksRead20() {
        Clock c = new Clock();
        long t = 0;
        for (int i = 0; i < 40; i++) { c.observe(t); t += 50 * MS; }
        expect("healthy ticks read 20 TPS", 20.0D, round(c.tps()));
    }

    // The whole point: 90 ms of chain work per tick must drag the reading down, which the server's
    // own getAverageTickTime would not do.
    static void sustained90msShowsUp() {
        Clock c = new Clock();
        long t = 0;
        for (int i = 0; i < 40; i++) { c.observe(t); t += 90 * MS; }
        double tps = c.tps();
        expect("90 ms ticks read ~11 TPS", 11.1D, round(tps));
        expect("reading is below the pause threshold check", true, tps < 12.0D);
    }

    // A single 500 ms tick inside an otherwise healthy stream must not swing the average far enough
    // to trip the critical threshold, so a one-off hitch cannot abort a job.
    static void oneSlowTickDoesNotPanic() {
        Clock c = new Clock();
        long t = 0;
        for (int i = 0; i < 60; i++) {
            c.observe(t);
            t += (i == 40) ? 500 * MS : 50 * MS;
        }
        double tps = c.tps();
        expect("one slow tick does not reach the critical threshold", true, tps > 8.0D);
    }

    static void capIsTwenty() {
        Clock c = new Clock();
        long t = 0;
        for (int i = 0; i < 40; i++) { c.observe(t); t += 10 * MS; }
        expect("fast ticks are capped at 20", 20.0D, round(c.tps()));
    }

    // Before two ticks there is no interval; the guard must assume healthy rather than stall.
    static void unmeasuredBeforeTwoTicks() {
        Clock c = new Clock();
        expect("no samples reads 20", 20.0D, round(c.tps()));
        c.observe(0);
        expect("one sample still reads 20", 20.0D, round(c.tps()));
    }

    static double round(double v) {
        return Math.round(v * 10.0D) / 10.0D;
    }

    static void expect(String label, double expected, double actual) {
        if (Math.abs(expected - actual) > 0.05D) {
            failures++;
            System.out.println("FAIL " + label + ": expected " + expected + ", got " + actual);
        } else {
            System.out.println("ok   " + label);
        }
    }

    static void expect(String label, boolean expected, boolean actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + label + ": expected " + expected + ", got " + actual);
        } else {
            System.out.println("ok   " + label);
        }
    }
}
