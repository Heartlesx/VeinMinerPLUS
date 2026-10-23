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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
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
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class ChainEvents {
    // Work is deliberately bounded so one large blast cannot monopolize the server thread.
    private static final int SEARCH_CHECKS_PER_TICK = 16384;
    private static final int SEARCH_CHECKS_PER_CENTER = 256;
    private static final int SPARSE_SCANS_PER_TICK = 256;
    private static final int PENDING_SEED_LIMIT = 256;
    // The interaction mode grows a square outward from the clicked block, one ring per step,
    // and only keeps positions the held item could actually be used on. Vanilla's useOn stays
    // the final gate when a position is processed.
    private static final int INTERACT_BLOCK_LIMIT = 1024;
    private static final int INTERACT_MAX_RADIUS = 16;
    private static final int INTERACT_BLOCKS_PER_TICK = 64;
    // The preview shows exactly what the server would handle, so both use the same cap.
    static final int INTERACT_PREVIEW_LIMIT = INTERACT_BLOCK_LIMIT;
    private static final double TPS_WARNING_THRESHOLD = 12.0D;
    private static final double TPS_CRITICAL_THRESHOLD = 8.0D;
    private static final double TPS_RECOVERY_THRESHOLD = 16.0D;
    private static final int TPS_CRITICAL_TICKS_TO_STOP = 20;
    private static final int TPS_WARNING_COOLDOWN_TICKS = 100;

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
    private static final Map<UUID, HandledBreak> HANDLED_BREAKS = new HashMap<>();
    private static final Map<UUID, PendingFireStart> PENDING_FIRE_STARTS = new HashMap<>();
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
        HANDLED_BREAKS.remove(id);
        PENDING_FIRE_STARTS.remove(id);
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player
                && event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) {
            LAST_BREAK_FACES.put(player.getUUID(), new BreakFace(event.getPos().immutable(), event.getFace()));
            HANDLED_BREAKS.remove(player.getUUID());
        }
    }

    // Another mod may perform the break inside its own listener and cancel the event afterwards
    // (Useless Mod's multi-tool does exactly this). A cancellation alone does not prove that
    // nothing was broken, so the resulting block state is the only reliable signal: once the
    // block is gone the break really happened and the chain can start from it, while a block
    // that is still in place means a protection or a refusal cancelled the break.
    @SubscribeEvent(receiveCanceled = true)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)
                || !HELD_KEYS.contains(player.getUUID())
                || ACTIVE_JOBS.containsKey(player.getUUID())
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        UUID id = player.getUUID();
        BlockPos target = event.getPos().immutable();
        BlockState state = event.getState();
        if (event.isCanceled() && level.getBlockState(target).is(state.getBlock())) {
            return;
        }

        // The drop event of a normal break fires after this handler, while a mod that breaks the
        // block inside its own listener fires it first. Record which order this break is in, so a
        // fallback buffer is only created for the latter.
        if (!PENDING_DROPS.containsKey(id)) {
            HANDLED_BREAKS.put(id, new HandledBreak(target, level.getGameTime()));
        }

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
            // onBlockDrops may have captured this break's drops already; they still have to go
            // somewhere even though no chain starts.
            discardPendingDrops(level, player);
            return;
        }

        BreakFace breakFace = LAST_BREAK_FACES.get(id);
        Direction face = breakFace != null && breakFace.pos().equals(target) ? breakFace.face() : Direction.UP;
        // A drop event may already have created the primary buffer (see onBlockDrops), so keep the
        // drops it captured instead of starting a fresh one.
        DropBuffer pending = PENDING_DROPS.get(id);
        DropBuffer drops;
        if (pending != null) {
            drops = pending;
        } else {
            drops = new DropBuffer();
            PENDING_DROPS.put(id, drops);
            PENDING_DROP_ORIGINS.put(id, target);
            PENDING_DOUBLE_PLANTS.put(id, state.getBlock() instanceof DoublePlantBlock);
        }
        List<BlockPos> pendingSeeds = new ArrayList<>();
        pendingSeeds.add(target);
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
            UUID id = player.getUUID();
            drops = PENDING_DROPS.get(id);
            if (drops == null) {
                ChainJob job = ACTIVE_JOBS.get(id);
                drops = job == null ? null : job.drops;
            }
            if (drops == null && HELD_KEYS.contains(id)) {
                HandledBreak handled = HANDLED_BREAKS.get(id);
                if (handled != null && handled.gameTime() == event.getLevel().getGameTime()
                        && handled.pos().equals(event.getPos())) {
                    // Our break handler already ran for this break, so this is a break it chose
                    // not to chain. Leave the drops in the world instead of buffering them.
                    HANDLED_BREAKS.remove(id);
                } else {
                    // Some mods (Useless Mod's multi-tool) break the block inside their own
                    // HIGHEST BreakEvent listener, so this drop event fires before our
                    // lower-priority break handler has created the primary buffer. Create it
                    // here; onBlockBreak reuses it.
                    drops = new DropBuffer();
                    PENDING_DROPS.put(id, drops);
                    PENDING_DROP_ORIGINS.put(id, event.getPos().immutable());
                    PENDING_DOUBLE_PLANTS.put(id, event.getState().getBlock() instanceof DoublePlantBlock);
                }
            }
        }

        CaptureContext capture = CAPTURING_DROPS.get();
        if (drops == null && capture != null
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

        List<ItemStack> stacks = new ArrayList<>(event.getDrops().size());
        for (ItemEntity item : event.getDrops()) {
            stacks.add(item.getItem());
        }
        if (event.getBreaker() instanceof ServerPlayer breaker) {
            // The tool may post-process its drops after they have been generated (Useless Mod's
            // auto-smelt does), and capturing here would skip that step. Its own helper decides
            // whether the held tool asks for the conversion.
            stacks = UselessModCompat.smeltDrops(event.getLevel(), stacks, breaker.getMainHandItem());
        }
        for (ItemStack stack : stacks) {
            drops.add(stack);
        }
        event.getDrops().clear();
        drops.addExperience(event.getDroppedExperience());
        event.setDroppedExperience(0);
    }

    // Drops captured for a primary break that turned out not to start a chain must not stay in the
    // buffer: flush them the same way the chain would, then forget the entry.
    private static void discardPendingDrops(ServerLevel level, ServerPlayer player) {
        UUID id = player.getUUID();
        DropBuffer drops = PENDING_DROPS.remove(id);
        PENDING_DROP_ORIGINS.remove(id);
        PENDING_DOUBLE_PLANTS.remove(id);
        if (drops != null) {
            drops.flush(level, player);
        }
    }

    private static boolean matchesDropPosition(BlockPos dropPosition, BlockPos origin, boolean doublePlant) {
        return dropPosition.equals(origin)
                || doublePlant && (dropPosition.equals(origin.above()) || dropPosition.equals(origin.below()));
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
                || PENDING_FIRE_STARTS.containsKey(id)
                || player.isSpectator()) {
            return;
        }

        ItemStack held = player.getMainHandItem();
        InteractAction action = interactAction(level, event.getPos(), held);
        if (action == null) {
            return;
        }

        // A flint and steel fires this event before the vanilla action runs, and only that action
        // decides whether the click succeeded, so the job waits for the end of the tick and starts
        // only when the aimed block really changed.
        if (held.is(Items.FLINT_AND_STEEL)) {
            PENDING_FIRE_STARTS.put(id, new PendingFireStart(level, event.getPos().immutable(),
                    event.getFace(), level.getBlockState(event.getPos()), action));
            return;
        }
        ACTIVE_INTERACT_JOBS.put(id, new InteractJob(level, player, event.getPos().immutable(),
                event.getFace(), action));
    }

    // Runs after the player packet that performed the click has been handled, so the aimed block has
    // already been acted on by then. A click the game refused leaves the block untouched and no job
    // starts.
    private void resolvePendingFireStart(ServerPlayer player) {
        UUID id = player.getUUID();
        PendingFireStart pending = PENDING_FIRE_STARTS.remove(id);
        if (pending == null || pending.level() != player.serverLevel() || player.isRemoved()
                || player.isSpectator() || !HELD_KEYS.contains(id)) {
            return;
        }
        // A click that used up the last durability leaves nothing to spread with.
        if (!player.getMainHandItem().is(Items.FLINT_AND_STEEL)) {
            return;
        }
        // Lit campfires, candles and primed TNT change the block itself, plain ignition places fire
        // against the clicked face. Either counts; an unchanged world means the game refused.
        boolean changed = !pending.state().equals(pending.level().getBlockState(pending.pos()));
        boolean firePlaced = pending.level().getBlockState(pending.pos().relative(pending.face()))
                .getBlock() instanceof BaseFireBlock;
        if (changed || firePlaced) {
            ACTIVE_INTERACT_JOBS.put(id, new InteractJob(pending.level(), player, pending.pos(),
                    pending.face(), pending.action()));
        }
    }

    // The action the interaction mode would apply, described once so the client preview and the
    // server job select from the same rules. Null when the held item is not handled by this mode, or
    // when the aimed block is not something this action applies to: an unsuitable start spreads
    // nothing.
    record InteractAction(Predicate<BlockPos> valid, boolean originCounts, boolean harvest) {
    }

    static InteractAction interactAction(Level level, BlockPos origin, ItemStack stack) {
        BlockState originState = level.getBlockState(origin);
        if (isHarvestable(originState) && !BlockBlacklist.contains(originState)) {
            return new InteractAction(pos -> {
                BlockState state = level.getBlockState(pos);
                return isHarvestable(state) && !BlockBlacklist.contains(state);
            }, true, true);
        }
        // The NeoForge ability, not `instanceof HoeItem`, for the same reason as the axe check
        // below: modded multi-tools may hoe without extending the vanilla class.
        if (stack.canPerformAction(ItemAbilities.HOE_TILL) && isTillable(level, origin)) {
            return new InteractAction(pos -> isTillable(level, pos), true, false);
        }
        // Shovels flatten grass and dirt into paths. The origin must itself be flattenable, so a
        // shovel aimed at gravel or sand does not silently flatten the lawn around it.
        if (stack.canPerformAction(ItemAbilities.SHOVEL_FLATTEN) && isFlattenable(level, origin)) {
            return new InteractAction(pos -> isFlattenable(level, pos), true, false);
        }
        if (stack.getItem() instanceof ItemNameBlockItem && isPlantable(level, origin)) {
            return new InteractAction(pos -> isPlantable(level, pos), true, false);
        }
        // An axe strips logs, and Create applies its own recipes to the clicked block; both work on
        // the block the player aimed at, so its neighbours of the same kind are collected. Anything
        // the held item does not apply to is skipped by the vanilla gate inside interactOne.
        if (isAppliedToBlock(level, stack, originState)) {
            Block originBlock = originState.getBlock();
            return new InteractAction(pos -> level.getBlockState(pos).is(originBlock), true, false);
        }
        // A flint and steel lights the block it is aimed at. Only the vanilla item is handled: the
        // vanilla gate inside interactOne stays the final authority, so a position the item would
        // refuse simply does nothing. Neighbours of the same block are collected without checking
        // whether fire could be placed next to them: TNT and other blocks ignite inside their own
        // right-click handler, where an adjacent free space is not required. The origin is skipped
        // because the click that started the chain already ran for it, but it still seeds the search.
        if (stack.is(Items.FLINT_AND_STEEL)) {
            Block originBlock = originState.getBlock();
            return new InteractAction(pos -> level.getBlockState(pos).is(originBlock), false, false);
        }
        return null;
    }

    static boolean collectInteractTargets(Level level, BlockPos origin, ItemStack stack, int limit,
            List<BlockPos> targets) {
        InteractAction action = interactAction(level, origin, stack);
        if (action == null) {
            return false;
        }
        InteractArea.collect(origin, INTERACT_MAX_RADIUS, limit, action.originCounts(),
                pos -> level.isInWorldBounds(pos) && action.valid().test(pos), targets);
        return true;
    }

    // An axe is applied to logs. Create's item application is recipe driven: the loaded recipe list
    // is the authority for which item applies to which block, so vanilla materials (copper ingots),
    // addon materials and datapack additions all work without a per-mod item list.
    private static boolean isAppliedToBlock(Level level, ItemStack stack, BlockState state) {
        if (stack.isEmpty()) {
            return false;
        }
        // The NeoForge ability, not `instanceof AxeItem`: multi-tools such as Mekanism's
        // paxel strip logs without extending AxeItem.
        if (stack.canPerformAction(ItemAbilities.AXE_STRIP)) {
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

        List<RecipeHolder<Recipe<?>>> recipes =
                (List) level.getRecipeManager().getAllRecipesFor((RecipeType) type);
        for (RecipeHolder<Recipe<?>> holder : recipes) {
            NonNullList<Ingredient> ingredients = holder.value().getIngredients();
            if (ingredients.size() >= 2
                    && ingredients.get(0).test(blockItem)
                    && ingredients.get(1).test(held)) {
                return true;
            }
        }
        return false;
    }

    // Selection is deliberately a little looser than vanilla: useOn is still the final gate when
    // the job runs, so a position vanilla would refuse is simply skipped.
    private static boolean isTillable(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.DIRT) && level.getBlockState(pos.above()).isAir();
    }

    // Vanilla flattening only applies to the shovel pathing table and needs air above the block;
    // the public helper keeps the list from being copied here.
    private static boolean isFlattenable(Level level, BlockPos pos) {
        return ShovelItem.getShovelPathingState(level.getBlockState(pos)) != null
                && level.getBlockState(pos.above()).isAir();
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
    public void onServerTick(ServerTickEvent.Post event) {
        for (Player player : event.getServer().getPlayerList().getPlayers()) {
            resolvePendingFireStart((ServerPlayer) player);
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
        return harvestCache != null
                ? harvestCache.canHarvest(player, level, pos, state)
                : EventHooks.doPlayerHarvestCheck(player, state, level, pos);
    }

    // The harvest check fires an event for every call, and a chain runs it on every candidate
    // block. Within one chain the held tool and the block state decide the answer, so each state
    // is asked once; the held item is compared on every lookup, so a tool that breaks mid-chain
    // starts a fresh round of checks.
    private static final class HarvestCache {
        private final Map<BlockState, Boolean> results = new HashMap<>();
        private Item tool;

        private boolean canHarvest(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
            Item held = player.getMainHandItem().getItem();
            if (held != tool) {
                results.clear();
                tool = held;
            }
            Boolean cached = results.get(state);
            if (cached != null) {
                return cached;
            }
            boolean result = EventHooks.doPlayerHarvestCheck(player, state, level, pos);
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
        if (state.is(ORE_BLOCKS) || state.is(COMMON_ORE_BLOCKS)
                || state.is(ALLTHEMODIUM_ORE_BLOCKS)
                || state.is(VIBRANIUM_ORE_BLOCKS)
                || state.is(UNOBTAINIUM_ORE_BLOCKS)) {
            return true;
        }

        // Some mod packs do not add their ores to the shared ore tags.
        return BuiltInRegistries.BLOCK.getKey(block).getPath().endsWith("_ore");
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
        if (job != null) {
            // The context stays set for the rest of the chain and is removed when the job ends.
            // Every break re-points it at its own block first, so interleaved jobs stay correct.
            CAPTURING_DROPS.set(job.captureAt(pos, state));
        }
        float exhaustion = player.getFoodData().getExhaustionLevel();
        boolean destroyed = isJustDireThingsTool(player)
                ? breakWithoutBreakEvent(level, player, pos, state)
                : player.gameMode.destroyBlock(pos);
        if (!destroyed && !level.getBlockState(pos).is(state.getBlock())) {
            // destroyBlock reports failure when another listener cancelled the BreakEvent after
            // removing the block itself, so the world is the only reliable success signal.
            destroyed = true;
        }
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

    private static void showInteractProgress(ServerPlayer player, int count) {
        player.displayClientMessage(Component.translatable("message.veinminerplus.interact_progress", count), true);
    }

    private static double getServerTps(ServerLevel level) {
        MinecraftServer server = level.getServer();
        long tickTimeNanos = server.getAverageTickTimeNanos();
        return tickTimeNanos <= 0L ? 20.0D : Math.min(20.0D, 1_000_000_000.0D / tickTimeNanos);
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
            if (Config.STORAGE_BINDING.getAsBoolean()) {
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

        // Identity of a bucket: the item plus every data component, the same pair the vanilla
        // helper compares. Count is deliberately excluded, it is what the bucket accumulates.
        private static final class StackKey {
            private final ItemStack template;
            private final int hash;

            private StackKey(ItemStack stack) {
                // Only the item and its components identify the bucket; the count is what the
                // bucket accumulates, and flush() sets its own count on every copy it makes.
                this.template = stack;
                this.hash = ItemStack.hashItemAndComponents(stack);
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof StackKey key
                        && ItemStack.isSameItemSameComponents(template, key.template);
            }
        }
    }

    // Runs the same server-side right click a manual one would reach, so tool damage, sounds, mod
    // interactions (Create's item application) and the actual conversion stay with vanilla and other
    // mods. A mature crop is harvested directly instead, because the pack's right click harvesting
    // lives in FTB Ultimine rather than in the interact pipeline.
    private static boolean interactOne(ServerLevel level, ServerPlayer player, BlockPos pos,
            Direction face) {
        // The chained positions never pass through the packet handler that normally checks this.
        if (!level.mayInteract(player, pos)) {
            return false;
        }

        BlockState state = level.getBlockState(pos);
        if (isHarvestable(state)) {
            return !BlockBlacklist.contains(state) && harvestOne(level, player, pos, state);
        }

        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return false;
        }

        float exhaustion = player.getFoodData().getExhaustionLevel();
        // The full pipeline, so mods that hook the interact event see every chained position too.
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

    // Spreads one interaction over the area, one step at a time. A position is only used to reach
    // further positions after the game actually applied the action to it, so a position that refused
    // the action cannot carry the chain across a gap.
    private static final class InteractJob {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final boolean harvest;
        private final Direction face;
        private final ItemStack tool;
        private final Predicate<BlockPos> valid;
        // Nearer positions are handled first; a position reached later is queued after everything
        // already waiting, which keeps the radius ordering of the old selection.
        private final PriorityQueue<BlockPos> frontier;
        private final Set<BlockPos> seen = new HashSet<>();
        private int processedCount;

        private InteractJob(ServerLevel level, ServerPlayer player, BlockPos origin, Direction face,
                InteractAction action) {
            this.level = level;
            this.player = player;
            this.harvest = action.harvest();
            this.face = face;
            this.tool = player.getMainHandItem().copy();
            this.valid = action.valid();
            this.frontier = new PriorityQueue<>(nearFirst(origin));
            this.seen.add(origin);
            // The click itself already ran for the aimed block, unless this action is one where the
            // aimed block is part of the area.
            if (action.originCounts()) {
                addNeighbours(origin);
            } else {
                frontier.addAll(collectNeighbours(origin));
            }
        }

        private static Comparator<BlockPos> nearFirst(BlockPos origin) {
            return Comparator
                    .comparingInt((BlockPos pos) -> Math.max(Math.abs(pos.getX() - origin.getX()),
                            Math.abs(pos.getZ() - origin.getZ())))
                    .thenComparingInt(pos -> pos.getZ() - origin.getZ())
                    .thenComparingInt(pos -> pos.getX() - origin.getX());
        }

        private void addNeighbours(BlockPos pos) {
            frontier.addAll(collectNeighbours(pos));
        }

        private List<BlockPos> collectNeighbours(BlockPos pos) {
            List<BlockPos> added = new ArrayList<>(8);
            for (BlockPos neighbour : InteractArea.neighbours(pos)) {
                if (neighbour.getY() != pos.getY() || !seen.add(neighbour)
                        || !level.isInWorldBounds(neighbour) || !valid.test(neighbour)) {
                    continue;
                }
                added.add(neighbour);
            }
            return added;
        }

        private void tick() {
            if (!HELD_KEYS.contains(player.getUUID()) || player.isRemoved() || player.isSpectator()
                    || player.serverLevel() != level) {
                finish();
                return;
            }
            // A tool that broke or was swapped out ends the job instead of acting with something the
            // player no longer holds.
            if (!harvest && !toolStillHeld()) {
                finish();
                return;
            }

            int processed = 0;
            int counted = 0;
            while (!frontier.isEmpty() && processed < INTERACT_BLOCKS_PER_TICK
                    && processedCount < INTERACT_BLOCK_LIMIT && HELD_KEYS.contains(player.getUUID())) {
                processed++;
                BlockPos pos = frontier.poll();
                // A candidate whose state changed while it waited is skipped and, because only
                // applied positions spread, it does not carry the chain further either.
                if (!valid.test(pos)) {
                    continue;
                }
                if (interactOne(level, player, pos, face)) {
                    processedCount++;
                    counted++;
                    addNeighbours(pos);
                }
                // A consumed seed stack or a broken tool ends the chain. Harvesting never uses up
                // the held item, and is usually done bare handed, so it must not stop there.
                if (!harvest && !toolStillHeld()) {
                    break;
                }
            }

            if (counted > 0) {
                showInteractProgress(player, processedCount);
            }

            if (frontier.isEmpty() || !toolStillHeld() && !harvest || processedCount >= INTERACT_BLOCK_LIMIT) {
                finish();
            }
        }

        // The job runs over several ticks, so the held item is compared on every tick: a tool that
        // broke mid-chain, or a hotbar slot switch, stops the job where it stands. Only the item is
        // compared: using a tool changes its damage, which must not end the job.
        private boolean toolStillHeld() {
            return player.getMainHandItem().getItem() == tool.getItem();
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
        // Long-keyed maps stay primitive here; boxed Long keys collided into the same few
        // HashMap buckets near the origin and treeified.
        private final Long2ObjectMap<Map<Integer, List<BlockPos>>> sparseChunkMatches = new Long2ObjectOpenHashMap<>();
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
            this.capture = new CaptureContext(drops);
            this.totalLimit = mode.isBlast() ? Config.MAX_BLAST_BLOCKS.getAsInt() : Config.MAX_NORMAL_BLOCKS.getAsInt();
            this.areaDepthLimit = Config.MAX_NORMAL_BLOCKS.getAsInt();
            this.graphOffsets = mode.isBlast()
                    ? BLAST_OFFSETS.computeIfAbsent(Config.BLAST_SEARCH_DISTANCE.getAsInt(), ChainEvents::createBlastOffsets)
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
            int breakLimit = mode.isBlast() ? effectiveBlastBreakLimit()
                    : Config.MAX_NORMAL_BLOCKS_PER_TICK.getAsInt();
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

        private void tickArea() {
            int size = mode == ChainMode.AREA_1X1 ? 1 : 3;
            int planeSize = size * size;
            int breaks = 0;
            // Area modes share the normal per-tick budget on purpose: one knob tunes both speeds.
            int breakLimit = Config.MAX_NORMAL_BLOCKS_PER_TICK.getAsInt();
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
            // An extreme blast is deliberately unthrottled: the whole chain finishes inside this tick.
            int breakLimit = extreme ? Integer.MAX_VALUE : effectiveBlastBreakLimit();
            // While targets are queued no scanning happens, so a finished vein can leave a
            // large backlog of centres behind and every one of them would be walked in a
            // single tick. Capping the number walked per tick bounds that worst case; the
            // remainder is simply handled on the following ticks.
            int centerLimit = extreme ? Integer.MAX_VALUE
                    : Math.min(Math.max(1, breakLimit), SPARSE_SCANS_PER_TICK);
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
            int configured = Config.MAX_BLAST_BLOCKS_PER_TICK.getAsInt();
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
            int distance = Config.BLAST_SEARCH_DISTANCE.getAsInt();
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
                            // Resident chunks only: a blocking getChunk generates terrain inside
                            // this tick, and against ungenerated ground the closure keeps finding
                            // new ore in what it just generated - one tick that never ends.
                            chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                            if (chunk == null) {
                                continue;
                            }
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

        private void finish() {
            drops.flush(player.serverLevel(), player);
            CAPTURING_DROPS.remove();
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

    // Marks a break our handler has already seen in this tick, so the drop event that follows it
    // is not mistaken for one generated before the handler ran.
    private record HandledBreak(BlockPos pos, long gameTime) {
    }

    // A flint and steel click whose vanilla action has not run yet. The face is kept so the chained
    // positions are ignited against the same side the player clicked.
    private record PendingFireStart(ServerLevel level, BlockPos pos, Direction face, BlockState state,
            InteractAction action) {
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
