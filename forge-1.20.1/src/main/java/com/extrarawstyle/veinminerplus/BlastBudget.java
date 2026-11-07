package com.extrarawstyle.veinminerplus;

/** Scales blast work based on the measured server TPS. */
final class BlastBudget {

    private static final double QUARTER_RATE_TPS = 12.0D;
    private static final double HALF_RATE_TPS = 16.0D;

    private BlastBudget() {
    }

    static int scale(int configured, double tps) {
        if (tps < QUARTER_RATE_TPS) {
            return Math.max(1, configured / 4);
        }
        if (tps < HALF_RATE_TPS) {
            return Math.max(1, configured / 2);
        }
        return configured;
    }

    static long scale(long configured, double tps) {
        if (tps < QUARTER_RATE_TPS) {
            return Math.max(1L, configured / 4L);
        }
        if (tps < HALF_RATE_TPS) {
            return Math.max(1L, configured / 2L);
        }
        return configured;
    }
}
