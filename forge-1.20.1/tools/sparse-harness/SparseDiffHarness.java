/**
 * Differential harness for the sparse scan's section pruning.
 *
 * The change under test replaces a per-position distance test with a per-section box test, on the
 * claim that the two accept exactly the same positions. That claim is easy to write and easy to get
 * subtly wrong - an off-by-one in the box bounds, an inverted comparison, a section coordinate
 * confusion between {@code >>4} and a block coordinate - and every one of those mistakes would look
 * like a working build: fewer targets, lower throughput, no exception.
 *
 * So this does not test the geometry formula in isolation. It runs the <em>original</em> scan
 * algorithm and the <em>new</em> one over the same synthetic world and the same centre sequence and
 * compares everything the rest of the job can observe:
 *
 *   - which positions each centre discovers and enqueues, in order;
 *   - the order the priority queue hands them back;
 *   - the final bucket contents, since a bucket is state a later centre reads;
 *   - the final set of positions ever enqueued.
 *
 * Both implementations are copied verbatim from ChainEvents and differ only in the pruning step, so
 * a divergence is a real behavioural difference and not a harness artefact.
 *
 * Run: javac SparseDiffHarness.java && java SparseDiffHarness
 */
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class SparseDiffHarness {

    private static int failures;
    private static int scenarios;

    // ==== geometry, copied from SparseSectionGeometry ====

    static final int SECTION_SIZE = 16;

    enum Reach { OUTSIDE, FULLY_INSIDE, PARTIAL }

    static long axisGap(int center, int section) {
        int low = section * SECTION_SIZE;
        int high = low + SECTION_SIZE - 1;
        if (center < low) {
            return (long) low - center;
        }
        if (center > high) {
            return (long) center - high;
        }
        return 0L;
    }

    static long axisFar(int center, int section) {
        int low = section * SECTION_SIZE;
        int high = low + SECTION_SIZE - 1;
        long toLow = Math.abs((long) center - low);
        long toHigh = Math.abs((long) center - high);
        return Math.max(toLow, toHigh);
    }

    static long minSquaredDistance(int cx, int cy, int cz, int sx, int sy, int sz) {
        long dx = axisGap(cx, sx), dy = axisGap(cy, sy), dz = axisGap(cz, sz);
        return dx * dx + dy * dy + dz * dz;
    }

    static long maxSquaredDistance(int cx, int cy, int cz, int sx, int sy, int sz) {
        long dx = axisFar(cx, sx), dy = axisFar(cy, sy), dz = axisFar(cz, sz);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Same verdict, as an int, so a benchmark has no enum dispatch to measure. */
    static int classifyIndex(int cx, int cy, int cz, int sx, int sy, int sz, long maxSquaredRadius) {
        if (minSquaredDistance(cx, cy, cz, sx, sy, sz) > maxSquaredRadius) {
            return 0;
        }
        if (maxSquaredDistance(cx, cy, cz, sx, sy, sz) <= maxSquaredRadius) {
            return 1;
        }
        return 2;
    }

    static Reach classify(int cx, int cy, int cz, int sx, int sy, int sz, long maxSquaredRadius) {
        if (minSquaredDistance(cx, cy, cz, sx, sy, sz) > maxSquaredRadius) {
            return Reach.OUTSIDE;
        }
        if (maxSquaredDistance(cx, cy, cz, sx, sy, sz) <= maxSquaredRadius) {
            return Reach.FULLY_INSIDE;
        }
        return Reach.PARTIAL;
    }

    // ==== a position and the world it lives in ====

    record P(int x, int y, int z) implements Comparable<P> {
        P immutable() {
            return this;
        }

        @Override
        public int compareTo(P other) {
            int c = Integer.compare(y, other.y);
            if (c != 0) return c;
            c = Integer.compare(x, other.x);
            return c != 0 ? c : Integer.compare(z, other.z);
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + "," + z + ")";
        }
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    static long squaredDistance(P a, P b) {
        long dx = (long) a.x() - b.x();
        long dy = (long) a.y() - b.y();
        long dz = (long) a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * A synthetic world: the matching positions, grouped the way {@code ChunkAccess.findBlocks}
     * would group them, plus a set of chunks that exist. A chunk outside {@code present} models
     * "not resident" and must be skipped by both implementations without generating anything.
     */
    static final class World {
        final List<P> all = new ArrayList<>();
        final Set<Long> present = new HashSet<>();

        World add(int x, int y, int z) {
            all.add(new P(x, y, z));
            present.add(chunkKey(x >> 4, z >> 4));
            return this;
        }

        /** Matching positions inside one chunk, as findBlocks would report them. */
        List<P> inChunk(int chunkX, int chunkZ) {
            List<P> out = new ArrayList<>();
            for (P p : all) {
                if ((p.x() >> 4) == chunkX && (p.z() >> 4) == chunkZ) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    // ==== the two scan implementations ====

    /** Shared observable state: exactly what ChainJob keeps between centres. */
    static final class State {
        final Set<P> examined = new HashSet<>();
        final Map<Long, Map<Integer, List<P>>> sparseChunkMatches = new LinkedHashMap<>();
        final Map<Long, List<P>> loadedChunks = new HashMap<>();
        final List<P> discoveryLog = new ArrayList<>();
        final PriorityQueue<P> queue;

        State(P origin) {
            // The comparator ChainEvents builds: distance from the job's origin, then y, x, z.
            this.queue = new PriorityQueue<>(Comparator
                    .comparingLong((P p) -> squaredDistance(origin, p))
                    .thenComparingInt(P::y)
                    .thenComparingInt(P::x)
                    .thenComparingInt(P::z));
        }

        void enqueue(P p) {
            if (examined.add(p)) {
                queue.add(p);
                discoveryLog.add(p);
            }
        }

        /** Pops everything, recording order. */
        List<P> drain() {
            List<P> out = new ArrayList<>();
            while (!queue.isEmpty()) {
                out.add(queue.poll());
            }
            return out;
        }
    }

    /**
     * The original scan: build buckets if the chunk has never been seen, then test every position in
     * every section in range against the sphere.
     */
    static void scanOriginal(World world, P center, int distance, State state) {
        long maxSquaredDistance = (long) distance * distance;
        int minChunkX = (center.x() - distance) >> 4;
        int maxChunkX = (center.x() + distance) >> 4;
        int minChunkZ = (center.z() - distance) >> 4;
        int maxChunkZ = (center.z() + distance) >> 4;
        int minSectionY = (center.y() - distance) >> 4;
        int maxSectionY = (center.y() + distance) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long key = chunkKey(chunkX, chunkZ);
                Map<Integer, List<P>> sections = state.sparseChunkMatches.get(key);
                if (sections == null) {
                    if (!world.present.contains(key)) {
                        continue;
                    }
                    state.loadedChunks.put(key, world.inChunk(chunkX, chunkZ));
                    sections = new HashMap<>();
                    for (P pos : world.inChunk(chunkX, chunkZ)) {
                        sections.computeIfAbsent(pos.y() >> 4, k -> new ArrayList<>()).add(pos);
                    }
                    state.sparseChunkMatches.put(key, sections);
                }

                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    List<P> matches = sections.get(sectionY);
                    if (matches == null) {
                        continue;
                    }
                    for (int index = 0; index < matches.size(); ) {
                        P pos = matches.get(index);
                        if (squaredDistance(center, pos) > maxSquaredDistance) {
                            index++;
                            continue;
                        }
                        matches.set(index, matches.get(matches.size() - 1));
                        matches.remove(matches.size() - 1);
                        state.enqueue(pos);
                    }
                }
            }
        }
    }

    /** The new scan: identical, plus a box test that can skip or trust a whole section. */
    static void scanPruned(World world, P center, int distance, State state) {
        long maxSquaredDistance = (long) distance * distance;
        int minChunkX = (center.x() - distance) >> 4;
        int maxChunkX = (center.x() + distance) >> 4;
        int minChunkZ = (center.z() - distance) >> 4;
        int maxChunkZ = (center.z() + distance) >> 4;
        int minSectionY = (center.y() - distance) >> 4;
        int maxSectionY = (center.y() + distance) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long key = chunkKey(chunkX, chunkZ);
                Map<Integer, List<P>> sections = state.sparseChunkMatches.get(key);
                if (sections == null) {
                    if (!world.present.contains(key)) {
                        continue;
                    }
                    state.loadedChunks.put(key, world.inChunk(chunkX, chunkZ));
                    sections = new HashMap<>();
                    for (P pos : world.inChunk(chunkX, chunkZ)) {
                        sections.computeIfAbsent(pos.y() >> 4, k -> new ArrayList<>()).add(pos);
                    }
                    state.sparseChunkMatches.put(key, sections);
                }

                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    List<P> matches = sections.get(sectionY);
                    if (matches == null) {
                        continue;
                    }
                    Reach reach = classify(center.x(), center.y(), center.z(),
                            chunkX, sectionY, chunkZ, maxSquaredDistance);
                    if (reach == Reach.OUTSIDE) {
                        continue;
                    }
                    boolean everyPositionInside = reach == Reach.FULLY_INSIDE;

                    for (int index = 0; index < matches.size(); ) {
                        P pos = matches.get(index);
                        if (!everyPositionInside && squaredDistance(center, pos) > maxSquaredDistance) {
                            index++;
                            continue;
                        }
                        matches.set(index, matches.get(matches.size() - 1));
                        matches.remove(matches.size() - 1);
                        state.enqueue(pos);
                    }
                }
            }
        }
    }

    // ==== comparison ====

    /**
     * Runs both implementations over one scenario and reports the first difference.
     *
     * The centre sequence is applied one centre at a time with a full queue drain in between, so the
     * comparison covers pop order as well as discovery: the job pops a target, breaks it, and feeds
     * it back as a new centre, which is exactly the loop reproduced here.
     */
    static void compare(String name, World world, List<P> centers, int distance) {
        scenarios++;
        State original = new State(centers.get(0));
        State pruned = new State(centers.get(0));

        for (int i = 0; i < centers.size(); i++) {
            P center = centers.get(i);
            int before = original.discoveryLog.size();
            scanOriginal(world, center, distance, original);
            int originalDiscovered = original.discoveryLog.size() - before;

            int beforeP = pruned.discoveryLog.size();
            scanPruned(world, center, distance, pruned);
            int prunedDiscovered = pruned.discoveryLog.size() - beforeP;

            if (originalDiscovered != prunedDiscovered) {
                fail(name, "centre " + i + " " + center + " discovered " + prunedDiscovered
                        + " targets, original discovered " + originalDiscovered);
                return;
            }
            List<P> originalEnqueued = original.discoveryLog.subList(before, original.discoveryLog.size());
            List<P> prunedEnqueued = pruned.discoveryLog.subList(beforeP, pruned.discoveryLog.size());
            if (!originalEnqueued.equals(prunedEnqueued)) {
                fail(name, "centre " + i + " " + center + " enqueue order differs: "
                        + firstDifference(originalEnqueued, prunedEnqueued));
                return;
            }

            List<P> originalOrder = original.drain();
            List<P> prunedOrder = pruned.drain();
            if (!originalOrder.equals(prunedOrder)) {
                fail(name, "centre " + i + " " + center + " pop order differs: "
                        + firstDifference(originalOrder, prunedOrder));
                return;
            }
        }

        if (!original.examined.equals(pruned.examined)) {
            fail(name, "final examined set differs: " + describeSetDifference(original.examined, pruned.examined));
            return;
        }
        if (!bucketSnapshot(original.sparseChunkMatches).equals(bucketSnapshot(pruned.sparseChunkMatches))) {
            fail(name, "final bucket contents differ");
            return;
        }
        pass(name, original.discoveryLog.size() + " targets");
    }

    static String firstDifference(List<P> a, List<P> b) {
        int limit = Math.min(a.size(), b.size());
        for (int i = 0; i < limit; i++) {
            if (!a.get(i).equals(b.get(i))) {
                return "index " + i + " is " + a.get(i) + " vs " + b.get(i);
            }
        }
        return "length " + a.size() + " vs " + b.size();
    }

    static String describeSetDifference(Set<P> a, Set<P> b) {
        Set<P> onlyOriginal = new HashSet<>(a);
        onlyOriginal.removeAll(b);
        Set<P> onlyPruned = new HashSet<>(b);
        onlyPruned.removeAll(a);
        return "only-in-original=" + sample(onlyOriginal) + " only-in-pruned=" + sample(onlyPruned);
    }

    static String sample(Set<P> set) {
        List<P> list = new ArrayList<>(set);
        list.sort(Comparator.naturalOrder());
        return list.size() > 5 ? list.subList(0, 5) + "..." : list.toString();
    }

    /** Deep, order-sensitive snapshot: a bucket's contents are read by later centres. */
    static Map<String, List<P>> bucketSnapshot(Map<Long, Map<Integer, List<P>>> matches) {
        Map<String, List<P>> out = new LinkedHashMap<>();
        matches.forEach((chunkKey, sections) -> sections.forEach((sectionY, list) ->
                out.put(chunkKey + ":" + sectionY, new ArrayList<>(list))));
        return out;
    }

    // ==== scenarios ====

    /**
     * A dense field spanning several chunks and sections in all directions, so both verdicts get
     * exercised: sections fully inside, sections cut by the sphere, sections fully outside.
     */
    static World field(int radius, int spacing) {
        World world = new World();
        for (int x = -radius; x <= radius; x += spacing) {
            for (int y = -radius; y <= radius; y += spacing) {
                for (int z = -radius; z <= radius; z += spacing) {
                    world.add(x, y, z);
                }
            }
        }
        return world;
    }

    static void centeredOrigin() {
        World world = field(40, 5);
        compare("dense field, origin centre", world, List.of(new P(0, 0, 0)), 20);
    }

    /** A sphere radius that lands exactly on block coordinates: equality must be a target. */
    static void onSphereEquality() {
        World world = new World();
        // Radius 16 around the origin: these are exactly 16 away, on the surface.
        world.add(16, 0, 0).add(-16, 0, 0).add(0, 16, 0).add(0, -16, 0)
                .add(0, 0, 16).add(0, 0, -16);
        // 17 away: the first ring that must be excluded.
        world.add(17, 0, 0).add(0, 17, 0).add(0, 0, 17);
        // Pythagorean surface points for radius 16 (12,12,8 -> 144+144+64=352 too big);
        // use radius 25: 7-24-25 and 15-20-25 lie exactly on it.
        World large = new World();
        large.add(7, 24, 0).add(15, 20, 0).add(0, 7, 24).add(24, 0, 7)
                .add(-7, -24, 0).add(-15, -20, 0);
        // Just outside.
        large.add(8, 24, 0).add(15, 21, 0).add(1, 7, 24);
        compare("on-sphere equality, r=16", world, List.of(new P(0, 0, 0)), 16);
        compare("on-sphere equality, r=25", large, List.of(new P(0, 0, 0)), 25);
    }

    /** Section corners: positions at the extreme block of a section, where an off-by-one shows. */
    static void sectionCorners() {
        World world = new World();
        for (int sectionX = -2; sectionX <= 2; sectionX++) {
            for (int sectionY = -2; sectionY <= 2; sectionY++) {
                for (int sectionZ = -2; sectionZ <= 2; sectionZ++) {
                    int baseX = sectionX * 16;
                    int baseY = sectionY * 16;
                    int baseZ = sectionZ * 16;
                    // all eight corners of each section
                    for (int dx : new int[]{0, 15}) {
                        for (int dy : new int[]{0, 15}) {
                            for (int dz : new int[]{0, 15}) {
                                world.add(baseX + dx, baseY + dy, baseZ + dz);
                            }
                        }
                    }
                }
            }
        }
        compare("section corners, r=16", world, List.of(new P(8, 8, 8)), 16);
        compare("section corners, r=20", world, List.of(new P(1, 1, 1)), 20);
        compare("section corners, r=33", world, List.of(new P(-7, 24, 3)), 33);
    }

    /** Negative coordinates and negative Y, where >>4 floors rather than truncates. */
    static void negativeCoordinates() {
        World world = field(48, 7);
        compare("negative centre, r=20", world, List.of(new P(-37, -41, -23)), 20);
        compare("deep negative Y, r=24", world, List.of(new P(-3, -60, 11)), 24);
        compare("straddling zero, r=17", world, List.of(new P(-1, 0, 1)), 17);
        // A centre exactly on a section boundary.
        compare("brace at section edge, r=16", world, List.of(new P(16, -16, 32)), 16);
    }

    /** Empty and never-discovered sections: a bucket that lost all its contents must not be special. */
    static void emptyAndUndiscoveredBuckets() {
        World world = new World();
        // A single position, so most sections in range are simply not in the map at all.
        world.add(0, 0, 0);
        compare("one position, empty buckets, r=20", world,
                List.of(new P(0, 0, 0), new P(5, 5, 5), new P(-5, -5, -5)), 20);

        // A bucket that this centre empties, then a second centre that finds nothing left in it.
        World drained = new World();
        drained.add(0, 0, 0).add(1, 0, 0).add(2, 0, 0);
        compare("bucket drained then revisited, r=20", drained,
                List.of(new P(0, 0, 0), new P(0, 0, 0), new P(0, 0, 0)), 20);

        // Positions concentrated in one section that is fully inside the sphere.
        World inner = new World();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                inner.add(x, y, 0);
            }
        }
        compare("fully-inside section, r=20", inner, List.of(new P(0, 0, 0)), 20);
    }

    /** Chunks that appear part-way through: the cache is per job, so arrival order matters. */
    static void dynamicWorldAndSnapshotSemantics() {
        // A position whose chunk is not resident at scan time must be invisible, and the chunk must
        // not be remembered as "checked and empty" in a way that hides it later. Both
        // implementations share the discovery step, so this pins the contract rather than the change.
        World absent = new World();
        absent.all.add(new P(0, 0, 0));
        absent.all.add(new P(40, 0, 0));
        absent.all.add(new P(41, 0, 0));
        absent.present.add(chunkKey(0, 0));
        absent.present.remove(chunkKey(2, 0));
        compare("chunk absent at scan time, r=20", absent, List.of(new P(0, 0, 0)), 20);

        // Now the same scenario with the chunk present from the start.
        World present = new World();
        present.add(0, 0, 0).add(40, 0, 0).add(41, 0, 0);
        compare("chunk present, cross-chunk reach, r=20", present, List.of(new P(0, 0, 0)), 20);
        compare("chunk present, two centres, r=20", present,
                List.of(new P(0, 0, 0), new P(40, 0, 0)), 20);

        // The job adds a centre for every block it breaks, so a centre can be a position the first
        // scan never enqueued. Both implementations must treat it the same way.
        World growing = new World();
        growing.add(0, 0, 0).add(3, 0, 0).add(6, 0, 0).add(30, 0, 0);
        compare("centre added where no target was, r=20", growing,
                List.of(new P(0, 0, 0), new P(15, 0, 0), new P(30, 0, 0)), 20);
    }

    /** R32 and the configured bounds: the largest radius the job can ask for. */
    static void radiusBounds() {
        World dense = field(140, 13);
        for (int distance : new int[]{3, 4, 16, 17, 20, 32, 64, 128}) {
            compare("radius " + distance, dense, List.of(new P(0, 0, 0)), distance);
        }
        compare("R32 off-centre", dense, List.of(new P(-61, -23, 47)), 32);
        compare("R128 off-centre", dense, List.of(new P(11, -100, -77)), 128);
    }

    /** A long centre sequence, the shape the job actually produces: each break becomes a centre. */
    static void longCentreSequence() {
        World world = new World();
        // A vein-like cluster: dense core, sparse tail, spread over many sections.
        for (int i = 0; i < 90; i++) {
            world.add((i * 7) % 61 - 30, (i * 11) % 41 - 20, (i * 13) % 53 - 26);
        }
        List<P> centers = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            centers.add(new P((i * 5) % 51 - 25, (i * 3) % 31 - 15, (i * 9) % 47 - 23));
        }
        compare("long centre sequence, r=20", world, centers, 20);
        compare("long centre sequence, r=32", world, centers, 32);
    }

    /** The box verdict must agree with the per-position predicate at every block of a section. */
    static void classifyMatchesPositionTest() {
        scenarios++;
        String problem = null;
        int checked = 0;
        for (int distance : new int[]{3, 16, 17, 20, 32}) {
            long limit = (long) distance * distance;
            for (int cx = -40; cx <= 40 && problem == null; cx += 9) {
                for (int cy = -40; cy <= 40 && problem == null; cy += 11) {
                    for (int cz = -40; cz <= 40 && problem == null; cz += 13) {
                        for (int sx = -4; sx <= 4 && problem == null; sx++) {
                            for (int sy = -4; sy <= 4 && problem == null; sy++) {
                                for (int sz = -4; sz <= 4 && problem == null; sz++) {
                                    Reach reach = classify(cx, cy, cz, sx, sy, sz, limit);
                                    int inside = 0;
                                    int outside = 0;
                                    // Only sample the section's corners and mid-block: the extremes
                                    // decide the verdict, and a full sweep would be 4096 tests per
                                    // combination.
                                    for (int dx : new int[]{0, 7, 15}) {
                                        for (int dy : new int[]{0, 7, 15}) {
                                            for (int dz : new int[]{0, 7, 15}) {
                                                P pos = new P(sx * 16 + dx, sy * 16 + dy, sz * 16 + dz);
                                                if (squaredDistance(new P(cx, cy, cz), pos) > limit) {
                                                    outside++;
                                                } else {
                                                    inside++;
                                                }
                                            }
                                        }
                                    }
                                    checked++;
                                    if (reach == Reach.OUTSIDE && inside > 0) {
                                        problem = "OUTSIDE but " + inside + " sampled positions pass"
                                                + " centre=(" + cx + "," + cy + "," + cz + ")"
                                                + " section=(" + sx + "," + sy + "," + sz + ")"
                                                + " r=" + distance;
                                    } else if (reach == Reach.FULLY_INSIDE && outside > 0) {
                                        problem = "FULLY_INSIDE but " + outside + " sampled positions fail"
                                                + " centre=(" + cx + "," + cy + "," + cz + ")"
                                                + " section=(" + sx + "," + sy + "," + sz + ")"
                                                + " r=" + distance;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (problem != null) break;
        }
        if (problem != null) {
            fail("classify agrees with the per-position test", problem);
        } else {
            pass("classify agrees with the per-position test", checked + " section/centre pairs");
        }
    }

    /**
     * A full block sweep of a few sections: every single position, not a sample. This is the
     * exhaustive version of the check above, kept small so it stays fast.
     */
    static void exhaustiveSectionSweep() {
        scenarios++;
        String problem = null;
        int positions = 0;
        int[] distances = {16, 17, 20};
        for (int distance : distances) {
            long limit = (long) distance * distance;
            for (int sx = -2; sx <= 2 && problem == null; sx++) {
                for (int sy = -2; sy <= 2 && problem == null; sy++) {
                    for (int sz = -2; sz <= 2 && problem == null; sz++) {
                        P center = new P(sx * 16 + 5, sy * 16 - 3, sz * 16 + 9);
                        Reach reach = classify(center.x(), center.y(), center.z(),
                                sx, sy, sz, limit);
                        boolean anyInside = false;
                        boolean anyOutside = false;
                        for (int dx = 0; dx < 16; dx++) {
                            for (int dy = 0; dy < 16; dy++) {
                                for (int dz = 0; dz < 16; dz++) {
                                    P pos = new P(sx * 16 + dx, sy * 16 + dy, sz * 16 + dz);
                                    positions++;
                                    if (squaredDistance(center, pos) > limit) {
                                        anyOutside = true;
                                    } else {
                                        anyInside = true;
                                    }
                                }
                            }
                        }
                        if (reach == Reach.OUTSIDE && anyInside) {
                            problem = "OUTSIDE but the section contains a passing position";
                        } else if (reach == Reach.FULLY_INSIDE && anyOutside) {
                            problem = "FULLY_INSIDE but the section contains a failing position";
                        } else if (reach == Reach.PARTIAL && !(anyInside && anyOutside)) {
                            problem = "PARTIAL but the section is not actually split";
                        }
                    }
                }
            }
            if (problem != null) break;
        }
        if (problem != null) {
            fail("exhaustive block sweep", problem + " (section at centre offset, r=" + distances[0] + ")");
        } else {
            pass("exhaustive block sweep", positions + " positions");
        }
    }

    // ==== reporting ====

    static void pass(String name, String detail) {
        System.out.println("ok   " + name + "  [" + detail + "]");
    }

    static void fail(String name, String detail) {
        failures++;
        System.out.println("FAIL " + name + ": " + detail);
    }

    public static void main(String[] args) {
        classifyMatchesPositionTest();
        exhaustiveSectionSweep();
        centeredOrigin();
        onSphereEquality();
        sectionCorners();
        negativeCoordinates();
        emptyAndUndiscoveredBuckets();
        dynamicWorldAndSnapshotSemantics();
        radiusBounds();
        longCentreSequence();

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " FAILURE(S) across " + scenarios + " scenario(s)");
            System.exit(1);
        }
        System.out.println("all " + scenarios + " sparse-diff scenarios passed");
    }
}
