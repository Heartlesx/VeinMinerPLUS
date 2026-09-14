package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.List;

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

// Offers drops to the bound targets in slot order. The targets are resolved once per flush and then
// reused for every stack, so a flush with many stacks does not repeat the lookups.
final class StorageRouter {
    private static final String AE2_MOD_ID = "ae2";

    private StorageRouter() {
    }

    // A bound target that is ready to take items.
    interface Sink {
        void insert(ItemStack stack);
    }

    static List<Sink> resolve(MinecraftServer server, ServerPlayer player, StorageBindings bindings) {
        List<Sink> sinks = new ArrayList<>(3);
        if (bindings.ae2() != null) {
            // The mod id is checked before Ae2Storage is touched, so no AE2 class is loaded without AE2.
            ServerLevel targetLevel = ModList.get().isLoaded(AE2_MOD_ID)
                    ? server.getLevel(bindings.ae2().dimension())
                    : null;
            Sink ae2 = targetLevel == null ? null
                    : Ae2Storage.sink(targetLevel, bindings.ae2().pos(), player);
            if (ae2 != null) {
                sinks.add(ae2);
            }
        }
        addBlock(server, bindings.sophisticated(), sinks);
        addBlock(server, bindings.functional(), sinks);
        return sinks;
    }

    static void insert(List<Sink> sinks, ItemStack stack) {
        for (Sink sink : sinks) {
            sink.insert(stack);
            if (stack.isEmpty()) {
                return;
            }
        }
    }

    private static void addBlock(MinecraftServer server, StorageBindings.BlockTarget target, List<Sink> sinks) {
        if (target == null) {
            return;
        }
        ServerLevel targetLevel = server.getLevel(target.dimension());
        if (targetLevel == null) {
            return;
        }
        IItemHandler handler = blockHandler(targetLevel, target.pos());
        if (handler != null) {
            sinks.add(stack -> stack.setCount(
                    ItemHandlerHelper.insertItemStacked(handler, stack.copy(), false).getCount()));
        }
    }

    // Some blocks only expose their inventory for a specific side, so a side-less lookup failing is
    // not the end of it.
    private static IItemHandler blockHandler(ServerLevel level, BlockPos pos) {
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
