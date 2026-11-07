import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How much work the section pruning actually removes, measured in the units that matter.
 *
 * The differential harness proves the pruning changes nothing observable. That is half the claim;
 * the other half is that it earns its place. "Reduces repeated work" is not evidence - these are the
 * counts a scan really performs before and after, over the centre/radius combinations the job
 * produces.
 *
 * Counted per centre:
 *   - section lookups: {@code sections.get(sectionY)} per (chunk, sectionY) in the radius box;
 *   - distance tests: per-position {@code squaredDistance} calls, the thing being reduced;
 *   - enqueues: positions that actually passed, which must be identical.
 *
 * Run: java SparseWorkCounter
 */
public final class SparseWorkCounter {

    /** One scan's counters. */
    static final class Count {
        long sectionLookups;
        long distanceTests;
        long enqueues;
        long bucketsSkipped;
        long bucketsTrusted;
        long bucketsTested;
    }

    /**
     * Counts the original scan: every reachable section's every position is distance-tested.
     * A section with no bucket is not counted as a lookup test, matching the real code's early
     * {@code continue}.
     */
    static Count original(int distance, int occupiedSections) {
        Count count = new Count();
        long maxSquared = (long) distance * distance;
        int span = (2 * distance) / 16 + 2;
        for (int sx = -span; sx <= span; sx++) {
            for (int sz = -span; sz <= span; sz++) {
                for (int sy = -span; sy <= span; sy++) {
                    count.sectionLookups++;
                    if (!occupied(sx, sy, sz, occupiedSections)) {
                        continue;
                    }
                    // A dense section: every one of its 4096 blocks is a distance test.
                    for (int i = 0; i < 4096; i++) {
                        count.distanceTests++;
                        if (inside(i, sx, sy, sz, maxSquared)) {
                            count.enqueues++;
                        }
                    }
                }
            }
        }
        return count;
    }

    /** Counts the pruned scan: an empty bucket is still looked up, a box decides its positions. */
    static Count pruned(int distance, int occupiedSections) {
        Count count = new Count();
        long maxSquared = (long) distance * distance;
        int span = (2 * distance) / 16 + 2;
        for (int sx = -span; sx <= span; sx++) {
            for (int sz = -span; sz <= span; sz++) {
                for (int sy = -span; sy <= span; sy++) {
                    count.sectionLookups++;
                    if (!occupied(sx, sy, sz, occupiedSections)) {
                        continue;
                    }
                    SparseDiffHarness.Reach reach = SparseDiffHarness.classify(
                            0, 0, 0, sx, sy, sz, maxSquared);
                    switch (reach) {
                        case OUTSIDE -> count.bucketsSkipped++;
                        case FULLY_INSIDE -> {
                            count.bucketsTrusted++;
                            count.enqueues += 4096;
                        }
                        case PARTIAL -> {
                            count.bucketsTested++;
                            for (int i = 0; i < 4096; i++) {
                                count.distanceTests++;
                                if (inside(i, sx, sy, sz, maxSquared)) {
                                    count.enqueues++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return count;
    }

    /** Deterministic occupancy: roughly a quarter of sections hold anything at all. */
    static boolean occupied(int sx, int sy, int sz, int percent) {
        int hash = (sx * 73856093) ^ (sy * 19349663) ^ (sz * 83492791);
        return Math.floorMod(hash, 100) < percent;
    }

    /** Whether block #i of a section falls inside the sphere, using the real per-position rule. */
    static boolean inside(int index, int sx, int sy, int sz, long maxSquared) {
        int dx = sx * 16 + (index & 15);
        int dy = sy * 16 + ((index >> 4) & 15);
        int dz = sz * 16 + ((index >> 8) & 15);
        return (long) dx * dx + (long) dy * dy + (long) dz * dz <= maxSquared;
    }

    public static void main(String[] args) {
        System.out.printf("%-8s %-6s %12s %14s %12s %10s %10s%n",
                "radius", "fill%", "distTests", "distTestsAfter", "enqueues", "skipped", "trusted");
        for (int distance : new int[]{20, 32, 128}) {
            for (int fill : new int[]{5, 25}) {
                Count before = original(distance, fill);
                Count after = pruned(distance, fill);
                if (before.enqueues != after.enqueues) {
                    System.out.println("MISMATCH: enqueue counts differ, the pruning is not "
                            + "behaviour-preserving");
                    System.exit(1);
                }
                System.out.printf("%-8d %-6d %12d %14d %12d %10d %10d%n",
                        distance, fill, before.distanceTests, after.distanceTests,
                        before.enqueues, after.bucketsSkipped, after.bucketsTrusted);
            }
        }
        System.out.println();
        // The headline: how much of the per-position work is gone at the configured radius.
        for (int distance : new int[]{20, 32}) {
            Count before = original(distance, 5);
            Count after = pruned(distance, 5);
            double reduction = 100.0 * (1.0 - (double) after.distanceTests / before.distanceTests);
            System.out.printf("r=%d, 5%% fill: distance tests %.1f%% lower, enqueues identical (%d)%n",
                    distance, reduction, after.enqueues);
        }
    }
}
