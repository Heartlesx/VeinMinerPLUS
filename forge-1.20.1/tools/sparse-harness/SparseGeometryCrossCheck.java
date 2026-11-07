import java.lang.reflect.Method;

/**
 * Cross-checks the harness's copy of the geometry against the class that actually ships.
 *
 * {@code SparseDiffHarness} runs two full scan implementations, but both live in the harness file -
 * useful for comparing algorithms, useless for proving the shipped class agrees with them. If
 * {@code SparseSectionGeometry} drifted from the copy (a changed bound, an inclusive rather than
 * exclusive comparison), the differential would still pass while the mod did something else.
 *
 * So this reflectively calls the compiled {@code SparseSectionGeometry} and compares every verdict
 * against the harness implementation over the same exhaustive grid.
 *
 * Run: java -cp out-here:real SparseGeometryCrossCheck
 */
public final class SparseGeometryCrossCheck {

    private static int failures;
    private static long checks;

    public static void main(String[] args) throws Exception {
        Class<?> real = Class.forName("com.extrarawstyle.veinminerplus.SparseSectionGeometry");
        Method minSquared = real.getDeclaredMethod("minSquaredDistance",
                int.class, int.class, int.class, int.class, int.class, int.class);
        Method maxSquared = real.getDeclaredMethod("maxSquaredDistance",
                int.class, int.class, int.class, int.class, int.class, int.class);
        Method classify = real.getDeclaredMethod("classify",
                int.class, int.class, int.class, int.class, int.class, int.class, long.class);
        minSquared.setAccessible(true);
        maxSquared.setAccessible(true);
        classify.setAccessible(true);

        int[] distances = {3, 4, 16, 17, 20, 32, 64, 128};
        int[] centers = {-129, -64, -33, -17, -16, -1, 0, 1, 15, 16, 17, 32, 63, 128};
        int[] sections = {-8, -4, -2, -1, 0, 1, 2, 4, 8};

        for (int distance : distances) {
            long limit = (long) distance * distance;
            for (int cx : centers) {
                for (int cy : centers) {
                    for (int cz : centers) {
                        for (int sx : sections) {
                            for (int sy : sections) {
                                for (int sz : sections) {
                                    long expectedMin = SparseDiffHarness.minSquaredDistance(
                                            cx, cy, cz, sx, sy, sz);
                                    long actualMin = (long) minSquared.invoke(null,
                                            cx, cy, cz, sx, sy, sz);
                                    long expectedMax = SparseDiffHarness.maxSquaredDistance(
                                            cx, cy, cz, sx, sy, sz);
                                    long actualMax = (long) maxSquared.invoke(null,
                                            cx, cy, cz, sx, sy, sz);
                                    Object expected = SparseDiffHarness.classify(
                                            cx, cy, cz, sx, sy, sz, limit);
                                    Object actual = classify.invoke(null,
                                            cx, cy, cz, sx, sy, sz, limit);
                                    checks++;
                                    if (expectedMin != actualMin) {
                                        report("min distance", cx, cy, cz, sx, sy, sz,
                                                distance, expectedMin, actualMin);
                                        return;
                                    }
                                    if (expectedMax != actualMax) {
                                        report("max distance", cx, cy, cz, sx, sy, sz,
                                                distance, expectedMax, actualMax);
                                        return;
                                    }
                                    if (!expected.toString().equals(actual.toString())) {
                                        report("classify", cx, cy, cz, sx, sy, sz,
                                                distance, expected, actual);
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("ok   shipped SparseSectionGeometry matches the harness copy  ["
                + checks + " cases]");
        System.out.println("all geometry cross-checks passed");
    }

    private static void report(String what, int cx, int cy, int cz, int sx, int sy, int sz,
            int distance, Object expected, Object actual) {
        failures++;
        System.out.println("FAIL " + what + " at centre=(" + cx + "," + cy + "," + cz + ")"
                + " section=(" + sx + "," + sy + "," + sz + ") r=" + distance
                + ": harness says " + expected + ", shipped class says " + actual);
        System.exit(1);
    }
}
