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

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.extrarawstyle.veinminerplus.compat.RemoveObservation;

public final class ChainEvents {
    // Work is deliberately bounded so one large blast cannot monopolize the server thread.
    private static final int SEARCH_CHECKS_PER_TICK = 16384;
    private static final int SEARCH_CHECKS_PER_CENTER = 256;
    // Every successful sparse break adds a centre to expand, so the budget has to exceed the
    // break limit or the backlog only ever grows. The ceiling bounds the worst case.
    private static final int SPARSE_CENTERS_PER_BREAK = 2;
    private static final int SPARSE_CENTER_LIMIT = 1024;
    // Soft per-tick budget for sparse scanning only. Centre cost grows with the search
    // distance, so a centre count alone does not bound the work.
    private static final long SPARSE_SCAN_BUDGET_NANOS = 5_000_000L;
    private static final int PENDING_SEED_LIMIT = 256;
    // The interaction mode grows outward from the clicked block, one ring per step, and only keeps
    // positions the held item could actually be used on. Vanilla's useOn stays the final gate when a
    // position is processed.
    private static final int INTERACT_BLOCK_LIMIT = 1024;
    private static final int INTERACT_MAX_RADIUS = 16;
    private static final int INTERACT_BLOCKS_PER_TICK = 64;
    // The preview shows the same reach the server job uses, so a large area is not silently cut off.
    static final int INTERACT_PREVIEW_LIMIT = INTERACT_BLOCK_LIMIT;
    private static final double TPS_CRITICAL_THRESHOLD = 8.0D;
    private static final int TPS_CRITICAL_TICKS_TO_STOP = 20;

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
    // Logged once per server, so the active counting path is visible without spamming the log.
    private static final java.util.concurrent.atomic.AtomicBoolean fallbackNotice =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final ThreadLocal<CaptureContext> CAPTURING_DROPS = new ThreadLocal<>();
    private static final Map<UUID, BreakFace> LAST_BREAK_FACES = new HashMap<>();
    private static final Map<UUID, List<BlockPos>> PENDING_SEEDS = new HashMap<>();

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            NetworkHandler.sendBlacklistSync(player);
        }
    }

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
        if (mode.isInteraction() || !isEligible(level, player, target, state, state.getBlock(), mode, null)) {
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
        InteractRule rule = resolveInteractRule(level, event.getPos(), player.getMainHandItem());
        if (rule == null) {
            return;
        }
        spreadConnected(level, event.getPos(), INTERACT_BLOCK_LIMIT, rule.includeOrigin(), targets,
                rule.valid());
        if (targets.isEmpty()) {
            return;
        }

        // The player's own click has not been applied yet: this listener sits in front of it, so a
        // later listener can still cancel it. The job waits for that click to take effect and only
        // spreads when it did; a refused first hit must not seed a ring of attempts.
        ACTIVE_INTERACT_JOBS.put(id, new InteractJob(level, player, event.getPos(), rule,
                event.getFace(), new ClickGate(level, rule.kind(), event.getPos(), event.getFace())));
    }

    // Which branch of the interaction mode claimed the click. The job needs to know, because the
    // first-hit check looks at a different position per action and the flint keeps the clicked face.
    enum InteractKind {
        CROP, HOE, SHOVEL, SEED, APPLICATION, FLINT
    }

    // One action rule: the positions it accepts, and whether the clicked block itself belongs to
    // the result. The player's own click always handles the origin, so the job never processes it;
    // the preview still shows it for every action except flint, which the click converts in place.
    record InteractRule(InteractKind kind, Predicate<BlockPos> valid, boolean includeOrigin) {
    }

    // Shared by the server job and the client preview, so the white outline always matches what
    // the job will do. Returns null when the held item is not handled by this mode, or when the
    // aimed block itself cannot be acted on - a chain never starts from a position vanilla refuses.
    static InteractRule resolveInteractRule(Level level, BlockPos origin, ItemStack stack) {
        BlockState originState = level.getBlockState(origin);
        if (isHarvestable(originState) && !BlockBlacklist.contains(originState)) {
            return new InteractRule(InteractKind.CROP, pos -> {
                BlockState state = level.getBlockState(pos);
                return isHarvestable(state) && !BlockBlacklist.contains(state);
            }, true);
        }
        // The Forge tool action, not `instanceof HoeItem`, for the same reason as the axe check
        // below: modded multi-tools may hoe without extending the vanilla class. The aimed block
        // must be tillable itself, otherwise a hoe pointed at stone would till the lawn around it.
        if (stack.canPerformAction(ToolActions.HOE_TILL) && isTillable(level, origin)) {
            return new InteractRule(InteractKind.HOE, pos -> isTillable(level, pos), true);
        }
        // Shovels flatten grass and dirt into paths. The origin must itself be flattenable, so a
        // shovel aimed at gravel or sand does not silently flatten the lawn around it.
        if (stack.canPerformAction(ToolActions.SHOVEL_FLATTEN) && isFlattenable(level, origin)) {
            return new InteractRule(InteractKind.SHOVEL, pos -> isFlattenable(level, pos), true);
        }
        if (stack.getItem() instanceof ItemNameBlockItem && isPlantable(level, origin)) {
            return new InteractRule(InteractKind.SEED, pos -> isPlantable(level, pos), true);
        }
        // Flint and steel lights a campfire or candle in place, ignites TNT through its own block
        // interaction, and otherwise puts fire next to the clicked face. The candidates are blocks
        // of the same kind for that reason, not empty positions where fire could stand. The origin
        // is left out of the result: the player's own click already handled it.
        if (stack.getItem() instanceof FlintAndSteelItem) {
            if (BlockBlacklist.contains(originState)) {
                return null;
            }
            Block originBlock = originState.getBlock();
            return new InteractRule(InteractKind.FLINT, pos -> level.getBlockState(pos).is(originBlock)
                    && !BlockBlacklist.contains(level.getBlockState(pos)), false);
        }
        // An axe strips logs, and Create applies its own recipes to the clicked block; both work on
        // the block the player aimed at, so its neighbours of the same kind are collected. Anything
        // the held item does not apply to is skipped by the vanilla gate inside interactOne.
        if (isAppliedToBlock(level, stack, originState)) {
            Block originBlock = originState.getBlock();
            return new InteractRule(InteractKind.APPLICATION,
                    pos -> level.getBlockState(pos).is(originBlock), true);
        }
        return null;
    }

    // An axe is applied to logs. Create's item application is recipe driven: the loaded recipe list
    // is the authority for which item applies to which block, so vanilla materials (copper ingots),
    // addon materials and datapack additions all work without a per-mod item list.
    private static boolean isAppliedToBlock(Level level, ItemStack stack, BlockState state) {
        if (stack.isEmpty()) {
            return false;
        }
        // The Forge tool action, not `instanceof AxeItem`: multi-tools such as Mekanism's paxel
        // strip logs without extending AxeItem.
        if (stack.canPerformAction(ToolActions.AXE_STRIP)) {
            return state.is(BlockTags.LOGS);
        }
        return matchesItemApplication(level, state, stack);
    }

    private static final ResourceLocation ITEM_APPLICATION_TYPE =
            ResourceLocation.fromNamespaceAndPath("create", "item_application");

    // Item application recipes read as [processed block, applied item], matched against the aimed
    // block's item form and the held stack, the same way Create's own deployer input is built. Both
    // sides hold the full synced recipe list, so the client preview agrees with the server.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean matchesItemApplication(Level level, BlockState state, ItemStack held) {
        RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.get(ITEM_APPLICATION_TYPE);
        if (type == null) {
            return false;
        }
        ItemStack blockItem = new ItemStack(state.getBlock().asItem());
        if (blockItem.isEmpty()) {
            return false;
        }

        List<Recipe<?>> recipes = (List) level.getRecipeManager().getAllRecipesFor((RecipeType) type);
        for (Recipe<?> recipe : recipes) {
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            if (ingredients.size() >= 2
                    && ingredients.get(0).test(blockItem)
                    && ingredients.get(1).test(held)) {
                return true;
            }
        }
        return false;
    }

    // Grows the selection outward from the origin through side, corner and diagonal contact on the
    // same Y plane. Only positions the predicate accepts are collected, and only they can carry the
    // spread further: an already converted, inapplicable or failed position never bridges a gap.
    // The result is ordered the way the old square walk ordered it - Chebyshev ring, then x, then z -
    // so the nearest positions come first and a cut at the limit is stable.
    static void spreadConnected(Level level, BlockPos origin, int limit, boolean includeOrigin,
            List<BlockPos> targets, Predicate<BlockPos> valid) {
        Set<BlockPos> queued = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        List<BlockPos> found = new ArrayList<>();
        frontier.addLast(origin);
        queued.add(origin);
        if (includeOrigin && valid.test(origin)) {
            found.add(origin);
        }
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.removeFirst();
            int radius = Math.max(Math.abs(current.getX() - origin.getX()),
                    Math.abs(current.getZ() - origin.getZ()));
            if (radius >= INTERACT_MAX_RADIUS) {
                continue;
            }
            for (BlockPos offset : CONNECTED_NEIGHBOURS) {
                BlockPos candidate = new BlockPos(current.getX() + offset.getX(), origin.getY(),
                        current.getZ() + offset.getZ());
                if (!queued.add(candidate) || !level.isInWorldBounds(candidate) || !valid.test(candidate)) {
                    continue;
                }
                if (!candidate.equals(origin)) {
                    found.add(candidate);
                }
                frontier.addLast(candidate);
            }
        }

        found.sort(Comparator
                .comparingInt((BlockPos pos) -> Math.max(Math.abs(pos.getX() - origin.getX()),
                        Math.abs(pos.getZ() - origin.getZ())))
                .thenComparingInt(pos -> pos.getX() - origin.getX())
                .thenComparingInt(pos -> pos.getZ() - origin.getZ()));
        for (BlockPos pos : found) {
            if (targets.size() >= limit) {
                break;
            }
            targets.add(pos);
        }
    }

    private static final BlockPos[] CONNECTED_NEIGHBOURS = {
            new BlockPos(0, 0, -1), new BlockPos(1, 0, 0),
            new BlockPos(0, 0, 1), new BlockPos(-1, 0, 0),
            new BlockPos(1, 0, -1), new BlockPos(1, 0, 1),
            new BlockPos(-1, 0, 1), new BlockPos(-1, 0, -1)
    };

    // Selection is deliberately a little looser than vanilla: useOn is still the final gate when
    // the job runs, so a position vanilla would refuse is simply skipped.
    private static boolean isTillable(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.DIRT) && level.getBlockState(pos.above()).isAir();
    }

    // Vanilla's flatten table is not public on this version, so the closest public signal is used:
    // the DIRT tag (a superset that also holds moss_block; the vanilla useOn gate inside
    // interactOne skips the extras). Same trade-off as isTillable next door.
    private static boolean isFlattenable(Level level, BlockPos pos) {
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
        // This runs inside ForgeEventFactory.onPostServerTick, which MinecraftServer calls AFTER it
        // has already stored this tick's duration and updated getAverageTickTime. So the server's
        // own figure never includes any of the work below - a 90 ms chain tick still reads as 4 ms
        // and "20 TPS" there. The interval measured here is the one that actually covers it.
        long now = System.nanoTime();
        ServerTickClock.observe(now);

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
            Block targetBlock, ChainMode mode, HarvestCache harvestCache) {
        if (BlockBlacklist.contains(state) || state.isAir() || !level.mayInteract(player, pos)
                || isUnbreakable(player, level, pos, state)) {
            return false;
        }

        boolean matches = switch (mode) {
            case BLAST_ANY -> true;
            case BLAST_ORES, EXTREME_BLAST_ORES -> isOre(state);
            case BLAST_LOGS -> state.is(BlockTags.LOGS);
            default -> state.getBlock() == targetBlock;
        };
        if (!matches) {
            return false;
        }
        if (player.isCreative()) {
            return true;
        }
        // 1.20.1 fires the HarvestCheck event from Player.hasCorrectToolForDrops, which is the
        // platform equivalent of the harvest check the NeoForge side runs through EventHooks.
        return harvestCache != null
                ? harvestCache.canHarvest(player, state)
                : player.hasCorrectToolForDrops(state);
    }

    // The harvest check fires an event for every call, and a chain runs it on every candidate
    // block. Within one chain the held tool and the block state decide the answer, so each state
    // is asked once; the held item is compared on every lookup, so a tool that breaks mid-chain
    // starts a fresh round of checks.
    private static final class HarvestCache {
        private final Map<BlockState, Boolean> results = new HashMap<>();
        private Item tool;

        private boolean canHarvest(ServerPlayer player, BlockState state) {
            Item held = player.getMainHandItem().getItem();
            if (held != tool) {
                results.clear();
                tool = held;
            }
            Boolean cached = results.get(state);
            if (cached != null) {
                return cached;
            }
            boolean result = player.hasCorrectToolForDrops(state);
            results.put(state, result);
            return result;
        }
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

    // A negative destroy speed is vanilla's "unbreakable" marker (bedrock and friends). Some mods
    // register mineable blocks that way while overriding getDestroyProgress, so the marker alone
    // is not the answer: a block counts as unbreakable only when the player cannot make progress
    // on it right now and no tool tag claims it is mineable.
    private static boolean isUnbreakable(ServerPlayer player, ServerLevel level, BlockPos pos,
            BlockState state) {
        if (state.getDestroySpeed(level, pos) >= 0.0F) {
            return false;
        }
        if (state.getDestroyProgress(player, level, pos) > 0.0F) {
            return false;
        }
        return !state.is(BlockTags.MINEABLE_WITH_PICKAXE) && !state.is(BlockTags.MINEABLE_WITH_AXE)
                && !state.is(BlockTags.MINEABLE_WITH_SHOVEL) && !state.is(BlockTags.MINEABLE_WITH_HOE);
    }

    // Every caller has already run isEligible on this exact state; doing it again here would
    // repeat mayInteract and the harvest check for every single chained block.
    private static boolean breakOne(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        // Use the same server-side entry point as a real player break. This keeps
        // BlockEvent, drops, tool damage, block entities, and client updates in sync.
        ChainJob job = ACTIVE_JOBS.get(player.getUUID());
        CaptureContext previous = CAPTURING_DROPS.get();
        if (job != null) {
            CAPTURING_DROPS.set(job.captureAt(pos, state));
        }
        RemoveObservation.Attempt attempt = RemoveObservation.begin(level, player, pos);
        try {
            boolean noHungerCost = Config.NO_HUNGER_COST.get();
            float exhaustion = noHungerCost ? player.getFoodData().getExhaustionLevel() : 0.0F;
            boolean isJdt = job != null ? job.isJustDireThingsTool() : isJustDireThingsTool(player);
            boolean destroyed = isJdt
                    ? breakWithoutBreakEvent(level, player, pos, state)
                    : player.gameMode.destroyBlock(pos);
            if (destroyed && noHungerCost) {
                player.getFoodData().setExhaustion(exhaustion);
            }
            return succeeds(attempt, destroyed, isJdt, job);
        } finally {
            RemoveObservation.end(attempt);
            if (previous == null) {
                CAPTURING_DROPS.remove();
            } else {
                CAPTURING_DROPS.set(previous);
            }
        }
    }

    // How one chained break's result was decided. The split matters because only the first is
    // evidence of a real removal; the rest still keep old behaviour working but must never be
    // counted toward a "512 really broken" claim.
    private enum Outcome {
        // The block's own removal call reported it removed.
        VERIFIED,
        // The removal call reported it stayed: a real failure.
        REFUSED,
        // The break was refused before any removal (a cancelled event, a game-master block, a tool
        // that broke first). Not a success, and not evidence of anything.
        REFUSED_BEFORE_REMOVAL,
        // No observation was available and the old destroyBlock result was used. Runs only when the
        // patch is not applied; it keeps the mod usable but cannot be called verified.
        FALLBACK_SUCCESS,
        FALLBACK_REFUSED,
        // Just Dire Things: its own removal path already returns the real answer.
        JDT
    }

    // Decides whether one chained break counts as a success, and records how it was decided.
    //
    // destroyBlock's own boolean is not the answer: vanilla's survival branch returns true even when
    // its internal removeBlock reported that the block stayed, so a refused removal could be counted
    // and could seed a new search centre.
    //
    // The three observed states are handled apart:
    // - REMOVED: the main removal call reported the block removed, so it counts as verified.
    // - NOT_REMOVED: the main call reported it stayed. It does not count.
    // - UNOBSERVED: no removal reached this block. That is a normal refusal (a cancelled event, a
    //   game-master block, a tool that broke first) whenever the patch is applied, so it does not
    //   count. "No removal seen" is deliberately NOT read as "the patch is missing": those innocent
    //   causes look identical from here, which is why the patch's own load-time flag decides.
    //
    // When the patch genuinely is not applied, the old destroyBlock result is used instead so the mod
    // still works, but every such block is counted as a fallback and the job tells the player its
    // counts are unverified.
    //
    // The Just Dire Things path bypasses destroyBlock entirely and has its own removal check, so it
    // keeps using its own result and is reported separately.
    private static boolean succeeds(RemoveObservation.Attempt attempt, boolean destroyed,
            boolean isJdt, ChainJob job) {
        RemoveObservation.State removal = RemoveObservation.stateOf(attempt);
        boolean patchApplied = RemoveObservation.isPatchApplied();

        Outcome outcome;
        if (isJdt) {
            outcome = Outcome.JDT;
        } else if (removal == RemoveObservation.State.REMOVED) {
            outcome = Outcome.VERIFIED;
        } else if (removal == RemoveObservation.State.NOT_REMOVED) {
            outcome = Outcome.REFUSED;
        } else if (patchApplied) {
            outcome = Outcome.REFUSED_BEFORE_REMOVAL;
        } else {
            outcome = destroyed ? Outcome.FALLBACK_SUCCESS : Outcome.FALLBACK_REFUSED;
        }

        noteObservationMode(job, patchApplied);
        return switch (outcome) {
            case VERIFIED, FALLBACK_SUCCESS, JDT -> true;
            default -> false;
        };
    }

    // Tells the player, once per server, when chained mining is counting on the fallback path.
    //
    // This is not a diagnostic-only message: the counts a fallback run reports are not verified
    // removals, so the player has to be able to tell that state apart without enabling anything.
    // It fires on the first chained break and never again, so it cannot spam a long session.
    private static void noteObservationMode(ChainJob job, boolean patchApplied) {
        if (patchApplied) {
            if (!fallbackNotice.get() && fallbackNotice.compareAndSet(false, true)) {
                VeinMinerPlus.LOGGER.info("Chained mining is using the removal observation.");
            }
            return;
        }
        if (!fallbackNotice.get() && fallbackNotice.compareAndSet(false, true)) {
            VeinMinerPlus.LOGGER.warn("Chained mining is running without the removal observation; "
                    + "block counts are unverified. Check that veinminerplus.mixins.json loaded.");
            ServerPlayer player = job == null ? null : job.player;
            if (player != null) {
                player.displayClientMessage(
                        Component.translatable("message.veinminerplus.unverified_counts"), false);
            }
        }
    }
    // hand the block to JDT instead: the chain counter would stick at 1 and every
    // attempt would multiply into another 3x3/5x5/7x7 area break. Chained blocks are
    // therefore broken directly, without the event. The manual hit still goes through
    // the normal path, so JDT's own hammer behaviour is untouched.
    private static boolean isJustDireThingsTool(ServerPlayer player) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem());
        return id != null && "justdirethings".equals(id.getNamespace());
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

    // TPS as seen by the guard, derived from the measured tick interval.
    //
    // Deliberately not server.getAverageTickTime(): that value is written before
    // ForgeEventFactory.onPostServerTick runs this mod's chain work, so a tick spending 90 ms in the
    // chain still reads there as a few milliseconds. Judging overload by it would mean the guard
    // never sees the cost it is supposed to guard against.
    private static double getServerTps(ServerLevel level) {
        return ServerTickClock.tps();
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
        private final Map<StackKey, DropCount> counts = new HashMap<>();
        private final StackKey lookupKey = new StackKey();
        private int experience;

        private void add(ItemStack stack) {
            if (stack.isEmpty()) {
                return;
            }
            try {
                lookupKey.set(stack.copyWithCount(1));
                long count = stack.getCount();
                DropCount bucket = counts.get(lookupKey);
                if (bucket == null) {
                    counts.put(new StackKey(lookupKey.template), new DropCount(count));
                } else {
                    bucket.count += count;
                }
            } finally {
                lookupKey.clear();
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
            for (Map.Entry<StackKey, DropCount> entry : counts.entrySet()) {
                // A bucket holds one running total, so it has to be cut back to stack size before
                // it leaves the buffer.
                ItemStack template = entry.getKey().template;
                int maxStackSize = template.getMaxStackSize();
                long remaining = entry.getValue().count;
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
            lookupKey.clear();
            experience = 0;
        }

        private static final class DropCount {
            private long count;

            private DropCount(long count) {
                this.count = count;
            }
        }

        // Identity of a bucket: the item plus every tag, the same pair the vanilla helper
        // compares. Count is deliberately excluded, it is what the bucket accumulates.
        private static final class StackKey {
            private ItemStack template;
            private int hash;

            private StackKey() {
            }

            private StackKey(ItemStack stack) {
                set(stack);
            }

            private void set(ItemStack stack) {
                template = stack;
                hash = 31 * template.getItem().hashCode() + Objects.hashCode(template.getTag());
            }

            private void clear() {
                template = null;
                hash = 0;
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
    private static boolean interactOne(ServerLevel level, ServerPlayer player, BlockPos pos,
            Direction face) {
        BlockState state = level.getBlockState(pos);
        // The network handler checks this before the player's own click, but a chained position
        // never goes through it, so the same permission gate is repeated here.
        if (!level.mayInteract(player, pos)) {
            return false;
        }
        if (isHarvestable(state)) {
            return !BlockBlacklist.contains(state) && harvestOne(level, player, pos, state);
        }

        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return false;
        }

        float exhaustion = player.getFoodData().getExhaustionLevel();
        // The full pipeline, so mods that hook the interact event see every chained position too.
        // The clicked face is the one the player used, which decides where fire lands.
        InteractionResult result = player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false));
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

    // Proof that the player's own click went through before the spread starts. This listener runs
    // ahead of the click itself, so the world as it was beforehand is recorded; a cancel from any
    // later listener and a click vanilla simply refused both leave that world untouched, and the
    // chain then never starts. The watched positions differ per action: a crop loses its age, a hoe
    // turns the block into farmland, a seed fills the air above it, and flint either changes the
    // clicked block or puts fire in front of it.
    private static final class ClickGate {
        private static final int WAIT = 0;
        private static final int PASS = 1;
        private static final int FAIL = 2;

        private final InteractKind kind;
        private final BlockPos origin;
        private final BlockPos above;
        private final BlockPos ahead;
        private final BlockState originState;
        private final BlockState aboveState;
        private final BlockState aheadState;
        private boolean deferred;

        private ClickGate(ServerLevel level, InteractKind kind, BlockPos origin, Direction face) {
            this.kind = kind;
            this.origin = origin;
            this.above = origin.above();
            this.ahead = origin.relative(face);
            this.originState = level.getBlockState(origin);
            this.aboveState = level.getBlockState(above);
            this.aheadState = level.getBlockState(ahead);
        }

        private int state(ServerLevel level) {
            if (kind == InteractKind.FLINT) {
                // A campfire, candle or TNT changes the clicked block; a plain ignition puts fire
                // in the position the clicked face points at.
                if (!level.getBlockState(origin).equals(originState)) {
                    return PASS;
                }
                BlockState now = level.getBlockState(ahead);
                return now.getBlock() instanceof BaseFireBlock && !now.equals(aheadState) ? PASS
                        : unchanged(level);
            }
            // Every other action changes either the clicked block or the space above it.
            if (!level.getBlockState(origin).equals(originState)
                    || !level.getBlockState(above).equals(aboveState)) {
                return PASS;
            }
            return unchanged(level);
        }

        // The click is posted to the server thread with its packet, so the job can look at the world
        // one tick before the click lands. Only a second unchanged look counts as a refusal.
        private int unchanged(ServerLevel level) {
            if (!deferred) {
                deferred = true;
                return WAIT;
            }
            return FAIL;
        }
    }

    // Works through the positions the rule accepts, nearest first, and grows the queue only from
    // positions that actually succeeded. A position vanilla refuses - a protected area, a block
    // another mod claimed, a crop that is not ripe any more - therefore cannot bridge the gap to
    // whatever lies beyond it.
    private static final class InteractJob {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final InteractRule rule;
        private final Direction face;
        private final Deque<BlockPos> pending = new ArrayDeque<>();
        private final Set<BlockPos> queued = new HashSet<>();
        private ClickGate gate;
        private int processedCount;

        private InteractJob(ServerLevel level, ServerPlayer player, BlockPos origin, InteractRule rule,
                Direction face, ClickGate gate) {
            this.level = level;
            this.player = player;
            this.origin = origin;
            this.rule = rule;
            this.face = face;
            this.gate = gate;
        }

        private void tick() {
            if (!HELD_KEYS.contains(player.getUUID()) || player.isRemoved() || player.isSpectator()
                    || player.serverLevel() != level) {
                finish();
                return;
            }

            if (gate != null) {
                int state = gate.state(level);
                if (state == ClickGate.WAIT) {
                    return;
                }
                gate = null;
                if (state == ClickGate.PASS) {
                    // The player's own click applied the action to the aimed block.
                    enqueueAround(origin);
                } else if (rule.kind() != InteractKind.FLINT
                        && interactOne(level, player, origin, face)) {
                    // Vanilla has no right-click action for this block at all (a plain crop), so
                    // the job does that one position itself and only a success may carry the
                    // spread outwards. Flint is excluded: its click already spent the durability,
                    // so retrying here would charge the player twice for the same block.
                    processedCount++;
                    showInteractProgress(player, processedCount);
                    enqueueAround(origin);
                } else {
                    // The origin refused the action too, so nothing is safe to spread from.
                    finish();
                    return;
                }
            }

            int processed = 0;
            int counted = 0;
            while (!pending.isEmpty() && processed < INTERACT_BLOCKS_PER_TICK
                    && HELD_KEYS.contains(player.getUUID()) && holdsTool()) {
                BlockPos pos = pending.pollFirst();
                processed++;
                // Another position may have been processed twice through a corner, and the world
                // may have changed since it was queued; both are re-checked here.
                if (!rule.valid().test(pos)) {
                    continue;
                }
                if (interactOne(level, player, pos, face)) {
                    processedCount++;
                    counted++;
                    if (queued.size() < INTERACT_BLOCK_LIMIT) {
                        enqueueAround(pos);
                    }
                }
            }

            if (counted > 0) {
                showInteractProgress(player, processedCount);
            }

            if (pending.isEmpty() || !holdsTool()) {
                finish();
            }
        }

        // Corner contact counts as connectivity, so all eight neighbours are candidates; the
        // queued set keeps a position from being offered twice. The origin itself is skipped: the
        // player's own click already applied the action there.
        private void enqueueAround(BlockPos center) {
            for (BlockPos offset : CONNECTED_NEIGHBOURS) {
                if (queued.size() >= INTERACT_BLOCK_LIMIT) {
                    return;
                }
                BlockPos candidate = new BlockPos(center.getX() + offset.getX(), origin.getY(),
                        center.getZ() + offset.getZ());
                if (candidate.equals(origin) || Math.max(Math.abs(candidate.getX() - origin.getX()),
                        Math.abs(candidate.getZ() - origin.getZ())) > INTERACT_MAX_RADIUS) {
                    continue;
                }
                if (!level.isInWorldBounds(candidate) || !rule.valid().test(candidate)
                        || !queued.add(candidate)) {
                    continue;
                }
                pending.addLast(candidate);
            }
        }

        // A consumed seed stack, a flint that wore out, or a tool swapped away all end the chain
        // at once: the remaining positions would only be walked to fail one by one. Harvesting
        // never uses up the held item, so crops keep going bare handed.
        private boolean holdsTool() {
            ItemStack held = player.getMainHandItem();
            if (rule.kind() == InteractKind.CROP) {
                return true;
            }
            if (rule.kind() == InteractKind.FLINT) {
                return held.getItem() instanceof FlintAndSteelItem;
            }
            return !held.isEmpty();
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
        // Blast graph search keeps a second, 26-offset queue. Walking one centre's whole
        // radius-R offset list spends nearly all of it on positions already examined, so the
        // near queue supplies neighbours cheaply while the radius-R queue still crosses gaps.
        private final Deque<SearchNode> nearQueue = new ArrayDeque<>();
        private final Deque<BlockPos> sparseCenters = new ArrayDeque<>();
        private final PriorityQueue<BlockPos> sparseTargets;
        // Chunk key -> section y -> matching positions inside that 16x16x16 section.
        // Bucketing matters: a chunk column holds up to 24 sections, while a scan sphere
        // only ever reaches a couple of them.
        // Long-keyed maps stay primitive here; boxed Long keys collided into the same few
        // HashMap buckets near the origin and treeified.
        private final Long2ObjectMap<Map<Integer, List<BlockPos>>> sparseChunkMatches = new Long2ObjectOpenHashMap<>();
        // A chunk is discovered a section at a time. An empty section still needs a marker, otherwise
        // every later centre would rescan it and recreate the cold-centre spike.
        private final Long2ObjectMap<Set<Integer>> sparseChunkScannedSections = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectMap<LevelChunk> loadedChunks = new Long2ObjectOpenHashMap<>();
        private final List<BlockPos> graphOffsets;
        private final int totalLimit;
        private final int areaDepthLimit;
        private final boolean sparseBlast;
        private final DropBuffer drops;
        private final CaptureContext capture;
        private final HarvestCache harvestCache = new HarvestCache();
        private int brokenCount = 1;
        private int areaDepth;
        private int areaIndex;
        private int criticalTpsTicks;
        // Set once the job has ended so a repeated finish() cannot flush the drops twice.
        private boolean ended;
        private Item cachedHeldItem;
        private boolean cachedHeldItemIsJdt;

        private ChainJob(ServerLevel level, ServerPlayer player, BlockPos origin, Block targetBlock,
                Direction face, ChainMode mode, DropBuffer drops, List<BlockPos> seeds) {
            this.level = level;
            this.player = player;
            this.origin = origin;
            this.targetBlock = targetBlock;
            this.face = face;
            this.mode = mode;
            this.drops = drops;
            this.capture = new CaptureContext(drops);
            this.totalLimit = mode.isBlast() ? Config.MAX_BLAST_BLOCKS.get() : Config.MAX_NORMAL_BLOCKS.get();
            this.areaDepthLimit = Config.MAX_NORMAL_BLOCKS.get();
            this.graphOffsets = mode.isBlast()
                    ? BLAST_OFFSETS.computeIfAbsent(Config.BLAST_SEARCH_DISTANCE.get(), ChainEvents::createBlastOffsets)
                    : NORMAL_OFFSETS;
            BlockState targetState = targetBlock.defaultBlockState();
            // An extreme blast always scans by chunk: one palette pass beats the graph's
            // per-node offset walks for every block type, not just the scattered veins.
            this.sparseBlast = mode.isExtremeBlast()
                    || mode == ChainMode.BLAST_ORES
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
                        // Seeds feed both queues: the near queue drives fast local supply, the
                        // radius-R queue keeps gap-crossing reachable.
                        if (mode.isBlast()) {
                            nearQueue.addLast(new SearchNode(start, 0));
                        }
                        frontier.addLast(new SearchNode(start, 0));
                    }
                }
            }
        }

        private void tick() {
            if (!HELD_KEYS.contains(player.getUUID()) || player.isRemoved() || player.isSpectator()
                    || player.serverLevel() != level) {
                // Outside the normal tick path: no further tick will come, so end and settle now.
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
            int checkLimit = mode.isBlast() ? effectiveBlastSearchChecks() : SEARCH_CHECKS_PER_TICK;
            int breakLimit = mode.isBlast() ? effectiveBlastBreakLimit()
                    : Config.MAX_NORMAL_BLOCKS_PER_TICK.get();

            if (!mode.isBlast()) {
                tickGraphSingleQueue(checkLimit, breakLimit);
                return;
            }

            GraphTick tick = new GraphTick(breakLimit);

            // Stage 1: a small radius-R quota so the far queue is never starved.
            tick.runQueue(frontier, graphOffsets, Math.min(checkLimit / 8, 2048), false);

            // Stage 2/3 interleaved: the near queue is always drained first; only when it runs
            // dry does the far queue advance, and it yields back as soon as a break feeds the
            // near queue again. The loop ends when the budget, the break limit or both queues
            // are spent.
            while (tick.checks < checkLimit && tick.breaks < breakLimit && HELD_KEYS.contains(player.getUUID())
                    && brokenCount < totalLimit) {
                int remaining = checkLimit - tick.checks;
                if (!nearQueue.isEmpty()) {
                    tick.runQueue(nearQueue, NORMAL_OFFSETS, remaining, false);
                } else if (!frontier.isEmpty()) {
                    tick.runQueue(frontier, graphOffsets, remaining, true);
                } else {
                    break;
                }
            }

            tick.complete(tick.isExhausted());
        }

        // Non-blast modes: the original single-queue walk, unchanged.
        private void tickGraphSingleQueue(int checkLimit, int breakLimit) {
            int checks = 0;
            int breaks = 0;
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
                    if (isEligible(level, player, candidate, state, targetBlock, mode, harvestCache)
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

        // One tick's worth of graph work. Both queues share the task-level `examined` set and the
        // per-tick break/check counters, so a duplicate keeps costing budget no matter which
        // queue produced it.
        private final class GraphTick {
            private final int breakLimit;
            private int checks;
            private int breaks;

            private GraphTick(int breakLimit) {
                this.breakLimit = breakLimit;
            }

            private boolean canContinue() {
                return brokenCount < totalLimit && HELD_KEYS.contains(player.getUUID());
            }

            // stopOnNearGain: leave as soon as a break puts new work in the near queue, so the
            // cheap queue regains priority instead of the far queue running to the end.
            private void runQueue(Deque<SearchNode> queue, List<BlockPos> offsets, int budget,
                    boolean stopOnNearGain) {
                if (budget <= 0) {
                    return;
                }
                int startChecks = checks;
                while (checks - startChecks < budget && breaks < breakLimit && canContinue()
                        && !queue.isEmpty()) {
                    SearchNode node = queue.removeFirst();
                    int centerChecks = 0;
                    while (centerChecks < SEARCH_CHECKS_PER_CENTER
                            && checks - startChecks < budget
                            && breaks < breakLimit
                            && canContinue()
                            && node.nextOffset() < offsets.size()) {
                        BlockPos offset = offsets.get(node.nextOffset());
                        node.advance();
                        BlockPos candidate = node.position().offset(offset.getX(), offset.getY(), offset.getZ());
                        checks++;
                        centerChecks++;
                        if (!examined.add(candidate)) {
                            continue;
                        }
                        if (!level.isInWorldBounds(candidate)) {
                            continue;
                        }

                        BlockState state = getBlockStateForSearch(level, candidate, loadedChunks);
                        if (isEligible(level, player, candidate, state, targetBlock, mode, harvestCache)
                                && breakOne(level, player, candidate, state)) {
                            brokenCount++;
                            breaks++;
                            // A successful break is the only thing that may become a new centre.
                            // Both queues get it so the near queue supplies local work and the
                            // radius-R queue keeps gap-crossing reachable.
                            nearQueue.addLast(new SearchNode(candidate, 0));
                            frontier.addLast(new SearchNode(candidate, 0));
                            if (stopOnNearGain) {
                                if (node.nextOffset() < offsets.size()) {
                                    queue.addFirst(node);
                                }
                                return;
                            }
                        }
                    }
                    // Requeue with the exact cursor reached so a paused centre resumes next tick.
                    if (node.nextOffset() < offsets.size()) {
                        queue.addLast(node);
                    }
                }
            }

            private boolean isExhausted() {
                return frontier.isEmpty() && nearQueue.isEmpty();
            }

            private void complete(boolean exhausted) {
                if (breaks > 0) {
                    showProgress(player, brokenCount);
                }

                if (!HELD_KEYS.contains(player.getUUID()) || exhausted || brokenCount >= totalLimit) {
                    finish();
                }
            }
        }

        private void tickArea() {
            int size = mode == ChainMode.AREA_1X1 ? 1 : 3;
            int planeSize = size * size;
            int breaks = 0;
            // Area modes share the normal per-tick budget on purpose: one knob tunes both speeds.
            int breakLimit = Config.MAX_NORMAL_BLOCKS_PER_TICK.get();
            while (breaks < breakLimit && areaDepth <= areaDepthLimit
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
                if (isEligible(level, player, candidate, state, targetBlock, mode, harvestCache)
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
            boolean extreme = mode.isExtremeBlast();
            int breaks = 0;
            int scannedCenters = 0;
            // Only the scan itself is timed here. breakOne and the drop capture are deliberately
            // excluded: the soft budget constrains scanning alone.
            long scanNanos = 0L;
            // An extreme blast is deliberately unthrottled: the whole chain finishes inside this tick.
            int breakLimit = extreme ? Integer.MAX_VALUE : effectiveBlastBreakLimit();
            // Every successful break adds a centre, so with the old 256 cap against a 512 break
            // limit the backlog could only grow. Two centres per allowed break leaves room for
            // empty scans and backlog drain; the 1024 ceiling keeps the worst case bounded.
            int centerLimit = extreme ? Integer.MAX_VALUE
                    : Math.min(SPARSE_CENTER_LIMIT, Math.max(1, breakLimit * SPARSE_CENTERS_PER_BREAK));
            // Scanning is the expensive part and its cost per centre grows with the search
            // distance, so the centre count alone is not a sufficient bound. This soft budget
            // covers scanSparseCenter only; breakOne and drops are excluded.
            long scanBudgetNanos = extreme ? Long.MAX_VALUE / 4 : sparseScanBudgetNanos();
            boolean scanBudgetSpent = false;
            while (breaks < breakLimit && brokenCount < totalLimit && HELD_KEYS.contains(player.getUUID())) {
                while (sparseTargets.isEmpty() && !sparseCenters.isEmpty() && scannedCenters < centerLimit
                        && !scanBudgetSpent) {
                    long scanStarted = System.nanoTime();
                    scanSparseCenter(sparseCenters.removeFirst());
                    scanNanos += System.nanoTime() - scanStarted;
                    scannedCenters++;
                    // Checked after each centre: a single centre can still overshoot, the budget
                    // only bounds how many are started.
                    if (scanNanos >= scanBudgetNanos) {
                        scanBudgetSpent = true;
                    }
                }
                if (sparseTargets.isEmpty()) {
                    break;
                }

                BlockPos candidate = sparseTargets.poll();
                BlockState state = getBlockStateForSearch(level, candidate, loadedChunks);
                if (isEligible(level, player, candidate, state, targetBlock, mode, harvestCache)
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
            // The extreme blasts deliberately run without the TPS guard.
            if (!mode.isBlast() || mode.isExtremeBlast()) {
                return true;
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
            return true;
        }

        // Back off before the critical threshold so the chain can recover instead of pausing after
        // it pushes the measured tick interval below the configured safety floor.
        private int effectiveBlastBreakLimit() {
            return BlastBudget.scale(Config.MAX_BLAST_BLOCKS_PER_TICK.get(), getServerTps(level));
        }

        private int effectiveBlastSearchChecks() {
            return BlastBudget.scale(SEARCH_CHECKS_PER_TICK, getServerTps(level));
        }

        private long sparseScanBudgetNanos() {
            return BlastBudget.scale(SPARSE_SCAN_BUDGET_NANOS, getServerTps(level));
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
                        sections = new HashMap<>();
                        sparseChunkMatches.put(key, sections);
                    }
                    Set<Integer> scannedSections = sparseChunkScannedSections.get(key);
                    if (scannedSections == null) {
                        scannedSections = new HashSet<>();
                        sparseChunkScannedSections.put(key, scannedSections);
                    }

                    LevelChunk chunk = null;
                    for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                        // Decide the whole section from its coordinate box before touching any
                        // position. An outside section is deliberately left unscanned: a later
                        // centre may reach it, and the scanned marker must mean "fully inspected".
                        SparseSectionGeometry.Reach reach = SparseSectionGeometry.classify(
                                center.getX(), center.getY(), center.getZ(),
                                chunkX, sectionY, chunkZ, maxSquaredDistance);
                        if (reach == SparseSectionGeometry.Reach.OUTSIDE) {
                            continue;
                        }

                        if (scannedSections.add(sectionY)) {
                            if (chunk == null) {
                                chunk = loadedChunks.get(key);
                                if (chunk == null) {
                                    // Resident chunks only: a blocking getChunk generates terrain inside
                                    // this tick, and against ungenerated ground the closure keeps finding
                                    // new ore in what it just generated - one tick that never ends.
                                    chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                                    if (chunk == null) {
                                        scannedSections.remove(sectionY);
                                        continue;
                                    }
                                    loadedChunks.put(key, chunk);
                                }
                            }
                            List<BlockPos> built = buildSparseSectionMatches(chunk, chunkX, sectionY);
                            if (!built.isEmpty()) {
                                sections.put(sectionY, built);
                            }
                        }

                        List<BlockPos> matches = sections.get(sectionY);
                        if (matches == null) {
                            continue;
                        }

                        boolean everyPositionInside = reach == SparseSectionGeometry.Reach.FULLY_INSIDE;

                        // Positions outside the sphere stay in the bucket, a later centre
                        // can still reach them. Positions that made it in are enqueued once
                        // and never looked at again, so they are dropped from the bucket.
                        for (int index = 0; index < matches.size();) {
                            BlockPos pos = matches.get(index);
                            if (!everyPositionInside
                                    && squaredDistance(center, pos) > maxSquaredDistance) {
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

        /** Builds only one 16-cube bucket; other sections remain discoverable for later centres. */
        private List<BlockPos> buildSparseSectionMatches(LevelChunk chunk, int chunkX, int sectionY) {
            int sectionIndex = level.getSectionIndexFromSectionY(sectionY);
            LevelChunkSection[] chunkSections = chunk.getSections();
            if (sectionIndex < 0 || sectionIndex >= chunkSections.length) {
                return Collections.emptyList();
            }
            LevelChunkSection section = chunkSections[sectionIndex];
            if (section.hasOnlyAir() || !section.maybeHas(this::matchesSparseState)) {
                return Collections.emptyList();
            }

            List<BlockPos> matches = new ArrayList<>();
            int baseX = chunkX << 4;
            int baseY = sectionY << 4;
            int baseZ = chunk.getPos().z << 4;
            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        if (matchesSparseState(section.getBlockState(localX, localY, localZ))) {
                            matches.add(new BlockPos(baseX + localX, baseY + localY, baseZ + localZ));
                        }
                    }
                }
            }
            return matches;
        }

        private boolean matchesSparseState(BlockState state) {
            if (BlockBlacklist.contains(state)) {
                return false;
            }
            return switch (mode) {
                case BLAST_ORES, EXTREME_BLAST_ORES -> isOre(state);
                case BLAST_LOGS -> state.is(BlockTags.LOGS);
                default -> state.is(targetBlock);
            };
        }

        private CaptureContext captureAt(BlockPos pos, BlockState state) {
            capture.set(pos.immutable(), state.getBlock() instanceof DoublePlantBlock);
            return capture;
        }

        private boolean isJustDireThingsTool() {
            Item held = player.getMainHandItem().getItem();
            if (held != cachedHeldItem) {
                cachedHeldItem = held;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(held);
                cachedHeldItemIsJdt = id != null && "justdirethings".equals(id.getNamespace());
            }
            return cachedHeldItemIsJdt;
        }

        /**
         * Ends the job and settles the drops.
         *
         * A job can end inside its own last tick - an extreme blast finishes the whole chain in one
         * tick - so the guard here matters: {@code ended} stops a second call from flushing the same
         * drops again.
         */
        private void finish() {
            if (ended) {
                return;
            }
            ended = true;
            drops.flush(player.serverLevel(), player);
            ACTIVE_JOBS.remove(player.getUUID(), this);
        }

    }

    private static BlockState getBlockStateForSearch(ServerLevel level, BlockPos pos,
            Long2ObjectMap<LevelChunk> loadedChunks) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = chunkKey(chunkX, chunkZ);
        LevelChunk chunk = loadedChunks.get(key);
        if (chunk == null) {
            chunk = level.getChunk(chunkX, chunkZ);
            loadedChunks.put(key, chunk);
        }
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

    // Mutable and reused: a chain owns one instance and points it at the block being broken now.
    private static final class CaptureContext {
        private final DropBuffer drops;
        private BlockPos origin;
        private boolean doublePlant;

        private CaptureContext(DropBuffer drops) {
            this.drops = drops;
        }

        private void set(BlockPos origin, boolean doublePlant) {
            this.origin = origin;
            this.doublePlant = doublePlant;
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
    }
}
