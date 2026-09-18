package com.extrarawstyle.veinminerplus;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

// Turns the binding of a card into the sink that takes the drops. The target is resolved once per flush
// and then reused for every stack, so a flush with many stacks does not repeat the lookups.
final class StorageRouter {
    private static final String AE2_MOD_ID = "ae2";

    @CapabilityInject(IItemHandler.class)
    private static Capability<IItemHandler> itemHandler = null;

    private StorageRouter() {
    }

    // A bound target that is ready to take items.
    interface Sink {
        void insert(ItemStack stack);
    }

    static Sink resolve(WorldServer currentLevel, EntityPlayer player, StorageBindings bindings) {
        WorldServer level = currentLevel.getMinecraftServer().getWorld(bindings.target().dimension());
        if (level == null) {
            return null;
        }
        BlockPos pos = new BlockPos(bindings.target().x(), bindings.target().y(), bindings.target().z());
        if (StorageBindings.TYPE_AE2.equals(bindings.type())) {
            // The mod id is checked before Ae2Storage is touched, so no AE2 class is loaded without AE2.
            return Loader.isModLoaded(AE2_MOD_ID) ? Ae2Storage.sink(level, pos, player) : null;
        }
        IItemHandler handler = blockHandler(level, pos);
        return handler == null ? null : stack -> {
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(handler, stack.copy(), false);
            stack.setCount(leftover.getCount());
        };
    }

    // Some blocks only expose their inventory for a specific side, so a side-less lookup failing is
    // not the end of it. The chunk is read in full first, so a bound target keeps working across
    // dimensions even when nobody is standing next to it. Binding uses the same lookup, so whatever
    // can be bound here can also be filled later.
    static IItemHandler blockHandler(WorldServer level, BlockPos pos) {
        TileEntity tileEntity = level.getTileEntity(pos);
        if (tileEntity == null) {
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            tileEntity = level.getTileEntity(pos);
        }
        if (tileEntity == null || itemHandler == null) {
            return null;
        }
        IItemHandler handler = tileEntity.hasCapability(itemHandler, null)
                ? tileEntity.getCapability(itemHandler, null)
                : null;
        if (handler != null) {
            return handler;
        }
        for (EnumFacing side : EnumFacing.values()) {
            if (tileEntity.hasCapability(itemHandler, side)) {
                handler = tileEntity.getCapability(itemHandler, side);
                if (handler != null) {
                    return handler;
                }
            }
        }
        return null;
    }
}
