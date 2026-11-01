package com.extrarawstyle.veinminerplus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

// Selects the positions of an interaction area that are reachable from the origin through
// face, edge or corner contact. A position that does not qualify is never a bridge, so a hole or an
// unsuitable block stops the spread there instead of letting the search jump over it.
//
// The result keeps the old ordering promise: nearer positions first, and a stable order inside one
// Chebyshev ring. Only reachable positions are listed.
final class InteractArea {
    private InteractArea() {
    }

    // Fills `targets` with at most `limit` reachable positions, nearest first.
    // `originCounts` is false when the origin must not be acted on again (a flint and steel click
    // already ran for it): such an origin is still the seed of the search, so blocks touching it are
    // reached, but it is not added to the result.
    static void collect(BlockPos origin, int radius, int limit, boolean originCounts,
            Predicate<BlockPos> valid, List<BlockPos> targets) {
        if (limit <= 0) {
            return;
        }
        List<BlockPos> found = new ArrayList<>();
        if (originCounts && valid.test(origin)) {
            found.add(origin);
        }

        Set<BlockPos> seen = new HashSet<>();
        seen.add(origin);
        Deque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(origin);
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.removeFirst();
            for (BlockPos neighbour : neighbours(current)) {
                if (!seen.add(neighbour) || chebyshev(origin, neighbour) > radius) {
                    continue;
                }
                // Only a qualifying position is both listed and used to continue the search, so an
                // unsuitable block can never carry the area across a gap.
                if (valid.test(neighbour)) {
                    found.add(neighbour);
                    frontier.addLast(neighbour);
                }
            }
        }

        found.sort(orderOf(origin));
        for (int index = 0; index < found.size() && targets.size() < limit; index++) {
            targets.add(found.get(index));
        }
    }

    static BlockPos[] neighbours(BlockPos pos) {
        BlockPos[] neighbours = new BlockPos[26];
        int index = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        neighbours[index++] = pos.offset(dx, dy, dz);
                    }
                }
            }
        }
        return neighbours;
    }

    private static int chebyshev(BlockPos origin, BlockPos pos) {
        return Math.max(Math.abs(pos.getY() - origin.getY()),
                Math.max(Math.abs(pos.getX() - origin.getX()), Math.abs(pos.getZ() - origin.getZ())));
    }

    // Chebyshev distance in all three axes, then a stable row order within that distance. Offsets
    // are measured from the origin, so the order does not depend on where the player aims.
    private static Comparator<BlockPos> orderOf(BlockPos origin) {
        return Comparator
                .comparingInt((BlockPos pos) -> chebyshev(origin, pos))
                .thenComparingInt(pos -> pos.getZ() - origin.getZ())
                .thenComparingInt(pos -> pos.getX() - origin.getX());
    }
}
