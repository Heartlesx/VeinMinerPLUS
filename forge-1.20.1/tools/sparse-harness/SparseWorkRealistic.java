import java.util.ArrayList;
import java.util.List;

/**
 * The same work count, but with bucket sizes taken from the real dev6 runs instead of "a section is
 * full".
 *
 * The first version of this counter assumed a matched section holds 4096 positions. It never does:
 * a bucket holds only the positions that matched the filter - ore, logs, or one block type - which
 * in the ore scenario is a handful per section. That assumption inflates the saving to 93-96% and is
 * exactly the kind of number that turns into a false claim, so it is not reported.
 *
 * The saving this change can actually deliver is bounded by how much per-position testing there is:
 *   - every section outside the sphere costs one box test instead of (bucket size) distance tests;
 *   - every section fully inside saves its bucket size;
 *   - a partial section costs the same as before plus one box test.
 *
 * Section lookups are unchanged, and they are a large share of the scan when buckets are small, so
 * the honest headline is "distance tests removed", not "scan time reduced".
 *
 * Run: java SparseWorkRealistic &lt;bucketSize&gt; [bucketSize...]
 */
public final class SparseWorkRealistic {

    /**
     * A section either holds {@code bucketSize} positions or holds none. Real runs are somewhere
     * between "almost all empty" and "a few per section", so a range is reported rather than one
     * figure.
     */
    static final class Tally {
        long sectionLookups;
        long occupiedSections;
        long distanceTestsBefore;
        long distanceTestsAfter;
        long enqueuesBefore;
        long enqueuesAfter;
        long sectionsSkipped;
        long sectionsTrusted;
        long sectionsPartial;
    }

    static Tally measure(int distance, int bucketSize, int occupiedPercent) {
        Tally tally = new Tally();
        long maxSquared = (long) distance * distance;
        int span = (2 * distance) / 16 + 2;
        for (int sx = -span; sx <= span; sx++) {
            for (int sz = -span; sz <= span; sz++) {
                for (int sy = -span; sy <= span; sy++) {
                    tally.sectionLookups++;
                    if (!SparseWorkCounter.occupied(sx, sy, sz, occupiedPercent)) {
                        continue;
                    }
                    tally.occupiedSections++;
                    SparseDiffHarness.Reach reach = SparseDiffHarness.classify(
                            0, 0, 0, sx, sy, sz, maxSquared);

                    // Before: every position in the bucket is distance-tested.
                    long inside = 0;
                    for (int i = 0; i < bucketSize; i++) {
                        if (positionInside(i, bucketSize, sx, sy, sz, maxSquared)) {
                            inside++;
                        }
                    }
                    tally.distanceTestsBefore += bucketSize;
                    tally.enqueuesBefore += inside;

                    // After: the box decides what it can.
                    switch (reach) {
                        case OUTSIDE -> {
                            tally.sectionsSkipped++;
                            // No tests at all.
                        }
                        case FULLY_INSIDE -> {
                            tally.sectionsTrusted++;
                            tally.enqueuesAfter += bucketSize;
                        }
                        case PARTIAL -> {
                            tally.sectionsPartial++;
                            tally.distanceTestsAfter += bucketSize;
                            tally.enqueuesAfter += inside;
                        }
                    }
                }
            }
        }
        return tally;
    }

    /**
     * Whether the i-th of {@code count} positions spread through a section is inside the sphere.
     *
     * The positions are spread evenly across the section rather than packed into one corner, so the
     * count is not biased by assuming a cluster shape.
     */
    static boolean positionInside(int index, int count, int sx, int sy, int sz, long maxSquared) {
        int step = Math.max(1, 4096 / Math.max(1, count));
        int cell = index * step;
        int dx = sx * 16 + (cell & 15);
        int dy = sy * 16 + ((cell >> 4) & 15);
        int dz = sz * 16 + ((cell >> 8) & 15);
        return (long) dx * dx + (long) dy * dy + (long) dz * dz <= maxSquared;
    }

    public static void main(String[] args) {
        int[] bucketSizes = args.length > 0
                ? parse(args)
                : new int[]{4, 16, 64, 256};
        int[] fillPercents = {2, 5, 25};

        for (int distance : new int[]{20, 32}) {
            System.out.println("=== radius " + distance + " ===");
            System.out.printf("%-8s %-6s %10s %10s %10s %10s %10s %10s%n",
                    "bucket", "fill%", "occupied", "lookups", "testsBef", "testsAft",
                    "skipped", "trusted");
            for (int fill : fillPercents) {
                for (int size : bucketSizes) {
                    Tally t = measure(distance, size, fill);
                    if (t.enqueuesBefore != t.enqueuesAfter) {
                        System.out.println("MISMATCH enqueues at bucket=" + size + " fill=" + fill);
                        System.exit(1);
                    }
                    System.out.printf("%-8d %-6d %10d %10d %10d %10d %10d %10d%n",
                            size, fill, t.occupiedSections, t.sectionLookups,
                            t.distanceTestsBefore, t.distanceTestsAfter,
                            t.sectionsSkipped, t.sectionsTrusted);
                }
            }
            System.out.println();
        }

        System.out.println("=== share of the scan's per-position work removed ===");
        for (int distance : new int[]{20, 32}) {
            for (int size : bucketSizes) {
                Tally t = measure(distance, size, 5);
                double pct = t.distanceTestsBefore == 0 ? 0.0
                        : 100.0 * (1.0 - (double) t.distanceTestsAfter / t.distanceTestsBefore);
                System.out.printf("r=%d bucket=%d: %.1f%% of distance tests removed "
                                + "(%d -> %d), enqueues identical (%d)%n",
                        distance, size, pct, t.distanceTestsBefore, t.distanceTestsAfter,
                        t.enqueuesAfter);
            }
        }
    }

    private static int[] parse(String[] args) {
        List<Integer> values = new ArrayList<>();
        for (String arg : args) {
            values.add(Integer.parseInt(arg));
        }
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
