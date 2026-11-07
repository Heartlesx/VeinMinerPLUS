import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Scan cost per centre, batched so the measurement is not noise.
 *
 * Two earlier attempts at this were wrong in instructive ways and are recorded here so the numbers
 * are not trusted for the wrong reason:
 *
 *  1. Running both variants in one loop let them deoptimise each other - a 4x larger bucket measured
 *     only 2.5x slower, which is impossible if the loop is the loop.
 *  2. Timing one scan per sample gave ~1.5 microseconds with a 5x spread between p10 and p90, while
 *     the real job records ~730 microseconds per centre. Two orders of magnitude apart means the
 *     benchmark was measuring a different thing than the game does.
 *
 * That gap is the finding, not an inconvenience. A centre scan on the cache-hit path genuinely is
 * cheap: 125 section lookups plus a few hundred distance tests. The 730 microseconds in the field
 * must therefore be the first-touch path - {@code ChunkAccess.findBlocks} walking a chunk's whole
 * palette - which this change does not touch at all.
 *
 * So this benchmark reports the cache-hit path only, clearly labelled, with enough repetition to be
 * stable. It answers one question: does the section box test pay for itself on a bucket that is
 * already built? It does not answer "will the 24 starved ticks disappear", and nothing here is
 * evidence that they will.
 *
 * Run: java SparseCacheHitBench [fillPercent...]
 */
public final class SparseCacheHitBench {

    static final int RADIUS = 32;
    /** Scans per timing sample. One scan is far too short to time on its own. */
    static final int BATCH = 2000;
    static final int SAMPLES = 41;

    record Pos(int x, int y, int z) {
    }

    public static void main(String[] args) {
        int[] fills = args.length > 0 ? parse(args) : new int[]{3, 10, 25, 50};
        int[] buckets = {4, 16, 64};

        System.out.println("cache-hit path only: buckets already built, no findBlocks, no chunk load");
        System.out.println("radius=" + RADIUS + ", batch=" + BATCH + " scans/sample, "
                + SAMPLES + " samples, median reported");
        System.out.printf("%-8s %-8s %-10s %14s %14s %10s%n",
                "bucket", "fill%", "accepted", "origNsScan", "pruneNsScan", "change");

        for (int fill : fills) {
            for (int bucket : buckets) {
                Map<Long, Map<Integer, List<Pos>>> world = buildWorld(bucket, fill);
                long maxSquared = (long) RADIUS * RADIUS;

                long a = scanOriginal(world, maxSquared);
                long b = scanPruned(world, maxSquared);
                if (a != b) {
                    System.out.println("MISMATCH accepted " + a + " vs " + b);
                    System.exit(1);
                }

                double original = medianNanosPerScan(world, maxSquared, false);
                double pruned = medianNanosPerScan(world, maxSquared, true);
                double change = 100.0 * (pruned - original) / original;
                System.out.printf("%-8d %-8d %-10d %14.1f %14.1f %9.1f%%%n",
                        bucket, fill, a, original, pruned, change);
            }
        }
        System.out.println();
        System.out.println("negative change = pruning is faster on an already-built bucket");
    }

    static double medianNanosPerScan(Map<Long, Map<Integer, List<Pos>>> world, long maxSquared,
            boolean pruned) {
        // Warm up until the JIT has seen the loop many times, then measure.
        long sink = 0;
        for (int i = 0; i < BATCH * 20; i++) {
            sink += pruned ? scanPruned(world, maxSquared) : scanOriginal(world, maxSquared);
        }
        if (sink == Long.MIN_VALUE) {
            System.out.println("unreachable " + sink);
        }

        double[] samples = new double[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < BATCH; i++) {
                sink += pruned ? scanPruned(world, maxSquared) : scanOriginal(world, maxSquared);
            }
            samples[sample] = (System.nanoTime() - t0) / (double) BATCH;
        }
        java.util.Arrays.sort(samples);
        return samples[SAMPLES / 2];
    }

    static Map<Long, Map<Integer, List<Pos>>> buildWorld(int bucketSize, int fillPercent) {
        Random random = new Random(999331L + bucketSize * 31L + fillPercent);
        Map<Long, Map<Integer, List<Pos>>> world = new HashMap<>();
        for (int cx = (-RADIUS) >> 4; cx <= RADIUS >> 4; cx++) {
            for (int cz = (-RADIUS) >> 4; cz <= RADIUS >> 4; cz++) {
                Map<Integer, List<Pos>> sections = new HashMap<>();
                for (int sy = (-RADIUS) >> 4; sy <= RADIUS >> 4; sy++) {
                    int hash = (cx * 73856093) ^ (sy * 19349663) ^ (cz * 83492791);
                    if (Math.floorMod(hash, 100) >= fillPercent) {
                        continue;
                    }
                    List<Pos> list = new ArrayList<>(bucketSize);
                    for (int i = 0; i < bucketSize; i++) {
                        list.add(new Pos(cx * 16 + random.nextInt(16), sy * 16 + random.nextInt(16),
                                cz * 16 + random.nextInt(16)));
                    }
                    sections.put(sy, list);
                }
                world.put(chunkKey(cx, cz), sections);
            }
        }
        return world;
    }

    static long scanOriginal(Map<Long, Map<Integer, List<Pos>>> world, long maxSquared) {
        long accepted = 0;
        for (int chunkX = (-RADIUS) >> 4; chunkX <= RADIUS >> 4; chunkX++) {
            for (int chunkZ = (-RADIUS) >> 4; chunkZ <= RADIUS >> 4; chunkZ++) {
                Map<Integer, List<Pos>> sections = world.get(chunkKey(chunkX, chunkZ));
                if (sections == null) {
                    continue;
                }
                for (int sectionY = (-RADIUS) >> 4; sectionY <= RADIUS >> 4; sectionY++) {
                    List<Pos> matches = sections.get(sectionY);
                    if (matches == null) {
                        continue;
                    }
                    for (int i = 0; i < matches.size(); i++) {
                        Pos pos = matches.get(i);
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

    static long scanPruned(Map<Long, Map<Integer, List<Pos>>> world, long maxSquared) {
        long accepted = 0;
        for (int chunkX = (-RADIUS) >> 4; chunkX <= RADIUS >> 4; chunkX++) {
            for (int chunkZ = (-RADIUS) >> 4; chunkZ <= RADIUS >> 4; chunkZ++) {
                Map<Integer, List<Pos>> sections = world.get(chunkKey(chunkX, chunkZ));
                if (sections == null) {
                    continue;
                }
                for (int sectionY = (-RADIUS) >> 4; sectionY <= RADIUS >> 4; sectionY++) {
                    List<Pos> matches = sections.get(sectionY);
                    if (matches == null) {
                        continue;
                    }
                    int reach = SparseDiffHarness.classifyIndex(0, 0, 0, chunkX, sectionY, chunkZ,
                            maxSquared);
                    if (reach == 0) {
                        continue;
                    }
                    boolean trustAll = reach == 1;
                    for (int i = 0; i < matches.size(); i++) {
                        if (trustAll) {
                            accepted++;
                            continue;
                        }
                        Pos pos = matches.get(i);
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

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static int[] parse(String[] args) {
        int[] out = new int[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = Integer.parseInt(args[i]);
        }
        return out;
    }
}
