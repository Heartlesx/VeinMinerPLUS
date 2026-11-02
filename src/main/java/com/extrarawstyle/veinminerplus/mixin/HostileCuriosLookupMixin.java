package com.extrarawstyle.veinminerplus.mixin;

import java.util.Optional;
import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * Replaces the item-identity curio lookup used by Hostile Neural Networks with the predicate
 * overload that takes the cache key directly.
 *
 * Curios 9.5.1's {@code findFirstCurio(Item)} builds a default {@code ItemStack}, walks its
 * components and renders a cache key string before it can consult - or reuse - its own per-tick
 * cache. Hostile Neural Networks calls that overload once per mined block, so a 512-block blast pays
 * for the key construction 512 times per tick.
 *
 * The predicate overload with an explicit key does the same slot scan against Curios' own gameTime
 * cache, with a key that is stable for "the stack's item is the deep learner". Filtering, slot
 * iteration order, active-slot handling, the returned {@code SlotResult} and the empty result are
 * all left to Curios.
 *
 * The redirect only fires on {@code applyToEquippedDeepLearner}'s lookup; the update consumer, the
 * model conditions and the slot write-back in that method are untouched. Mod version gating happens
 * in the mixin config, so a different Curios or Hostile build leaves this class unapplied.
 */
@Mixin(targets = "dev.shadowsoffire.hostilenetworks.curios.CuriosCompat", remap = false)
public abstract class HostileCuriosLookupMixin {

    // Stable identity key: the filter is "stack item == the deep learner item", so one key covers
    // every stack of it regardless of components. Namespaced to avoid colliding with Curios' own
    // keys inside the same cache map.
    private static final String VEINMINERPLUS_DEEP_LEARNER_KEY = "veinminerplus:curio/deep_learner";

    @Redirect(
            method = "applyToEquippedDeepLearner",
            at = @At(value = "INVOKE",
                    target = "Ltop/theillusivec4/curios/api/type/capability/ICuriosItemHandler;"
                            + "findFirstCurio(Lnet/minecraft/world/item/Item;)Ljava/util/Optional;"),
            remap = false)
    private static Optional<SlotResult> veinminerplus$findByPredicate(ICuriosItemHandler handler, Item item) {
        Predicate<ItemStack> matches = stack -> stack.getItem() == item;
        return handler.findFirstCurio(matches, VEINMINERPLUS_DEEP_LEARNER_KEY);
    }
}
