package com.extrarawstyle.veinminerplus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
import net.minecraft.world.Container;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class ChainEvents {
    // Work is deliberately bounded so one large blast cannot monopolize the server thread.
    private static final int SEARCH_CHECKS_PER_TICK = 16384;
    private static final int SEARCH_CHECKS_PER_CENTER = 256;
    private static final int BLOCK_BREAKS_PER_TICK = 8;
    private static final double TPS_WARNING_THRESHOLD = 12.0D;
    private static final double TPS_CRITICAL_THRESHOLD = 8.0D;
    private static final double TPS_RECOVERY_THRESHOLD = 16.0D;
    private static final int TPS_CRITICAL_TICKS_TO_STOP = 20;
    private static final int TPS_WARNING_COOLDOWN_TICKS = 100;

    private static final TagKey<Block> ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.withDefaultNamespace("ores"));
    private static final TagKey<Block> FORGE_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("forge", "ores"));
    private static final TagKey<Block> COMMON_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "ores"));
    private static final List<BlockPos> NORMAL_OFFSETS = createNormalOffsets();
    private static final Map<Integer, List<BlockPos>> BLAST_OFFSETS = new ConcurrentHashMap<>();
    private static final Map<UUID, ChainMode> PLAYER_MODES = new HashMap<>();
    private static final Set<UUID> HELD_KEYS = new HashSet<>();
    private static final Map<UUID, ChainJob> ACTIVE_JOBS = new HashMap<>();
    private static final Set<UUID> PENDING_JOBS = new HashSet<>();
    private static final Map<UUID, DropBuffer> PENDING_DROPS = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING_DROP_ORIGINS = new HashMap<>();
    private static final ThreadLocal<DropBuffer> CAPTURING_DROPS = new ThreadLocal<>();
    private static final Map<UUID, BreakFace> LAST_BREAK_FACES = new HashMap<>();

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
                pendingDrops.flush(player.serverLevel(), player);
            }
        }
        PLAYER_MODES.remove(id);
        HELD_KEYS.remove(id);
        PENDING_JOBS.remove(id);
        PENDING_DROP_ORIGINS.remove(id);
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
                || PENDING_JOBS.contains(player.getUUID())
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockPos target = event.getPos().immutable();
        BlockState state = event.getState();
        ChainMode mode = PLAYER_MODES.getOrDefault(player.getUUID(), ChainMode.NORMAL);
        if (!isEligible(level, player, target, state, state.getBlock(), mode)) {
            return;
        }

        BreakFace breakFace = LAST_BREAK_FACES.get(player.getUUID());
        Direction face = breakFace != null && breakFace.pos().equals(target) ? breakFace.face() : Direction.UP;
        DropBuffer drops = new DropBuffer();
        PENDING_DROPS.put(player.getUUID(), drops);
        PENDING_DROP_ORIGINS.put(player.getUUID(), target);
        PENDING_JOBS.add(player.getUUID());
        level.getServer().execute(() -> startAfterPrimaryBreak(level, player, target, state, face, mode,
                drops));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)
                || event.loadedFromDisk()
                || !(event.getEntity() instanceof ItemEntity || event.getEntity() instanceof ExperienceOrb)) {
            return;
        }

        DropBuffer drops = CAPTURING_DROPS.get();
        if (drops != null) {
            captureEntity(event, drops);
            return;
        }

        for (Map.Entry<UUID, DropBuffer> entry : PENDING_DROPS.entrySet()) {
            BlockPos origin = PENDING_DROP_ORIGINS.get(entry.getKey());
            if (origin != null && event.getEntity().blockPosition().distSqr(origin) <= 9.0D) {
                captureEntity(event, entry.getValue());
                return;
            }
        }
    }

    private static void captureEntity(EntityJoinLevelEvent event, DropBuffer drops) {
        if (event.getEntity() instanceof ItemEntity item) {
            drops.add(item.getItem());
            event.setCanceled(true);
        } else if (event.getEntity() instanceof ExperienceOrb orb) {
            drops.addExperience(orb.getValue());
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (ChainJob job : new ArrayList<>(ACTIVE_JOBS.values())) {
            job.tick();
        }
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
            BlockState originalState, Direction face, ChainMode mode, DropBuffer drops) {
        UUID id = player.getUUID();
        PENDING_JOBS.remove(id);
        PENDING_DROPS.remove(id, drops);
        PENDING_DROP_ORIGINS.remove(id, target);

        if (!level.isInWorldBounds(target) || level.getBlockState(target).is(originalState.getBlock())) {
            drops.clear();
            return;
        }

        if (!HELD_KEYS.contains(id) || ACTIVE_JOBS.containsKey(id) || player.isSpectator()) {
            drops.flush(level, player);
            return;
        }

        ACTIVE_JOBS.put(id, new ChainJob(level, player, target, originalState.getBlock(), face, mode, drops));
        showProgress(player, 1);
    }

    private static boolean isEligible(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state,
            Block targetBlock, ChainMode mode) {
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F || !level.mayInteract(player, pos)) {
            return false;
        }

        boolean matches = switch (mode) {
            case BLAST_ANY -> true;
            case BLAST_ORES -> isOre(state);
            case BLAST_LOGS -> state.is(BlockTags.LOGS);
            default -> state.getBlock() == targetBlock;
        };
        return matches
                && !isContainer(level, pos, state)
                && (player.isCreative() || ForgeHooks.isCorrectToolForDrops(state, player));
    }

    private static boolean isContainer(ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container
                || state.getMenuProvider(level, pos) != null
                || blockEntity != null && blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent();
    }

    private static boolean isOre(BlockState state) {
        if (state.is(ORE_BLOCKS) || state.is(FORGE_ORE_BLOCKS) || state.is(COMMON_ORE_BLOCKS)) {
            return true;
        }

        // Some mod packs do not add their ores to the shared ore tags.
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore");
    }

    private static boolean breakOne(ServerLevel level, ServerPlayer player, BlockPos pos, Block targetBlock,
            ChainMode mode) {
        BlockState state = level.getBlockState(pos);
        if (!isEligible(level, player, pos, state, targetBlock, mode)) {
            return false;
        }
        // Use the same server-side entry point as a real player break. This keeps
        // BlockEvent, drops, tool damage, block entities, and client updates in sync.
        ChainJob job = ACTIVE_JOBS.get(player.getUUID());
        DropBuffer previous = CAPTURING_DROPS.get();
        if (job != null) {
            CAPTURING_DROPS.set(job.drops);
        }
        try {
            if (!Config.NO_HUNGER_COST.get()) {
                return player.gameMode.destroyBlock(pos);
            }

            float exhaustion = player.getFoodData().getExhaustionLevel();
            boolean destroyed = player.gameMode.destroyBlock(pos);
            if (destroyed) {
                player.getFoodData().setExhaustion(exhaustion);
            }
            return destroyed;
        } finally {
            if (previous == null) {
                CAPTURING_DROPS.remove();
            } else {
                CAPTURING_DROPS.set(previous);
            }
        }
    }

    private static BlockState getBlockStateForSearch(ServerLevel level, BlockPos pos) {
        // Force a full chunk read so blast searches can cross the loaded-area boundary.
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        return level.getBlockState(pos);
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

    private static List<BlockPos> createBlastOffsets(int distance) {
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = -distance; x <= distance; x++) {
            for (int y = -distance; y <= distance; y++) {
                for (int z = -distance; z <= distance; z++) {
                    long squaredDistance = (long) x * x + (long) y * y + (long) z * z;
                    if ((x != 0 || y != 0 || z != 0)
                            && squaredDistance <= (long) distance * distance) {
                        offsets.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        offsets.sort(Comparator
                .comparingLong((BlockPos pos) -> (long) pos.getX() * pos.getX()
                        + (long) pos.getY() * pos.getY()
                        + (long) pos.getZ() * pos.getZ())
                .thenComparingInt(pos -> Math.abs(pos.getY()))
                .thenComparingInt(pos -> Math.abs(pos.getX()))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return Collections.unmodifiableList(offsets);
    }

    private static void showProgress(ServerPlayer player, int count) {
        player.displayClientMessage(Component.translatable("message.veinminerplus.progress", count), true);
    }

    private static double getServerTps(ServerLevel level) {
        MinecraftServer server = level.getServer();
        float tickTimeMillis = server.getAverageTickTime();
        return tickTimeMillis <= 0.0F ? 20.0D : Math.min(20.0D, 1_000.0D / tickTimeMillis);
    }

    private static int roundedTps(double tps) {
        return Math.max(0, (int) Math.round(tps));
    }

    private static void showTpsMessage(ServerPlayer player, String translationKey, double tps) {
        player.displayClientMessage(Component.translatable(translationKey, roundedTps(tps)), true);
    }

    private static final class DropBuffer {
        private final List<ItemStack> items = new ArrayList<>();
        private int experience;

        private void add(ItemStack stack) {
            if (stack.isEmpty()) {
                return;
            }

            int remaining = stack.getCount();
            for (ItemStack existing : items) {
                if (!ItemStack.isSameItemSameTags(existing, stack)) {
                    continue;
                }
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space <= 0) {
                    continue;
                }

                int amount = Math.min(space, remaining);
                existing.setCount(existing.getCount() + amount);
                remaining -= amount;
                if (remaining == 0) {
                    return;
                }
            }

            int maxStackSize = stack.getMaxStackSize();
            while (remaining > 0) {
                int amount = Math.min(maxStackSize, remaining);
                items.add(stack.copyWithCount(amount));
                remaining -= amount;
            }
        }

        private void addExperience(int amount) {
            experience += amount;
        }

        private void flush(ServerLevel level, ServerPlayer player) {
            if (!level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
                clear();
                return;
            }

            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();
            for (ItemStack stack : items) {
                ItemEntity item = new ItemEntity(level, x, y, z, stack);
                item.setDefaultPickUpDelay();
                item.setDeltaMovement(0.0D, 0.0D, 0.0D);
                level.addFreshEntity(item);
            }
            if (experience > 0) {
                ExperienceOrb.award(level, player.position(), experience);
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
        private final BlockPos origin;
        private final Block targetBlock;
        private final Direction face;
        private final ChainMode mode;
        private final Set<BlockPos> examined = new HashSet<>();
        private final Deque<SearchNode> frontier = new ArrayDeque<>();
        private final Deque<BlockPos> sparseCenters = new ArrayDeque<>();
        private final PriorityQueue<BlockPos> sparseTargets;
        private final Map<Long, List<BlockPos>> sparseChunkMatches = new HashMap<>();
        private final Map<Long, LevelChunk> loadedChunks = new HashMap<>();
        private final List<BlockPos> graphOffsets;
        private final int totalLimit;
        private final int areaDepthLimit;
        private final boolean sparseBlast;
        private final DropBuffer drops;
        private int brokenCount = 1;
        private int areaDepth = 1;
        private int areaIndex;
        private int criticalTpsTicks;
        private int tpsWarningCooldown;

        private ChainJob(ServerLevel level, ServerPlayer player, BlockPos origin, Block targetBlock,
                Direction face, ChainMode mode, DropBuffer drops) {
            this.level = level;
            this.player = player;
            this.origin = origin;
            this.targetBlock = targetBlock;
            this.face = face;
            this.mode = mode;
            this.drops = drops;
            this.totalLimit = mode.isBlast() ? Config.MAX_BLAST_BLOCKS.get() : Config.MAX_NORMAL_BLOCKS.get();
            this.areaDepthLimit = Config.MAX_NORMAL_BLOCKS.get();
            this.graphOffsets = mode.isBlast()
                    ? BLAST_OFFSETS.computeIfAbsent(Config.BLAST_SEARCH_DISTANCE.get(), ChainEvents::createBlastOffsets)
                    : NORMAL_OFFSETS;
            BlockState targetState = targetBlock.defaultBlockState();
            this.sparseBlast = mode == ChainMode.BLAST_ORES
                    || mode == ChainMode.BLAST_LOGS
                    || mode == ChainMode.BLAST_SAME && (isOre(targetState) || targetState.is(BlockTags.LOGS));
            this.sparseTargets = new PriorityQueue<>(Comparator
                    .comparingLong((BlockPos pos) -> squaredDistance(origin, pos))
                    .thenComparingInt(BlockPos::getY)
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ));

            examined.add(origin);
            loadedChunks.put(chunkKey(origin), level.getChunk(origin.getX() >> 4, origin.getZ() >> 4));
            if (!mode.isArea()) {
                if (sparseBlast) {
                    sparseCenters.addLast(origin);
                } else {
                    frontier.addLast(new SearchNode(origin, 0));
                }
            }
        }

        private void tick() {
            if (!HELD_KEYS.contains(player.getUUID()) || player.isRemoved() || player.isSpectator()
                    || player.serverLevel() != level) {
                finish();
                return;
            }

            if (!updateTpsSafety()) {
                return;
            }

            if (mode.isArea()) {
                tickArea();
            } else if (sparseBlast) {
                tickSparseBlast();
            } else {
                tickGraph();
            }
        }

        private void tickGraph() {
            int checks = 0;
            int breaks = 0;
            int checkLimit = mode.isBlast() ? effectiveBlastSearchChecks() : SEARCH_CHECKS_PER_TICK;
            int breakLimit = mode.isBlast() ? Config.MAX_BLAST_BLOCKS_PER_TICK.get()
                    : mode == ChainMode.NORMAL ? Config.MAX_NORMAL_BLOCKS_PER_TICK.get()
                    : BLOCK_BREAKS_PER_TICK;
            if (mode.isBlast()) {
                breakLimit = effectiveBlastBreakLimit();
            }
            while (checks < checkLimit && breaks < breakLimit
                    && !frontier.isEmpty() && brokenCount < totalLimit
                    && HELD_KEYS.contains(player.getUUID())) {
                SearchNode node = frontier.removeFirst();
                int centerChecks = 0;
                while (centerChecks < SEARCH_CHECKS_PER_CENTER
                        && checks < checkLimit
                        && breaks < breakLimit
                        && node.nextOffset() < graphOffsets.size()) {
                    BlockPos offset = graphOffsets.get(node.nextOffset());
                    node.advance();
                    BlockPos candidate = node.position().offset(offset.getX(), offset.getY(), offset.getZ());
                    checks++;
                    centerChecks++;
                    if (!examined.add(candidate) || !level.isInWorldBounds(candidate)) {
                        continue;
                    }

                    BlockState state = getBlockStateForSearch(level, candidate, loadedChunks);
                    if (isEligible(level, player, candidate, state, targetBlock, mode)
                            && breakOne(level, player, candidate, targetBlock, mode)) {
                        brokenCount++;
                        breaks++;
                        frontier.addLast(new SearchNode(candidate, 0));
                    }
                }
                if (node.nextOffset() < graphOffsets.size()) {
                    frontier.addLast(node);
                }
            }

            if (breaks > 0) {
                showProgress(player, brokenCount);
            }

            if (!HELD_KEYS.contains(player.getUUID()) || frontier.isEmpty() || brokenCount >= totalLimit) {
                finish();
            }
        }

        private void tickArea() {
            int size = mode == ChainMode.AREA_1X1 ? 1 : 3;
            int planeSize = size * size;
            int breaks = 0;
            while (breaks < BLOCK_BREAKS_PER_TICK && areaDepth <= areaDepthLimit
                    && HELD_KEYS.contains(player.getUUID())) {
                if (areaIndex >= planeSize) {
                    areaDepth++;
                    areaIndex = 0;
                    continue;
                }

                int startOffset = -(size / 2);
                int first = startOffset + areaIndex / size;
                int second = startOffset + areaIndex % size;
                areaIndex++;
                // The hit face points back toward the player. Advance into the block instead.
                BlockPos candidate = areaOffset(origin, face, first, second).relative(face.getOpposite(), areaDepth);
                if (!level.isInWorldBounds(candidate) || !examined.add(candidate)) {
                    continue;
                }

                BlockState state = getBlockStateForSearch(level, candidate, loadedChunks);
                if (isEligible(level, player, candidate, state, targetBlock, mode)
                        && breakOne(level, player, candidate, targetBlock, mode)) {
                    brokenCount++;
                    breaks++;
                }
            }

            if (breaks > 0) {
                showProgress(player, brokenCount);
            }

            if (!HELD_KEYS.contains(player.getUUID()) || areaDepth > areaDepthLimit) {
                finish();
            }
        }

        private void tickSparseBlast() {
            int breaks = 0;
            int scannedCenters = 0;
            int breakLimit = effectiveBlastBreakLimit();
            int centerLimit = Math.max(1, breakLimit);
            while (breaks < breakLimit && brokenCount < totalLimit && HELD_KEYS.contains(player.getUUID())) {
                while (sparseTargets.isEmpty() && !sparseCenters.isEmpty() && scannedCenters < centerLimit) {
                    scanSparseCenter(sparseCenters.removeFirst());
                    scannedCenters++;
                }
                if (sparseTargets.isEmpty()) {
                    break;
                }

                BlockPos candidate = sparseTargets.poll();
                BlockState state = getBlockStateForSearch(level, candidate, loadedChunks);
                if (isEligible(level, player, candidate, state, targetBlock, mode)
                        && breakOne(level, player, candidate, targetBlock, mode)) {
                    brokenCount++;
                    breaks++;
                    sparseCenters.addLast(candidate);
                }
            }

            if (breaks > 0) {
                showProgress(player, brokenCount);
            }
            if (!HELD_KEYS.contains(player.getUUID())
                    || sparseTargets.isEmpty() && sparseCenters.isEmpty()
                    || brokenCount >= totalLimit) {
                finish();
            }
        }

        private boolean updateTpsSafety() {
            if (!mode.isBlast()) {
                return true;
            }

            if (tpsWarningCooldown > 0) {
                tpsWarningCooldown--;
            }

            double tps = getServerTps(level);
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

        private int effectiveBlastBreakLimit() {
            int configured = Config.MAX_BLAST_BLOCKS_PER_TICK.get();
            double tps = getServerTps(level);
            if (tps < TPS_WARNING_THRESHOLD) {
                return Math.max(1, configured / 4);
            }
            if (tps < TPS_RECOVERY_THRESHOLD) {
                return Math.max(1, configured / 2);
            }
            return configured;
        }

        private int effectiveBlastSearchChecks() {
            double tps = getServerTps(level);
            if (tps < TPS_WARNING_THRESHOLD) {
                return SEARCH_CHECKS_PER_TICK / 4;
            }
            if (tps < TPS_RECOVERY_THRESHOLD) {
                return SEARCH_CHECKS_PER_TICK / 2;
            }
            return SEARCH_CHECKS_PER_TICK;
        }

        private void scanSparseCenter(BlockPos center) {
            int distance = Config.BLAST_SEARCH_DISTANCE.get();
            int minChunkX = (center.getX() - distance) >> 4;
            int maxChunkX = (center.getX() + distance) >> 4;
            int minChunkZ = (center.getZ() - distance) >> 4;
            int maxChunkZ = (center.getZ() + distance) >> 4;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    long key = chunkKey(chunkX, chunkZ);
                    LevelChunk chunk = loadedChunks.get(key);
                    if (chunk == null) {
                        chunk = level.getChunk(chunkX, chunkZ);
                        loadedChunks.put(key, chunk);
                    }

                    List<BlockPos> matches = sparseChunkMatches.get(key);
                    if (matches == null) {
                        matches = new ArrayList<>();
                        List<BlockPos> positions = matches;
                        chunk.findBlocks(this::matchesSparseState,
                                (pos, state) -> positions.add(pos.immutable()));
                        sparseChunkMatches.put(key, matches);
                    }

                    for (BlockPos pos : matches) {
                        if (squaredDistance(center, pos) <= (long) distance * distance && examined.add(pos)) {
                            sparseTargets.add(pos);
                        }
                    }
                }
            }
        }

        private boolean matchesSparseState(BlockState state) {
            return switch (mode) {
                case BLAST_ORES -> isOre(state);
                case BLAST_LOGS -> state.is(BlockTags.LOGS);
                default -> state.is(targetBlock);
            };
        }

        private void finish() {
            drops.flush(player.serverLevel(), player);
            ACTIVE_JOBS.remove(player.getUUID(), this);
        }

    }

    private static BlockState getBlockStateForSearch(ServerLevel level, BlockPos pos,
            Map<Long, LevelChunk> loadedChunks) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        LevelChunk chunk = loadedChunks.computeIfAbsent(chunkKey(chunkX, chunkZ),
                key -> level.getChunk(chunkX, chunkZ));
        return chunk.getBlockState(pos);
    }

    private static long chunkKey(BlockPos pos) {
        return chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static long squaredDistance(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static final class SearchNode {
        private final BlockPos position;
        private int nextOffset;

        private SearchNode(BlockPos position, int nextOffset) {
            this.position = position;
            this.nextOffset = nextOffset;
        }

        private BlockPos position() {
            return position;
        }

        private int nextOffset() {
            return nextOffset;
        }

        private void advance() {
            nextOffset++;
        }
    }

    private record BreakFace(BlockPos pos, Direction face) {
    }
}
