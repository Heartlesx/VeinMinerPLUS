package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.enchantment.Enchantments;

/** Server-thread owner for fast-mining tasks. Workers only inspect immutable snapshots. */
final class FastMineTaskManager {
    private static final long MAX_SELECTED_CHUNKS = 4_096L;
    private static final int MAX_CHUNKS_PER_TICK = 8;
    private static final long MAX_LOAD_BUDGET_NANOS = 20_000_000L;
    /** Position preparation runs alongside mining, so keep its rate below the summary pass. */
    private static final int SCANS_SUBMITTED_PER_TICK = 10;
    /** Summary scanning has no world-mutation work competing with it. */
    private static final int SUMMARY_SCANS_SUBMITTED_PER_TICK = 20;
    private static final int MAX_IN_FLIGHT_SCANS = 20;
    private static final int SUMMARY_MAX_IN_FLIGHT_SCANS = 64;
    private static final int MAX_PENDING_TARGETS = 65_536;
    /*
     * World mutation is still server-thread work.  Use a shared time budget instead of
     * pretending that an unlimited block count is safe.  The batch grows while there is
     * headroom and is cut back as soon as a tick approaches the configured 80 ms window.
     */
    private static final int MIN_BLOCKS_PER_TICK = 32;
    private static final int INITIAL_BLOCKS_PER_TICK = 256;
    /* The cap is only a safety ceiling; the nanosecond deadline below remains authoritative. */
    // This is only an upper bound for the adaptive loop. The nanosecond deadline
    // below remains authoritative, so a larger ceiling can use spare TPS without
    // allowing an over-budget tick to run longer.
    private static final int MAX_BLOCKS_PER_TICK = 262_144;
    private static final long MIN_MINE_BUDGET_NANOS = 4_000_000L;
    /*
     * Fast mining is deliberately allowed to use most of the configured 80 ms tick window.
     * The live tick-time calculation below still cuts this back as soon as the
     * server gets busy, while the reserve leaves room for vanilla and other mods.
     */
    private static final long MAX_MINE_BUDGET_NANOS = 80_000_000L;
    private static final long MINE_TICK_WINDOW_NANOS = 80_000_000L;
    private static final long SERVER_TICK_RESERVE_NANOS = 0L;
    /** UI progress is sent every server tick so an open screen never waits five seconds. */
    private static final int CLIENT_PROGRESS_INTERVAL = 1;
    /** File telemetry stays coarse to avoid turning logging into a workload of its own. */
    private static final int PERFORMANCE_PROGRESS_INTERVAL = 100;
    /** Permanent forced chunks change rarely; rebuilding the saved-data sets every tick is expensive. */
    private static final int FORCED_CHUNK_REFRESH_INTERVAL = 20;
    private static final Map<UUID, Task> TASKS = new HashMap<>();
    /** Network threads only enqueue a UUID; all world and task mutation stays on the server thread. */
    private static final Set<UUID> CANCEL_REQUESTS = ConcurrentHashMap.newKeySet();

    private FastMineTaskManager() {}

