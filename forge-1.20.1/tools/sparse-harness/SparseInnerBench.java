import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * JVM microbenchmark of the scan's inner work, at bucket sizes real ore runs produce.
 *
 * The counting harness shows 93% of distance tests disappear, which is true and also not the number
 * that decides whether this helps. Two things make the arithmetic misleading on its own:
 *
 *  - section lookups do not decrease, and {@code HashMap<Integer,...>.get(int)} autoboxes its key,
 *    so a lookup can cost more than the per-position test it saves;
 *  - a real ore bucket holds a handful of positions, not thousands, so the removed work is a small
 *    absolute number per section.
 *
 * This measures the two loops against each other with the real data structures and real bucket
 * sizes, which is the only way to know whether the change is worth its complexity. It is a JVM
 * microbenchmark on synthetic data: it bounds the scan's own cost, and says nothing about the
 * per-block destruction cost that dominates the job.
 *
 * Run: java SparseInnerBench [bucketSize...]
 */
public final class SparseInnerBench {

    static final int RADIUS = 32;
    static final int ROUNDS = 2000;

    /** A position stand-in with the same access shape as BlockPos (three int fields). */
    record Pos(int x, int y, int z) {
    }

    /**
     * Builds the section map the way {@code findBlocks} would: for each resident chunk in range,
     * a handful of matched positions bucketed by section Y. Only a fraction of sections hold
     * anything, matching a sparse ore field.
     */
    static Map<Integer, List<Pos>> buildSections(int chunkX, int chunkZ, int bucketSize, int fillPercent,
            Random random, int centerX, int centerY, int centerZ) {
        Map<Integer, List<Pos>> sections = new HashMap<>();
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        for (int sy = (centerY - RADIUS) >> 4; sy <= (centerY + RADIUS) >> 4; sy++) {
            if (Math.floorMod(hash(chunkX, sy, chunkZ, random), 100) >= fillPercent) {
                continue;
            }
            List<Pos> list = new ArrayList<>(bucketSize);
            for (int i = 0; i < bucketSize; i++) {
                list.add(new Pos(baseX + random.nextInt(16), sy * 16 + random.nextInt(16),
                        baseZ + random.nextInt(16)));
            }
            sections.put(sy, list);
        }
        return sections;
    }

    static int hash(int a, int b, int c, Random random) {
        return (a * 73856093) ^ (b * 19349663) ^ (c * 83492791);
    }

