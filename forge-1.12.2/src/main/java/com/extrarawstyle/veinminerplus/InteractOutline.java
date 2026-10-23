package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

// Draws one region outline instead of a box per block: the top and bottom rings of the outer
// boundary plus a vertical line at every boundary corner. Blocks next to each other never show the
// face between them, so a solid area draws no inner grid, a hole is outlined truthfully and two
// areas that only touch at a corner each draw their own lines there.
@SideOnly(Side.CLIENT)
final class InteractOutline {
    // The vanilla selection box is grown by this much for the same reason: a line drawn exactly on
    // the block face z-fights with it. The offset is applied once to the final boundary.
    private static final double EPSILON = 0.002D;
    private static final float RED = 1.0F;
    private static final float GREEN = 1.0F;
    private static final float BLUE = 1.0F;
    private static final float ALPHA = 1.0F;

    private static List<BlockPos> cachedTargets = new ArrayList<>();
    private static BlockPos cachedHidden;
    // Six doubles per line: start x/y/z then end x/y/z, in world coordinates.
    private static double[] vertices = new double[0];

    private InteractOutline() {
    }

    // hidden is the block the crosshair is on. The job never uses it (the vanilla right click
    // already did), so it is not a candidate, but it is also not a hole: it joins the region for
    // the border only, and the parts of the border that belong to its own box are left out.
    static void draw(List<BlockPos> targets, BlockPos hidden, double viewX, double viewY, double viewZ) {
        if (!isSameRegion(targets, hidden)) {
            rebuild(targets, hidden);
        }
        if (vertices.length == 0) {
            return;
        }

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int index = 0; index < vertices.length; index += 6) {
            buffer.pos(vertices[index] - viewX, vertices[index + 1] - viewY, vertices[index + 2] - viewZ)
                    .color(RED, GREEN, BLUE, ALPHA).endVertex();
            buffer.pos(vertices[index + 3] - viewX, vertices[index + 4] - viewY, vertices[index + 5] - viewZ)
                    .color(RED, GREEN, BLUE, ALPHA).endVertex();
        }
        tessellator.draw();
    }

    // The region is rebuilt per frame while the aim moves, so the geometry is only recomputed when
    // the positions actually change; an unchanged aim reuses the same vertices. The hidden block
    // is part of the key: moving the crosshair inside the same region still moves the gap.
    private static boolean isSameRegion(List<BlockPos> targets, BlockPos hidden) {
        if (hidden == null ? cachedHidden != null : !hidden.equals(cachedHidden)) {
            return false;
        }
        if (targets.size() != cachedTargets.size()) {
            return false;
        }
        if (targets == cachedTargets) {
            return true;
        }
        for (int index = 0; index < targets.size(); index++) {
            if (!targets.get(index).equals(cachedTargets.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static void rebuild(List<BlockPos> targets, BlockPos hidden) {
        cachedTargets = new ArrayList<>(targets);
        cachedHidden = hidden;
        if (targets.isEmpty()) {
            vertices = new double[0];
            return;
        }

        // The border topology is computed over the candidates plus the block under the crosshair:
        // that block is not a hole, so nothing is drawn around it. The hidden block is only added
        // here, never to the job's own list.
        Set<BlockPos> region = new HashSet<>(targets);
        if (hidden != null) {
            region.add(hidden);
        }
        int y = targets.get(0).getY();
        // Runs along x keyed by the z plane, runs along z keyed by the x plane, plus the boundary
        // corners that need a vertical line. Values are integer grid intervals.
        Map<Integer, List<int[]>> xRuns = new HashMap<>();
        Map<Integer, List<int[]>> zRuns = new HashMap<>();
        Set<Long> corners = new HashSet<>();

        for (BlockPos pos : region) {
            int x = pos.getX();
            int z = pos.getZ();
            if (!region.contains(new BlockPos(x, y, z - 1))) {
                addRun(xRuns, lowSide(z), x, x + 1);
            }
            if (!region.contains(new BlockPos(x, y, z + 1))) {
                addRun(xRuns, highSide(z + 1), x, x + 1);
            }
            if (!region.contains(new BlockPos(x - 1, y, z))) {
                addRun(zRuns, lowSide(x), z, z + 1);
            }
            if (!region.contains(new BlockPos(x + 1, y, z))) {
                addRun(zRuns, highSide(x + 1), z, z + 1);
            }
            corners.add(vertexKey(x, z));
            corners.add(vertexKey(x + 1, z));
            corners.add(vertexKey(x, z + 1));
            corners.add(vertexKey(x + 1, z + 1));
        }

        List<double[]> lines = new ArrayList<>();
        double bottom = y - EPSILON;
        double top = y + 1.0D + EPSILON;
        for (int height = 0; height < 2; height++) {
            double lineY = height == 0 ? bottom : top;
            for (Map.Entry<Integer, List<int[]>> entry : xRuns.entrySet()) {
                for (int[] run : merge(entry.getValue())) {
                    // Cut on the integer grid before the outward offset, so a long line that runs
                    // past the hidden block keeps the rest of its length.
                    addHiddenFreeRun(lines, run, true, lineY, entry.getKey(), hidden);
                }
            }
            for (Map.Entry<Integer, List<int[]>> entry : zRuns.entrySet()) {
                for (int[] run : merge(entry.getValue())) {
                    addHiddenFreeRun(lines, run, false, lineY, entry.getKey(), hidden);
                }
            }
        }

        for (Long key : corners) {
            int cornerX = (int) (key >>> 32);
            int cornerZ = (int) (long) key;
            // A corner on the hidden block's own footprint would draw its vertical edge.
            if (hidden != null && cornerOnCell(cornerX, cornerZ, hidden)) {
                continue;
            }
            double[] outward = outwardCorner(region, cornerX, cornerZ, y);
            if (outward == null) {
                continue;
            }
            double x = cornerX + outward[0] * EPSILON;
            double z = cornerZ + outward[1] * EPSILON;
            lines.add(new double[] {x, bottom, z, x, top, z});
        }

        vertices = new double[lines.size() * 6];
        for (int index = 0; index < lines.size(); index++) {
            System.arraycopy(lines.get(index), 0, vertices, index * 6, 6);
        }
    }

    // The outward direction of a boundary corner. Summing the directions toward the region cells
    // around it and flipping the sum points away from them, which is outward for both a convex and
    // a concave corner. Null means the vertex is not on the boundary at all (inside the area).
    private static double[] outwardCorner(Set<BlockPos> region, int cornerX, int cornerZ, int y) {
        boolean southWest = region.contains(new BlockPos(cornerX - 1, y, cornerZ - 1));
        boolean southEast = region.contains(new BlockPos(cornerX, y, cornerZ - 1));
        boolean northWest = region.contains(new BlockPos(cornerX - 1, y, cornerZ));
        boolean northEast = region.contains(new BlockPos(cornerX, y, cornerZ));
        int count = (southWest ? 1 : 0) + (southEast ? 1 : 0) + (northWest ? 1 : 0) + (northEast ? 1 : 0);
        if (count == 0 || count == 4) {
            return null;
        }
        if (count == 2 && (southWest && northEast || southEast && northWest)) {
            // The cells only touch at this vertex, so the two directions cancel out. Each area
            // draws its own vertical line at the corner itself, which is where both of them end.
            return new double[] {0.0D, 0.0D};
        }
        if (count == 2) {
            // Two cells sharing an edge pass straight through this vertex: no vertical line.
            return null;
        }

        double sumX = 0.0D;
        double sumZ = 0.0D;
        if (southWest) {
            sumX -= 1.0D;
            sumZ -= 1.0D;
        }
        if (southEast) {
            sumX += 1.0D;
            sumZ -= 1.0D;
        }
        if (northWest) {
            sumX -= 1.0D;
            sumZ += 1.0D;
        }
        if (northEast) {
            sumX += 1.0D;
            sumZ += 1.0D;
        }
        double length = Math.sqrt(sumX * sumX + sumZ * sumZ);
        return new double[] {-sumX / length, -sumZ / length};
    }

    // Adds a grid run with the part that traces the hidden block's own box removed. The run is
    // along x when alongX is true (fixed z), otherwise along z (fixed x). Both the decision and the
    // cut happen on the integer grid; the outward offset is applied only to the pieces that
    // survive, because an offset boundary no longer equals the cell coordinate.
    private static void addHiddenFreeRun(List<double[]> lines, int[] run, boolean alongX, double lineY,
            int encodedPlane, BlockPos hidden) {
        int plane = encodedPlane >> 1;
        double fixedCoordinate = sideCoordinate(encodedPlane);
        if (hidden == null) {
            addSegment(lines, run[0], run[1], alongX, lineY, fixedCoordinate);
            return;
        }
        // The hidden cell covers [cell, cell + 1) on the axis the run travels along, and its own
        // box borders are the two planes at the other axis.
        int cell = alongX ? hidden.getX() : hidden.getZ();
        int fixedCell = alongX ? hidden.getZ() : hidden.getX();
        boolean onHiddenBorder = plane == fixedCell || plane == fixedCell + 1;
        if (!onHiddenBorder) {
            addSegment(lines, run[0], run[1], alongX, lineY, fixedCoordinate);
            return;
        }
        int from = run[0];
        int to = run[1];
        if (to <= cell || from >= cell + 1) {
            addSegment(lines, from, to, alongX, lineY, fixedCoordinate);
            return;
        }
        if (from < cell) {
            addSegment(lines, from, cell, alongX, lineY, fixedCoordinate);
        }
        if (to > cell + 1) {
            addSegment(lines, cell + 1, to, alongX, lineY, fixedCoordinate);
        }
    }

    private static void addSegment(List<double[]> lines, int from, int to, boolean alongX, double lineY,
            double fixedCoordinate) {
        if (to <= from) {
            return;
        }
        if (alongX) {
            lines.add(new double[] {from, lineY, fixedCoordinate, to, lineY, fixedCoordinate});
        } else {
            lines.add(new double[] {fixedCoordinate, lineY, from, fixedCoordinate, lineY, to});
        }
    }

    // A boundary corner belongs to the hidden cell's own box when it sits on the cell's footprint.
    private static boolean cornerOnCell(int cornerX, int cornerZ, BlockPos hidden) {
        return cornerX >= hidden.getX() && cornerX <= hidden.getX() + 1
                && cornerZ >= hidden.getZ() && cornerZ <= hidden.getZ() + 1;
    }

    private static void addRun(Map<Integer, List<int[]>> runs, int plane, int from, int to) {        List<int[]> list = runs.get(plane);
        if (list == null) {
            list = new ArrayList<>();
            runs.put(plane, list);
        }
        list.add(new int[] {from, to});
    }

    // Collinear touching runs become one segment, so a straight side is a single line.
    private static List<int[]> merge(List<int[]> runs) {
        List<int[]> sorted = new ArrayList<>(runs);
        sorted.sort((first, second) -> Integer.compare(first[0], second[0]));
        List<int[]> merged = new ArrayList<>();
        int[] current = sorted.get(0).clone();
        for (int index = 1; index < sorted.size(); index++) {
            int[] next = sorted.get(index);
            if (next[0] <= current[1]) {
                current[1] = Math.max(current[1], next[1]);
            } else {
                merged.add(current);
                current = next.clone();
            }
        }
        merged.add(current);
        return merged;
    }

    private static long vertexKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    // A boundary plane is encoded with the side it faces, because the same plane has an outward
    // direction that differs by side: the low side of plane p is p - epsilon, the high side is
    // p + epsilon (the block on the other side of p ends there).
    private static int lowSide(int plane) {
        return plane << 1;
    }

    private static int highSide(int plane) {
        return (plane << 1) | 1;
    }

    private static double sideCoordinate(int encoded) {
        double plane = encoded >> 1;
        return (encoded & 1) == 0 ? plane - EPSILON : plane + EPSILON;
    }
}
