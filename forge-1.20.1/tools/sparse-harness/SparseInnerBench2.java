import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Scan cost, measured one implementation per JVM process.
 *
 * The previous version of this benchmark ran both implementations in the same loop and reported
 * figures that did not scale with the work (a 4x larger bucket measured only 2.5x slower), which
 * means the two paths were deoptimising each other and the numbers were JIT noise. Comparing two
 * shapes of the same loop in one process is exactly the case where the compiler's profile decides
 * the answer.
 *
 * So this runs one implementation, in one process, with a long warmup and then a fixed number of
 * measured rounds. {@code SparseInnerBench.main} launches it twice and compares. Both variants
 * report the accepted count so a wrong comparison fails loudly rather than being believed.
 *
 * Run: java SparseInnerBench2 variant=original|pruned bucket=8 fill=3
 */
public final class SparseInnerBench2 {

    static final int RADIUS = 32;

    record Pos(int x, int y, int z) {
    }

    public static void main(String[] args) {
        String variant = "original";
        int bucketSize = 8;
        int fillPercent = 3;
        int warmupRounds = 3000;
        int measureRounds = 5000;
        for (String arg : args) {
            String[] parts = arg.split("=", 2);
            switch (parts[0]) {
                case "variant" -> variant = parts[1];
                case "bucket" -> bucketSize = Integer.parseInt(parts[1]);
                case "fill" -> fillPercent = Integer.parseInt(parts[1]);
                case "warmup" -> warmupRounds = Integer.parseInt(parts[1]);
                case "rounds" -> measureRounds = Integer.parseInt(parts[1]);
                default -> throw new IllegalArgumentException("unknown argument " + arg);
            }
        }

        Map<Long, Map<Integer, List<Pos>>> world = buildWorld(bucketSize, fillPercent);
        long maxSquared = (long) RADIUS * RADIUS;
        boolean pruned = variant.equals("pruned");

        long accepted = 0;
        for (int i = 0; i < warmupRounds; i++) {
            accepted = pruned ? scanPruned(world, maxSquared) : scanOriginal(world, maxSquared);
        }
        // Read the sink so the loop cannot be optimised away.
        if (accepted < 0) {
            System.out.println("unreachable");
        }

        long[] samples = new long[measureRounds];
        for (int round = 0; round < measureRounds; round++) {
            long t0 = System.nanoTime();
            accepted = pruned ? scanPruned(world, maxSquared) : scanOriginal(world, maxSquared);
            samples[round] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(samples);

        System.out.printf("%s bucket=%d fill=%d accepted=%d medianNs=%.0f p10=%.0f p90=%.0f%n",
                variant, bucketSize, fillPercent, accepted,
                samples[measureRounds / 2] / 1.0,
                samples[measureRounds / 10] / 1.0,
                samples[(measureRounds * 9) / 10] / 1.0);
    }

    static Map<Long, Map<Integer, List<Pos>>> buildWorld(int bucketSize, int fillPercent) {
        Random random = new Random(1234567L + bucketSize * 31L + fillPercent);
        Map<Long, Map<Integer, List<Pos>>> world = new HashMap<>();
        int minChunk = (-RADIUS) >> 4;
        int maxChunk = (RADIUS) >> 4;
        int minSectionY = (-RADIUS) >> 4;
        int maxSectionY = (RADIUS) >> 4;
        for (int cx = minChunk; cx <= maxChunk; cx++) {
            for (int cz = minChunk; cz <= maxChunk; cz++) {
                Map<Integer, List<Pos>> sections = new HashMap<>();
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
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
                    // The shipped predicate, called through the shipped class.
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
}
