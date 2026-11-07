package com.extrarawstyle.veinminerplus;

/**
 * Distance from a scan centre to a 16-cube, in whole blocks, as squared integers.
 *
 * The sparse scan walks every candidate position in a section bucket and drops the ones outside the
 * sphere. Nearly all of those per-position tests are decidable from the section's coordinate box
 * alone: a section entirely outside the sphere contributes nothing, and one entirely inside needs no
 * individual test. This class answers exactly that question and nothing else, so the answer can be
 * checked exhaustively without a world, a chunk or a clock.
 *
 * A section with section coordinate {@code (sx, sy, sz)} covers blocks
 * {@code [sx*16, sx*16+15]} on each axis. Distances are squared because comparing against
 * {@code radius*radius} keeps everything in integers and avoids a square root in the inner loop.
 *
 * The three verdicts must be exhaustive and mutually consistent with the per-position test:
 * - {@link #OUTSIDE}: every position in the box is farther than the radius. Skipping the bucket
 *   cannot lose a target.
 * - {@link #FULLY_INSIDE}: every position in the box is within the radius. Every position would pass
 *   the per-position test, so the test can be omitted without changing which positions pass.
 * - {@link #PARTIAL}: neither holds; the per-position test still decides.
 *
 * The bounds are inclusive: a box corner exactly on the sphere is INSIDE, because the per-position
 * test uses {@code distance > radius} to reject and therefore accepts equality.
 */
final class SparseSectionGeometry {

    /** A 16-cube section edge, in blocks. */
    static final int SECTION_SIZE = 16;

    enum Reach {
        /** Every block in the section is strictly outside the sphere. */
        OUTSIDE,
        /** Every block in the section is inside or exactly on the sphere. */
        FULLY_INSIDE,
        /** The sphere cuts through the section; positions must be tested one by one. */
        PARTIAL
    }

    private SparseSectionGeometry() {
    }

    /**
     * Squared distance from {@code center} to the nearest block of the section.
     *
     * Zero when the centre is inside the section on every axis.
     */
    static long minSquaredDistance(int centerX, int centerY, int centerZ, int sectionX, int sectionY,
            int sectionZ) {
        long dx = axisGap(centerX, sectionX);
        long dy = axisGap(centerY, sectionY);
        long dz = axisGap(centerZ, sectionZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Squared distance from {@code center} to the farthest block of the section.
     *
     * This is the corner-to-corner maximum, so it is exactly the largest value the per-position test
     * can produce for this section. Comparing it to the radius decides whether any position could
     * still fail.
     */
    static long maxSquaredDistance(int centerX, int centerY, int centerZ, int sectionX, int sectionY,
            int sectionZ) {
        long dx = axisFar(centerX, sectionX);
        long dy = axisFar(centerY, sectionY);
        long dz = axisFar(centerZ, sectionZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Classifies a section against the sphere.
     *
     * {@code maxSquaredRadius} is the radius squared, computed once by the caller. Equality counts as
     * inside: the per-position test rejects only {@code distance > maxSquaredRadius}, so a position
     * exactly on the sphere is a target.
     */
    static Reach classify(int centerX, int centerY, int centerZ, int sectionX, int sectionY,
            int sectionZ, long maxSquaredRadius) {
        if (minSquaredDistance(centerX, centerY, centerZ, sectionX, sectionY, sectionZ)
                > maxSquaredRadius) {
            return Reach.OUTSIDE;
        }
        if (maxSquaredDistance(centerX, centerY, centerZ, sectionX, sectionY, sectionZ)
                <= maxSquaredRadius) {
            return Reach.FULLY_INSIDE;
        }
        return Reach.PARTIAL;
    }

    /** Gap from {@code center} to the section's nearest block on one axis; 0 when inside it. */
    private static long axisGap(int center, int section) {
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

    /** Distance from {@code center} to the section's farthest block on one axis. */
    private static long axisFar(int center, int section) {
        int low = section * SECTION_SIZE;
        int high = low + SECTION_SIZE - 1;
        // Farthest is whichever end the centre is furthest from. With the centre inside the box,
        // both ends are candidates and the larger wins.
        long toLow = Math.abs((long) center - low);
        long toHigh = Math.abs((long) center - high);
        return Math.max(toLow, toHigh);
    }
}
