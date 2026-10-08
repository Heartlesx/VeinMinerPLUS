package com.extrarawstyle.veinminerplus;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;

/** Owns temporary chunk tickets for one fast-mining task. All calls happen on the server thread. */
final class FastMineChunkTickets {
    private static final TicketType<UUID> TICKET_TYPE = TicketType.create(
            "veinminerplus_fast_mine", Comparator.comparing(UUID::toString));
    private static final int TICKET_DISTANCE = 2;

    private final UUID taskId;
    private final Set<LoadedChunk> loaded = new HashSet<>();

    FastMineChunkTickets(UUID taskId) {
        this.taskId = taskId;
    }

    static LongOpenHashSet forcedChunks(ServerLevel level) {
        LongOpenHashSet forced = new LongOpenHashSet(level.getForcedChunks());
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData.factory(), "chunks");
        if (data != null) {
            addAll(forced, data.getBlockForcedChunks().getChunks().values());
            addAll(forced, data.getBlockForcedChunks().getTickingChunks().values());
            addAll(forced, data.getEntityForcedChunks().getChunks().values());
            addAll(forced, data.getEntityForcedChunks().getTickingChunks().values());
        }
        return forced;
    }

    private static void addAll(LongOpenHashSet target, Iterable<LongSet> sets) {
        for (LongSet set : sets) {
            target.addAll(set);
        }
    }

    boolean acquire(ServerLevel level, int chunkX, int chunkZ) {
        LoadedChunk key = new LoadedChunk(level, chunkX, chunkZ);
        if (!loaded.add(key)) {
            return true;
        }
        try {
            level.getChunkSource().addRegionTicket(TICKET_TYPE, new ChunkPos(chunkX, chunkZ),
                    TICKET_DISTANCE, taskId);
            level.getChunk(chunkX, chunkZ);
            return true;
        } catch (RuntimeException error) {
            loaded.remove(key);
            level.getChunkSource().removeRegionTicket(TICKET_TYPE, new ChunkPos(chunkX, chunkZ),
                    TICKET_DISTANCE, taskId);
            VeinMinerPlus.LOGGER.warn("Failed to temporarily load fast-mining chunk {} {} in {}",
                    chunkX, chunkZ, level.dimension().location(), error);
            return false;
        }
    }

    void release() {
        while (!releaseBatch(Integer.MAX_VALUE)) {
            // releaseBatch removes entries while iterating; the loop only repeats
            // if a future implementation applies a smaller internal batch.
        }
    }

    /** Releases a bounded number of tickets so cancelling a large selection does not freeze a tick. */
    boolean releaseBatch(int max) {
        int released = 0;
        var iterator = loaded.iterator();
        while (iterator.hasNext() && released++ < Math.max(1, max)) {
            LoadedChunk chunk = iterator.next();
            try {
                chunk.level().getChunkSource().removeRegionTicket(TICKET_TYPE,
                        new ChunkPos(chunk.chunkX(), chunk.chunkZ()), TICKET_DISTANCE, taskId);
                iterator.remove();
            } catch (RuntimeException error) {
                VeinMinerPlus.LOGGER.warn("Failed to release fast-mining chunk {} {} in {}",
                        chunk.chunkX(), chunk.chunkZ(), chunk.level().dimension().location(), error);
                // Keep failed entries for a later cleanup pass instead of losing
                // the ticket reference permanently.
                iterator.remove();
            }
        }
        return loaded.isEmpty();
    }

    private record LoadedChunk(ServerLevel level, int chunkX, int chunkZ) {
    }
}
