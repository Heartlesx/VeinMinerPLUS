package com.extrarawstyle.veinminerplus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import com.extrarawstyle.veinminerplus.compat.DropStorage;
import com.extrarawstyle.veinminerplus.compat.StorageResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Main-thread, server-lifecycle-bound retry queue for temporary AE failures.
 *
 * Minecraft worlds, chunks and AE grids are not thread-safe, so this deliberately
 * does not use a Java background thread. Server ticks provide the daemon-like
 * retry behaviour without concurrent world access or leaked worker threads.
 */
final class DropReturnGuardian {
    private static final int RETRY_INTERVAL_TICKS = 10;
    private static final int MAX_RETRY_TICKS = 200;
    private static final int MAX_ATTEMPTS = 20;
    private static final int MAX_ATTEMPTS_PER_TICK = 4;
    private static final int MAX_PENDING_RETURNS = 256;
    private static final long MAX_QUEUED_ITEMS = 4_000_000L;

    private static final Deque<PendingReturn> PENDING = new ArrayDeque<>();
    private static long guardianTick;
    private static long queuedItems;

    private DropReturnGuardian() {
    }

    static void reset() {
        PENDING.clear();
        guardianTick = 0;
        queuedItems = 0;
    }

    static boolean enqueue(ServerPlayer player, NetworkTarget target, List<ItemStack> items, long alreadyInserted,
            int brokenBlocks, ServerLevel fallbackLevel, double fallbackX, double fallbackY, double fallbackZ) {
        List<ItemStack> ownedItems = ItemStackAccumulator.copyAndAggregate(items);
        long itemCount = ItemStackAccumulator.count(ownedItems);
        if (itemCount == 0) {
            sendResult(player, brokenBlocks, alreadyInserted, 0);
            return true;
        }
        if (PENDING.size() >= MAX_PENDING_RETURNS || queuedItems + itemCount > MAX_QUEUED_ITEMS) {
            VeinMinerPlus.LOGGER.warn("AE return guardian queue is full; falling back to world drops for {} items",
                    itemCount);
            return false;
        }

        PENDING.addLast(new PendingReturn(player.getUUID(), target, ownedItems, alreadyInserted, brokenBlocks,
                fallbackLevel.dimension().location(), fallbackX, fallbackY, fallbackZ,
                guardianTick + RETRY_INTERVAL_TICKS, guardianTick + MAX_RETRY_TICKS, 0));
        queuedItems += itemCount;
        return true;
    }

