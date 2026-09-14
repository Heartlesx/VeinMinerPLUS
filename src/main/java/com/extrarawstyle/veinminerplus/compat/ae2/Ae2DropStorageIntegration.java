package com.extrarawstyle.veinminerplus.compat.ae2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.extrarawstyle.veinminerplus.NetworkTarget;
import com.extrarawstyle.veinminerplus.VeinMinerPlus;
import com.extrarawstyle.veinminerplus.compat.BindingResult;
import com.extrarawstyle.veinminerplus.compat.DropStorageIntegration;
import com.extrarawstyle.veinminerplus.compat.StorageResult;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class Ae2DropStorageIntegration implements DropStorageIntegration {
    @Override
    public BindingResult findTarget(Level level, BlockPos pos, Direction side) {
        IGridNode node = findNode(level, pos, side);
        if (node != null) {
            // Keep the clicked face so multipart cable buses can prefer the same part
            // when the card is resolved later.
            return BindingResult.success(new NetworkTarget(level.dimension().location(), pos, side));
        }

        // Distinguish an AE host whose node is currently unavailable from an unrelated block.
        if (GridHelper.getNodeHost(level, pos) != null || level.getBlockEntity(pos) instanceof IActionHost) {
            return BindingResult.invalid();
        }
        return BindingResult.notNode();
    }

    @Override
    public StorageResult store(MinecraftServer server, ServerPlayer player, NetworkTarget target, List<ItemStack> items) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, target.dimension()));
        if (level == null) {
            return StorageResult.temporarilyUnavailable(items);
        }

        try {
            // The card explicitly names this remote target. Load it only for the lookup;
            // no persistent forced-chunk ticket is created.
            level.getChunkAt(target.pos());
            IGridNode node = findNode(level, target.pos(), target.side());
            if (node == null || !node.isActive()) {
                return StorageResult.temporarilyUnavailable(items);
            }

            var grid = node.getGrid();
            if (grid == null) {
                return StorageResult.temporarilyUnavailable(items);
            }

            var storage = grid.getStorageService().getInventory();
            var energy = grid.getEnergyService();
            var source = IActionSource.ofPlayer(player);

            // Drop capture may contain hundreds of legal 64-item stacks of the same
            // component variant. Aggregate by AE key so poweredInsert runs once per
            // distinct variant instead of once per vanilla stack.
            Map<AEItemKey, AggregatedStack> aggregated = new LinkedHashMap<>();
            List<ItemStack> unkeyed = new ArrayList<>();
            for (ItemStack stack : items) {
                if (stack.isEmpty()) {
                    continue;
                }
                AEItemKey key = AEItemKey.of(stack);
                if (key == null) {
                    unkeyed.add(stack.copy());
                    continue;
                }
                aggregated.compute(key, (ignored, current) -> current == null
                        ? new AggregatedStack(stack.copyWithCount(1), stack.getCount())
                        : current.add(stack.getCount()));
            }

            List<ItemStack> remaining = new ArrayList<>(unkeyed);
            long insertedTotal = 0;
            List<Map.Entry<AEItemKey, AggregatedStack>> entries = new ArrayList<>(aggregated.entrySet());
            for (int index = 0; index < entries.size(); index++) {
                Map.Entry<AEItemKey, AggregatedStack> entry = entries.get(index);
                AggregatedStack value = entry.getValue();
                try {
                    long inserted = StorageHelper.poweredInsert(energy, storage, entry.getKey(), value.amount(), source);
                    inserted = Math.max(0, Math.min(inserted, value.amount()));
                    insertedTotal += inserted;
                    addAmount(remaining, value.representative(), value.amount() - inserted);
                } catch (RuntimeException | LinkageError error) {
                    VeinMinerPlus.LOGGER.warn("AE2 insertion failed for chained drop {} at {} {}",
                            value.representative(), target.dimension(), target.pos(), error);
                    addAmount(remaining, value.representative(), value.amount());
                    for (int rest = index + 1; rest < entries.size(); rest++) {
                        AggregatedStack later = entries.get(rest).getValue();
                        addAmount(remaining, later.representative(), later.amount());
                    }
                    return StorageResult.failed(insertedTotal, remaining);
                }
            }
            return StorageResult.available(insertedTotal, remaining);
        } catch (RuntimeException | LinkageError error) {
            VeinMinerPlus.LOGGER.warn("AE2 network lookup failed at {} {}", target.dimension(), target.pos(), error);
            return StorageResult.failed(items);
        }
    }

    private static void addAmount(List<ItemStack> output, ItemStack representative, long amount) {
        while (amount > 0) {
            int count = (int) Math.min(Integer.MAX_VALUE, amount);
            output.add(representative.copyWithCount(count));
            amount -= count;
        }
    }

    /**
     * Resolves an AE node with the public exposed-node API first (the approach used
     * by PatternChecker), then falls back to the raw node host and finally the
     * block entity's actionable node (the approach used by GTLCore's terminal).
     */
    private static IGridNode findNode(Level level, BlockPos pos, Direction preferredSide) {
        if (preferredSide != null) {
            IGridNode node = GridHelper.getExposedNode(level, pos, preferredSide);
            if (node != null) {
                return node;
            }
        }

        for (Direction candidate : Direction.values()) {
            if (candidate != preferredSide) {
                IGridNode node = GridHelper.getExposedNode(level, pos, candidate);
                if (node != null) {
                    return node;
                }
            }
        }

        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
        if (host != null) {
            if (preferredSide != null) {
                IGridNode node = host.getGridNode(preferredSide);
                if (node != null) {
                    return node;
                }
            }
            for (Direction candidate : Direction.values()) {
                if (candidate != preferredSide) {
                    IGridNode node = host.getGridNode(candidate);
                    if (node != null) {
                        return node;
                    }
                }
            }
            IGridNode centerNode = host.getGridNode(null);
            if (centerNode != null) {
                return centerNode;
            }
        }

        if (level.getBlockEntity(pos) instanceof IActionHost actionHost) {
            return actionHost.getActionableNode();
        }
        return null;
    }

    private record AggregatedStack(ItemStack representative, long amount) {
        private AggregatedStack add(long extra) {
            return new AggregatedStack(representative, amount + extra);
        }
    }
}
