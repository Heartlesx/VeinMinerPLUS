package com.extrarawstyle.veinminerplus.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.extrarawstyle.veinminerplus.compat.LeafAreaCheck;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;

/**
 * Lets FastLeafDecay skip the full neighbourhood load check for scheduled positions that are
 * already known not to be leaves.
 *
 * FastLeafDecay 35 decrements every scheduled entry each server post-tick and, once due, runs
 * {@code isAreaLoaded(pos, 1)} before reading the block. A big tree felled by a chain fills that
 * queue with positions that a previous server tick has already turned into air or logs, and the
 * neighbourhood check then costs more than deciding it is a no-op would.
 *
 * {@link LeafAreaCheck} answers "is this definitely no longer a leaf" only when the chunk is
 * resident. It never loads a chunk, never reads through to a different world and returns the
 * original {@code isAreaLoaded} result whenever it cannot be sure, so the scheduling, delay
 * decrement, weak-reference handling, random tick call and random consumption are unchanged.
 */
@Mixin(targets = "net.olafski.fastleafdecay.FldScheduler", remap = false)
public abstract class FastLeafDecaySchedulerMixin {

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/LevelAccessor;isAreaLoaded("
                            + "Lnet/minecraft/core/BlockPos;I)Z"),
            remap = false)
    private boolean veinminerplus$skipNonLeaf(LevelAccessor accessor, BlockPos pos, int radius) {
        if (accessor instanceof ServerLevel level && !LeafAreaCheck.isStillLeaf(level, pos)) {
            // Not a leaf any more: the original code would have discarded the entry right after the
            // area check, so answering false takes the same path without walking the neighbourhood.
            return false;
        }
        return accessor.isAreaLoaded(pos, radius);
    }
}
