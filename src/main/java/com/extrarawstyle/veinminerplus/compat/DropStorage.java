package com.extrarawstyle.veinminerplus.compat;

import java.util.List;

import com.extrarawstyle.veinminerplus.NetworkTarget;
import com.extrarawstyle.veinminerplus.VeinMinerPlus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

public final class DropStorage {
    private static final DropStorageIntegration INTEGRATION = createIntegration();

    private DropStorage() {
    }

    public static boolean isAvailable() {
        return INTEGRATION != null;
    }

    public static BindingResult findTarget(Level level, BlockPos pos, Direction side) {
        if (INTEGRATION == null) {
            return BindingResult.notNode();
        }
        try {
            return INTEGRATION.findTarget(level, pos, side);
        } catch (RuntimeException | LinkageError error) {
            VeinMinerPlus.LOGGER.warn("Failed to inspect AE2 node at {} {}", level.dimension().location(), pos, error);
            return BindingResult.invalid();
        }
    }

    public static StorageResult store(MinecraftServer server, ServerPlayer player, NetworkTarget target,
            List<ItemStack> items) {
        if (INTEGRATION == null) {
            return StorageResult.unsupported(items);
        }
        try {
            return INTEGRATION.store(server, player, target, items);
        } catch (RuntimeException | LinkageError error) {
            VeinMinerPlus.LOGGER.warn("Failed to insert chained drops into AE2 network at {} {}",
                    target.dimension(), target.pos(), error);
            return StorageResult.failed(items);
        }
    }

    private static DropStorageIntegration createIntegration() {
        if (!ModList.get().isLoaded("ae2")) {
            return null;
        }
        try {
            Class<?> integrationClass = Class.forName(
                    "com.extrarawstyle.veinminerplus.compat.ae2.Ae2DropStorageIntegration");
            return (DropStorageIntegration) integrationClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException | LinkageError error) {
            VeinMinerPlus.LOGGER.error("AE2 is installed, but VeinMinerPlus AE2 integration could not be loaded", error);
            return null;
        }
    }
}