    /** The original inner loop: test every position in every reachable section. */
    static long scanOriginal(Map<Long, Map<Integer, List<Pos>>> world, int centerX, int centerY,
            int centerZ, long maxSquared) {
        long accepted = 0;
        int minChunkX = (centerX - RADIUS) >> 4;
        int maxChunkX = (centerX + RADIUS) >> 4;
        int minChunkZ = (centerZ - RADIUS) >> 4;
        int maxChunkZ = (centerZ + RADIUS) >> 4;
        int minSectionY = (centerY - RADIUS) >> 4;
        int maxSectionY = (centerY + RADIUS) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                Map<Integer, List<Pos>> sections = world.get(chunkKey(chunkX, chunkZ));
                if (sections == null) {
                    continue;
                }
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    List<Pos> matches = sections.get(sectionY);
                    if (matches == null) {
                        continue;
                    }
                    for (Pos pos : matches) {
                        long dx = (long) centerX - pos.x();
                        long dy = (long) centerY - pos.y();
                        long dz = (long) centerZ - pos.z();
                        if (dx * dx + dy * dy + dz * dz <= maxSquared) {
                            accepted++;
                        }
                    }
                }
            }
        }
        return accepted;
    }

    /** The pruned loop: the section box decides first. */
    static long scanPruned(Map<Long, Map<Integer, List<Pos>>> world, int centerX, int centerY,
            int centerZ, long maxSquared) {
        long accepted = 0;
        int minChunkX = (centerX - RADIUS) >> 4;
        int maxChunkX = (centerX + RADIUS) >> 4;
        int minChunkZ = (centerZ - RADIUS) >> 4;
        int maxChunkZ = (centerZ + RADIUS) >> 4;
        int minSectionY = (centerY - RADIUS) >> 4;
        int maxSectionY = (centerY + RADIUS) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                Map<Integer, List<Pos>> sections = world.get(chunkKey(chunkX, chunkZ));
                if (sections == null) {
                    continue;
                }
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    List<Pos> matches = sections.get(sectionY);
                    if (matches == null) {
                        continue;
                    }
                    SparseDiffHarness.Reach reach = SparseDiffHarness.classify(
                            centerX, centerY, centerZ, chunkX, sectionY, chunkZ, maxSquared);
                    if (reach == SparseDiffHarness.Reach.OUTSIDE) {
                        continue;
                    }
                    boolean trustAll = reach == SparseDiffHarness.Reach.FULLY_INSIDE;
                    for (Pos pos : matches) {
                        if (trustAll) {
                            accepted++;
                            continue;
                        }
                        long dx = (long) centerX - pos.x();
                        long dy = (long) centerY - pos.y();
                        long dz = (long) centerZ - pos.z();
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

    public static void main(String[] args) {
        int[] bucketSizes = args.length > 0 ? parse(args) : new int[]{2, 8, 32, 128};
        long maxSquared = (long) RADIUS * RADIUS;

        System.out.println("radius=" + RADIUS + ", rounds=" + ROUNDS
                + ", sparse ore field, JVM microbenchmark (not a game measurement)");
        System.out.printf("%-8s %-8s %-8s %14s %14s %10s%n",
                "bucket", "fill%", "agree", "originalNs", "prunedNs", "change");

        for (int fill : new int[]{3, 10}) {
            for (int size : bucketSizes) {
                Random random = new Random(1234567L + size * 31L + fill);
                Map<Long, Map<Integer, List<Pos>>> world = new HashMap<>();
                int minChunk = (-RADIUS) >> 4;
                int maxChunk = (RADIUS) >> 4;
                for (int cx = minChunk; cx <= maxChunk; cx++) {
                    for (int cz = minChunk; cz <= maxChunk; cz++) {
                        world.put(chunkKey(cx, cz), buildSections(cx, cz, size, fill, random, 0, 0, 0));
                    }
                }

                // Warm up both paths so the comparison is not measuring the interpreter.
                long originalAccepted = 0;
                long prunedAccepted = 0;
                for (int i = 0; i < 200; i++) {
                    originalAccepted = scanOriginal(world, 0, 0, 0, maxSquared);
                    prunedAccepted = scanPruned(world, 0, 0, 0, maxSquared);
                }
                if (originalAccepted != prunedAccepted) {
                    System.out.println("MISMATCH: " + originalAccepted + " vs " + prunedAccepted);
                    System.exit(1);
                }

                long originalNanos = 0;
                long prunedNanos = 0;
                for (int round = 0; round < ROUNDS; round++) {
                    long t0 = System.nanoTime();
                    scanOriginal(world, 0, 0, 0, maxSquared);
                    long t1 = System.nanoTime();
                    scanPruned(world, 0, 0, 0, maxSquared);
                    long t2 = System.nanoTime();
                    originalNanos += t1 - t0;
                    prunedNanos += t2 - t1;
                }
                double originalAvg = originalNanos / (double) ROUNDS;
                double prunedAvg = prunedNanos / (double) ROUNDS;
                double change = 100.0 * (prunedAvg - originalAvg) / originalAvg;
                System.out.printf("%-8d %-8d %-8d %14.0f %14.0f %9.1f%%%n",
                        size, fill, originalAccepted, originalAvg, prunedAvg, change);
            }
        }
        System.out.println();
        System.out.println("negative change = pruned scan is faster");
    }

    private static int[] parse(String[] args) {
        int[] out = new int[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = Integer.parseInt(args[i]);
        }
        return out;
    }
}
