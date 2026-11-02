package com.extrarawstyle.veinminerplus.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Answers "this scheduled position is definitely not a leaf any more" without loading anything.
 *
 * Type checks run first so a foreign {@code LevelAccessor} never reaches the position lookup.
 */
public final class LeafAreaCheck {

    private LeafAreaCheck() {
    }

    public static boolean isStillLeaf(ServerLevel level, BlockPos pos) {
        // A chunk that is not resident returns null here, which is NOT the same as the position
        // being unloaded for the purpose of isAreaLoaded: an unloaded chunk still fails the area
        // check, but a loaded one whose section is outside the vertical range also fails it. Any
        // uncertainty therefore has to fall back to the original check, so treat null as "still a
        // leaf" - the caller then runs exactly the code it ran before.
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return true;
        }
        BlockState state = chunk.getBlockState(pos);
        return state.is(net.minecraft.tags.BlockTags.LEAVES);
    }
}
