package com.extrarawstyle.veinminerplus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

import com.extrarawstyle.veinminerplus.compat.DropStorage;
import com.extrarawstyle.veinminerplus.compat.StorageResult;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class ChainEvents {
    // Work is deliberately bounded so one large blast cannot monopolize the server thread.
    private static final int SEARCH_CHECKS_PER_TICK = 16384;
    private static final int SEARCH_CHECKS_PER_CENTER = 256;
    private static final int PENDING_SEED_LIMIT = 256;
    private static final int MAX_GLOBAL_BLOCK_BREAKS_PER_TICK = 8192;
    private static final int MAX_GLOBAL_SEARCH_CHECKS_PER_TICK = 65536;
    private static final int MAX_GLOBAL_SPARSE_SECTION_SCANS_PER_TICK = 32;
    private static final int LOADED_CHUNK_CACHE_LIMIT = 256;
    private static final double TPS_WARNING_THRESHOLD = 12.0D;
    private static final double TPS_CRITICAL_THRESHOLD = 8.0D;
    private static final double TPS_RECOVERY_THRESHOLD = 16.0D;
    private static final int TPS_CRITICAL_TICKS_TO_STOP = 20;
    private static final int TPS_WARNING_COOLDOWN_TICKS = 100;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;
    private static final long TARGET_TOTAL_TICK_NANOS = 47L * NANOS_PER_MILLISECOND;
    private static final long POST_TICK_RESERVE_NANOS = 2L * NANOS_PER_MILLISECOND;
    private static final long MIN_JOB_BUDGET_NANOS = 1L * NANOS_PER_MILLISECOND;
    private static final long MAX_HEALTHY_JOB_BUDGET_NANOS = 40L * NANOS_PER_MILLISECOND;
    private static final long INITIAL_BREAK_COST_NANOS = 250_000L;
    private static final long MIN_BREAK_COST_NANOS = 10_000L;
    private static final long MAX_BREAK_COST_NANOS = 20_000_000L;
    private static final long MIN_BREAK_START_MARGIN_NANOS = 50_000L;
    private static final long MAX_BREAK_START_MARGIN_NANOS = 300_000L;
    private static final int BREAK_START_MARGIN_DIVISOR = 4;
    private static final int SPARSE_SKIP_DEADLINE_CHECK_INTERVAL = 64;
    private static final int PERFORMANCE_LOG_INTERVAL_TICKS = 100;

    private static final TagKey<Block> ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.withDefaultNamespace("ores"));
    private static final TagKey<Block> COMMON_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "ores"));
    private static final TagKey<Block> ALLTHEMODIUM_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "ores/allthemodium"));
    private static final TagKey<Block> VIBRANIUM_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "ores/vibranium"));
    private static final TagKey<Block> UNOBTAINIUM_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "ores/unobtainium"));
    private static final List<BlockPos> NORMAL_OFFSETS = createNormalOffsets();
    private static final Map<UUID, ChainMode> PLAYER_MODES = new HashMap<>();
    private static final Set<UUID> HELD_KEYS = new HashSet<>();
    private static final Map<UUID, ChainJob> ACTIVE_JOBS = new HashMap<>();
    private static final Set<UUID> PENDING_JOBS = new HashSet<>();
    private static final Map<UUID, DropBuffer> PENDING_DROPS = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING_DROP_ORIGINS = new HashMap<>();
    private static final Map<UUID, Boolean> PENDING_DOUBLE_PLANTS = new HashMap<>();
    private static final ThreadLocal<CaptureContext> CAPTURING_DROPS = new ThreadLocal<>();
    private static final Map<UUID, BreakFace> LAST_BREAK_FACES = new HashMap<>();
    private static final Map<UUID, List<BlockPos>> PENDING_SEEDS = new HashMap<>();
    private static final Map<Block, Boolean> ORE_CACHE = new IdentityHashMap<>();
    private static final Map<Block, Boolean> ALLTHEMODIUM_CACHE = new IdentityHashMap<>();
    private static int roundRobinStart;
    private static long serverTickStartedNanos;
    private static final PerformanceLog PERFORMANCE_LOG = new PerformanceLog();

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        if (event.getEntity() instanceof ServerPlayer player) {
            ChainJob job = ACTIVE_JOBS.get(id);
            if (job != null) {
                job.finish();
            }
            DropBuffer pendingDrops = PENDING_DROPS.remove(id);
            if (pendingDrops != null) {
                pendingDrops.flush(player.serverLevel(), player, 1);
            }
        }
        PLAYER_MODES.remove(id);
        HELD_KEYS.remove(id);
        PENDING_JOBS.remove(id);
        PENDING_DROP_ORIGINS.remove(id);
        PENDING_DOUBLE_PLANTS.remove(id);
        PENDING_SEEDS.remove(id);
        LAST_BREAK_FACES.remove(id);
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player
                && event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) {
            LAST_BREAK_FACES.put(player.getUUID(), new BreakFace(event.getPos().immutable(), event.getFace()));
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()
                || !(event.getPlayer() instanceof ServerPlayer player)
                || !HELD_KEYS.contains(player.getUUID())
                || ACTIVE_JOBS.containsKey(player.getUUID())
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        UUID id = player.getUUID();
        BlockPos target = event.getPos().immutable();
        BlockState state = event.getState();

        List<BlockPos> seeds = PENDING_SEEDS.get(id);
        if (seeds != null) {
            // One player action, but a mod such as Just Dire Things breaks a whole area per
            // action and fires this event for every block of it. Starting the chain from a
            // single block inside that area would trap it: all of its neighbours are already
            // air, so the search can never reach the untouched blocks around the area.
            // Collect the whole area instead, so the chain starts from its edge.
            if (seeds.size() < PENDING_SEED_LIMIT) {
                seeds.add(target);
            }
            return;
        }
        if (PENDING_JOBS.contains(id)) {
            return;
        }

        ChainMode mode = PLAYER_MODES.getOrDefault(id, ChainMode.NORMAL);
        if (!isEligible(level, player, target, state, state.getBlock(), mode)) {
            return;
        }

        BreakFace breakFace = LAST_BREAK_FACES.get(id);
        Direction face = breakFace != null && breakFace.pos().equals(target) ? breakFace.face() : Direction.UP;
        DropBuffer drops = new DropBuffer(ChainMemoryCardItem.findBoundTarget(player));
        List<BlockPos> pendingSeeds = new ArrayList<>();
        pendingSeeds.add(target);
        PENDING_DROPS.put(id, drops);
        PENDING_DROP_ORIGINS.put(id, target);
        PENDING_DOUBLE_PLANTS.put(id, state.getBlock() instanceof DoublePlantBlock);
        PENDING_JOBS.add(id);
        PENDING_SEEDS.put(id, pendingSeeds);
        level.getServer().execute(() -> startAfterPrimaryBreak(level, player, target, state, face, mode, drops,
                pendingSeeds));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockDrops(BlockDropsEvent event) {
        if (event.isCanceled()) {
            // Another mod (e.g. Just Dire Things) already took over these drops.
            return;
        }
        DropBuffer drops = null;
        if (event.getBreaker() instanceof ServerPlayer player) {
            drops = PENDING_DROPS.get(player.getUUID());
            if (drops == null) {
                ChainJob job = ACTIVE_JOBS.get(player.getUUID());
                drops = job == null ? null : job.drops;
            }
        }

        CaptureContext capture = CAPTURING_DROPS.get();
        if (drops == null && capture != null && capture.hasPosition()
                && matchesDropPosition(event.getPos(), capture.origin(), capture.doublePlant())) {
            drops = capture.drops();
        }
        if (drops == null) {
            for (Map.Entry<UUID, DropBuffer> entry : PENDING_DROPS.entrySet()) {
                BlockPos origin = PENDING_DROP_ORIGINS.get(entry.getKey());
                boolean doublePlant = PENDING_DOUBLE_PLANTS.getOrDefault(entry.getKey(), false);
                if (origin != null && matchesDropPosition(event.getPos(), origin, doublePlant)) {
                    drops = entry.getValue();
                    break;
                }
            }
        }
        if (drops == null) {
            return;
        }

        for (ItemEntity item : event.getDrops()) {
            drops.add(item.getItem());
        }
        event.getDrops().clear();
        drops.addExperience(event.getDroppedExperience());
        event.setDroppedExperience(0);
    }

    private static boolean matchesDropPosition(BlockPos dropPosition, BlockPos origin, boolean doublePlant) {
        return dropPosition.equals(origin)
                || doublePlant && (dropPosition.equals(origin.above()) || dropPosition.equals(origin.below()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerTick(ServerTickEvent.Pre event) {
        serverTickStartedNanos = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        DropReturnGuardian.tick(server);
        if (ACTIVE_JOBS.isEmpty()) {
            PERFORMANCE_LOG.flush();
            return;
        }

        long jobsStartedNanos = System.nanoTime();
        long preJobElapsedNanos = measuredPreJobElapsedNanos(jobsStartedNanos);
        List<ChainJob> jobs = new ArrayList<>(ACTIVE_JOBS.values());
        int size = jobs.size();
        int start = Math.floorMod(roundRobinStart++, size);
        double tps = getServerTps(server);
        int globalBreaks = adaptiveGlobalBreakBudget(tps);
        int globalSearches = adaptiveGlobalSearchBudget(globalBreaks);
        int globalSparseScans = adaptiveGlobalSparseSectionBudget(globalBreaks);
        long headroomNanos = availableJobHeadroomNanos(preJobElapsedNanos);
        long grantedBudgetNanos = adaptiveTickTimeBudgetNanos(tps, preJobElapsedNanos);
        long deadlineNanos = jobsStartedNanos + grantedBudgetNanos;
        TickBudget budget = new TickBudget(globalBreaks, globalSearches, globalSparseScans, deadlineNanos);
        int fairBreaks = Math.max(1, globalBreaks / size);
        int fairSearches = Math.max(1, globalSearches / size);
        int fairSparseScans = Math.max(1, globalSparseScans / size);
        try {
            int offset = 0;
            for (; offset < size && budget.hasWork(); offset++) {
                jobs.get((start + offset) % size).tick(tps, budget, fairBreaks, fairSearches, fairSparseScans);
            }
            // Jobs not scheduled after the shared budget expires are not executed slices.
            PERFORMANCE_LOG.recordSkipped(size - offset);
        } finally {
            PERFORMANCE_LOG.recordTick(size, budget.initialBreaks - budget.remainingBreaks(),
                    budget.initialSearches - budget.remainingSearches(),
                    budget.initialSparseScans - budget.remainingSparseScans(),
                    System.nanoTime() - jobsStartedNanos, preJobElapsedNanos, headroomNanos, grantedBudgetNanos,
                    budget.indexedSections, budget.cachedSectionVisits, budget.emptyIndexedSections,
                    budget.exhaustedSectionSkips);
            if (ACTIVE_JOBS.isEmpty()) {
                PERFORMANCE_LOG.flush();
            }
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        DropReturnGuardian.reset();
        PERFORMANCE_LOG.reset();
        serverTickStartedNanos = 0L;
        if (Config.ENABLE_PERFORMANCE_LOG.getAsBoolean()) {
            VeinMinerPlus.LOGGER.info(
                    "[VMP Perf] enabled build=perf-log-v3 intervalActiveTicks={} flushOnIdle=true dynamicTickTargetMs={} postReserveMs={}",
                    PERFORMANCE_LOG_INTERVAL_TICKS, TARGET_TOTAL_TICK_NANOS / NANOS_PER_MILLISECOND,
                    POST_TICK_RESERVE_NANOS / NANOS_PER_MILLISECOND);
        }
        roundRobinStart = 0;
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        for (ChainJob job : new ArrayList<>(ACTIVE_JOBS.values())) {
            job.finish();
        }
        for (Map.Entry<UUID, DropBuffer> entry : new ArrayList<>(PENDING_DROPS.entrySet())) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                entry.getValue().flush(player.serverLevel(), player, 1);
            }
        }
        DropReturnGuardian.shutdown(event.getServer());
        PERFORMANCE_LOG.flush();
        clearRuntimeState();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        DropReturnGuardian.reset();
        clearRuntimeState();
    }

    private static void clearRuntimeState() {
        serverTickStartedNanos = 0L;
        PERFORMANCE_LOG.reset();
        ACTIVE_JOBS.clear();
        PENDING_JOBS.clear();
        PENDING_DROPS.clear();
        PENDING_DROP_ORIGINS.clear();
        PENDING_DOUBLE_PLANTS.clear();
        PENDING_SEEDS.clear();
        LAST_BREAK_FACES.clear();
        HELD_KEYS.clear();
        PLAYER_MODES.clear();
        ORE_CACHE.clear();
        ALLTHEMODIUM_CACHE.clear();
        CAPTURING_DROPS.remove();
    }
    static void setKeyHeld(Player player, boolean held) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (held) {
            HELD_KEYS.add(serverPlayer.getUUID());
        } else {
            HELD_KEYS.remove(serverPlayer.getUUID());
        }
    }

    static void setMode(Player player, int ordinal) {
        if (player instanceof ServerPlayer serverPlayer) {
            PLAYER_MODES.put(serverPlayer.getUUID(), ChainMode.fromOrdinal(ordinal));
        }
    }

    private static void startAfterPrimaryBreak(ServerLevel level, ServerPlayer player, BlockPos target,
            BlockState originalState, Direction face, ChainMode mode, DropBuffer drops, List<BlockPos> seeds) {
        UUID id = player.getUUID();
        PENDING_JOBS.remove(id);
        PENDING_DROPS.remove(id, drops);
        PENDING_DROP_ORIGINS.remove(id, target);
        PENDING_DOUBLE_PLANTS.remove(id);
        PENDING_SEEDS.remove(id, seeds);

        if (!level.isInWorldBounds(target) || level.getBlockState(target).is(originalState.getBlock())) {
            drops.clear();
            return;
        }

        if (!HELD_KEYS.contains(id) || ACTIVE_JOBS.containsKey(id) || player.isSpectator()) {
            drops.flush(level, player, 1);
            return;
        }

        ACTIVE_JOBS.put(id, new ChainJob(level, player, target, originalState.getBlock(), face, mode, drops, seeds));
        showProgress(player, 1);
    }

    private static boolean isEligible(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state,
            Block targetBlock, ChainMode mode) {
        if (state.isAir() || !matchesMode(state, targetBlock, mode)) {
            return false;
        }
        if (!level.mayInteract(player, pos)
                || !isAllthemodiumBlock(state) && state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        return player.isCreative() || EventHooks.doPlayerHarvestCheck(player, state, level, pos);
    }

    private static boolean matchesMode(BlockState state, Block targetBlock, ChainMode mode) {
        return switch (mode) {
            case BLAST_ANY -> !state.isAir();
            case BLAST_ORES -> isOre(state);
            case BLAST_LOGS -> state.is(BlockTags.LOGS);
            default -> state.getBlock() == targetBlock;
        };
    }

    private static boolean isOre(BlockState state) {
        Block block = state.getBlock();
        return ORE_CACHE.computeIfAbsent(block, ignored -> {
            if (state.is(ORE_BLOCKS) || state.is(COMMON_ORE_BLOCKS)
                    || state.is(ALLTHEMODIUM_ORE_BLOCKS)
                    || state.is(VIBRANIUM_ORE_BLOCKS)
                    || state.is(UNOBTAINIUM_ORE_BLOCKS)) {
                return true;
            }

            // Some mod packs do not add their ores to the shared ore tags.
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return id != null && id.getPath().endsWith("_ore");
        });
    }

    // Allthemodium registers its ore and ancient stone sets with a negative destroy
    // speed while overriding getDestroyProgress, so players can still mine them.
    // Reading that negative value as "unbreakable" would keep them out of chains.
    private static boolean isAllthemodiumBlock(BlockState state) {
        Block block = state.getBlock();
        return ALLTHEMODIUM_CACHE.computeIfAbsent(block, ignored -> {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return id != null && "allthemodium".equals(id.getNamespace());
        });
    }

    private static boolean breakOne(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state,
            CaptureContext capture, boolean justDireThingsTool) {
        // Use the same server-side entry point as a real player break. This keeps
        // BlockEvent, drops, tool damage, block entities, and client updates in sync.
        // The capture object and ThreadLocal binding are reused for the whole job tick.
        capture.begin(pos, state.getBlock() instanceof DoublePlantBlock);
        float exhaustion = player.getFoodData().getExhaustionLevel();
        boolean destroyed = justDireThingsTool
                ? breakWithoutBreakEvent(level, player, pos, state)
                : player.gameMode.destroyBlock(pos);
        if (destroyed && Config.NO_HUNGER_COST.get()) {
            player.getFoodData().setExhaustion(exhaustion);
        }
        return destroyed;
    }

    // Just Dire Things listens to BlockEvent.BreakEvent, runs its own hammer area
    // break there and cancels the event. Firing that event for a chained block would
    // hand the block to JDT instead: the chain counter would stick at 1 and every
    // attempt would multiply into another 3x3/5x5/7x7 area break. Chained blocks are
    // therefore broken directly, without the event. The manual hit still goes through
    // the normal path, so JDT's own hammer behaviour is untouched.
    private static boolean isJustDireThingsTool(Item item) {
        return item.getClass().getName().startsWith("com.direwolf20.justdirethings.");
    }

    // Mirrors ServerPlayerGameMode.destroyBlock aside from the BreakEvent.
    private static boolean breakWithoutBreakEvent(ServerLevel level, ServerPlayer player, BlockPos pos,
            BlockState state) {
        Block block = state.getBlock();
        if (block instanceof GameMasterBlock && !player.canUseGameMasterBlocks()) {
            level.sendBlockUpdated(pos, state, state, 3);
            return false;
        }
        GameType gameType = player.isCreative() ? GameType.CREATIVE : GameType.SURVIVAL;
        if (player.blockActionRestricted(level, pos, gameType)) {
            return false;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        ItemStack tool = player.getMainHandItem();
        BlockState remaining = block.playerWillDestroy(level, pos, state, player);
        if (!level.removeBlock(pos, false)) {
            return false;
        }
        block.destroy(level, pos, state);
        if (!player.isCreative()) {
            // isEligible already required a successful harvest check, so drops apply.
            block.playerDestroy(level, player, pos, remaining, blockEntity, tool);
            if (state.getDestroySpeed(level, pos) != 0.0F) {
                tool.mineBlock(level, remaining, pos, player);
            }
        }
        return true;
    }

    private static BlockPos areaOffset(BlockPos start, Direction face, int first, int second) {
        return switch (face.getAxis()) {
            case X -> start.offset(0, first, second);
            case Y -> start.offset(first, 0, second);
            case Z -> start.offset(first, second, 0);
        };
    }

    private static List<BlockPos> createNormalOffsets() {
        List<BlockPos> offsets = new ArrayList<>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        offsets.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return Collections.unmodifiableList(offsets);
    }

    private static void showProgress(ServerPlayer player, int count) {
        player.displayClientMessage(Component.translatable("message.veinminerplus.progress", count), true);
    }

    private static double getServerTps(MinecraftServer server) {
        long tickTimeNanos = server.getAverageTickTimeNanos();
        return tickTimeNanos <= 0L ? 20.0D : Math.min(20.0D, 1_000_000_000.0D / tickTimeNanos);
    }

    private static int roundedTps(double tps) {
        return Math.max(0, (int) Math.round(tps));
    }

    private static int adaptiveGlobalBreakBudget(double tps) {
        if (tps >= 19.0D) {
            return MAX_GLOBAL_BLOCK_BREAKS_PER_TICK;
        }
        if (tps >= 17.0D) {
            return 384;
        }
        if (tps >= 15.0D) {
            return 256;
        }
        if (tps >= TPS_WARNING_THRESHOLD) {
            return 128;
        }
        return 64;
    }

    private static int adaptiveGlobalSearchBudget(int breakBudget) {
        return Math.min(MAX_GLOBAL_SEARCH_CHECKS_PER_TICK, Math.max(8192, breakBudget * 128));
    }

    private static int adaptiveGlobalSparseSectionBudget(int breakBudget) {
        return Math.min(MAX_GLOBAL_SPARSE_SECTION_SCANS_PER_TICK, Math.max(4, breakBudget / 16));
    }

    private static long measuredPreJobElapsedNanos(long nowNanos) {
        long started = serverTickStartedNanos;
        if (started <= 0L || nowNanos < started) {
            return -1L;
        }
        long elapsed = nowNanos - started;
        // A stale timestamp can only occur around lifecycle transitions. Do not let it
        // collapse the work budget for the first valid tick after such a transition.
        return elapsed <= 1_000L * NANOS_PER_MILLISECOND ? elapsed : -1L;
    }

    private static long availableJobHeadroomNanos(long preJobElapsedNanos) {
        if (preJobElapsedNanos < 0L) {
            return -1L;
        }
        return Math.max(0L, TARGET_TOTAL_TICK_NANOS - preJobElapsedNanos - POST_TICK_RESERVE_NANOS);
    }

    private static long adaptiveTickTimeBudgetNanos(double tps, long preJobElapsedNanos) {
        long tpsCeiling;
        if (tps >= 19.5D) {
            tpsCeiling = MAX_HEALTHY_JOB_BUDGET_NANOS;
        } else if (tps >= 18.0D) {
            tpsCeiling = 32L * NANOS_PER_MILLISECOND;
        } else if (tps >= 16.0D) {
            tpsCeiling = 24L * NANOS_PER_MILLISECOND;
        } else if (tps >= TPS_WARNING_THRESHOLD) {
            tpsCeiling = 12L * NANOS_PER_MILLISECOND;
        } else {
            tpsCeiling = 4L * NANOS_PER_MILLISECOND;
        }

        if (preJobElapsedNanos < 0L) {
            return legacyTickTimeBudgetNanos(tps);
        }
        long headroom = availableJobHeadroomNanos(preJobElapsedNanos);
        return Math.min(tpsCeiling, Math.max(MIN_JOB_BUDGET_NANOS, headroom));
    }

    private static long legacyTickTimeBudgetNanos(double tps) {
        long milliseconds;
        if (tps >= 19.5D) milliseconds = 35L;
        else if (tps >= 18.0D) milliseconds = 28L;
        else if (tps >= 16.0D) milliseconds = 20L;
        else if (tps >= TPS_WARNING_THRESHOLD) milliseconds = 10L;
        else milliseconds = 4L;
        return milliseconds * NANOS_PER_MILLISECOND;
    }

    static void recordAeFlush(long elapsedNanos, int itemKeys) {
        PERFORMANCE_LOG.recordAeFlush(elapsedNanos, itemKeys);
    }

    private static void showTpsMessage(ServerPlayer player, String translationKey, double tps) {
        player.displayClientMessage(Component.translatable(translationKey, roundedTps(tps)), true);
    }

    private static final class DropBuffer {
        private final ItemStackAccumulator items = new ItemStackAccumulator();
        private final NetworkTarget networkTarget;
        private int experience;
        private boolean flushed;

        private DropBuffer(NetworkTarget networkTarget) {
            this.networkTarget = networkTarget;
        }

        private void add(ItemStack stack) {
            items.add(stack);
        }

        private void addExperience(int amount) {
            experience += amount;
        }

        private void flush(ServerLevel level, ServerPlayer player, int brokenBlocks) {
            // A job can be finished by more than one safety/cleanup path in the same
            // tick. Drops and the AE report must be settled exactly once.
            if (flushed) {
                return;
            }
            flushed = true;
            if (!level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
                clear();
                return;
            }

            List<ItemStack> aggregated = items.toAggregatedStacks();
            long totalItems = items.totalCount();
            long inserted = 0;
            List<ItemStack> remaining = aggregated;
            StorageResult result = null;
            if (Config.STORE_DROPS_IN_AE.getAsBoolean() && networkTarget != null && totalItems > 0) {
                long aeStartedNanos = System.nanoTime();
                try {
                    result = DropStorage.store(level.getServer(), player, networkTarget, aggregated);
                } finally {
                    recordAeFlush(System.nanoTime() - aeStartedNanos, aggregated.size());
                }
                inserted = result.inserted();
                remaining = result.remaining();
            }

            // Experience is never sent to AE and is settled immediately. Use the
            // player's current level so a dimension change cannot strand the orbs.
            ServerLevel settlementLevel = player.serverLevel();
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();
            if (experience > 0) {
                ExperienceOrb.award(settlementLevel, player.position(), experience);
            }

            if (result != null && result.retryable() && !remaining.isEmpty()) {
                if (DropReturnGuardian.enqueue(player, networkTarget, remaining, inserted, brokenBlocks,
                        settlementLevel, x, y, z)) {
                    clear();
                    return;
                }
            }

            DropReturnGuardian.dropImmediately(settlementLevel, x, y, z, remaining);
            if (Config.STORE_DROPS_IN_AE.getAsBoolean() && networkTarget != null && totalItems > 0) {
                DropReturnGuardian.sendResult(player, brokenBlocks, inserted, ItemStackAccumulator.count(remaining));
            }
            clear();
        }

        private void clear() {
            items.clear();
            experience = 0;
        }
    }
    private static final class ChainJob {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final UUID playerId;
        private final BlockPos origin;
        private final Block targetBlock;
        private final Direction face;
        private final ChainMode mode;
        private final LongOpenHashSet examined = new LongOpenHashSet();
        private final Deque<SearchNode> frontier = new ArrayDeque<>();
        private final LongArrayFIFOQueue sparseCenters = new LongArrayFIFOQueue();
        private final PriorityQueue<SparseTarget> sparseTargets;
        private final Long2ObjectOpenHashMap<LongArrayList> sparseSectionMatches = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet exhaustedSparseSections = new LongOpenHashSet();
        private final LinkedHashMap<Long, LevelChunk> loadedChunks = new LinkedHashMap<>(32, 0.75F, true);
        private final LongOpenHashSet unavailableChunks = new LongOpenHashSet();
        private final int totalLimit;
        private final int areaDepthLimit;
        private final boolean sparseBlast;
        private final int blastDistance;
        private final DropBuffer drops;
        private final CaptureContext captureContext;
        private SparseScanCursor sparseScan;
        private Item cachedToolItem;
        private boolean cachedJustDireThingsTool;
        private long breakCostEwmaNanos = INITIAL_BREAK_COST_NANOS;
        private int brokenCount = 1;
        private int areaDepth;
        private int areaIndex;
        private int criticalTpsTicks;
        private int tpsWarningCooldown;
        private int progressCooldown;
        private boolean sectionBudgetBlocked;

        private ChainJob(ServerLevel level, ServerPlayer player, BlockPos origin, Block targetBlock,
                Direction face, ChainMode mode, DropBuffer drops, List<BlockPos> seeds) {
            this.level = level;
            this.player = player;
            this.playerId = player.getUUID();
            this.origin = origin;
            this.targetBlock = targetBlock;
            this.face = face;
            this.mode = mode;
            this.drops = drops;
            this.captureContext = new CaptureContext(drops);
            this.totalLimit = mode.isBlast() ? Config.MAX_BLAST_BLOCKS.getAsInt() : Config.MAX_NORMAL_BLOCKS.getAsInt();
            this.blastDistance = Config.BLAST_SEARCH_DISTANCE.getAsInt();
            this.sparseBlast = mode.isBlast();
            int areaSize = mode == ChainMode.AREA_1X1 ? 1 : 3;
            int planeSize = areaSize * areaSize;
            this.areaDepthLimit = Math.max(0, (totalLimit + planeSize - 1) / planeSize - 1);
            this.sparseTargets = new PriorityQueue<>(Comparator
                    .comparingLong(SparseTarget::distanceSquared)
                    .thenComparingLong(SparseTarget::position));

            List<BlockPos> starts = new ArrayList<>();
            starts.add(origin);
            if (seeds != null) {
                starts.addAll(seeds);
            }
            for (BlockPos start : starts) {
                if (!examined.add(start.asLong())) {
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(start.getX() >> 4, start.getZ() >> 4);
                if (chunk != null) {
                    rememberLoadedChunk(chunkKey(start), chunk);
                }
                if (!mode.isArea()) {
                    if (sparseBlast) {
                        sparseCenters.enqueue(start.asLong());
                    } else {
                        frontier.addLast(new SearchNode(start));
                    }
                }
            }
        }

        private void tick(double tps, TickBudget budget, int fairBreaks, int fairSearches, int fairSparseScans) {
            int initialBreaks = budget.remainingBreaks();
            int initialSearches = budget.remainingSearches();
            budget.deadlineBlocked = false;
            sectionBudgetBlocked = false;
            TickStopReason reason = TickStopReason.ERROR;
            CaptureContext previous = CAPTURING_DROPS.get();
            CAPTURING_DROPS.set(captureContext);
            try {
                if (progressCooldown > 0) {
                    progressCooldown--;
                }
                if (!HELD_KEYS.contains(playerId) || player.isRemoved() || player.isSpectator()
                        || player.serverLevel() != level) {
                    reason = !HELD_KEYS.contains(playerId) ? TickStopReason.KEY_RELEASED
                            : player.serverLevel() != level ? TickStopReason.DIMENSION_CHANGED
                            : TickStopReason.PLAYER_INVALID;
                    finish();
                    return;
                }
                if (!updateTpsSafety(tps)) {
                    reason = criticalTpsTicks >= TPS_CRITICAL_TICKS_TO_STOP
                            ? TickStopReason.TPS_STOPPED : TickStopReason.TPS_PAUSED;
                    return;
                }

                if (mode.isArea()) {
                    tickArea(budget, fairBreaks, fairSearches);
                } else if (sparseBlast) {
                    tickSparseBlast(tps, budget, fairBreaks, fairSearches, fairSparseScans);
                } else {
                    tickGraph(tps, budget, fairBreaks, fairSearches);
                }
                reason = classifyStop(tps, budget, initialBreaks - budget.remainingBreaks(),
                        initialSearches - budget.remainingSearches(), fairBreaks, fairSearches);
            } finally {
                PERFORMANCE_LOG.recordSlice(reason, breakCostEwmaNanos);
                captureContext.clearPosition();
                if (previous == null) {
                    CAPTURING_DROPS.remove();
                } else {
                    CAPTURING_DROPS.set(previous);
                }
            }
        }

        // Classify the actual loop exit, not time elapsed later during progress/drop settlement.
        private TickStopReason classifyStop(double tps, TickBudget budget, int breaks, int searches,
                int fairBreaks, int fairSearches) {
            if (!HELD_KEYS.contains(playerId)) return TickStopReason.KEY_RELEASED;
            if (brokenCount >= totalLimit) return TickStopReason.TOTAL_LIMIT;
            boolean noWork = mode.isArea() ? areaDepth > areaDepthLimit
                    : sparseBlast ? sparseTargets.isEmpty() && sparseCenters.isEmpty() && sparseScan == null
                    : frontier.isEmpty();
            if (noWork) return TickStopReason.NO_PENDING_WORK;
            int configured = sparseBlast ? effectiveBlastBreakLimit(tps) : Config.MAX_NORMAL_BLOCKS_PER_TICK.getAsInt();
            if (budget.remainingBreaks() <= 0) return TickStopReason.GLOBAL_BREAK_LIMIT;
            if (breaks >= configured) return TickStopReason.CONFIGURED_BREAK_LIMIT;
            if (breaks >= fairBreaks) return TickStopReason.FAIR_BREAK_LIMIT;
            if (budget.remainingSearches() <= 0) return TickStopReason.GLOBAL_SEARCH_LIMIT;
            int localSearches = sparseBlast ? effectiveBlastSearchChecks(tps)
                    : mode.isArea() ? Integer.MAX_VALUE : SEARCH_CHECKS_PER_TICK;
            if (searches >= localSearches) return TickStopReason.LOCAL_SEARCH_LIMIT;
            if (searches >= fairSearches) return TickStopReason.FAIR_SEARCH_LIMIT;
            if (sectionBudgetBlocked) return budget.remainingSparseScans() <= 0
                    ? TickStopReason.GLOBAL_SPARSE_SECTION_LIMIT : TickStopReason.FAIR_SPARSE_SECTION_LIMIT;
            if (budget.deadlineBlocked) return TickStopReason.DEADLINE;
            return TickStopReason.OTHER;
        }

        private void tickGraph(double tps, TickBudget budget, int fairBreaks, int fairSearches) {
            int checkLimit = Math.min(fairSearches, SEARCH_CHECKS_PER_TICK);
            checkLimit = Math.min(checkLimit, budget.remainingSearches());
            int configuredBreakLimit = Config.MAX_NORMAL_BLOCKS_PER_TICK.getAsInt();
            int breakLimit = Math.min(Math.min(fairBreaks, configuredBreakLimit), budget.remainingBreaks());
            int checks = 0;
            int breaks = 0;
            BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();

            while (checks < checkLimit && breaks < breakLimit && !frontier.isEmpty()
                    && brokenCount < totalLimit && budget.canAttemptBreak(breakCostEwmaNanos)) {
                SearchNode node = frontier.removeFirst();
                int centerChecks = 0;
                while (centerChecks < SEARCH_CHECKS_PER_CENTER && checks < checkLimit && breaks < breakLimit) {
                    if (!node.nextCandidate(candidate)) {
                        break;
                    }
                    checks++;
                    centerChecks++;
                    budget.consumeSearch();
                    if (!examined.add(candidate.asLong()) || !level.isInWorldBounds(candidate)) {
                        continue;
                    }

                    BlockState state = getLoadedBlockState(candidate);
                    if (state != null && isEligible(level, player, candidate, state, targetBlock, mode)
                            && breakCandidate(candidate, state, budget)) {
                        brokenCount++;
                        breaks++;
                        budget.consumeBreak();
                        frontier.addLast(new SearchNode(candidate));
                    }
                }
                if (node.hasMore()) {
                    frontier.addLast(node);
                }
            }

            reportProgress(breaks);
            if (!HELD_KEYS.contains(playerId) || frontier.isEmpty() || brokenCount >= totalLimit) {
                finish();
            }
        }

        private void tickArea(TickBudget budget, int fairBreaks, int fairSearches) {
            int size = mode == ChainMode.AREA_1X1 ? 1 : 3;
            int planeSize = size * size;
            int breakLimit = Math.min(Math.min(Config.MAX_NORMAL_BLOCKS_PER_TICK.getAsInt(), fairBreaks),
                    budget.remainingBreaks());
            int checkLimit = Math.min(fairSearches, budget.remainingSearches());
            int breaks = 0;
            int checks = 0;
            while (breaks < breakLimit && checks < checkLimit && areaDepth <= areaDepthLimit
                    && brokenCount < totalLimit && budget.canAttemptBreak(breakCostEwmaNanos)) {
                if (areaIndex >= planeSize) {
                    areaDepth++;
                    areaIndex = 0;
                    continue;
                }

                int startOffset = -(size / 2);
                int first = startOffset + areaIndex / size;
                int second = startOffset + areaIndex % size;
                areaIndex++;
                checks++;
                budget.consumeSearch();
                BlockPos candidate = areaOffset(origin, face, first, second).relative(face.getOpposite(), areaDepth);
                if (!level.isInWorldBounds(candidate) || !examined.add(candidate.asLong())) {
                    continue;
                }

                BlockState state = getLoadedBlockState(candidate);
                if (state != null && isEligible(level, player, candidate, state, targetBlock, mode)
                        && breakCandidate(candidate, state, budget)) {
                    brokenCount++;
                    breaks++;
                    budget.consumeBreak();
                }
            }

            reportProgress(breaks);
            if (!HELD_KEYS.contains(playerId) || areaDepth > areaDepthLimit || brokenCount >= totalLimit) {
                finish();
            }
        }

        private void tickSparseBlast(double tps, TickBudget budget, int fairBreaks, int fairSearches,
                int fairSparseScans) {
            int breakLimit = Math.min(Math.min(effectiveBlastBreakLimit(tps), fairBreaks), budget.remainingBreaks());
            int checkLimit = Math.min(Math.min(effectiveBlastSearchChecks(tps), fairSearches),
                    budget.remainingSearches());
            int sectionLimit = Math.min(fairSparseScans, budget.remainingSparseScans());
            int breaks = 0;
            int checks = 0;
            int scannedSections = 0;
            BlockPos.MutableBlockPos candidate = new BlockPos.MutableBlockPos();

            while (breaks < breakLimit && checks < checkLimit && brokenCount < totalLimit
                    && budget.canAttemptBreak(breakCostEwmaNanos)) {
                if (!sparseTargets.isEmpty()) {
                    SparseTarget target = sparseTargets.poll();
                    setPackedPosition(candidate, target.position());
                    checks++;
                    budget.consumeSearch();
                    BlockState state = getLoadedBlockState(candidate);
                    if (state != null && isEligible(level, player, candidate, state, targetBlock, mode)
                            && breakCandidate(candidate, state, budget)) {
                        brokenCount++;
                        breaks++;
                        budget.consumeBreak();
                        sparseCenters.enqueue(candidate.asLong());
                    }
                    continue;
                }

                if (sparseScan == null && !sparseCenters.isEmpty()) {
                    sparseScan = new SparseScanCursor(sparseCenters.dequeueLong(), blastDistance,
                            level.getMinBuildHeight(), level.getMaxBuildHeight());
                }
                if (sparseScan == null) {
                    break;
                }

                if (!skipExhaustedSparseSections(budget)) {
                    break;
                }
                if (sparseScan == null) {
                    continue;
                }

                boolean needsSectionScan = !sparseSectionMatches.containsKey(sparseScan.sectionKey());
                if (needsSectionScan && scannedSections >= sectionLimit) {
                    sectionBudgetBlocked = true;
                    break;
                }

                if (scanNextSparseSection(sparseScan, budget)) {
                    scannedSections++;
                    budget.consumeSparseScan();
                }
                checks++;
                budget.consumeSearch();
                if (!sparseScan.hasMore()) {
                    sparseScan = null;
                }
            }

            reportProgress(breaks);
            if (!HELD_KEYS.contains(playerId)
                    || sparseTargets.isEmpty() && sparseCenters.isEmpty() && sparseScan == null
                    || brokenCount >= totalLimit) {
                finish();
            }
        }

        private void reportProgress(int breaks) {
            if (breaks > 0 && progressCooldown == 0) {
                showProgress(player, brokenCount);
                progressCooldown = 4;
            }
        }

        private boolean breakCandidate(BlockPos pos, BlockState state, TickBudget budget) {
            budget.markBreakAttempt();
            long startedNanos = System.nanoTime();
            try {
                return breakOne(level, player, pos, state, captureContext, usesJustDireThingsBreakPath());
            } finally {
                updateBreakCostEwma(System.nanoTime() - startedNanos);
            }
        }

        private boolean usesJustDireThingsBreakPath() {
            Item current = player.getMainHandItem().getItem();
            if (current != cachedToolItem) {
                cachedToolItem = current;
                cachedJustDireThingsTool = isJustDireThingsTool(current);
            }
            return cachedJustDireThingsTool;
        }

        private void updateBreakCostEwma(long elapsedNanos) {
            long sample = Math.max(MIN_BREAK_COST_NANOS, Math.min(MAX_BREAK_COST_NANOS, elapsedNanos));
            breakCostEwmaNanos += (sample - breakCostEwmaNanos) / 8L;
        }

        private boolean updateTpsSafety(double tps) {
            if (!mode.isBlast()) {
                return true;
            }
            if (tpsWarningCooldown > 0) {
                tpsWarningCooldown--;
            }
            if (tps < TPS_CRITICAL_THRESHOLD) {
                criticalTpsTicks++;
                if (criticalTpsTicks == 1) {
                    showTpsMessage(player, "message.veinminerplus.tps_paused", tps);
                }
                if (criticalTpsTicks >= TPS_CRITICAL_TICKS_TO_STOP) {
                    showTpsMessage(player, "message.veinminerplus.tps_stopped", tps);
                    finish();
                }
                return false;
            }

            criticalTpsTicks = 0;
            if (tps < TPS_WARNING_THRESHOLD && tpsWarningCooldown == 0) {
                showTpsMessage(player, "message.veinminerplus.tps_warning", tps);
                tpsWarningCooldown = TPS_WARNING_COOLDOWN_TICKS;
            }
            return true;
        }

        private int effectiveBlastBreakLimit(double tps) {
            int configured = Config.MAX_BLAST_BLOCKS_PER_TICK.getAsInt();
            if (tps < TPS_WARNING_THRESHOLD) {
                return Math.max(1, configured / 4);
            }
            if (tps < TPS_RECOVERY_THRESHOLD) {
                return Math.max(1, configured / 2);
            }
            return configured;
        }

        private int effectiveBlastSearchChecks(double tps) {
            if (tps < TPS_WARNING_THRESHOLD) {
                return SEARCH_CHECKS_PER_TICK / 4;
            }
            if (tps < TPS_RECOVERY_THRESHOLD) {
                return SEARCH_CHECKS_PER_TICK / 2;
            }
            return SEARCH_CHECKS_PER_TICK;
        }

        private boolean skipExhaustedSparseSections(TickBudget budget) {
            int skippedSinceDeadlineCheck = 0;
            while (sparseScan != null && exhaustedSparseSections.contains(sparseScan.sectionKey())) {
                sparseScan.advance();
                budget.exhaustedSectionSkips++;
                if (!sparseScan.hasMore()) {
                    sparseScan = null;
                    return true;
                }
                if (++skippedSinceDeadlineCheck >= SPARSE_SKIP_DEADLINE_CHECK_INTERVAL) {
                    skippedSinceDeadlineCheck = 0;
                    if (System.nanoTime() >= budget.deadlineNanos) {
                        budget.deadlineBlocked = true;
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean scanNextSparseSection(SparseScanCursor cursor, TickBudget budget) {
            int sectionX = cursor.sectionX();
            int sectionY = cursor.sectionY();
            int sectionZ = cursor.sectionZ();
            long key = cursor.sectionKey();
            cursor.advance();

            LongArrayList matches = sparseSectionMatches.get(key);
            boolean scanned = false;
            if (matches == null) {
                scanned = true;
                budget.indexedSections++;
                matches = indexSparseSection(sectionX, sectionY, sectionZ);
                if (matches.isEmpty()) {
                    budget.emptyIndexedSections++;
                    exhaustedSparseSections.add(key);
                    return true;
                }
                sparseSectionMatches.put(key, matches);
            } else {
                budget.cachedSectionVisits++;
            }

            long maximumDistance = (long) blastDistance * blastDistance;
            int index = 0;
            while (index < matches.size()) {
                long position = matches.getLong(index);
                if (squaredDistance(cursor.centerX(), cursor.centerY(), cursor.centerZ(), position) > maximumDistance) {
                    index++;
                    continue;
                }

                int lastIndex = matches.size() - 1;
                if (index != lastIndex) {
                    matches.set(index, matches.getLong(lastIndex));
                }
                matches.removeLong(lastIndex);
                if (examined.add(position)) {
                    sparseTargets.add(new SparseTarget(position, squaredDistance(origin.getX(), origin.getY(), origin.getZ(), position)));
                }
            }
            if (matches.isEmpty()) {
                sparseSectionMatches.remove(key);
                exhaustedSparseSections.add(key);
            }
            return scanned;
        }

        private LongArrayList indexSparseSection(int sectionX, int sectionY, int sectionZ) {
            LongArrayList matches = new LongArrayList();
            LevelChunk chunk = getLoadedChunk(sectionX, sectionZ);
            if (chunk == null) {
                return matches;
            }

            int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                return matches;
            }
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir()) {
                return matches;
            }

            int baseX = sectionX << 4;
            int baseY = sectionY << 4;
            int baseZ = sectionZ << 4;
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            for (int localY = 0; localY < 16; localY++) {
                int worldY = baseY + localY;
                if (worldY < level.getMinBuildHeight() || worldY >= level.getMaxBuildHeight()) {
                    continue;
                }
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        if (!matchesSparseState(state)) {
                            continue;
                        }
                        mutable.set(baseX + localX, worldY, baseZ + localZ);
                        matches.add(mutable.asLong());
                    }
                }
            }
            return matches;
        }

        private boolean matchesSparseState(BlockState state) {
            return switch (mode) {
                case BLAST_ORES -> isOre(state);
                case BLAST_LOGS -> state.is(BlockTags.LOGS);
                case BLAST_ANY -> !state.isAir();
                default -> state.is(targetBlock);
            };
        }

        private BlockState getLoadedBlockState(BlockPos pos) {
            LevelChunk chunk = getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockState(pos);
        }

        private LevelChunk getLoadedChunk(int chunkX, int chunkZ) {
            long key = chunkKey(chunkX, chunkZ);
            LevelChunk cached = loadedChunks.get(key);
            if (cached != null) {
                return cached;
            }
            if (unavailableChunks.contains(key)) {
                return null;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                unavailableChunks.add(key);
                return null;
            }
            rememberLoadedChunk(key, chunk);
            return chunk;
        }

        private void rememberLoadedChunk(long key, LevelChunk chunk) {
            loadedChunks.put(key, chunk);
            if (loadedChunks.size() <= LOADED_CHUNK_CACHE_LIMIT) {
                return;
            }
            var eldest = loadedChunks.keySet().iterator();
            eldest.next();
            eldest.remove();
        }

        private void finish() {
            drops.flush(player.serverLevel(), player, brokenCount);
            ACTIVE_JOBS.remove(playerId, this);
        }
    }

    private enum TickStopReason {
        DEADLINE, GLOBAL_BREAK_LIMIT, CONFIGURED_BREAK_LIMIT, FAIR_BREAK_LIMIT,
        GLOBAL_SEARCH_LIMIT, LOCAL_SEARCH_LIMIT, FAIR_SEARCH_LIMIT,
        GLOBAL_SPARSE_SECTION_LIMIT, FAIR_SPARSE_SECTION_LIMIT,
        TOTAL_LIMIT, NO_PENDING_WORK, KEY_RELEASED, DIMENSION_CHANGED, PLAYER_INVALID,
        TPS_PAUSED, TPS_STOPPED, ERROR, OTHER
    }

    private static final class TickBudget {
        private final int initialBreaks;
        private final int initialSearches;
        private final int initialSparseScans;
        private int breaks;
        private int searches;
        private int sparseScans;
        private final long deadlineNanos;
        private boolean attemptedBreak;
        private boolean deadlineBlocked;
        private long indexedSections;
        private long cachedSectionVisits;
        private long emptyIndexedSections;
        private long exhaustedSectionSkips;

        private TickBudget(int breaks, int searches, int sparseScans, long deadlineNanos) {
            this.initialBreaks = breaks;
            this.initialSearches = searches;
            this.initialSparseScans = sparseScans;
            this.breaks = breaks;
            this.searches = searches;
            this.sparseScans = sparseScans;
            this.deadlineNanos = deadlineNanos;
        }

        private int remainingBreaks() {
            return breaks;
        }

        private int remainingSearches() {
            return searches;
        }

        private int remainingSparseScans() {
            return sparseScans;
        }

        private void consumeBreak() {
            breaks--;
        }

        private void consumeSearch() {
            searches--;
        }

        private void consumeSparseScan() {
            sparseScans--;
        }

        private boolean canAttemptBreak(long predictedBreakNanos) {
            if (breaks <= 0 || searches <= 0) {
                return false;
            }
            long remaining = deadlineNanos - System.nanoTime();
            boolean allowed = remaining > 0L
                    && (!attemptedBreak || remaining > Math.max(MIN_BREAK_COST_NANOS, predictedBreakNanos));
            if (!allowed) deadlineBlocked = true;
            return allowed;
        }

        private void markBreakAttempt() {
            attemptedBreak = true;
        }

        private boolean hasWork() {
            return breaks > 0 && searches > 0 && System.nanoTime() < deadlineNanos;
        }
    }

    private static final class SearchNode {
        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private int normalIndex;

        private SearchNode(BlockPos position) {
            this.baseX = position.getX();
            this.baseY = position.getY();
            this.baseZ = position.getZ();
        }

        private boolean nextCandidate(BlockPos.MutableBlockPos result) {
            if (normalIndex >= NORMAL_OFFSETS.size()) {
                return false;
            }
            BlockPos offset = NORMAL_OFFSETS.get(normalIndex++);
            result.set(baseX + offset.getX(), baseY + offset.getY(), baseZ + offset.getZ());
            return true;
        }

        private boolean hasMore() {
            return normalIndex < NORMAL_OFFSETS.size();
        }
    }

    private static final class SparseScanCursor {
        private final int centerX;
        private final int centerY;
        private final int centerZ;
        private final int minSectionX;
        private final int maxSectionX;
        private final int minSectionY;
        private final int maxSectionY;
        private final int minSectionZ;
        private final int maxSectionZ;
        private int sectionX;
        private int sectionY;
        private int sectionZ;

        private SparseScanCursor(long packedCenter, int distance, int minBuildHeight, int maxBuildHeight) {
            this.centerX = BlockPos.getX(packedCenter);
            this.centerY = BlockPos.getY(packedCenter);
            this.centerZ = BlockPos.getZ(packedCenter);
            this.minSectionX = (centerX - distance) >> 4;
            this.maxSectionX = (centerX + distance) >> 4;
            this.minSectionY = Math.max(minBuildHeight >> 4, (centerY - distance) >> 4);
            this.maxSectionY = Math.min((maxBuildHeight - 1) >> 4, (centerY + distance) >> 4);
            this.minSectionZ = (centerZ - distance) >> 4;
            this.maxSectionZ = (centerZ + distance) >> 4;
            this.sectionX = minSectionX;
            this.sectionY = minSectionY;
            this.sectionZ = minSectionZ;
        }

        private int centerX() { return centerX; }
        private int centerY() { return centerY; }
        private int centerZ() { return centerZ; }

        private int sectionX() {
            return sectionX;
        }

        private int sectionY() {
            return sectionY;
        }

        private int sectionZ() {
            return sectionZ;
        }

        private long sectionKey() {
            return ChainEvents.sectionKey(sectionX, sectionY, sectionZ);
        }

        private void advance() {
            sectionY++;
            if (sectionY > maxSectionY) {
                sectionY = minSectionY;
                sectionZ++;
                if (sectionZ > maxSectionZ) {
                    sectionZ = minSectionZ;
                    sectionX++;
                }
            }
        }

        private boolean hasMore() {
            return sectionX <= maxSectionX;
        }
    }

    private record SparseTarget(long position, long distanceSquared) {
    }

    private static long chunkKey(BlockPos pos) {
        return chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static long squaredDistance(int firstX, int firstY, int firstZ, long packedSecond) {
        long dx = (long) firstX - BlockPos.getX(packedSecond);
        long dy = (long) firstY - BlockPos.getY(packedSecond);
        long dz = (long) firstZ - BlockPos.getZ(packedSecond);
        return dx * dx + dy * dy + dz * dz;
    }

    private static void setPackedPosition(BlockPos.MutableBlockPos result, long packedPosition) {
        result.set(BlockPos.getX(packedPosition), BlockPos.getY(packedPosition), BlockPos.getZ(packedPosition));
    }

    private static long sectionKey(int sectionX, int sectionY, int sectionZ) {
        return ((long) sectionX & 0x3FFFFFL) << 42
                | ((long) sectionZ & 0x3FFFFFL) << 20
                | ((long) sectionY & 0xFFFFFL);
    }

    private record BreakFace(BlockPos pos, Direction face) {
    }

    private static final class CaptureContext {
        private final DropBuffer drops;
        private final BlockPos.MutableBlockPos origin = new BlockPos.MutableBlockPos();
        private boolean doublePlant;
        private boolean hasPosition;

        private CaptureContext(DropBuffer drops) {
            this.drops = drops;
        }

        private void begin(BlockPos pos, boolean doublePlant) {
            origin.set(pos);
            this.doublePlant = doublePlant;
            hasPosition = true;
        }

        private void clearPosition() {
            hasPosition = false;
        }

        private DropBuffer drops() {
            return drops;
        }

        private BlockPos origin() {
            return origin;
        }

        private boolean doublePlant() {
            return doublePlant;
        }

        private boolean hasPosition() {
            return hasPosition;
        }
    }
    /** Server-thread-only counters: no per-block logging or per-tick map allocation. */
    private static final class PerformanceLog {
        private int ticks;
        private int validPreJobSamples;
        private int maxJobs;
        private int maxBlocksPerTick;
        private long slices;
        private long skippedJobs;
        private long breaks;
        private long searches;
        private long sparseSections;
        private long indexedSections;
        private long cachedSectionVisits;
        private long emptyIndexedSections;
        private long exhaustedSectionSkips;
        private long elapsedNanos;
        private long maxElapsedNanos;
        private long preJobElapsedNanos;
        private long maxPreJobElapsedNanos;
        private long headroomNanos;
        private long grantedBudgetNanos;
        private long observedTickNanos;
        private long maxObservedTickNanos;
        private long breakEwmaNanos;
        private long aeFlushes;
        private long aeFlushNanos;
        private long maxAeFlushNanos;
        private long aeItemKeys;
        private final long[] stops = new long[TickStopReason.values().length];

        private void recordSlice(TickStopReason reason, long ewmaNanos) {
            if (!Config.ENABLE_PERFORMANCE_LOG.getAsBoolean()) {
                return;
            }
            slices++;
            stops[reason.ordinal()]++;
            breakEwmaNanos += ewmaNanos;
        }

        private void recordSkipped(int count) {
            if (!Config.ENABLE_PERFORMANCE_LOG.getAsBoolean()) {
                return;
            }
            skippedJobs += count;
        }

        private void recordAeFlush(long elapsed, int itemKeys) {
            if (!Config.ENABLE_PERFORMANCE_LOG.getAsBoolean()) {
                return;
            }
            aeFlushes++;
            aeFlushNanos += elapsed;
            maxAeFlushNanos = Math.max(maxAeFlushNanos, elapsed);
            aeItemKeys += itemKeys;
        }

        private void recordTick(int jobs, int tickBreaks, int tickSearches, int tickSparseSections, long elapsed,
                long preJobElapsed, long headroom, long grantedBudget, long tickIndexedSections,
                long tickCachedSectionVisits, long tickEmptyIndexedSections, long tickExhaustedSectionSkips) {
            if (!Config.ENABLE_PERFORMANCE_LOG.getAsBoolean()) {
                return;
            }
            ticks++;
            maxJobs = Math.max(maxJobs, jobs);
            breaks += tickBreaks;
            maxBlocksPerTick = Math.max(maxBlocksPerTick, tickBreaks);
            searches += tickSearches;
            sparseSections += tickSparseSections;
            indexedSections += tickIndexedSections;
            cachedSectionVisits += tickCachedSectionVisits;
            emptyIndexedSections += tickEmptyIndexedSections;
            exhaustedSectionSkips += tickExhaustedSectionSkips;
            elapsedNanos += elapsed;
            maxElapsedNanos = Math.max(maxElapsedNanos, elapsed);
            if (preJobElapsed >= 0L) {
                validPreJobSamples++;
                preJobElapsedNanos += preJobElapsed;
                maxPreJobElapsedNanos = Math.max(maxPreJobElapsedNanos, preJobElapsed);
                headroomNanos += headroom;
                long observedTick = preJobElapsed + elapsed;
                observedTickNanos += observedTick;
                maxObservedTickNanos = Math.max(maxObservedTickNanos, observedTick);
            }
            grantedBudgetNanos += grantedBudget;
            if (ticks >= PERFORMANCE_LOG_INTERVAL_TICKS) {
                flush();
            }
        }

        private void flush() {
            if (!Config.ENABLE_PERFORMANCE_LOG.getAsBoolean()) {
                reset();
                return;
            }
            if (ticks == 0 && aeFlushes == 0) return;
            EnumMap<TickStopReason, Long> reasons = new EnumMap<>(TickStopReason.class);
            for (TickStopReason reason : TickStopReason.values()) {
                if (stops[reason.ordinal()] > 0) reasons.put(reason, stops[reason.ordinal()]);
            }
            int tickDivisor = Math.max(1, ticks);
            int preJobDivisor = Math.max(1, validPreJobSamples);
            VeinMinerPlus.LOGGER.info(
                    "[VMP Perf] activeTicks={} jobSlices={} skippedJobs={} maxJobs={} breaks={} avgBlocksPerTick={} maxBlocksPerTick={} searches={} sparseSections={} indexedSections={} cachedSectionVisits={} emptyIndexedSections={} exhaustedSectionSkips={} avgPreJobMs={} maxPreJobMs={} avgHeadroomMs={} avgGrantedBudgetMs={} avgJobMs={} maxJobMs={} avgObservedTickMs={} maxObservedTickMs={} avgBreakEwmaUs={} aeFlushes={} avgAeFlushMs={} maxAeFlushMs={} aeItemKeys={} stops={}",
                    ticks, slices, skippedJobs, maxJobs, breaks, rounded(breaks / (double) tickDivisor),
                    maxBlocksPerTick, searches, sparseSections, indexedSections, cachedSectionVisits,
                    emptyIndexedSections, exhaustedSectionSkips,
                    rounded(preJobElapsedNanos / 1_000_000.0D / preJobDivisor),
                    rounded(maxPreJobElapsedNanos / 1_000_000.0D),
                    rounded(headroomNanos / 1_000_000.0D / preJobDivisor),
                    rounded(grantedBudgetNanos / 1_000_000.0D / tickDivisor),
                    rounded(elapsedNanos / 1_000_000.0D / tickDivisor),
                    rounded(maxElapsedNanos / 1_000_000.0D),
                    rounded(observedTickNanos / 1_000_000.0D / preJobDivisor),
                    rounded(maxObservedTickNanos / 1_000_000.0D),
                    rounded(slices == 0 ? 0 : breakEwmaNanos / 1_000.0D / slices), aeFlushes,
                    rounded(aeFlushes == 0 ? 0 : aeFlushNanos / 1_000_000.0D / aeFlushes),
                    rounded(maxAeFlushNanos / 1_000_000.0D), aeItemKeys, reasons);
            reset();
        }

        private static double rounded(double value) {
            return Math.round(value * 100.0D) / 100.0D;
        }

        private void reset() {
            ticks = 0;
            validPreJobSamples = 0;
            maxJobs = 0;
            maxBlocksPerTick = 0;
            slices = 0;
            skippedJobs = 0;
            breaks = 0;
            searches = 0;
            sparseSections = 0;
            indexedSections = 0;
            cachedSectionVisits = 0;
            emptyIndexedSections = 0;
            exhaustedSectionSkips = 0;
            elapsedNanos = 0;
            maxElapsedNanos = 0;
            preJobElapsedNanos = 0;
            maxPreJobElapsedNanos = 0;
            headroomNanos = 0;
            grantedBudgetNanos = 0;
            observedTickNanos = 0;
            maxObservedTickNanos = 0;
            breakEwmaNanos = 0;
            aeFlushes = 0;
            aeFlushNanos = 0;
            maxAeFlushNanos = 0;
            aeItemKeys = 0;
            java.util.Arrays.fill(stops, 0L);
        }
    }
}
