package com.extrarawstyle.veinminerplus;

import java.util.HashSet;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// Draws one outline around the selected area instead of a full box per block. A horizontal edge is
// only drawn where the neighbour on that side is not part of the selection, which removes the
// internal lines between touching blocks; positions that touch only at a corner keep their own
// outlines and meet at that corner, and a gap or a hole is followed as the real boundary instead of
// the enclosing rectangle. A single selected block still comes out as a complete cube.
final class InteractOutline {
    private InteractOutline() {
    }

    // Emits the outline as white line segments. Every segment is submitted as one vertex pair with
    // its own direction as the normal, which is the convention the LINES shader expects.
    //
    // `hiddenBlock` is the block the mod must not draw a box for (a flint and steel click already ran
    // for the aimed block, so it is not part of the action). Its box edges are dropped, but it stays
    // in the area used for the topology, which keeps a real hole from appearing in the middle of the
    // region. Edges shared with a neighbour are kept. Passing null draws every boundary edge.
    static void render(PoseStack poseStack, VertexConsumer lines, Set<BlockPos> area, BlockPos hiddenBlock,
            Vec3 camera, float red, float green, float blue, float alpha) {
        if (area.isEmpty()) {
            return;
        }
        PoseStack.Pose pose = poseStack.last();
        Set<Edge> hidden = hiddenBlock == null ? Set.of() : boxEdges(hiddenBlock);
        for (Edge edge : boundaryEdges(area)) {
            if (hidden.contains(edge)) {
                continue;
            }
            emit(pose, lines, edge, camera, red, green, blue, alpha);
        }
    }

    // The twelve edges of one block's own box. A boundary edge that is also one of these is drawn for
    // that block alone, which is exactly the part to hide when the block itself must not be shown.
    private static Set<Edge> boxEdges(BlockPos pos) {
        double x0 = pos.getX();
        double y0 = pos.getY();
        double z0 = pos.getZ();
        double x1 = x0 + 1;
        double y1 = y0 + 1;
        double z1 = z0 + 1;
        Set<Edge> edges = new HashSet<>();
        for (double x : new double[] {x0, x1}) {
            for (double z : new double[] {z0, z1}) {
                edges.add(new Edge(x, y0, z, x, y1, z));
            }
        }
        for (double y : new double[] {y0, y1}) {
            edges.add(new Edge(x0, y, z0, x1, y, z0));
            edges.add(new Edge(x0, y, z0, x0, y, z1));
            edges.add(new Edge(x1, y, z0, x1, y, z1));
            edges.add(new Edge(x0, y, z1, x1, y, z1));
        }
        return edges;
    }

    // The area boundary, as unit-length edges on the block grid. Horizontal edges live on the top or
    // the bottom of the layer; vertical edges live on a lattice corner.
    private static Set<Edge> boundaryEdges(Set<BlockPos> area) {
        Set<Edge> edges = new HashSet<>();
        for (BlockPos pos : area) {
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();

            // Top and bottom faces, one edge per side that has no neighbour in the area.
            for (Direction2 side : HORIZONTAL) {
                if (area.contains(new BlockPos(x + side.dx, y, z + side.dz))) {
                    continue;
                }
                int[] from = side.edgeStart(x, z);
                int[] to = side.edgeEnd(x, z);
                edges.add(new Edge(from[0], y + 1, from[1], to[0], y + 1, to[1]));
                edges.add(new Edge(from[0], y, from[1], to[0], y, to[1]));
            }

            // Vertical edges, one per boundary corner of this block. A corner is interior when all
            // four blocks meeting there belong to the area, which is the only case worth skipping.
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    int cx = x + dx;
                    int cz = z + dz;
                    if (area.contains(new BlockPos(cx - 1, y, cz - 1))
                            && area.contains(new BlockPos(cx, y, cz - 1))
                            && area.contains(new BlockPos(cx - 1, y, cz))
                            && area.contains(new BlockPos(cx, y, cz))) {
                        continue;
                    }
                    edges.add(new Edge(cx, y, cz, cx, y + 1, cz));
                }
            }
        }
        return edges;
    }

    private static void emit(PoseStack.Pose pose, VertexConsumer lines, Edge edge, Vec3 camera,
            float red, float green, float blue, float alpha) {
        // Camera-relative first, float last: subtracting two large doubles keeps the fraction, while
        // turning each into a float before the subtraction snaps both to the same few representable
        // values and makes the line jump by whole blocks when the camera moves smoothly.
        float ax = (float) (edge.x0 - camera.x);
        float ay = (float) (edge.y0 - camera.y);
        float az = (float) (edge.z0 - camera.z);
        float bx = (float) (edge.x1 - camera.x);
        float by = (float) (edge.y1 - camera.y);
        float bz = (float) (edge.z1 - camera.z);

        float nx = bx - ax;
        float ny = by - ay;
        float nz = bz - az;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0.0F) {
            nx /= length;
            ny /= length;
            nz /= length;
        }
        lines.addVertex(pose, ax, ay, az).setColor(red, green, blue, alpha).setNormal(pose, nx, ny, nz);
        lines.addVertex(pose, bx, by, bz).setColor(red, green, blue, alpha).setNormal(pose, nx, ny, nz);
    }

    // One straight piece of the boundary, in block-grid coordinates. Kept in double: the camera
    // offset has to be subtracted before anything turns into a float, or far-away coordinates lose
    // the fraction and the outline jumps by whole blocks as the camera moves.
    private record Edge(double x0, double y0, double z0, double x1, double y1, double z1) {
    }

    private enum Direction2 {
        WEST(-1, 0),
        EAST(1, 0),
        NORTH(0, -1),
        SOUTH(0, 1);

        private final int dx;
        private final int dz;

        Direction2(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        // The two ends of the side this direction faces, on the block's own footprint, shared with
        // the neighbouring block's side so both produce the same edge.
        private int[] edgeStart(int x, int z) {
            return switch (this) {
                case WEST -> new int[] {x, z};
                case EAST -> new int[] {x + 1, z};
                case NORTH -> new int[] {x, z};
                case SOUTH -> new int[] {x, z + 1};
            };
        }

        private int[] edgeEnd(int x, int z) {
            return switch (this) {
                case WEST -> new int[] {x, z + 1};
                case EAST -> new int[] {x + 1, z + 1};
                case NORTH -> new int[] {x + 1, z};
                case SOUTH -> new int[] {x + 1, z + 1};
            };
        }
    }

    static final Direction2[] HORIZONTAL = Direction2.values();
}
