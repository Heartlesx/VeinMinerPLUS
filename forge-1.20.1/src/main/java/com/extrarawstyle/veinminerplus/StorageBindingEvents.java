package com.extrarawstyle.veinminerplus;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

public final class StorageBindingEvents {
    private static final String AE2_MOD_ID = "ae2";
    private static final String AE2_NAMESPACE = "ae2";
    // A backpack counts as Sophisticated Storage when it is placed on the ground; a carried one has
    // its own magnet upgrade and is deliberately left alone.
    private static final Set<String> SOPHISTICATED_NAMESPACES = Set.of("sophisticatedstorage",
            "sophisticatedbackpacks");
    private static final String FUNCTIONAL_NAMESPACE = "functionalstorage";

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
        String namespace = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getNamespace();
        // The mod id is checked before Ae2Storage is touched, so no AE2 class is loaded without AE2.
        if (ModList.get().isLoaded(AE2_MOD_ID) && AE2_NAMESPACE.equals(namespace)) {
            if (Ae2Storage.canBind(level, pos)) {
                bind(event, player, card, StorageBindings.TYPE_AE2, target(level, pos), pos);
            } else {
                player.displayClientMessage(
                        Component.translatable("message.veinminerplus.binder.ae2_offline"), true);
            }
            return;
        }
        if (SOPHISTICATED_NAMESPACES.contains(namespace)) {
            bind(event, player, card, StorageBindings.TYPE_SOPHISTICATED, target(level, pos), pos);
        } else if (namespace.equals(FUNCTIONAL_NAMESPACE)) {
            bind(event, player, card, StorageBindings.TYPE_FUNCTIONAL, target(level, pos), pos);
        } else {
            // Nothing to bind here, so the click is left alone and the block behaves as always.
            player.displayClientMessage(Component.translatable("message.veinminerplus.binder.unsupported"), true);
        }
    }

    // Binding always replaces the target the card already holds, so a card never points at two places.
    private static void bind(PlayerInteractEvent.RightClickBlock event, ServerPlayer player, ItemStack card,
            String type, StorageBindings.BlockTarget target, BlockPos pos) {
        StorageBindings.write(card, new StorageBindings(type, target));
        Component where = Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        player.displayClientMessage(Component.translatable("message.veinminerplus.binder." + type, where), true);
        event.setCanceled(true);
    }

    private static StorageBindings.BlockTarget target(ServerLevel level, BlockPos pos) {
        return new StorageBindings.BlockTarget(level.dimension(), pos.immutable());
    }
}
