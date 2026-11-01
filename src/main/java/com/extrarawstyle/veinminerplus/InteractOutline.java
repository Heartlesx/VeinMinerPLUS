package com.extrarawstyle.veinminerplus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// Draws one outline around the selected area instead of a full box per block. An edge is drawn
// only where the surface or grid line is on the boundary of the three-dimensional selection, which
// removes internal lines between touching blocks. Positions that touch only at an edge or corner
// keep their real outlines, and a gap or hole is followed instead of the enclosing box.
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

    // A one-block-tall area keeps the shipped flat-outline algorithm verbatim; only multi-layer
    // areas need the three-dimensional boundary.
    private static Set<Edge> boundaryEdges(Set<BlockPos> area) {
        if (isSingleLayer(area)) {
            return flatBoundaryEdges(area);
        }
        return volumeBoundaryEdges(area);
    }

    private static boolean isSingleLayer(Set<BlockPos> area) {
        int y = Integer.MIN_VALUE;
        for (BlockPos pos : area) {
            if (y == Integer.MIN_VALUE) {
                y = pos.getY();
            } else if (pos.getY() != y) {
                return false;
            }
        }
        return true;
    }

    // The flat outline: top and bottom rims where the horizontal neighbour is missing, plus a
    // vertical edge at every rim corner. Preserved verbatim so single-layer previews stay identical.
    private static Set<Edge> flatBoundaryEdges(Set<BlockPos> area) {
        Set<Edge> edges = new HashSet<>();
        for (BlockPos pos : area) {
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();

            for (Direction2 side : HORIZONTAL) {
                if (area.contains(new BlockPos(x + side.dx, y, z + side.dz))) {
                    continue;
                }
                int[] from = side.edgeStart(x, z);
                int[] to = side.edgeEnd(x, z);
                edges.add(new Edge(from[0], y + 1, from[1], to[0], y + 1, to[1]));
                edges.add(new Edge(from[0], y, from[1], to[0], y, to[1]));
            }

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

    // The volume boundary is the rim of the exposed faces in all three axes. An edge shared by two
    // coplanar exposed faces is an internal seam and is dropped; an edge on one exposed face only,
    // or shared by perpendicular faces, is part of the real surface and stays.
    private static Set<Edge> volumeBoundaryEdges(Set<BlockPos> area) {
        Map<FaceEdge, Integer> counts = new HashMap<>();
        for (BlockPos pos : area) {
            for (int axis = 0; axis < 3; axis++) {
                int u = (axis + 1) % 3;
                int v = (axis + 2) % 3;
                for (int direction = -1; direction <= 1; direction += 2) {
                    if (area.contains(offset(pos, axis, direction))) {
                        continue;
                    }
                    int normal = axis * 2 + (direction == 1 ? 1 : 0);
                    int[][] corners = new int[4][3];
                    for (int index = 0; index < 4; index++) {
                        corners[index][axis] = coordinate(pos, axis) + (direction == 1 ? 1 : 0);
                        corners[index][u] = coordinate(pos, u) + (index == 1 || index == 2 ? 1 : 0);
                        corners[index][v] = coordinate(pos, v) + (index >= 2 ? 1 : 0);
                    }
                    for (int index = 0; index < 4; index++) {
                        int[] from = corners[index];
                        int[] to = corners[(index + 1) % 4];
                        counts.merge(new FaceEdge(new GridEdge(from[0], from[1], from[2],
                                to[0], to[1], to[2]), normal), 1, Integer::sum);
                    }
                }
            }
        }
        Set<Edge> edges = new HashSet<>();
        for (Map.Entry<FaceEdge, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == 1) {
                GridEdge edge = entry.getKey().edge();
                edges.add(new Edge(edge.x0(), edge.y0(), edge.z0(), edge.x1(), edge.y1(), edge.z1()));
            }
        }
        return edges;
    }

    private static int coordinate(BlockPos pos, int axis) {
        return switch (axis) {
            case 0 -> pos.getX();
            case 1 -> pos.getY();
            default -> pos.getZ();
        };
    }

    private static BlockPos offset(BlockPos pos, int axis, int amount) {
        return switch (axis) {
            case 0 -> pos.offset(amount, 0, 0);
            case 1 -> pos.offset(0, amount, 0);
            default -> pos.offset(0, 0, amount);
        };
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

    private record FaceEdge(GridEdge edge, int normal) {
    }

    private record GridEdge(int x0, int y0, int z0, int x1, int y1, int z1) {
        GridEdge {
            if (x0 > x1 || x0 == x1 && (y0 > y1 || y0 == y1 && z0 > z1)) {
                int x = x0;
                int y = y0;
                int z = z0;
                x0 = x1;
                y0 = y1;
                z0 = z1;
                x1 = x;
                y1 = y;
                z1 = z;
            }
        }
    }

    private record Edge(double x0, double y0, double z0, double x1, double y1, double z1) {
    }

}
