package com.extrarawstyle.veinminerplus;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

// Everything that touches AE2 classes lives in this one class, so the rest of the mod still loads
// when AE2 is not installed.
final class Ae2Storage {
    private Ae2Storage() {
    }

    // True when the position really is part of a connected AE2 network, so a stray AE2 block cannot
    // be bound to the network slot.
    static boolean canBind(Level level, BlockPos pos) {
        return gridAt(level, pos) != null;
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
