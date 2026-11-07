import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Scenario harness for the interaction outline.
 *
 * The geometry below the marker is NOT written by hand: extract.py copies it verbatim out of
 * VeinMinerPlusClient.java before every run, so these scenarios exercise the shipped code. What
 * this file supplies is only the scaffolding around it - a BlockPos stand-in with the four
 * neighbour helpers the geometry calls, the render-time decision under test, and the assertions.
 *
 * Run: javac OutlineGenerated.java && java OutlineGenerated
 */
public final class OutlineGenerated {
    private static int failures;

    public static void main(String[] args) {
        threeByOneMiddleAimKeepsOuterLine();
        solid3x3AimEverywhere();
        sameTargetsAimWanders();
        loneAimDrawsNothing();
        shapesKeepTheirRealBoundaries();
        flintExcludedAimStillHides();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // The case the plan calls out: 3x1 in a row with the aim on the middle block. The outer line
    // has to run straight through, with no gap where the aim sits.
    private static void threeByOneMiddleAimKeepsOuterLine() {
        List<BlockPos> targets = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(2, 0, 0));
        BlockPos aim = new BlockPos(1, 0, 0);
        List<double[]> withAim = drawn(targets, aim);
        List<double[]> withoutAim = drawn(targets, null);
        checkTrue("3x1 middle aim: outline identical to the unhidden outline",
                sameGeometry(withAim, withoutAim));
        checkTrue("3x1 middle aim: the north rim runs the full width",
                hasSegment(withAim, 0.0, 0.0, 0.0, 3.0, 0.0, 0.0));
        checkTrue("3x1 middle aim: the south rim runs the full width",
                hasSegment(withAim, 0.0, 0.0, 1.0, 3.0, 0.0, 1.0));
        checkTrue("3x1 middle aim: no interior cross line at the aim",
                !hasSegment(withAim, 1.0, 0.0, 0.0, 1.0, 0.0, 1.0));
    }

    // A solid area must show one continuous outer edge no matter where the aim is, and never an
    // extra box around it.
    private static void solid3x3AimEverywhere() {
        List<BlockPos> targets = square(3);
        List<double[]> reference = drawn(targets, null);
        BlockPos[] aims = {null, new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(1, 0, 1)};
        String[] labels = {"no aim", "centre", "edge middle", "corner"};
        for (int index = 0; index < aims.length; index++) {
            List<double[]> result = drawn(targets, aims[index]);
            check("3x3 aim at " + labels[index] + ": outline unchanged", reference.size(), result.size());
            checkTrue("3x3 aim at " + labels[index] + ": geometry identical",
                    sameGeometry(reference, result));
        }
    }

    // Moving the aim inside the same area must not make the geometry follow it.
    private static void sameTargetsAimWanders() {
        List<BlockPos> targets = square(3);
        List<double[]> first = drawn(targets, new BlockPos(-1, 0, -1));
        List<double[]> second = drawn(targets, new BlockPos(1, 0, 1));
        checkTrue("wandering aim inside one area does not change the outline",
                sameGeometry(first, second));
    }

    // A single aimed block produces no outline of its own; vanilla still draws its selection box.
    private static void loneAimDrawsNothing() {
        List<BlockPos> justTheAim = List.of(new BlockPos(0, 0, 0));
        check("lone aim: nothing is drawn", 0, drawn(justTheAim, new BlockPos(0, 0, 0)).size());
        check("lone aim without candidates: nothing is drawn", 0,
                drawn(List.of(), new BlockPos(0, 0, 0)).size());
    }

