package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import com.extrarawstyle.veinminerplus.CuriosLookup;

// Offers drops to the bound targets in slot order. The targets are resolved once per flush and then
// reused for every stack, so a flush with many stacks does not repeat the lookups.
final class StorageRouter {

    private StorageRouter() {
    }

    // A bound target that is ready to take items.
    interface Sink {
        void insert(ItemStack stack);
    }

    static List<Sink> resolveFallback(MinecraftServer server, ServerPlayer player, StorageBindings bindings) {
        List<Sink> sinks = new ArrayList<>(7);
        addPlayerBackpacks(player, sinks);
        addBlock(server, bindings.sophisticated(), sinks);
        addBlock(server, bindings.functional(), sinks);
        addPlayerInventory(player, sinks);
        return sinks;
    }

    // Sophisticated backpacks can be carried in the normal inventory or worn through Curios.
    // Their item capability is the authoritative insertion API, so this also works for upgrades
    // and modded backpack sizes without depending on the backpack implementation classes.
    private static void addPlayerBackpacks(ServerPlayer player, List<Sink> sinks) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            addBackpack(player.getInventory().getItem(slot), sinks);
        }
        if (ModList.get().isLoaded("curios")) {
            addBackpackHandler(CuriosLookup.equipped(player), sinks);
        }
    }

    private static void addBackpackHandler(IItemHandler handler, List<Sink> sinks) {
        if (handler == null) return;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            addBackpack(handler.getStackInSlot(slot), sinks);
        }
    }

    private static void addBackpack(ItemStack backpack, List<Sink> sinks) {
        if (backpack.isEmpty()) return;
        var key = BuiltInRegistries.ITEM.getKey(backpack.getItem());
        if (key == null || !key.getNamespace().equals("sophisticatedbackpacks")) return;
        IItemHandler handler = backpack.getCapability(Capabilities.ItemHandler.ITEM);
        if (handler != null) {
            sinks.add(stack -> stack.setCount(ItemHandlerHelper.insertItemStacked(handler, stack.copy(), false).getCount()));
        }
    }

    private static void addPlayerInventory(ServerPlayer player, List<Sink> sinks) {
        sinks.add(stack -> {
            ItemStack copy = stack.copy();
            player.getInventory().add(copy);
            stack.setCount(copy.getCount());
            player.getInventory().setChanged();
        });
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