    static void begin(ServerPlayer player, FastMineSelection selection) {
        if (!selection.dimension().equals(player.level().dimension())
                || selection.chunkCount() <= 0 || selection.chunkCount() > MAX_SELECTED_CHUNKS) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.veinminerplus.fast_mine.invalid_selection"), true);
            return;
        }
        ChainEvents.FastMineTool tool = ChainEvents.fastMineTool(player.getMainHandItem());
        if (tool == null) {
            player.displayClientMessage(Component.translatable(
                    "message.veinminerplus.fast_mine.tool_required"), true);
            return;
        }
        cancel(player.getUUID());
        CANCEL_REQUESTS.remove(player.getUUID());
        TASKS.put(player.getUUID(), new Task(player, selection, tool));
        if (Config.ENABLE_PERFORMANCE_LOG.getAsBoolean()) {
            VeinMinerPlus.LOGGER.info("[VMP FastMine] event=begin player={} dimension={} minChunk=({}, {}) maxChunk=({}, {}) chunks={} tool={} miningLevel={}",
                    player.getUUID(), selection.dimension().location(), selection.minChunkX(), selection.minChunkZ(),
                    selection.maxChunkX(), selection.maxChunkZ(), selection.chunkCount(),
                    BuiltInRegistries.ITEM.getKey(tool.stack().getItem()), tool.miningLevel());
        }
        PerformanceFileLog.write("event=fast_mine_begin player=" + player.getUUID() + " dimension="
                + selection.dimension().location() + " chunks=" + selection.chunkCount()
                + " tool=" + BuiltInRegistries.ITEM.getKey(tool.stack().getItem())
                + " miningLevel=" + tool.miningLevel());
        NetworkHandler.sendFastMineProgress(player, new FastMineProgress(0L, selection.chunkCount(), 0L,
                selection.chunkCount() * 256L * (long) (player.level().getMaxBuildHeight() - player.level().getMinBuildHeight()),
                0L, 0L, 0L, 0L, FastMineProgress.State.LOADING));
    }

    static void tick(MinecraftServer server) {
        if (Config.ENABLE_PERFORMANCE_LOG.getAsBoolean()) {
            PerformanceFileLog.ensureHealthy();
        }
        for (Task task : TASKS.values().toArray(Task[]::new)) {
            ServerPlayer player = server.getPlayerList().getPlayer(task.playerId);
            if (player == null || player.serverLevel() != task.level) {
                task.cancel();
                TASKS.remove(task.playerId);
                continue;
            }
            try {
                if (CANCEL_REQUESTS.remove(task.playerId)) {
                    task.cancelDeferred(player);
                }
                task.tick(player, Math.max(1, TASKS.size()));
            } catch (RuntimeException error) {
                VeinMinerPlus.LOGGER.error("Fast mining task failed for player {}", task.playerId, error);
                task.fail();
                NetworkHandler.sendFastMineProgress(player, task.progress());
            }
            if (task.terminal() && task.cleanupComplete()) TASKS.remove(task.playerId);
        }
    }

    /** Safe to call from a network thread: it only writes to a concurrent UUID set. */
    static void requestCancel(UUID playerId) {
        CANCEL_REQUESTS.add(playerId);
    }

    static void cancel(UUID playerId) {
        CANCEL_REQUESTS.remove(playerId);
        Task task = TASKS.remove(playerId);
        if (task != null) task.cancel();
    }

    static void cancelAll() {
        for (Task task : TASKS.values()) task.cancel();
        TASKS.clear();
        CANCEL_REQUESTS.clear();
    }

    private static final class Task {
        private final UUID playerId;
        private final ServerLevel level;
        private final FastMineSelection selection;
        private final ChainEvents.FastMineTool tool;
        private final int fortuneLevel;
        private final FastMineChunkTickets tickets;
        private final LongOpenHashSet forcedChunks;
        private final Map<Block, net.minecraft.resources.ResourceLocation> blockIds = new IdentityHashMap<>();
        private final Deque<TargetBatch> targetBatches = new ArrayDeque<>();
        private long pendingTargetCount;
        private final LongOpenHashSet skippedForcedChunks = new LongOpenHashSet();
        private final List<Future<ScanResult>> scans = new ArrayList<>();
        private ChainEvents.FastMineDropSession dropSession;
        private ServerPlayer lastPlayer;
        private int nextChunkX;
        private int nextChunkZ;
        private int scanChunkX;
        private int scanChunkZ;
        private int forcedChunkRefreshTicks;
        /** First pass fixes all totals; the second pass supplies positions for mining. */
        private boolean summaryScan = true;
        private long loadedChunks;
        private long skippedForcedChunkCount;
        private long scannedBlocks;
        private long totalBlocks;
        private long brokenBlocks;
        private long foundOres;
        private long skippedToolLevelOres;
        private long targetBlocks;
        private long processedBlocks;
        private final Map<net.minecraft.resources.ResourceLocation, Long> oreBreakdown = new LinkedHashMap<>();
        private int clientProgressTicks;
        private int performanceProgressTicks;
        private final long startedNanos = System.nanoTime();
        private long loadNanos;
        private long snapshotNanos;
        private long workerScanNanos;
        private long mineNanos;
        private long budgetNanosTotal;
        private long maxBudgetNanosObserved;
        private int blocksPerTick = INITIAL_BLOCKS_PER_TICK;
        private int maxBlocksPerTickObserved = INITIAL_BLOCKS_PER_TICK;
        private long mineBatches;
        private long breakEwmaNanos;
        private boolean deferredCleanup;
        private boolean cleanupComplete = true;
        private long scanStageStartedNanos;
        private long mineStageStartedNanos;
        private FastMineProgress.State state = FastMineProgress.State.LOADING;

        private Task(ServerPlayer player, FastMineSelection selection, ChainEvents.FastMineTool tool) {
            this.playerId = player.getUUID();
            this.level = player.serverLevel();
            this.selection = selection;
            this.tool = tool;
            this.fortuneLevel = Math.max(1, tool.stack()
                    .getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                    .getLevel(player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                            .getOrThrow(Enchantments.FORTUNE)));
            this.tickets = new FastMineChunkTickets(UUID.randomUUID());
            this.forcedChunks = FastMineChunkTickets.forcedChunks(level);
            this.lastPlayer = player;
            this.nextChunkX = selection.minChunkX();
            this.nextChunkZ = selection.minChunkZ();
            this.scanChunkX = selection.minChunkX();
            this.scanChunkZ = selection.minChunkZ();
            this.totalBlocks = selection.chunkCount() * 256L
                    * (long) (level.getMaxBuildHeight() - level.getMinBuildHeight());
        }


        private void tick(ServerPlayer player, int activeTasks) {
            lastPlayer = player;
            lastPlayerX = player.getX();
            lastPlayerY = player.getY();
            lastPlayerZ = player.getZ();
            if (deferredCleanup) {
                cleanupCancelled(player);
                return;
            }
            if (terminal()) return;
            if (state == FastMineProgress.State.LOADING) loadBatch(player);
            if (state == FastMineProgress.State.SCANNING || state == FastMineProgress.State.MINING) {
                scanAndMine(player, activeTasks);
            }
            if (++clientProgressTicks >= CLIENT_PROGRESS_INTERVAL || state == FastMineProgress.State.COMPLETED
                    || state == FastMineProgress.State.FAILED || state == FastMineProgress.State.CANCELLED) {
                clientProgressTicks = 0;
                NetworkHandler.sendFastMineProgress(player, progress());
            }
            if (++performanceProgressTicks >= PERFORMANCE_PROGRESS_INTERVAL || state == FastMineProgress.State.COMPLETED
                    || state == FastMineProgress.State.FAILED || state == FastMineProgress.State.CANCELLED) {
                performanceProgressTicks = 0;
                FastMineProgress snapshot = progress();
                PerformanceFileLog.write("event=fast_mine_progress player=" + playerId + " state=" + state
                        + " loadedChunks=" + snapshot.loadedChunks() + " totalChunks=" + snapshot.totalChunks()
                        + " scannedBlocks=" + snapshot.scannedBlocks() + " targetBlocks=" + snapshot.targetBlocks()
                        + " processedBlocks=" + snapshot.processedBlocks() + " brokenBlocks=" + snapshot.brokenBlocks());
            }
        }

        private void loadBatch(ServerPlayer player) {
            long tickNanos = level.getServer().getAverageTickTimeNanos();
            int chunksPerTick = tickNanos > 45_000_000L ? 1
                    : tickNanos > 35_000_000L ? 2 : MAX_CHUNKS_PER_TICK;
            long loadBudget = Math.min(MAX_LOAD_BUDGET_NANOS,
                    Math.max(4_000_000L, 45_000_000L - tickNanos - SERVER_TICK_RESERVE_NANOS));
            long deadline = System.nanoTime() + loadBudget;
            int loadedThisTick = 0;
            while (loadedThisTick++ < chunksPerTick && nextChunkZ <= selection.maxChunkZ()
                    && (loadedThisTick == 1 || System.nanoTime() < deadline)) {
                if (forcedChunks.contains(ChunkPos.asLong(nextChunkX, nextChunkZ))) {
                    skippedForcedChunks.add(ChunkPos.asLong(nextChunkX, nextChunkZ));
                    skippedForcedChunkCount++;
                    advanceLoadCursor();
                    continue;
                }
                if (!tickets.acquire(level, nextChunkX, nextChunkZ)) {
                    fail();
                    return;
                }
                loadedChunks++;
                advanceLoadCursor();
            }
            if (nextChunkZ > selection.maxChunkZ()) {
                loadNanos = System.nanoTime() - startedNanos;
                totalBlocks = loadedChunks * 256L
                        * (long) (level.getMaxBuildHeight() - level.getMinBuildHeight());
                scanStageStartedNanos = System.nanoTime();
                state = FastMineProgress.State.SCANNING;
                if (skippedForcedChunkCount > 0L) {
                    playerMessage(player, "message.veinminerplus.fast_mine.forced_chunks_skipped",
                            skippedForcedChunkCount);
                    PerformanceFileLog.write("event=fast_mine_forced_chunks_skipped player=" + playerId
                            + " count=" + skippedForcedChunkCount);
                }
            }
        }

        private void playerMessage(ServerPlayer player, String key, Object... args) {
            if (player != null && !player.isRemoved()) {
                player.sendSystemMessage(Component.translatable(key, args));
            }
        }

        private void advanceLoadCursor() {
            nextChunkX++;
            if (nextChunkX > selection.maxChunkX()) {
                nextChunkX = selection.minChunkX();
                nextChunkZ++;
            }
        }

        private void scanAndMine(ServerPlayer player, int activeTasks) {
            collectCompletedScans();
            if (terminal()) return;
            if (forcedChunkRefreshTicks-- <= 0) {
                forcedChunks.clear();
                forcedChunks.addAll(FastMineChunkTickets.forcedChunks(level));
                forcedChunkRefreshTicks = FORCED_CHUNK_REFRESH_INTERVAL;
            }

            int submitted = 0;
            int scanSubmitLimit = summaryScan ? SUMMARY_SCANS_SUBMITTED_PER_TICK : SCANS_SUBMITTED_PER_TICK;
            int inFlightLimit = summaryScan ? SUMMARY_MAX_IN_FLIGHT_SCANS : MAX_IN_FLIGHT_SCANS;
            while (submitted++ < scanSubmitLimit && scans.size() < inFlightLimit
                    && (summaryScan || pendingTargetCount < MAX_PENDING_TARGETS)
                    && scanChunkZ <= selection.maxChunkZ()) {
                int chunkX = scanChunkX;
                int chunkZ = scanChunkZ;
                scanChunkX++;
                if (scanChunkX > selection.maxChunkX()) {
                    scanChunkX = selection.minChunkX();
                    scanChunkZ++;
                }
                long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
                if (skippedForcedChunks.contains(chunkKey) || forcedChunks.contains(chunkKey)) {
                    continue;
                }
                long snapshotStarted = System.nanoTime();
                Snapshot snapshot = snapshot(level.getChunk(chunkX, chunkZ));
                snapshotNanos += System.nanoTime() - snapshotStarted;
                try {
                    scans.add(FastMineRuntime.submit(() -> scan(snapshot)));
                } catch (RuntimeException rejected) {
                    fail();
                    return;
                }
            }

            if (!summaryScan && !targetBatches.isEmpty()) {
                state = FastMineProgress.State.MINING;
                if (dropSession == null) dropSession = ChainEvents.createFastMineDropSession(player, tool.stack());
                mineBatch(player, activeTasks);
            } else if (summaryScan || scanChunkZ <= selection.maxChunkZ() || !scans.isEmpty()) {
                state = FastMineProgress.State.SCANNING;
            }

            if (summaryScan && scanChunkZ > selection.maxChunkZ() && scans.isEmpty()) {
                if (targetBlocks == 0L) {
                    finish(player);
                    return;
                }
                summaryScan = false;
                scanChunkX = selection.minChunkX();
                scanChunkZ = selection.minChunkZ();
                PerformanceFileLog.write("event=fast_mine_scan_summary_complete player=" + playerId
                        + " scannedBlocks=" + scannedBlocks + " targetBlocks=" + targetBlocks
                        + " ores=" + foundOres + " skippedToolLevelOres=" + skippedToolLevelOres);
            }

            if (!summaryScan && scanChunkZ > selection.maxChunkZ() && scans.isEmpty()
                    && targetBatches.isEmpty()) {
                finish(player);
            }
        }

        private void collectCompletedScans() {
            for (int index = 0; index < scans.size();) {
                Future<ScanResult> future = scans.get(index);
                if (!future.isDone() || (pendingTargetCount > 0L && pendingTargetCount >= MAX_PENDING_TARGETS)) {
                    index++;
                    continue;
                }
                try {
                    ScanResult result = future.get();
                    if (summaryScan) {
                        scannedBlocks += result.scannedBlocks();
                        targetBlocks += result.targetCount();
                        foundOres += result.oreCount();
                        skippedToolLevelOres += result.skippedToolLevelOres();
                    } else {
                        for (TargetBatch batch : result.batches()) {
                            targetBatches.addLast(batch);
                            pendingTargetCount += batch.size();
                        }
                    }
                    workerScanNanos += result.workerNanos();
                    scans.remove(index);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail();
                    return;
                } catch (ExecutionException error) {
                    VeinMinerPlus.LOGGER.warn("Fast mining snapshot scan failed", error.getCause());
                    fail();
                    return;
                }
            }
        }

        private Snapshot snapshot(LevelChunk chunk) {
            List<TargetBatch> batches = new ArrayList<>();
            long skippedToolLevelOres = 0L;
            long targetCount = 0L;
            long oreCount = 0L;
            ChunkPos pos = chunk.getPos();
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            LevelChunkSection[] sections = chunk.getSections();
            int minSectionY = level.getMinBuildHeight() >> 4;
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                LevelChunkSection section = sections[sectionIndex];
                if (section.hasOnlyAir()) {
                    continue;
                }
                if (summaryScan) {
                    final long[] sectionCounts = new long[3];
                    section.getStates().count((state, count) -> {
                        if (state.isAir() || state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
                            return;
                        }
                        boolean ore = ChainEvents.isOreForFastMine(state);
                        if (ore && !ChainEvents.fastMineCanMineOre(state, tool)) {
                            sectionCounts[2] += count;
                            return;
                        }
                        sectionCounts[0] += count;
                        if (ore) sectionCounts[1] += count;
                    });
                    targetCount += sectionCounts[0];
                    oreCount += sectionCounts[1];
                    skippedToolLevelOres += sectionCounts[2];
                    continue;
                }
                LongArrayList positions = summaryScan ? null : new LongArrayList();
                ByteArrayList oreFlags = summaryScan ? null : new ByteArrayList();
                int baseY = (minSectionY + sectionIndex) << 4;
                for (int localY = 0; localY < 16; localY++) {
                    int worldY = baseY + localY;
                    if (worldY < level.getMinBuildHeight() || worldY >= level.getMaxBuildHeight()) {
                        continue;
                    }
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            BlockState state = section.getBlockState(localX, localY, localZ);
                            if (state.isAir() || state.is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
                                continue;
                            }
                            boolean ore = ChainEvents.isOreForFastMine(state);
                            if (ore && !ChainEvents.fastMineCanMineOre(state, tool)) {
                                skippedToolLevelOres++;
                                continue;
                            }
                            targetCount++;
                            if (ore) oreCount++;
                            if (summaryScan) {
                                continue;
                            }
                            mutable.set(pos.getMinBlockX() + localX, worldY,
                                    pos.getMinBlockZ() + localZ);
                            positions.add(mutable.asLong());
                            oreFlags.add((byte) (ore ? 1 : 0));
                        }
                    }
                }
                if (!summaryScan && !positions.isEmpty()) {
                    batches.add(new TargetBatch(positions.toLongArray(), oreFlags.toByteArray()));
                }
            }
            return new Snapshot(List.copyOf(batches),
                    256L * (level.getMaxBuildHeight() - level.getMinBuildHeight()), targetCount, oreCount,
                    skippedToolLevelOres, summaryScan);
        }

        private ScanResult scan(Snapshot snapshot) {
            long started = System.nanoTime();
            // Snapshot construction already counted targets and ore flags on the
            // server thread. Recounting every packed position here only burns worker
            // CPU and can never change the result because the snapshot is immutable.
            return new ScanResult(snapshot.scannedBlocks(), snapshot.batches(), snapshot.targetCount(),
                    snapshot.oreCount(),
                    snapshot.skippedToolLevelOres(), System.nanoTime() - started);
        }

        private void mineBatch(ServerPlayer player, int activeTasks) {
            long batchStarted = System.nanoTime();
            long averageTickNanos = level.getServer().getAverageTickTimeNanos();
            long availableNanos = Math.min(MAX_MINE_BUDGET_NANOS,
                    Math.max(MIN_MINE_BUDGET_NANOS,
                            MINE_TICK_WINDOW_NANOS - averageTickNanos - SERVER_TICK_RESERVE_NANOS));
            long budgetNanos = Math.max(MIN_MINE_BUDGET_NANOS, availableNanos / Math.max(1, activeTasks));
            budgetNanosTotal += budgetNanos;
            maxBudgetNanosObserved = Math.max(maxBudgetNanosObserved, budgetNanos);
            long deadlineNanos = System.nanoTime() + budgetNanos;
            int fortune = fortuneLevel;
            int count = 0;
            dropSession.beginBatchCapture();
            try {
                while (count < blocksPerTick && pendingTargetCount > 0L) {
                    if (count > 0 && System.nanoTime() + Math.max(10_000L, breakEwmaNanos) >= deadlineNanos) {
                        break;
                    }
                    count++;
                    TargetBatch batch = targetBatches.peekFirst();
                    int targetIndex = batch.nextIndex();
                    pendingTargetCount--;
                    if (batch.exhausted()) {
                        targetBatches.removeFirst();
                    }
                    BlockPos pos = BlockPos.of(batch.positions()[targetIndex]);
                    boolean ore = batch.oreFlags()[targetIndex] != 0;
                    processedBlocks++;
                    int chunkX = pos.getX() >> 4;
                    int chunkZ = pos.getZ() >> 4;
                    if (!selection.contains(chunkX, chunkZ)
                            || forcedChunks.contains(ChunkPos.asLong(chunkX, chunkZ))) {
                        continue;
                    }
                    Block block = ore ? level.getBlockState(pos).getBlock() : null;
                    net.minecraft.resources.ResourceLocation oreId = block == null ? null
                            : blockIds.computeIfAbsent(block, BuiltInRegistries.BLOCK::getKey);
                    if (dropSession.breakBlock(level, player, pos, fortune, ore)) {
                        brokenBlocks++;
                        if (oreId != null) oreBreakdown.merge(oreId, 1L, Long::sum);
                    }
                }
            } finally {
                dropSession.endBatchCapture();
            }
            long elapsed = System.nanoTime() - batchStarted;
            mineNanos += elapsed;
            if (count > 0) {
                mineBatches++;
                maxBlocksPerTickObserved = Math.max(maxBlocksPerTickObserved, count);
                long sample = elapsed / count;
                breakEwmaNanos = breakEwmaNanos == 0L ? sample : (breakEwmaNanos * 7L + sample) / 8L;
                if (count >= blocksPerTick && elapsed < budgetNanos * 2L / 3L) {
                    blocksPerTick = Math.min(MAX_BLOCKS_PER_TICK, Math.max(blocksPerTick + 1,
                            blocksPerTick * 3 / 2));
                } else if (elapsed > budgetNanos * 9L / 10L) {
                    blocksPerTick = Math.max(MIN_BLOCKS_PER_TICK, blocksPerTick / 2);
                }
            }
        }

        private void finish(ServerPlayer player) {
            ChainEvents.FastMineSettlement settlement = finishDrops(player);
            state = FastMineProgress.State.COMPLETED;
            tickets.release();
            announce(player, "message.veinminerplus.fast_mine.completed", settlement);
            logTerminal();
        }

        private FastMineProgress progress() {
            return new FastMineProgress(loadedChunks, selection.chunkCount(), scannedBlocks, totalBlocks,
                    brokenBlocks, foundOres, targetBlocks, processedBlocks, oreBreakdown, state);
        }

        private void fail() {
            if (terminal()) return;
            state = FastMineProgress.State.FAILED;
            tickets.release();
            for (Future<?> future : scans) future.cancel(true);
            scans.clear();
            targetBatches.clear();
            pendingTargetCount = 0L;
            ChainEvents.FastMineSettlement settlement = finishDrops(lastPlayer);
            announce(lastPlayer, "message.veinminerplus.fast_mine.failed", settlement);
            sendProgress(lastPlayer);
            logTerminal();
        }

        private void cancel() {
            if (terminal()) return;
            state = FastMineProgress.State.CANCELLED;
            tickets.release();
            for (Future<?> future : scans) future.cancel(true);
            scans.clear();
            targetBatches.clear();
            pendingTargetCount = 0L;
            ChainEvents.FastMineSettlement settlement = finishDrops(lastPlayer);
            announce(lastPlayer, "message.veinminerplus.fast_mine.cancelled", settlement);
            sendProgress(lastPlayer);
            logTerminal();
        }

        /** Marks cancellation immediately; ticket release and drop settlement are spread across ticks. */
        private void cancelDeferred(ServerPlayer player) {
            if (terminal()) return;
            state = FastMineProgress.State.CANCELLED;
            deferredCleanup = true;
            cleanupComplete = false;
            for (Future<?> future : scans) future.cancel(true);
            scans.clear();
            targetBatches.clear();
            pendingTargetCount = 0L;
            // Push the terminal state before releasing thousands of chunk tickets
            // so the client UI responds to Cancel without waiting for cleanup.
            sendProgress(player);
            PerformanceFileLog.write("event=fast_mine_cancel_requested player=" + playerId);
        }

        private void cleanupCancelled(ServerPlayer player) {
            if (!tickets.releaseBatch(128)) return;
            ChainEvents.FastMineSettlement settlement = finishDrops(player);
            deferredCleanup = false;
            cleanupComplete = true;
            announce(player, "message.veinminerplus.fast_mine.cancelled", settlement);
            sendProgress(player);
            logTerminal();
        }

        private boolean cleanupComplete() {
            return cleanupComplete;
        }

        private ChainEvents.FastMineSettlement finishDrops(ServerPlayer player) {
            if (dropSession != null) {
                ChainEvents.FastMineSettlement result;
                if (player != null && !player.isRemoved()) {
                    result = dropSession.finish(level, player,
                            (int) Math.min(Integer.MAX_VALUE, brokenBlocks));
                } else {
                    result = dropSession.finishWithoutPlayer(level, lastPlayerX, lastPlayerY, lastPlayerZ,
                            (int) Math.min(Integer.MAX_VALUE, brokenBlocks));
                }
                dropSession = null;
                return result;
            }
            return new ChainEvents.FastMineSettlement(0L, 0L, 0L, 0L, false, false);
        }

        private void sendProgress(ServerPlayer player) {
            if (player != null && !player.isRemoved()) {
                NetworkHandler.sendFastMineProgress(player, progress());
            }
        }

        private void announce(ServerPlayer player, String key, ChainEvents.FastMineSettlement settlement) {
            if (player == null || player.isRemoved()) return;
            double elapsedSeconds = Math.round((System.nanoTime() - startedNanos) / 10_000_000.0D) / 100.0D;
            player.sendSystemMessage(Component.translatable(key, brokenBlocks, foundOres, elapsedSeconds,
                    Component.translatable(settlement.destinationKey()), settlement.inserted(),
                    settlement.dropped(), settlement.pending()));
        }

        private void logTerminal() {
            if (!Config.ENABLE_PERFORMANCE_LOG.getAsBoolean()) return;
            VeinMinerPlus.LOGGER.info(
                    "[VMP FastMine] state={} player={} dimension={} chunks={} loaded={} skippedForced={} skippedToolLevelOres={} scanned={} targets={} processed={} ores={} broken={} loadMs={} snapshotMs={} workerScanMs={} mineMs={} avgBudgetMs={} maxBudgetMs={} totalMs={}",
                    state, playerId, level.dimension().location(), selection.chunkCount(), loadedChunks,
                    skippedForcedChunkCount, skippedToolLevelOres, scannedBlocks,
                    targetBlocks, processedBlocks, foundOres, brokenBlocks, nanosToMillis(loadNanos), nanosToMillis(snapshotNanos),
                    nanosToMillis(workerScanNanos), nanosToMillis(mineNanos),
                    mineBatches == 0 ? 0.0D : nanosToMillis(budgetNanosTotal / mineBatches),
                    nanosToMillis(maxBudgetNanosObserved), nanosToMillis(System.nanoTime() - startedNanos));
            PerformanceFileLog.write("event=fast_mine_terminal state=" + state + " player=" + playerId
                    + " dimension=" + level.dimension().location() + " chunks=" + selection.chunkCount()
                    + " loaded=" + loadedChunks + " skippedForced=" + skippedForcedChunkCount
                    + " skippedToolLevelOres=" + skippedToolLevelOres
                    + " scanned=" + scannedBlocks + " targets=" + targetBlocks + " processed=" + processedBlocks
                    + " ores=" + foundOres + " broken=" + brokenBlocks + " loadMs=" + nanosToMillis(loadNanos)
                    + " snapshotMs=" + nanosToMillis(snapshotNanos) + " workerScanMs=" + nanosToMillis(workerScanNanos)
                    + " mineMs=" + nanosToMillis(mineNanos) + " mineBatches=" + mineBatches
                    + " avgBudgetMs=" + (mineBatches == 0 ? 0.0D
                            : nanosToMillis(budgetNanosTotal / mineBatches))
                    + " maxBudgetMs=" + nanosToMillis(maxBudgetNanosObserved)
                    + " maxBatch=" + maxBlocksPerTickObserved + " breakEwmaUs=" + nanosToMillis(breakEwmaNanos) * 1000.0D
                    + " totalMs=" + nanosToMillis(System.nanoTime() - startedNanos));
        }

        private static double nanosToMillis(long nanos) {
            return Math.round(nanos / 10_000.0D) / 100.0D;
        }

        private boolean terminal() {
            return state == FastMineProgress.State.COMPLETED || state == FastMineProgress.State.FAILED
                    || state == FastMineProgress.State.CANCELLED;
        }

        private double lastPlayerX;
        private double lastPlayerY;
        private double lastPlayerZ;
    }

    private static final class TargetBatch {
        private final long[] positions;
        private final byte[] oreFlags;
        private int cursor;

        private TargetBatch(long[] positions, byte[] oreFlags) {
            this.positions = positions;
            this.oreFlags = oreFlags;
        }

        private long[] positions() {
            return positions;
        }

        private byte[] oreFlags() {
            return oreFlags;
        }

        private int size() {
            return positions.length;
        }

        private int nextIndex() {
            return cursor++;
        }

        private boolean exhausted() {
            return cursor >= positions.length;
        }
    }

    private record Snapshot(List<TargetBatch> batches, long scannedBlocks, long targetCount,
            long oreCount, long skippedToolLevelOres, boolean summaryScan) {}
    private record ScanResult(long scannedBlocks, List<TargetBatch> batches, long targetCount,
            long oreCount, long skippedToolLevelOres, long workerNanos) {}
}
