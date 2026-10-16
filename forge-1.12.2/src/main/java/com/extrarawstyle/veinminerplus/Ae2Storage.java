package com.extrarawstyle.veinminerplus;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.parts.IPartItem;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AEPartLocation;
import appeng.me.helpers.PlayerSource;
import appeng.util.item.AEItemStack;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

// Everything that touches AE2 classes lives in this one class, so the rest of the mod still loads
// when AE2 is not installed.
final class Ae2Storage {
    // 1.12.2 registers everything under the old mod id; "ae2" is only the id from 1.16 on. This is
    // a compile-time constant, so naming it from the outside does not load this class (and with it
    // AE2) when AE2 is absent.
    static final String MOD_ID = "appliedenergistics2";

    private Ae2Storage() {
    }

    // True when the position holds a block that belongs to the grid, decided by the interface the
    // AE2 blocks implement rather than by their namespace, so the blocks of AE2 addons are covered
    // as well. Only called when the AE2 mod id is loaded.
    static boolean isGridHost(World level, BlockPos pos) {
        return level.getTileEntity(pos) instanceof IGridHost;
    }

    // True when the position really is part of a connected AE2 network, so a stray AE2 block cannot
    // be bound to the network slot.
    static boolean canBind(World level, BlockPos pos) {
        return gridAt(level, pos) != null;
    }

    // A held AE2 part item (cable, terminal, ...) is not a block item: every part is placed inside
    // the one shared cable bus block, so that block is what a blacklist entry has to name. The part
    // item interface is the general signal; the cable bus itself is resolved through the block
    // registry, since it is not part of the AE2 API this mod compiles against.
    static Block blacklistBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof IPartItem)) {
            return null;
        }
        return Block.REGISTRY.getObject(new ResourceLocation(MOD_ID, "cable_bus"));
    }

    // Resolves the network once and hands back a sink that keeps inserting into it for the rest of
    // the flush. Returns null when that position is not part of a network right now.
    static StorageRouter.Sink sink(World level, BlockPos pos, EntityPlayer player) {
        IGrid grid = gridAt(level, pos);
        if (grid == null) {
            return null;
        }
        IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
        if (storageGrid == null) {
            return null;
        }
        IItemStorageChannel channel = AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class);
        IMEMonitor<IAEItemStack> storage = storageGrid.getInventory(channel);
        IEnergySource energy = grid.getCache(IEnergyGrid.class);
        IActionSource source = new PlayerSource(player, null);
        return stack -> {
            IAEItemStack input = AEItemStack.fromItemStack(stack);
            if (input == null || energy == null) {
                return;
            }
            IAEItemStack leftover = AEApi.instance().storage().poweredInsert(energy, storage, input, source,
                    Actionable.MODULATE);
            long inserted = stack.getCount() - (leftover == null ? 0L : leftover.getStackSize());
            stack.shrink((int) Math.min(inserted, stack.getCount()));
        };
    }

    // The bound block is resolved again on every flush, so a moved or broken target simply stops
    // working. 1.12.2 AE2 exposes the node of a block host per side rather than by position.
    private static IGrid gridAt(World level, BlockPos pos) {
        TileEntity tileEntity = level.getTileEntity(pos);
        if (!(tileEntity instanceof IGridHost)) {
            return null;
        }
        IGridHost host = (IGridHost) tileEntity;
        for (AEPartLocation side : AEPartLocation.SIDE_LOCATIONS) {
            IGridNode node = host.getGridNode(side);
            if (node != null && node.getGrid() != null) {
                return node.getGrid();
            }
        }
        IGridNode internal = host.getGridNode(AEPartLocation.INTERNAL);
        return internal == null ? null : internal.getGrid();
    }
}