    // Real topology still shows: a hole, an L shape, a one-wide corridor and a diagonal touch.
    private static void shapesKeepTheirRealBoundaries() {
        List<BlockPos> ring = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    ring.add(new BlockPos(x, 0, z));
                }
            }
        }
        List<double[]> ringOutline = drawn(ring, new BlockPos(0, 0, 1));
        checkTrue("hole: the aimed edge block does not erase the hole ring",
                ringOutline.stream().anyMatch(OutlineGenerated::liesOnHoleEdge));

        List<BlockPos> corner = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(0, 0, 1));
        checkTrue("L shape: outline is continuous", !drawn(corner, new BlockPos(0, 0, 0)).isEmpty());

        List<BlockPos> corridor = List.of(new BlockPos(0, 0, 0), new BlockPos(0, 0, 1),
                new BlockPos(0, 0, 2), new BlockPos(1, 0, 1));
        checkTrue("corridor: the branch keeps its own rim",
                drawn(corridor, new BlockPos(0, 0, 1)).stream()
                        .anyMatch(s -> Math.abs(s[0] - 2.0) < 1.0E-9 && Math.abs(s[3] - 2.0) < 1.0E-9));

        List<BlockPos> diagonal = List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 1));
        check("diagonal touch: drawn rim length equals two separate boxes", 16,
                (int) Math.round(rimLength(drawn(diagonal, new BlockPos(0, 0, 0)))));
    }

    // Flint: the aim is not a candidate, so it keeps the dedicated hiding path from the previous
    // round. This is deliberately unchanged by this fix.
    private static void flintExcludedAimStillHides() {
        List<BlockPos> neighbours = List.of(new BlockPos(-1, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(0, 0, -1), new BlockPos(0, 0, 1));
        BlockPos aim = new BlockPos(0, 0, 0);
        List<double[]> result = drawn(neighbours, aim);
        checkTrue("flint: no vertical edge of the aimed block is drawn",
                result.stream().noneMatch(s -> isAimVerticalEdge(s, aim)));
        checkTrue("flint: the neighbours are still outlined", result.size() >= 4);
    }

    // ---- the render-time decision under test, mirrored from renderInteractPreview ----

    // Set from the command line: "before" runs the old call (aim always used as the hiding
    // position), "after" runs the fixed one. The failing run is the evidence the gap was real.
    private static final boolean RUN_BEFORE_FIX =
            System.getProperty("outline.mode", "after").equals("before");

    private static List<double[]> drawn(List<BlockPos> targets, BlockPos previewAim) {
        if (targets.isEmpty()) {
            return List.of();
        }
        if (RUN_BEFORE_FIX) {
            return outlineSegments(targets, previewAim);
        }
        boolean aimedBlockIsSelected = previewAim != null && targets.contains(previewAim);
        if (aimedBlockIsSelected && targets.size() == 1) {
            return List.of();
        }
        BlockPos hiddenAim = aimedBlockIsSelected ? null : previewAim;
        return outlineSegments(targets, hiddenAim);
    }

    // ---- geometry extracted verbatim from VeinMinerPlusClient.java by extract.py ----

    /*__GEOMETRY__*/

    // ---- scaffolding: the parts of BlockPos the geometry actually uses ----

    static final class BlockPos {
        private final int x;
        private final int y;
        private final int z;

        BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        int getX() {
            return x;
        }

        int getY() {
            return y;
        }

        int getZ() {
            return z;
        }

        BlockPos north() {
            return new BlockPos(x, y, z - 1);
        }

        BlockPos south() {
            return new BlockPos(x, y, z + 1);
        }

        BlockPos east() {
            return new BlockPos(x + 1, y, z);
        }

        BlockPos west() {
            return new BlockPos(x - 1, y, z);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BlockPos pos && pos.x == x && pos.y == y && pos.z == z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }

        @Override
        public String toString() {
            return "(" + x + "," + y + "," + z + ")";
        }
    }

    // The cache fields the extracted geometry reads and writes, with the source's own names.
    private static List<BlockPos> outlineCacheKey = List.of();
    private static BlockPos outlineCacheAim;
    private static List<double[]> outlineCache = List.of();

    // ---- assertions ----

    private static List<BlockPos> square(int size) {
        List<BlockPos> targets = new ArrayList<>();
        int half = size / 2;
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                targets.add(new BlockPos(x, 0, z));
            }
        }
        return targets;
    }

    private static void check(String label, int expected, int actual) {
        if (expected == actual) {
            System.out.println("PASS " + label + " (" + actual + ")");
        } else {
            failures++;
            System.out.println("FAIL " + label + " expected " + expected + " got " + actual);
        }
    }

    private static void checkTrue(String label, boolean value) {
        if (value) {
            System.out.println("PASS " + label);
        } else {
            failures++;
            System.out.println("FAIL " + label);
        }
    }

    private static boolean hasSegment(List<double[]> segments, double x0, double y0, double z0,
            double x1, double y1, double z1) {
        for (double[] segment : segments) {
            if (near(segment[0], x0) && near(segment[1], y0) && near(segment[2], z0)
                    && near(segment[3], x1) && near(segment[4], y1) && near(segment[5], z1)) {
                return true;
            }
            if (near(segment[0], x1) && near(segment[1], y1) && near(segment[2], z1)
                    && near(segment[3], x0) && near(segment[4], y0) && near(segment[5], z0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < 1.0E-9;
    }

    private static boolean sameGeometry(List<double[]> first, List<double[]> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!Arrays.equals(first.get(index), second.get(index))) {
                return false;
            }
        }
        return true;
    }

    // Total horizontal rim length, ignoring the vertical corner lines.
    private static double rimLength(List<double[]> segments) {
        double total = 0.0;
        for (double[] segment : segments) {
            if (!(segment[0] == segment[3] && segment[2] == segment[5])) {
                total += Math.abs(segment[3] - segment[0]) + Math.abs(segment[5] - segment[2]);
            }
        }
        return total;
    }

    // The hole in the ring case is the block at (0,0,0).
    private static boolean liesOnHoleEdge(double[] segment) {
        boolean vertical = segment[0] == segment[3] && segment[2] == segment[5];
        if (vertical) {
            return (segment[0] == 0.0 || segment[0] == 1.0)
                    && (segment[2] == 0.0 || segment[2] == 1.0)
                    && segment[1] == 0.0 && segment[4] == 1.0;
        }
        if (segment[1] == segment[4] && segment[2] == segment[5]) {
            return (segment[2] == 0.0 || segment[2] == 1.0)
                    && Math.min(segment[0], segment[3]) == 0.0
                    && Math.max(segment[0], segment[3]) == 1.0;
        }
        return (segment[0] == 0.0 || segment[0] == 1.0)
                && Math.min(segment[2], segment[5]) == 0.0
                && Math.max(segment[2], segment[5]) == 1.0;
    }

    private static boolean isAimVerticalEdge(double[] segment, BlockPos aim) {
        if (!(segment[0] == segment[3] && segment[2] == segment[5])) {
            return false;
        }
        double minX = aim.getX();
        double minZ = aim.getZ();
        return (segment[0] == minX || segment[0] == minX + 1.0)
                && (segment[2] == minZ || segment[2] == minZ + 1.0);
    }
}
