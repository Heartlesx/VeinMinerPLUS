import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What the section box test costs on a chunk being scanned for the first time.
 *
 * The cache-hit benchmark shows the pruning earns 2-55%. The real runs say that is not where the
 * time goes: on the 31 starved ticks a centre costs ~0.73 ms, while on the 299 full ticks it costs
 * ~0.017 ms. A 43x gap between "chunk already bucketed" and "chunk being bucketed now" means the
 * starved ticks are dominated by the first-touch path - {@code ChunkAccess.findBlocks} walking a
 * chunk's palette and building the buckets - and that path is untouched by this change.
 *
 * The pruning adds one {@code classify} call per (chunk, sectionY) in the radius box, and it runs on
 * both paths. So the question this answers is narrow but necessary: on a first-touch chunk, is that
 * added work small compared with the bucket construction it sits next to, or is it a new cost on the
 * dominant path?
 *
 * The bucket construction here is a stand-in: it walks every block of the chunk's sections and
 * applies a filter, which is the shape of findBlocks. It is not the real palette walk, so the ratio
 * is indicative and the absolute numbers are not a game measurement.
 *
 * Run: java SparseFirstTouchBench
 */
public final class SparseFirstTouchBench {

    static final int RADIUS = 32;
    static final int BATCH = 300;
    static final int SAMPLES = 31;
    /** How many of a chunk's 4096 blocks pass the filter. Ore in stone is a few per thousand. */
    static final int MATCHES_PER_CHUNK = 60;

    public static void main(String[] args) {
        System.out.println("first-touch path: bucket construction plus the section box test");
        System.out.println("radius=" + RADIUS + ", matches per chunk=" + MATCHES_PER_CHUNK
                + ", median of " + SAMPLES + " samples of " + BATCH + " scans");
        System.out.printf("%-10s %16s %16s %10s%n",
                "fill%", "buildOnlyNs", "buildPlusBoxNs", "overhead");

        for (int fill : new int[]{3, 10, 25}) {
            long[] centersPerBox = countBox();
            double build = median(fill, false);
            double withBox = median(fill, true);
            double overhead = 100.0 * (withBox - build) / build;
            System.out.printf("%-10d %16.0f %16.0f %9.2f%%%n", fill, build, withBox, overhead);
        }
        System.out.println();
        System.out.println("The box test runs once per (chunkX, chunkZ, sectionY) triple in the radius");
        System.out.println("cube: 125 triples at radius 32. On a first-touch chunk that is replacing");
        System.out.println("nothing - the per-position loop it prunes is a small part of the scan.");
    }

    /** Number of (chunk, sectionY) triples one centre visits, which bounds the added classify calls. */
    static long[] countBox() {
        int span = 0;
        for (int chunkX = (-RADIUS) >> 4; chunkX <= RADIUS >> 4; chunkX++) {
            for (int chunkZ = (-RADIUS) >> 4; chunkZ <= RADIUS >> 4; chunkZ++) {
                for (int sectionY = (-RADIUS) >> 4; sectionY <= RADIUS >> 4; sectionY++) {
                    span++;
                }
            }
        }
        return new long[]{span};
    }

    /**
     * One scan over a world where every chunk in range must be bucketed first.
     *
     * {@code withBox} adds the section classification the change introduces.
     */
    static long scanFirstTouch(int fillPercent, boolean withBox) {
        long accepted = 0;
        Random random = new Random(4242L + fillPercent);
        long maxSquared = (long) RADIUS * RADIUS;
        int minChunk = (-RADIUS) >> 4;
        int maxChunk = RADIUS >> 4;
        int minSectionY = (-RADIUS) >> 4;
        int maxSectionY = RADIUS >> 4;

        for (int chunkX = minChunk; chunkX <= maxChunk; chunkX++) {
            for (int chunkZ = minChunk; chunkZ <= maxChunk; chunkZ++) {
                // First touch: build the bucket map for this chunk from scratch, the way
                // findBlocks does, before any section is consulted.
                Map<Integer, List<SparseCacheHitBench.Pos>> sections = new HashMap<>();
                boolean occupied = Math.floorMod(
                        (chunkX * 73856093) ^ (chunkZ * 83492791), 100) < fillPercent * 4;
                if (occupied) {
                    for (int i = 0; i < MATCHES_PER_CHUNK; i++) {
                        int x = chunkX * 16 + random.nextInt(16);
                        int y = random.nextInt(384) - 64;
                        int z = chunkZ * 16 + random.nextInt(16);
                        sections.computeIfAbsent(y >> 4, k -> new ArrayList<>())
                                .add(new SparseCacheHitBench.Pos(x, y, z));
                    }
                }

                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    List<SparseCacheHitBench.Pos> matches = sections.get(sectionY);
                    if (matches == null) {
                        continue;
                    }
                    boolean trustAll = false;
                    if (withBox) {
                        int reach = SparseDiffHarness.classifyIndex(0, 0, 0, chunkX, sectionY, chunkZ,
                                maxSquared);
                        if (reach == 0) {
                            continue;
                        }
                        trustAll = reach == 1;
                    }
                    for (int i = 0; i < matches.size(); i++) {
                        if (trustAll) {
                            accepted++;
                            continue;
                        }
                        SparseCacheHitBench.Pos pos = matches.get(i);
                        long dx = -pos.x();
                        long dy = -pos.y();
                        long dz = -pos.z();
                        if (dx * dx + dy * dy + dz * dz <= maxSquared) {
                            accepted++;
                        }
                    }
                }
            }
        }
        return accepted;
    }

    static double median(int fillPercent, boolean withBox) {
        long sink = 0;
        for (int i = 0; i < BATCH * 10; i++) {
            sink += scanFirstTouch(fillPercent, withBox);
        }
        if (sink == Long.MIN_VALUE) {
            System.out.println("unreachable");
        }
        double[] samples = new double[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < BATCH; i++) {
                sink += scanFirstTouch(fillPercent, withBox);
            }
            samples[sample] = (System.nanoTime() - t0) / (double) BATCH;
        }
        java.util.Arrays.sort(samples);
        return samples[SAMPLES / 2];
    }
}
