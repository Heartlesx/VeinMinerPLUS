package com.extrarawstyle.veinminerplus;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPartItem;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

// Everything that touches AE2 classes lives in this one class, so the rest of the mod still loads
// when AE2 is not installed.
final class Ae2Storage {
    // Compile-time constant, so referencing it does not load any AE2 class.
    static final String MOD_ID = "ae2";

    private Ae2Storage() {
    }

    // True when the position really is part of a connected AE2 network, so a stray AE2 block cannot
    // be bound to the network slot.
    static boolean canBind(Level level, BlockPos pos) {
        return gridAt(level, pos) != null;
    }

    // True when the block is built to host a grid node (cable, machine, ...), whether or not an
    // exposed node exists right now. This is how an unconnected AE2 block reports itself. The
    // 1.20.1 AE2 api jar does not expose the grid host capability constant, so the api helper
    // that wraps it is used instead.
    static boolean canHostGrid(Level level, BlockPos pos) {
        return GridHelper.getNodeHost(level, pos) != null;
    }

    // A held AE2 part item (cable, terminal, ...) is not a block item: every part is placed inside
    // the one shared cable bus block, so that block is what a blacklist entry has to name. The AE2
    // API jar (the compile-time dependency) does not expose the block constant, so the id is
    // resolved through the block registry instead.
    static Block blacklistBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof IPartItem<?>)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MOD_ID, "cable_bus");
        return BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id) : null;
    }

    // Resolves the network once and hands back a sink that keeps inserting into it for the rest of
    // the flush. Returns null when that position is not part of a network right now.
    static StorageRouter.Sink sink(Level level, BlockPos pos, Player player) {
        IGrid grid = gridAt(level, pos);
        if (grid == null) {
            return null;
        }
        MEStorage storage = grid.getStorageService().getInventory();
        IEnergySource energy = grid.getEnergyService();
        IActionSource source = IActionSource.ofPlayer(player);
        return stack -> {
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) {
                return;
            }
            long inserted = StorageHelper.poweredInsert(energy, storage, key, stack.getCount(), source);
            stack.shrink((int) Math.min(inserted, stack.getCount()));
        };
    }

    // The bound block is resolved again on every flush, so a moved or broken target simply stops
    // working. The AE2 API jar of 1.20.1 does not expose the grid host capability, so the exposed
    // node is asked directly; a block that is not part of a network simply answers null on every side.
    private static IGrid gridAt(Level level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            IGridNode node = GridHelper.getExposedNode(level, pos, side);
            if (node != null && node.getGrid() != null) {
                return node.getGrid();
            }
        }
        return null;
    }
}
