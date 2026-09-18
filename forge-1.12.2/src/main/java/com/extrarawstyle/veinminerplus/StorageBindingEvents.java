package com.extrarawstyle.veinminerplus;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
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
    private static final String AE2_MOD_ID = "ae2";
    private static final String AE2_NAMESPACE = "ae2";
    // A backpack counts as Sophisticated Storage when it is placed on the ground; a carried one has
    // its own magnet upgrade and is deliberately left alone.
    private static final Set<String> SOPHISTICATED_NAMESPACES = new HashSet<>(Arrays.asList(
            "sophisticatedstorage", "sophisticatedbackpacks"));
    private static final String FUNCTIONAL_NAMESPACE = "functionalstorage";

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
        String namespace = event.getWorld().getBlockState(pos).getBlock().getRegistryName().getNamespace();
        // The mod id is checked before Ae2Storage is touched, so no AE2 class is loaded without AE2.
        if (Loader.isModLoaded(AE2_MOD_ID) && AE2_NAMESPACE.equals(namespace)) {
            if (Ae2Storage.canBind(event.getWorld(), pos)) {
                bind(event, player, card, StorageBindings.TYPE_AE2, pos);
            } else {
                player.sendStatusMessage(
                        new TextComponentTranslation("message.veinminerplus.binder.ae2_offline"), true);
            }
            return;
        }
        if (SOPHISTICATED_NAMESPACES.contains(namespace)) {
            bind(event, player, card, StorageBindings.TYPE_SOPHISTICATED, pos);
        } else if (FUNCTIONAL_NAMESPACE.equals(namespace)) {
            bind(event, player, card, StorageBindings.TYPE_FUNCTIONAL, pos);
        } else if (StorageRouter.blockHandler(player.getServerWorld(), pos) != null) {
            // Anything that exposes an item handler is worth binding, which covers vanilla chests,
            // hoppers and furnaces as well as the machines of other mods.
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
        event.setCanceled(true);
    }
}
