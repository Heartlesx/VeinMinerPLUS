package com.extrarawstyle.veinminerplus;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class StorageBindingEvents {
    // Binding has to happen before the block gets the click, otherwise the ME controller or a drawer
    // would open its own screen instead.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
                || !event.getEntity().isShiftKeyDown()
                || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ItemStack card = player.getMainHandItem();
        if (!card.is(ModItems.STORAGE_BINDER.get())) {
            return;
        }

        BlockPos pos = event.getPos();
        // Which storage a block is comes from the capabilities it exposes, never from a namespace
        // list: AE2's grid capability decides the network slot, and any block with an item handler
        // binds as generic storage. New storage mods therefore work without changes here.
        if (ModList.get().isLoaded(Ae2Storage.MOD_ID)) {
            // The mod id is checked before Ae2Storage is touched, so no AE2 class is loaded without AE2.
            if (Ae2Storage.canBind(level, pos)) {
                bind(event, player, card, StorageBindings.TYPE_AE2, target(level, pos), pos);
                return;
            }
            if (Ae2Storage.canHostGrid(level, pos)) {
                player.displayClientMessage(
                        Component.translatable("message.veinminerplus.binder.ae2_offline"), true);
                return;
            }
        }
        if (StorageRouter.blockHandler(level, pos) != null) {
            bind(event, player, card, StorageBindings.TYPE_STORAGE, target(level, pos), pos);
        } else {
            // Nothing to bind here, so the click is left alone and the block behaves as always.
            player.displayClientMessage(
                    Component.translatable("message.veinminerplus.binder.unsupported"), true);
        }
    }

    // Binding always replaces the target the card already holds, so a card never points at two places.
    private static void bind(PlayerInteractEvent.RightClickBlock event, ServerPlayer player, ItemStack card,
            String type, StorageBindings.BlockTarget target, BlockPos pos) {
        StorageBindings.write(card, new StorageBindings(type, target));
        Component where = Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        player.displayClientMessage(Component.translatable("message.veinminerplus.binder." + type, where), true);
        // A binding does nothing while the config switch is off, and nothing else would say so.
        if (!Config.STORAGE_BINDING.getAsBoolean()) {
            player.displayClientMessage(Component.translatable("message.veinminerplus.binder.disabled"), false);
        }
        event.setCanceled(true);
    }

    private static StorageBindings.BlockTarget target(ServerLevel level, BlockPos pos) {
        return new StorageBindings.BlockTarget(level.dimension(), pos.immutable());
    }
}