    static void tick(MinecraftServer server) {
        guardianTick++;
        if (PENDING.isEmpty()) {
            return;
        }

        int checks = PENDING.size();
        int attempted = 0;
        while (checks-- > 0 && attempted < MAX_ATTEMPTS_PER_TICK && !PENDING.isEmpty()) {
            PendingReturn pending = PENDING.removeFirst();
            if (guardianTick < pending.nextAttemptTick()) {
                PENDING.addLast(pending);
                continue;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
            if (player == null) {
                if (guardianTick >= pending.deadlineTick()) {
                    settleFallback(server, pending, null);
                } else {
                    PENDING.addLast(pending.withNextAttempt(guardianTick + RETRY_INTERVAL_TICKS));
                }
                continue;
            }

            attempted++;
            long before = ItemStackAccumulator.count(pending.items());
            long aeStartedNanos = System.nanoTime();
            StorageResult result;
            try {
                result = DropStorage.store(server, player, pending.target(), pending.items());
            } finally {
                ChainEvents.recordAeFlush(System.nanoTime() - aeStartedNanos, pending.items().size());
            }
            long after = ItemStackAccumulator.count(result.remaining());
            queuedItems -= Math.max(0, before - after);
            PendingReturn updated = pending.withResult(result.remaining(), result.inserted(),
                    guardianTick + RETRY_INTERVAL_TICKS);

            if (!result.retryable()) {
                settleAvailable(server, updated, player);
            } else if (updated.attempts() >= MAX_ATTEMPTS || guardianTick >= updated.deadlineTick()) {
                settleFallback(server, updated, player);
            } else {
                PENDING.addLast(updated);
            }
        }
    }

    static void shutdown(MinecraftServer server) {
        // Stopping happens on the server thread while levels are still valid. Give
        // every pending return one last insertion attempt, then materialize any
        // remainder so no in-memory queue is abandoned during shutdown.
        while (!PENDING.isEmpty()) {
            PendingReturn pending = PENDING.removeFirst();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
            if (player != null) {
                long aeStartedNanos = System.nanoTime();
                StorageResult result;
                try {
                    result = DropStorage.store(server, player, pending.target(), pending.items());
                } finally {
                    ChainEvents.recordAeFlush(System.nanoTime() - aeStartedNanos, pending.items().size());
                }
                long before = ItemStackAccumulator.count(pending.items());
                long after = ItemStackAccumulator.count(result.remaining());
                queuedItems -= Math.max(0, before - after);
                pending = pending.withResult(result.remaining(), result.inserted(), guardianTick);
                if (!result.retryable()) {
                    settleAvailable(server, pending, player);
                    continue;
                }
            }
            settleFallback(server, pending, player);
        }
        reset();
    }

    private static void settleAvailable(MinecraftServer server, PendingReturn pending, ServerPlayer player) {
        long dropped = ItemStackAccumulator.count(pending.items());
        if (dropped > 0) {
            dropItems(server, pending, player);
        }
        queuedItems -= dropped;
        sendResult(player, pending.brokenBlocks(), pending.inserted(), dropped);
    }

    private static void settleFallback(MinecraftServer server, PendingReturn pending, ServerPlayer player) {
        long dropped = ItemStackAccumulator.count(pending.items());
        if (dropped > 0) {
            dropItems(server, pending, player);
        }
        queuedItems -= dropped;
        if (player != null) {
            sendResult(player, pending.brokenBlocks(), pending.inserted(), dropped);
        }
    }

    private static void dropItems(MinecraftServer server, PendingReturn pending, ServerPlayer onlinePlayer) {
        ServerLevel level;
        double x;
        double y;
        double z;
        if (onlinePlayer != null && !onlinePlayer.isRemoved()) {
            level = onlinePlayer.serverLevel();
            x = onlinePlayer.getX();
            y = onlinePlayer.getY();
            z = onlinePlayer.getZ();
        } else {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, pending.fallbackDimension());
            level = server.getLevel(key);
            if (level == null) {
                level = server.overworld();
            }
            x = pending.fallbackX();
            y = pending.fallbackY();
            z = pending.fallbackZ();
            level.getChunkAt(BlockPos.containing(x, y, z));
        }

        for (ItemStack stack : ItemStackAccumulator.splitForEntities(pending.items())) {
            ItemEntity item = new ItemEntity(level, x, y, z, stack);
            item.setDefaultPickUpDelay();
            item.setDeltaMovement(0.0D, 0.0D, 0.0D);
            level.addFreshEntity(item);
        }
    }

    static void dropImmediately(ServerLevel level, double x, double y, double z, List<ItemStack> items) {
        for (ItemStack stack : ItemStackAccumulator.splitForEntities(items)) {
            ItemEntity item = new ItemEntity(level, x, y, z, stack);
            item.setDefaultPickUpDelay();
            item.setDeltaMovement(0.0D, 0.0D, 0.0D);
            level.addFreshEntity(item);
        }
    }

    static void sendResult(ServerPlayer player, int brokenBlocks, long inserted, long dropped) {
        if (inserted > 0 && dropped == 0) {
            player.sendSystemMessage(Component.translatable("message.veinminerplus.ae_stored", brokenBlocks, inserted));
        } else if (inserted > 0) {
            player.sendSystemMessage(Component.translatable("message.veinminerplus.ae_partial", brokenBlocks,
                    inserted, dropped));
        } else {
            player.sendSystemMessage(Component.translatable("message.veinminerplus.ae_failed", brokenBlocks, dropped));
        }
    }

    private record PendingReturn(UUID playerId, NetworkTarget target, List<ItemStack> items, long inserted,
            int brokenBlocks, ResourceLocation fallbackDimension, double fallbackX, double fallbackY, double fallbackZ,
            long nextAttemptTick, long deadlineTick, int attempts) {
        private PendingReturn {
            items = List.copyOf(new ArrayList<>(items));
        }

        private PendingReturn withNextAttempt(long tick) {
            return new PendingReturn(playerId, target, items, inserted, brokenBlocks, fallbackDimension,
                    fallbackX, fallbackY, fallbackZ, tick, deadlineTick, attempts);
        }

        private PendingReturn withResult(List<ItemStack> remaining, long newlyInserted, long tick) {
            return new PendingReturn(playerId, target, ItemStackAccumulator.copyAndAggregate(remaining),
                    inserted + newlyInserted, brokenBlocks, fallbackDimension, fallbackX, fallbackY, fallbackZ,
                    tick, deadlineTick, attempts + 1);
        }
    }
}
