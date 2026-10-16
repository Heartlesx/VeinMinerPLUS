package com.extrarawstyle.veinminerplus;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class StorageBindingEvents {
    // Binding has to happen before the block gets the click, otherwise the ME controller or a drawer
    // would open its own screen instead.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != EnumHand.MAIN_HAND
                || event.getWorld().isRemote
                || !event.getEntityPlayer().isSneaking()
                || !(event.getEntityPlayer() instanceof EntityPlayerMP)) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.getEntityPlayer();
        ItemStack card = player.getHeldItemMainhand();
        if (card.isEmpty() || card.getItem() != ModItems.STORAGE_BINDER) {
            return;
        }

        BlockPos pos = event.getPos();
        // A block of the AE2 grid binds to the network. The grid host interface decides this, not
        // the block's namespace, so the blocks of AE2 addons are covered as well. The mod id is
        // checked before Ae2Storage is touched, so no AE2 class is loaded without AE2.
        if (Loader.isModLoaded(Ae2Storage.MOD_ID) && Ae2Storage.isGridHost(event.getWorld(), pos)) {
            if (Ae2Storage.canBind(event.getWorld(), pos)) {
                bind(event, player, card, StorageBindings.TYPE_AE2, pos);
            } else {
                player.sendStatusMessage(
                        new TextComponentTranslation("message.veinminerplus.binder.ae2_offline"), true);
            }
            return;
        }
        // Everything else with an item handler binds as a plain container, which covers vanilla
        // chests, hoppers and furnaces as well as the machines and storage blocks of other mods.
        // The same handler check is what the flush uses later, so whatever can be bound here can
        // also be filled.
        if (StorageRouter.blockHandler(player.getServerWorld(), pos) != null) {
            bind(event, player, card, StorageBindings.TYPE_BLOCK, pos);
        } else {
            // Nothing to bind here, so the click is left alone and the block behaves as always.
            player.sendStatusMessage(
                    new TextComponentTranslation("message.veinminerplus.binder.unsupported"), true);
        }
    }

    // Binding always replaces the target the card already holds, so a card never points at two places.
    private static void bind(PlayerInteractEvent.RightClickBlock event, EntityPlayerMP player, ItemStack card,
            String type, BlockPos pos) {
        StorageBindings.BlockTarget target = new StorageBindings.BlockTarget(
                player.getServerWorld().provider.getDimension(), pos.getX(), pos.getY(), pos.getZ());
        StorageBindings.write(card, new StorageBindings(type, target));
        player.sendStatusMessage(new TextComponentTranslation("message.veinminerplus.binder." + type,
                new TextComponentString(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())), true);
        // A binding does nothing while the config switch is off, and nothing else would say so.
        if (!Config.storageBinding) {
            player.sendStatusMessage(
                    new TextComponentTranslation("message.veinminerplus.binder.disabled"), false);
        }
        event.setCanceled(true);
    }
}
