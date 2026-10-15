package com.extrarawstyle.veinminerplus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

// Turns the binding of a card into the sink that takes the drops. The target is resolved once per flush
// and then reused for every stack, so a flush with many stacks does not repeat the lookups.
final class StorageRouter {
    private StorageRouter() {
    }

    // A bound target that is ready to take items.
    interface Sink {
        void insert(ItemStack stack);
    }

    static Sink resolve(MinecraftServer server, ServerPlayer player, StorageBindings bindings) {
        ServerLevel level = server.getLevel(bindings.target().dimension());
        if (level == null) {
            return null;
        }
        if (StorageBindings.TYPE_AE2.equals(bindings.type())) {
            // The mod id is checked before Ae2Storage is touched, so no AE2 class is loaded without AE2.
            return ModList.get().isLoaded(Ae2Storage.MOD_ID)
                    ? Ae2Storage.sink(level, bindings.target().pos(), player)
                    : null;
        }
        IItemHandler handler = blockHandler(level, bindings.target().pos());
        return handler == null ? null
                : stack -> stack.setCount(
                        ItemHandlerHelper.insertItemStacked(handler, stack.copy(), false).getCount());
    }

    // Some blocks only expose their inventory for a specific side, so a side-less lookup failing is
    // not the end of it.
    static IItemHandler blockHandler(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            return handler;
        }
        for (Direction side : Direction.values()) {
            handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }
}
