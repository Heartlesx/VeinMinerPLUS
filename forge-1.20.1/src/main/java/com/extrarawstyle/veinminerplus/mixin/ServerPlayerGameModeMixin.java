package com.extrarawstyle.veinminerplus.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.extrarawstyle.veinminerplus.compat.RemoveObservation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;

/**
 * Reports what vanilla's own removal call returned for a chained block.
 *
 * {@code destroyBlock} saves {@code removeBlock}'s boolean to decide {@code playerDestroy} and the
 * experience drop, but the survival branch still returns {@code true} at the end. A {@code true}
 * from {@code destroyBlock} therefore does not prove the block was removed, which is what the chain
 * counter used to rely on.
 *
 * The observation changes nothing: the method runs exactly as vanilla wrote it and callers see the
 * same boolean. Events, drops, experience, tool damage, block updates and the creative branch all
 * behave as before.
 *
 * Both ends of the call are hooked, not just the return. The entry hook is what lets the observation
 * tell the block's own removal apart from a removal some listener triggered from inside it: the
 * outermost entry is the main call, and anything reported while it is still running is nested. A
 * return-only hook cannot make that distinction, and would let an inner removal at the same position
 * answer for the outer one.
 *
 * Only the game-mode instance and the position are passed on. The level and the player are not read
 * here: they are private fields whose names differ between the development and production jars, and
 * this mod ships no refmap. The attempt already carries them, bound from the chain code that opened
 * it, so the identity check stays complete without them.
 *
 * {@code removeBlock} is a Forge addition and keeps its name in the production jar, so the target
 * needs no refmap entry either.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Inject(method = "removeBlock", at = @At("HEAD"))
    private void veinminerplus$enterRemoval(BlockPos pos, boolean canHarvest,
            CallbackInfoReturnable<Boolean> callback) {
        RemoveObservation.enter((ServerPlayerGameMode) (Object) this, pos);
    }

    @Inject(method = "removeBlock", at = @At("RETURN"))
    private void veinminerplus$exitRemoval(BlockPos pos, boolean canHarvest,
            CallbackInfoReturnable<Boolean> callback) {
        RemoveObservation.exit((ServerPlayerGameMode) (Object) this, pos, callback.getReturnValueZ());
    }
}
