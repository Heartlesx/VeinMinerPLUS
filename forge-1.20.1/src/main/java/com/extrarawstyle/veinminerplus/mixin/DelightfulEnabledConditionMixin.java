package com.extrarawstyle.veinminerplus.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.brnbrd.delightful.Util;
import net.brnbrd.delightful.common.item.DelightfulItems;
import net.brnbrd.delightful.common.loot.LootItemEnabledCondition;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraftforge.registries.RegistryObject;

/**
 * Caches the registry entry a Delightful loot condition asks about, not the answer.
 *
 * {@code LootItemEnabledCondition.test} calls {@code Util.enabled(String)}, which streams every
 * registered Delightful item on every invocation, compares registry id paths, and then reads the
 * live configuration. For a block-drops condition that runs once per broken block, the stream walk
 * repeats hundreds of times a tick for an answer that only ever depends on the item name.
 *
 * The registration list cannot change after the registry freezes, so which entry a given name
 * resolves to is fixed; whether that entry is enabled is not, and is still read every time. So this
 * caches only the lookup - the {@code RegistryObject} - and then calls the same
 * {@code Util.enabled(Supplier)} overload the original would have reached, which performs the live
 * {@code IConfigured.enabled()} / configuration read.
 *
 * Deliberately not cached:
 * - The enabled/disabled answer, which can change when the config is edited.
 * - Anything per player, per level or per loot context.
 * - A miss before registrations are complete: an unresolvable name keeps the original path, so an
 *   early call cannot pin "not registered" forever.
 *
 * Reloading loot builds fresh condition instances, so each reload re-resolves from scratch.
 */
@Mixin(LootItemEnabledCondition.class)
public abstract class DelightfulEnabledConditionMixin {

    // The condition's own item name. Package-private in the target, so it is shadowed rather than
    // read reflectively.
    @Shadow(remap = false)
    @Final
    String item;

    // The resolved entry, or null while unresolved. Per instance: no shared global state, so a
    // reloaded loot table starts clean.
    @Unique
    private RegistryObject<?> veinminerplus$resolved;

    @Inject(method = "test(Lnet/minecraft/world/level/storage/loot/LootContext;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void veinminerplus$testWithCachedEntry(LootContext context,
            CallbackInfoReturnable<Boolean> callback) {
        String name = item;
        if (name == null) {
            return;
        }

        RegistryObject<?> entry = veinminerplus$resolved;
        if (entry == null) {
            entry = veinminerplus$resolve(name);
            if (entry == null) {
                // Not registrable yet, or not a Delightful item: leave the original call in charge.
                // Nothing is cached, so a later call can still resolve it.
                return;
            }
            veinminerplus$resolved = entry;
        }

        // Mirrors the original expression exactly:
        //
        //     DelightfulItems.ITEMS.getEntries().stream()
        //         .filter(reg -> reg.getId().getPath().equals(name))
        //         .map(Util::enabled).findAny()
        //         .orElse(configEnabled(name))
        //
        // `orElse` is eager: even when the entry is found, `configEnabled(name)` is evaluated by the
        // caller before the found value is selected. That is not a no-op - it reads the config map
        // and can throw - so skipping it would change the observable call sequence and the exception
        // behaviour. Only the registry walk is cached here; both reads still happen every call.
        @SuppressWarnings("unchecked")
        RegistryObject<Item> typed = (RegistryObject<Item>) entry;
        boolean found = Util.enabled(typed);
        Util.configEnabled(name);
        callback.setReturnValue(found);
    }

    /**
     * The entry whose registry id path equals the condition's item name.
     *
     * Mirrors the original filter exactly: path only, no namespace, no trimming, no case folding.
     */
    private static RegistryObject<?> veinminerplus$resolve(String name) {
        Optional<? extends RegistryObject<?>> match = DelightfulItems.ITEMS.getEntries().stream()
                .filter(entry -> entry.getId().getPath().equals(name))
                .findAny();
        return match.orElse(null);
    }
}
