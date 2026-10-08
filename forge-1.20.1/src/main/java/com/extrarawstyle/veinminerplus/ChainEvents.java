package com.extrarawstyle.veinminerplus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
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
    private static final int SPARSE_SCANS_PER_TICK = 256;
    private static final int BLOCK_BREAKS_PER_TICK = 8;
    private static final int PENDING_SEED_LIMIT = 256;
    // The interaction mode grows a square outward from the clicked block, one ring per step,
    // and only keeps positions the held item could actually be used on. Vanilla's useOn stays
    // the final gate when a position is processed.
    private static final int INTERACT_BLOCK_LIMIT = 1024;
    private static final int INTERACT_MAX_RADIUS = 16;
    private static final int INTERACT_BLOCKS_PER_TICK = 64;
    static final int INTERACT_PREVIEW_LIMIT = 128;
    private static final double TPS_WARNING_THRESHOLD = 12.0D;
    private static final double TPS_CRITICAL_THRESHOLD = 8.0D;
    private static final double TPS_RECOVERY_THRESHOLD = 16.0D;
    private static final int TPS_CRITICAL_TICKS_TO_STOP = 20;
    private static final int TPS_WARNING_COOLDOWN_TICKS = 100;

    private static final TagKey<Block> ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("minecraft", "ores"));
    private static final TagKey<Block> FORGE_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("forge", "ores"));
    private static final TagKey<Block> COMMON_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "ores"));
    private static final TagKey<Block> ALLTHEMODIUM_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("forge", "ores/allthemodium"));
    private static final TagKey<Block> VIBRANIUM_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("forge", "ores/vibranium"));
    private static final TagKey<Block> UNOBTAINIUM_ORE_BLOCKS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("forge", "ores/unobtainium"));
    private static final List<BlockPos> NORMAL_OFFSETS = createNormalOffsets();
    private static final Map<Integer, List<BlockPos>> BLAST_OFFSETS = new ConcurrentHashMap<>();
    private static final Map<Block, Boolean> ORE_CACHE = new HashMap<>();
    private static final Map<UUID, ChainMode> PLAYER_MODES = new HashMap<>();
    private static final Set<UUID> HELD_KEYS = new HashSet<>();
    private static final Map<UUID, ChainJob> ACTIVE_JOBS = new HashMap<>();
    private static final Map<UUID, InteractJob> ACTIVE_INTERACT_JOBS = new HashMap<>();
    private static final Set<UUID> PENDING_JOBS = new HashSet<>();
    private static final Map<UUID, DropBuffer> PENDING_DROPS = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING_DROP_ORIGINS = new HashMap<>();
    private static final Map<UUID, Boolean> PENDING_DOUBLE_PLANTS = new HashMap<>();
    private static final ThreadLocal<CaptureContext> CAPTURING_DROPS = new ThreadLocal<>();
    private static final Map<UUID, BreakFace> LAST_BREAK_FACES = new HashMap<>();
    private static final Map<UUID, List<BlockPos>> PENDING_SEEDS = new HashMap<>();

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
        ACTIVE_INTERACT_JOBS.remove(id);
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
        if (mode.isInteraction() || !isEligible(level, player, target, state, state.getBlock(), mode)) {
            return;
        }

        BreakFace breakFace = LAST_BREAK_FACES.get(id);
        Direction face = breakFace != null && breakFace.pos().equals(target) ? breakFace.face() : Direction.UP;
        DropBuffer drops = new DropBuffer();
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
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel)
                || event.loadedFromDisk()
                || !(event.getEntity() instanceof ItemEntity || event.getEntity() instanceof ExperienceOrb)) {
            return;
        }

        CaptureContext capture = CAPTURING_DROPS.get();
        if (capture != null) {
            if (matchesDropPosition(event.getEntity().blockPosition(), capture.origin(), capture.doublePlant())) {
                captureEntity(event, capture.drops());
            }
            return;
        }

        for (Map.Entry<UUID, DropBuffer> entry : PENDING_DROPS.entrySet()) {
            BlockPos origin = PENDING_DROP_ORIGINS.get(entry.getKey());
            boolean doublePlant = PENDING_DOUBLE_PLANTS.getOrDefault(entry.getKey(), false);
            if (origin != null && matchesDropPosition(event.getEntity().blockPosition(), origin, doublePlant)) {
                captureEntity(event, entry.getValue());
                return;
            }
        }
    }

    private static boolean matchesDropPosition(BlockPos dropPosition, BlockPos origin, boolean doublePlant) {
        return dropPosition.equals(origin)
                || doublePlant && (dropPosition.equals(origin.above()) || dropPosition.equals(origin.below()));
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

    // Right click chaining for the interaction mode. The vanilla interaction still runs for the
    // block the player aimed at; this only spreads the same action over the square around it.
    // Nothing is cancelled, so a use vanilla refuses simply does nothing.
    // HIGHEST because FTB Ultimine harvests mature crops from this same event on HIGH and cancels
    // it, even with no key held. A lower priority listener would never be called.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
                || event.getFace() == Direction.DOWN
                || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        UUID id = player.getUUID();
        if (!HELD_KEYS.contains(id)
                || PLAYER_MODES.getOrDefault(id, ChainMode.NORMAL) != ChainMode.SPECIAL_INTERACT
                || ACTIVE_INTERACT_JOBS.containsKey(id)
                || player.isSpectator()) {
            return;
        }

        List<BlockPos> targets = new ArrayList<>();
        if (!collectInteractTargets(level, event.getPos(), player.getMainHandItem(),
                INTERACT_BLOCK_LIMIT, targets) || targets.isEmpty()) {
            return;
        }

        boolean harvest = isHarvestable(level.getBlockState(event.getPos()));
        ACTIVE_INTERACT_JOBS.put(id, new InteractJob(level, player, targets, harvest));
    }

    // Shared by the server job and the client preview, so the white outline always matches what
    // the job will do. Returns false when the held item is not handled by this mode.
    static boolean collectInteractTargets(Level level, BlockPos origin, ItemStack stack, int limit,
            List<BlockPos> targets) {
        BlockState originState = level.getBlockState(origin);
        if (isHarvestable(originState)) {
            spreadSquare(level, origin, limit, targets,
                    pos -> isHarvestable(level.getBlockState(pos)));
            return true;
        }
        if (stack.getItem() instanceof HoeItem) {
            spreadSquare(level, origin, limit, targets, pos -> isTillable(level, pos));
            return true;
        }
        if (stack.getItem() instanceof ItemNameBlockItem) {
            spreadSquare(level, origin, limit, targets, pos -> isPlantable(level, pos));
            return true;
        }
        // An axe strips logs, and Create applies its own recipes to the clicked block; both work on
        // the block the player aimed at, so its neighbours of the same kind are collected. Anything
        // the held item does not apply to is skipped by the vanilla gate inside interactOne.
        if (isAppliedToBlock(stack, originState)) {
            Block originBlock = originState.getBlock();
            spreadSquare(level, origin, limit, targets, pos -> level.getBlockState(pos).is(originBlock));
            return true;
        }
        return false;
    }

    // An axe is applied to logs. Create runs its item application through the interact event instead
    // of useOn, so anything from that mod is offered to the block that was clicked. Items that place
    // blocks are left out on purpose: chaining them would build a wall.
    private static boolean isAppliedToBlock(ItemStack stack, BlockState state) {
        if (stack.isEmpty() || stack.getItem() instanceof BlockItem) {
            return false;
        }
        if (stack.getItem() instanceof AxeItem) {
            return state.is(BlockTags.LOGS);
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && "create".equals(id.getNamespace());
    }

    // Grows a square outward from the origin, one ring per step. Stops at the limit, at the
    // radius cap, or as soon as a whole ring holds nothing the held item could be used on.
    private static void spreadSquare(Level level, BlockPos origin, int limit, List<BlockPos> targets,
            Predicate<BlockPos> valid) {
        int y = origin.getY();
        for (int radius = 0; radius <= INTERACT_MAX_RADIUS && targets.size() < limit; radius++) {
            boolean found = false;
            for (int dx = -radius; dx <= radius && targets.size() < limit; dx++) {
                for (int dz = -radius; dz <= radius && targets.size() < limit; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                    if (level.isInWorldBounds(pos) && valid.test(pos)) {
                        targets.add(pos);
                        found = true;
                    }
                }
            }
            if (radius > 0 && !found) {
                break;
            }
        }
    }

    // Selection is deliberately a little looser than vanilla: useOn is still the final gate when
    // the job runs, so a position vanilla would refuse is simply skipped.
    private static boolean isTillable(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.DIRT) && level.getBlockState(pos.above()).isAir();
    }

    private static boolean isPlantable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof FarmBlock
                && level.getBlockState(pos.above()).isAir();
    }

    // Mirrors what the pack already lets a player harvest with a single right click (FTB
    // Ultimine), so anything harvestable by hand can also be chained.
    static boolean isHarvestable(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        if (state.getBlock() instanceof SweetBerryBushBlock) {
            return state.getValue(SweetBerryBushBlock.AGE) > 1;
        }
        if (state.getBlock() instanceof CocoaBlock) {
            return state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        }
        return false;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (ChainJob job : new ArrayList<>(ACTIVE_JOBS.values())) {
            job.tick();
        }
        for (InteractJob job : new ArrayList<>(ACTIVE_INTERACT_JOBS.values())) {
            job.tick();
        }
    }

    @SubscribeEvent
    public void onTagsUpdated(TagsUpdatedEvent event) {
        // A datapack reload can change which blocks count as ore, so cached lookups must
        // not outlive it.
        ORE_CACHE.clear();
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
            drops.flush(level, player);
            return;
        }

        ACTIVE_JOBS.put(id, new ChainJob(level, player, target, originalState.getBlock(), face, mode, drops, seeds));
        showProgress(player, 1);
    }

    private static boolean isEligible(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state,
            Block targetBlock, ChainMode mode) {
        if (state.isAir() || !level.mayInteract(player, pos)
                || !isAllthemodiumBlock(state) && state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }

        boolean matches = switch (mode) {
            case BLAST_ANY -> true;
            case BLAST_ORES -> isOre(state);
            case BLAST_LOGS -> state.is(BlockTags.LOGS);
            default -> state.getBlock() == targetBlock;
        };
        // 1.20.1 fires the HarvestCheck event from Player.hasCorrectToolForDrops, which is the
        // platform equivalent of the harvest check the NeoForge side runs through EventHooks.
        return matches
                && (player.isCreative() || player.hasCorrectToolForDrops(state));
    }

    // A blast search tests the same handful of blocks over and over, and the ore tags of a
    // block cannot change during a session. Resolving them once per block instead of once
    // per tested position takes five tag lookups and a registry lookup out of the hot path.
    // The plain get() matters as much as the cache does: with computeIfAbsent alone, the
    // lookup itself was the largest cost inside the scan.
    private static boolean isOre(BlockState state) {
        Block block = state.getBlock();
        Boolean cached = ORE_CACHE.get(block);
        return cached != null ? cached : ORE_CACHE.computeIfAbsent(block, ChainEvents::computeIsOre);
    }

    private static boolean computeIsOre(Block block) {
        BlockState state = block.defaultBlockState();
        if (state.is(ORE_BLOCKS) || state.is(FORGE_ORE_BLOCKS) || state.is(COMMON_ORE_BLOCKS)
                || state.is(ALLTHEMODIUM_ORE_BLOCKS)
                || state.is(VIBRANIUM_ORE_BLOCKS)
                || state.is(UNOBTAINIUM_ORE_BLOCKS)) {
            return true;
        }

        // Some mod packs do not add their ores to the shared ore tags.
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && id.getPath().endsWith("_ore");
    }

    // Allthemodium registers its ore and ancient stone sets with a negative destroy
    // speed while overriding getDestroyProgress, so players can still mine them.
    // Reading that negative value as "unbreakable" would keep them out of chains.
    private static boolean isAllthemodiumBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && "allthemodium".equals(id.getNamespace());
    }

    // Every caller has already run isEligible on this exact state; doing it again here would
    // repeat mayInteract and the harvest check for every single chained block.
    private static boolean breakOne(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        // Use the same server-side entry point as a real player break. This keeps
        // BlockEvent, drops, tool damage, block entities, and client updates in sync.
        ChainJob job = ACTIVE_JOBS.get(player.getUUID());
        CaptureContext previous = CAPTURING_DROPS.get();
        if (job != null) {
            CAPTURING_DROPS.set(new CaptureContext(job.drops, pos.immutable(), state.getBlock() instanceof DoublePlantBlock));
        }
        try {
            float exhaustion = player.getFoodData().getExhaustionLevel();
            boolean destroyed = isJustDireThingsTool(player)
                    ? breakWithoutBreakEvent(level, player, pos, state)
                    : player.gameMode.destroyBlock(pos);
            if (destroyed && Config.NO_HUNGER_COST.get()) {
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

    // Just Dire Things listens to BlockEvent.BreakEvent, runs its own hammer area
    // break there and cancels the event. Firing that event for a chained block would
    // hand the block to JDT instead: the chain counter would stick at 1 and every
    // attempt would multiply into another 3x3/5x5/7x7 area break. Chained blocks are
    // therefore broken directly, without the event. The manual hit still goes through
    // the normal path, so JDT's own hammer behaviour is untouched.
    private static boolean isJustDireThingsTool(ServerPlayer player) {
        return player.getMainHandItem().getItem().getClass().getName()
                .startsWith("com.direwolf20.justdirethings.");
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
        if (player.getMainHandItem().onBlockStartBreak(pos, player)) {
            return false;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        ItemStack tool = player.getMainHandItem();
        // Forge fires BlockEvent.BreakEvent from ServerPlayerGameMode.destroyBlock, not from here,
        // so this call keeps the visual and game-event side effects without handing the block
        // back to the BreakEvent listeners the JDT path has to avoid.
        block.playerWillDestroy(level, pos, state, player);
        if (!level.removeBlock(pos, false)) {
            return false;
        }
        block.destroy(level, pos, state);
        if (!player.isCreative()) {
            // isEligible already required a successful harvest check, so drops apply.
            block.playerDestroy(level, player, pos, state, blockEntity, tool);
            if (state.getDestroySpeed(level, pos) != 0.0F) {
                tool.mineBlock(level, state, pos, player);
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

    private static void showInteractProgress(ServerPlayer player, int count) {
        player.displayClientMessage(Component.translatable("message.veinminerplus.interact_progress", count), true);
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
        // One bucket per item and component set, so a drop finds its total in a single lookup.
        // A long chain accumulates thousands of buckets; scanning all of them per drop made every
        // drop cost grow with the number already accumulated.
        private final Map<StackKey, Long> counts = new HashMap<>();
        private int experience;

        private void add(ItemStack stack) {
            if (stack.isEmpty()) {
                return;
            }
            counts.merge(new StackKey(stack), (long) stack.getCount(), Long::sum);
        }

        private void addExperience(int amount) {
            experience += amount;
        }

        private void flush(ServerLevel level, ServerPlayer player) {
            if (!level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
                clear();
                return;
            }

            // The bound storage takes what it can; only what it does not accept is dropped. The target
            // is resolved once here, not once per stack.
            StorageRouter.Sink sink = null;
            if (Config.STORAGE_BINDING.get()) {
                StorageBindings bindings = StorageBindings.findIn(player);
                if (bindings != null) {
                    sink = StorageRouter.resolve(level.getServer(), player, bindings);
                }
            }
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();
            for (Map.Entry<StackKey, Long> entry : counts.entrySet()) {
                // A bucket holds one running total, so it has to be cut back to stack size before
                // it leaves the buffer.
                ItemStack template = entry.getKey().template;
                int maxStackSize = template.getMaxStackSize();
                long remaining = entry.getValue();
                while (remaining > 0) {
                    int amount = (int) Math.min(maxStackSize, remaining);
                    remaining -= amount;
                    ItemStack stack = template.copyWithCount(amount);
                    if (sink != null) {
                        sink.insert(stack);
                        if (stack.isEmpty()) {
                            continue;
                        }
                    }
                    ItemEntity item = new ItemEntity(level, x, y, z, stack);
                    item.setDefaultPickUpDelay();
                    item.setDeltaMovement(0.0D, 0.0D, 0.0D);
                    level.addFreshEntity(item);
                }
            }
            if (experience > 0) {
                ExperienceOrb.award(level, player.position(), experience);
            }
            clear();
        }

        private void clear() {
            counts.clear();
            experience = 0;
        }

        // Identity of a bucket: the item plus every tag, the same pair the vanilla helper
        // compares. Count is deliberately excluded, it is what the bucket accumulates.
        private static final class StackKey {
            private final ItemStack template;
            private final int hash;

            private StackKey(ItemStack stack) {
                this.template = stack.copyWithCount(1);
                this.hash = 31 * this.template.getItem().hashCode() + Objects.hashCode(this.template.getTag());
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof StackKey key
                        && ItemStack.isSameItemSameTags(template, key.template);
            }
        }
    }

    // Runs the same server-side right click a manual one would reach, so tool damage, sounds, mod
    // interactions (Create's item application) and the actual conversion stay with vanilla and other
    // mods. A mature crop is harvested directly instead, because the pack's right click harvesting
    // lives in FTB Ultimine rather than in the interact pipeline.
    private static boolean interactOne(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isHarvestable(state)) {
            return harvestOne(level, player, pos, state);
        }

        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return false;
        }

        float exhaustion = player.getFoodData().getExhaustionLevel();
        // The full pipeline, so mods that hook the interact event see every chained position too.
        InteractionResult result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        if (Config.NO_HUNGER_COST.get()) {
            player.getFoodData().setExhaustion(exhaustion);
        }
        // A cancelled interaction hands back no result at all; that counts as "not applied".
        return result != null && result.consumesAction();
    }

    // Harvest and replant the way FTB Ultimine does it: the vanilla loot table decides the drops,
    // one of them pays for the replant, and the block is reset to its first growth stage. Like
    // FTB Ultimine, the drops are rolled without a tool.
    private static boolean harvestOne(ServerLevel level, ServerPlayer player, BlockPos pos,
            BlockState state) {
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        for (ItemStack drop : Block.getDrops(state, level, pos, blockEntity, player, ItemStack.EMPTY)) {
            if (Block.byItem(drop.getItem()) == state.getBlock()) {
                drop.shrink(1);
            }
            if (!drop.isEmpty()) {
                Block.popResource(level, pos, drop);
            }
        }
        level.setBlockAndUpdate(pos, replanted(state));
        return true;
    }

    private static BlockState replanted(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.getStateForAge(0);
        }
        if (state.getBlock() instanceof SweetBerryBushBlock) {
            return state.setValue(SweetBerryBushBlock.AGE, 1);
        }
        return state.setValue(CocoaBlock.AGE, 0);
    }

    private static final class InteractJob {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final Deque<BlockPos> targets;
        private final boolean harvest;
        private int processedCount;

        private InteractJob(ServerLevel level, ServerPlayer player, List<BlockPos> targets,
                boolean harvest) {
            this.level = level;
            this.player = player;
            this.targets = new ArrayDeque<>(targets);
            this.harvest = harvest;
        }

        private void tick() {
            if (!HELD_KEYS.contains(player.getUUID()) || player.isRemoved() || player.isSpectator()
                    || player.serverLevel() != level) {
                finish();
                return;
            }

            int processed = 0;
            int counted = 0;
            while (!targets.isEmpty() && processed < INTERACT_BLOCKS_PER_TICK
                    && HELD_KEYS.contains(player.getUUID())) {
                processed++;
                if (interactOne(level, player, targets.poll())) {
                    processedCount++;
                    counted++;
                }
                // A consumed seed stack or a broken tool ends the chain. Harvesting never uses up
                // the held item, and is usually done bare handed, so it must not stop there.
                if (!harvest && player.getMainHandItem().isEmpty()) {
                    break;
                }
            }

            if (counted > 0) {
                showInteractProgress(player, processedCount);
            }

            if (targets.isEmpty()) {
                finish();
            }
        }

        private void finish() {
            ACTIVE_INTERACT_JOBS.remove(player.getUUID(), this);
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
        // Chunk key -> section y -> matching positions inside that 16x16x16 section.
        // Bucketing matters: a chunk column holds up to 24 sections, while a scan sphere
        // only ever reaches a couple of them.
        private final Map<Long, Map<Integer, List<BlockPos>>> sparseChunkMatches = new HashMap<>();
        private final Map<Long, LevelChunk> loadedChunks = new HashMap<>();
        private final List<BlockPos> graphOffsets;
        private final int totalLimit;
        private final int areaDepthLimit;
        private final boolean sparseBlast;
        private final DropBuffer drops;
        private int brokenCount = 1;
        private int areaDepth;
        private int areaIndex;
        private int criticalTpsTicks;
        private int tpsWarningCooldown;

        private ChainJob(ServerLevel level, ServerPlayer player, BlockPos origin, Block targetBlock,
                Direction face, ChainMode mode, DropBuffer drops, List<BlockPos> seeds) {
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

            List<BlockPos> starts = new ArrayList<>();
            starts.add(origin);
            if (seeds != null) {
                starts.addAll(seeds);
            }
            for (BlockPos start : starts) {
                if (!examined.add(start)) {
                    continue;
                }
                loadedChunks.put(chunkKey(start), level.getChunk(start.getX() >> 4, start.getZ() >> 4));
                if (!mode.isArea()) {
                    if (sparseBlast) {
                        sparseCenters.addLast(start);
                    } else {
                        frontier.addLast(new SearchNode(start, 0));
                    }
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
                            && breakOne(level, player, candidate, state)) {
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
                        && breakOne(level, player, candidate, state)) {
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
            // While targets are queued no scanning happens, so a finished vein can leave a
            // large backlog of centres behind and every one of them would be walked in a
            // single tick. Capping the number walked per tick bounds that worst case; the
            // remainder is simply handled on the following ticks.
            int centerLimit = Math.min(Math.max(1, breakLimit), SPARSE_SCANS_PER_TICK);
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
                        && breakOne(level, player, candidate, state)) {
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
            long maxSquaredDistance = (long) distance * distance;
            int minChunkX = (center.getX() - distance) >> 4;
            int maxChunkX = (center.getX() + distance) >> 4;
            int minChunkZ = (center.getZ() - distance) >> 4;
            int maxChunkZ = (center.getZ() + distance) >> 4;
            int minSectionY = (center.getY() - distance) >> 4;
            int maxSectionY = (center.getY() + distance) >> 4;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    long key = chunkKey(chunkX, chunkZ);
                    Map<Integer, List<BlockPos>> sections = sparseChunkMatches.get(key);
                    if (sections == null) {
                        LevelChunk chunk = loadedChunks.get(key);
                        if (chunk == null) {
                            chunk = level.getChunk(chunkX, chunkZ);
                            loadedChunks.put(key, chunk);
                        }

                        sections = new HashMap<>();
                        Map<Integer, List<BlockPos>> buckets = sections;
                        chunk.findBlocks(this::matchesSparseState,
                                (pos, state) -> buckets
                                        .computeIfAbsent(pos.getY() >> 4, sectionY -> new ArrayList<>())
                                        .add(pos.immutable()));
                        sparseChunkMatches.put(key, sections);
                    }

                    for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                        List<BlockPos> matches = sections.get(sectionY);
                        if (matches == null) {
                            continue;
                        }

                        // Positions outside the sphere stay in the bucket, a later centre
                        // can still reach them. Positions that made it in are enqueued once
                        // and never looked at again, so they are dropped from the bucket.
                        for (int index = 0; index < matches.size();) {
                            BlockPos pos = matches.get(index);
                            if (squaredDistance(center, pos) > maxSquaredDistance) {
                                index++;
                                continue;
                            }

                            matches.set(index, matches.get(matches.size() - 1));
                            matches.remove(matches.size() - 1);
                            if (examined.add(pos)) {
                                sparseTargets.add(pos);
                            }
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

    private record CaptureContext(DropBuffer drops, BlockPos origin, boolean doublePlant) {
    }
}
