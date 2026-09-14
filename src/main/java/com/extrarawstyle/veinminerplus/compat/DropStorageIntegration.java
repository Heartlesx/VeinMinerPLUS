package com.extrarawstyle.veinminerplus.compat;

import java.util.List;

import com.extrarawstyle.veinminerplus.NetworkTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public interface DropStorageIntegration {
    BindingResult findTarget(Level level, BlockPos pos, Direction side);

    StorageResult store(MinecraftServer server, ServerPlayer player, NetworkTarget target, List<ItemStack> items);
}
