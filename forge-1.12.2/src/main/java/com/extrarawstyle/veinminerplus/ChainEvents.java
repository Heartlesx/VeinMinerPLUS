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

import net.minecraft.block.Block;
import net.minecraft.block.BlockCocoa;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.IGrowable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemDye;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.oredict.OreDictionary;

public final class ChainEvents {
    // Work is deliberately bounded so one large blast cannot monopolize the server thread.
    private static final int SEARCH_CHECKS_PER_TICK = 16384;
    private static final int SEARCH_CHECKS_PER_CENTER = 256;
    private static final int SPARSE_SCANS_PER_TICK = 256;
    // One player action can break a whole area through a tool of another mod, and every block of
    // it arrives as its own break event. The positions are collected up to this many.
    private static final int PENDING_SEED_LIMIT = 256;
    // The interaction mode grows a square outward from the clicked block, one ring per step,
    // and only keeps positions the held item could actually be used on. The vanilla use stays
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
    // 1.12.2 has no block tag system; the ore dictionary is the shared list every mod
    // registers its ores on, and it never changes after init, so the cache is never
    // invalidated.
    private static final String ORE_PREFIX = "ore";
    private static final String LOG_ORE_NAME = "logWood";
    private static final String GREGTECH_MOD_ID = "gregtech";
    // 1.12.2 Forge ships no constant for the vanilla tool class names, so the shovel name is kept
    // here as one. GregTech registers its tools under this class instead of extending ItemSpade.
    private static final String SHOVEL_TOOL_CLASS = "shovel";

    private static final List<BlockPos> NORMAL_OFFSETS = createNormalOffsets();
    private static final Map<Integer, List<BlockPos>> BLAST_OFFSETS = new ConcurrentHashMap<>();
    private static final Map<IBlockState, Boolean> ORE_CACHE = new HashMap<>();
    // The client preview resolves block states on its own thread while the server fills this cache.
    private static final Map<IBlockState, Boolean> LOG_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, ChainMode> PLAYER_MODES = new HashMap<>();
    private static final Set<UUID> HELD_KEYS = new HashSet<>();
    private static final Map<UUID, ChainJob> ACTIVE_JOBS = new HashMap<>();
    private static final Map<UUID, InteractJob> ACTIVE_INTERACT_JOBS = new HashMap<>();
    // 1.12.2 runs a task handed to MinecraftServer.addScheduledTask inline when it is called from
    // the server thread, so a job started there still sees the primary block in place and the
    // "was the block actually broken" check rejects every chain. Holding the start until the end
    // of the tick lets the break finish first.
    private static final Map<UUID, PendingStart> PENDING_STARTS = new HashMap<>();
    private static final Map<UUID, DropBuffer> PENDING_DROPS = new HashMap<>();
    private static final Map<UUID, List<BlockPos>> PENDING_SEEDS = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING_DROP_ORIGINS = new HashMap<>();
    private static final Map<UUID, Boolean> PENDING_DOUBLE_PLANTS = new HashMap<>();
    private static final Map<UUID, BreakFace> LAST_BREAK_FACES = new HashMap<>();
    private static final ThreadLocal<CaptureContext> CAPTURING_DROPS = new ThreadLocal<>();

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.player.getUniqueID();
        if (event.player instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            ChainJob job = ACTIVE_JOBS.get(id);
            if (job != null) {
                job.finish();
            }
            DropBuffer pendingDrops = PENDING_DROPS.remove(id);
            if (pendingDrops != null) {
                pendingDrops.flush(player.getServerWorld(), player);
            }
        }
        PLAYER_MODES.remove(id);
        ACTIVE_INTERACT_JOBS.remove(id);
        HELD_KEYS.remove(id);
        PENDING_STARTS.remove(id);
        PENDING_SEEDS.remove(id);
        PENDING_DROP_ORIGINS.remove(id);
        PENDING_DOUBLE_PLANTS.remove(id);
        LAST_BREAK_FACES.remove(id);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // The client mirror of the blacklist is built from this first snapshot; every later change
        // broadcasts a fresh one.
        if (event.player instanceof EntityPlayerMP) {
            NetworkHandler.sendBlacklistSync((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        if (player instanceof EntityPlayerMP && !event.getWorld().isRemote) {
            LAST_BREAK_FACES.put(player.getUniqueID(),
                    new BreakFace(event.getPos().toImmutable(), event.getFace()));
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()
                || !(event.getPlayer() instanceof EntityPlayerMP)
                || !HELD_KEYS.contains(event.getPlayer().getUniqueID())
                || ACTIVE_JOBS.containsKey(event.getPlayer().getUniqueID())) {
            // A chained break arrives here as well. Its experience joins the running chain so it
            // is paid out with the drops instead of popping out at the block that was broken.
            CaptureContext capture = CAPTURING_DROPS.get();
            if (capture != null && event.getExpToDrop() > 0) {
                capture.experience += event.getExpToDrop();
                event.setExpToDrop(0);
            }
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.getPlayer();
        World world = event.getWorld();
        if (!(world instanceof WorldServer)) {
            return;
        }

        WorldServer level = (WorldServer) world;
        UUID id = player.getUniqueID();
        BlockPos target = event.getPos().toImmutable();
        List<BlockPos> seeds = PENDING_SEEDS.get(id);
        if (seeds != null) {
            // One player action, but a tool such as a GregTech area tool breaks a whole area in
            // that one action and fires this event for every block of it. Starting the chain from
            // a single block inside that area would trap it: all of its neighbours are already
            // air, so the search can never reach the untouched blocks around the area. Collect
            // the whole area instead, so the chain starts from its edge.
            if (seeds.size() < PENDING_SEED_LIMIT) {
                seeds.add(target);
            }
            return;
        }
        if (PENDING_STARTS.containsKey(id)) {
            return;
        }

        IBlockState state = event.getState();
        ChainMode mode = PLAYER_MODES.get(id);
        if (mode == null) {
            mode = ChainMode.NORMAL;
        }
        if (mode.isInteraction() || !isEligible(level, player, target, state, state.getBlock(),
                state.getBlock().damageDropped(state), mode)) {
            return;
        }

        BreakFace breakFace = LAST_BREAK_FACES.get(id);
        EnumFacing face = breakFace != null && breakFace.pos.equals(target)
                ? breakFace.face
                : EnumFacing.UP;
        DropBuffer drops = new DropBuffer();
        // The block the player aimed at never spawns its own experience orb either; it is held
        // with the drops so the whole chain pays out in one place.
        if (event.getExpToDrop() > 0) {
            drops.addExperience(event.getExpToDrop());
            event.setExpToDrop(0);
        }
        List<BlockPos> pendingSeeds = new ArrayList<>();
        pendingSeeds.add(target);
        PENDING_DROPS.put(id, drops);
        PENDING_DROP_ORIGINS.put(id, target);
        PENDING_DOUBLE_PLANTS.put(id, state.getBlock() instanceof BlockDoublePlant);
        PENDING_SEEDS.put(id, pendingSeeds);
        PENDING_STARTS.put(id, new PendingStart(level, player, target, state, face, mode, drops, pendingSeeds));
    }

    // Item drops are taken here rather than from the spawned item: the harvest event names the
    // block that was broken, so a drop cannot be confused with an item a chunk load brings in,
    // and no item has to be spawned before it can be captured.
    @SubscribeEvent
    public void onHarvestDrops(BlockEvent.HarvestDropsEvent event) {
        if (event.getWorld().isRemote) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        if (drops.isEmpty()) {
            return;
        }
        DropBuffer buffer = captureBuffer(event.getPos());
        if (buffer == null) {
            return;
        }
        for (ItemStack stack : drops) {
            buffer.add(stack);
        }
        drops.clear();
    }

    // Experience orbs and the drops of blocks that skip the harvest call still arrive as an
    // entity. The join event cannot tell "just spawned" from "loaded with a chunk", so a
    // position matching the block still on its way out of a break is the condition.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote || !(event.getWorld() instanceof WorldServer)) {
            return;
        }
        if (!(event.getEntity() instanceof EntityItem) && !(event.getEntity() instanceof EntityXPOrb)) {
            return;
        }
        DropBuffer drops = captureBuffer(event.getEntity().getPosition());
        if (drops != null) {
            captureEntity(event, drops);
        }
    }

    // The buffer that owns a destroyed block, or null when the block is not part of a chain.
    private static DropBuffer captureBuffer(BlockPos pos) {
        CaptureContext capture = CAPTURING_DROPS.get();
        if (capture != null) {
            return matchesDropPosition(pos, capture.origin, capture.doublePlant) ? capture.drops : null;
        }
        for (Map.Entry<UUID, DropBuffer> entry : PENDING_DROPS.entrySet()) {
            BlockPos origin = PENDING_DROP_ORIGINS.get(entry.getKey());
            boolean doublePlant = PENDING_DOUBLE_PLANTS.containsKey(entry.getKey())
                    && PENDING_DOUBLE_PLANTS.get(entry.getKey());
            if (origin != null && matchesDropPosition(pos, origin, doublePlant)) {
                return entry.getValue();
            }
            List<BlockPos> seeds = PENDING_SEEDS.get(entry.getKey());
            if (seeds != null && seeds.contains(pos)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean matchesDropPosition(BlockPos dropPosition, BlockPos origin, boolean doublePlant) {
        if (dropPosition.equals(origin)) {
            return true;
        }
        if (!doublePlant) {
            return false;
        }
        BlockPos above = origin.up();
        BlockPos below = origin.down();
        return dropPosition.equals(above) || dropPosition.equals(below);
    }

    private static void captureEntity(EntityJoinWorldEvent event, DropBuffer drops) {
        if (event.getEntity() instanceof EntityItem) {
            drops.add(((EntityItem) event.getEntity()).getItem());
            event.setCanceled(true);
        } else if (event.getEntity() instanceof EntityXPOrb) {
            drops.addExperience(((EntityXPOrb) event.getEntity()).getXpValue());
            event.setCanceled(true);
        }
    }

    // Right click chaining for the interaction mode. The vanilla interaction still runs for the
    // block the player aimed at; this only spreads the same action over the square around it.
    // Nothing is cancelled, so a use vanilla refuses simply does nothing.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != EnumHand.MAIN_HAND
                || event.getFace() == EnumFacing.DOWN
                || event.getWorld().isRemote
                || !(event.getEntityPlayer() instanceof EntityPlayerMP)) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.getEntityPlayer();
        World world = event.getWorld();
        if (!(world instanceof WorldServer)) {
            return;
        }

        UUID id = player.getUniqueID();
        ChainMode mode = PLAYER_MODES.get(id);
        if (mode == null) {
            mode = ChainMode.NORMAL;
        }
        if (!HELD_KEYS.contains(id)
                || mode != ChainMode.SPECIAL_INTERACT
                || ACTIVE_INTERACT_JOBS.containsKey(id)
                || player.isSpectator()) {
            return;
        }

        WorldServer level = (WorldServer) world;
        List<BlockPos> targets = new ArrayList<>();
        boolean handled = collectInteractTargets(level, event.getPos(), player.getHeldItemMainhand(),
                INTERACT_BLOCK_LIMIT, targets);
        if (!handled || targets.isEmpty()) {
            return;
        }

        boolean harvest = isHarvestable(level.getBlockState(event.getPos()));
        ACTIVE_INTERACT_JOBS.put(id, new InteractJob(level, player, targets, harvest));
    }

    // Shared by the server job and the client preview, so the outline always matches what the
    // job will do. Returns false when the held item is not handled by this mode.
    static boolean collectInteractTargets(World level, BlockPos origin, ItemStack stack, int limit,
            List<BlockPos> targets) {
        IBlockState originState = level.getBlockState(origin);
        if (isHarvestable(originState) && !BlockBlacklist.contains(originState)) {
            spreadSquare(level, origin, limit, targets, pos -> {
                IBlockState state = level.getBlockState(pos);
                return isHarvestable(state) && !BlockBlacklist.contains(state);
            });
            return true;
        }
        Item item = stack.getItem();
        if (item instanceof ItemHoe) {
            spreadSquare(level, origin, limit, targets, pos -> isTillable(level, pos));
            return true;
        }
        // GregTech registers its tools under the vanilla shovel tool class instead of extending
        // ItemSpade, so the class alone would miss them.
        if (item instanceof ItemSpade || item.getToolClasses(stack).contains(SHOVEL_TOOL_CLASS)) {
            spreadSquare(level, origin, limit, targets, pos -> isPathable(level, pos));
            return true;
        }
        // 1.12.2 splits the seeds over two families that share no superclass: ItemSeeds (wheat,
        // nether wart) and ItemSeedFood (carrot, potato, beetroot). IPlantable is what both use to
        // ask a soil whether it accepts them, so the same question is asked here.
        if (item instanceof IPlantable) {
            spreadSquare(level, origin, limit, targets, pos -> isPlantable(level, pos, (IPlantable) item));
            return true;
        }
        // The vanilla item grows whatever IGrowable accepts, so the same gate is used here.
        if (item instanceof ItemDye && EnumDyeColor.byDyeDamage(stack.getMetadata()) == EnumDyeColor.WHITE) {
            spreadSquare(level, origin, limit, targets, pos -> isFertilizable(level, pos));
            return true;
        }
        // An axe strips logs, so its neighbours of the same kind are collected. Anything the held
        // item does not apply to is skipped by the vanilla gate inside interactOne.
        if (isAppliedToBlock(stack, originState)) {
            spreadSquare(level, origin, limit, targets, pos -> sameKind(originState, level.getBlockState(pos)));
            return true;
        }
        return false;
    }

    private static boolean isAppliedToBlock(ItemStack stack, IBlockState state) {
        if (stack.isEmpty() || stack.getItem() instanceof ItemBlock) {
            return false;
        }
        if (stack.getItem() instanceof ItemAxe) {
            return isLog(state);
        }
        return false;
    }

    // Grows a square outward from the origin, one ring per step. Stops at the limit, at the
    // radius cap, or as soon as a whole ring holds nothing the held item could be used on.
    private static void spreadSquare(World level, BlockPos origin, int limit, List<BlockPos> targets,
            TargetFilter valid) {
        int y = origin.getY();
        for (int radius = 0; radius <= INTERACT_MAX_RADIUS && targets.size() < limit; radius++) {
            boolean found = false;
            for (int dx = -radius; dx <= radius && targets.size() < limit; dx++) {
                // The top and bottom rows of a ring hold every column; the rows between them hold
                // only their two edge cells. Walking just those cells visits the same positions in
                // the same order without stepping over the interior of the square.
                int step = dx == -radius || dx == radius ? 1 : radius * 2;
                for (int dz = -radius; dz <= radius && targets.size() < limit; dz += step) {
                    BlockPos pos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                    if (level.isValid(pos) && valid.test(pos)) {
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

    // Selection is deliberately a little looser than vanilla: the use is still the final gate when
    // the job runs, so a position vanilla would refuse is simply skipped.
    private static boolean isTillable(World level, BlockPos pos) {
        // Grass is a block of its own in 1.12.2, not a dirt variant, and vanilla tills grass and
        // grass path just like dirt. A dirt-only test refuses the very block the player aims at,
        // which leaves the aimed block tilled by vanilla while the square around it stays untouched.
        Block block = level.getBlockState(pos).getBlock();
        return (block instanceof BlockDirt || block == Blocks.GRASS || block == Blocks.GRASS_PATH)
                && level.isAirBlock(pos.up());
    }

    // The soil is asked exactly the question the vanilla item asks it, so farmland, soul sand and
    // the soils of other mods are all decided by the block itself instead of by a hardcoded list.
    private static boolean isPlantable(World level, BlockPos pos, IPlantable plantable) {
        IBlockState state = level.getBlockState(pos);
        return level.isAirBlock(pos.up())
                && state.getBlock().canSustainPlant(state, level, pos, EnumFacing.UP, plantable);
    }

    // A shovel turns grass into a path and leaves dirt alone, which is what vanilla does with it.
    private static boolean isPathable(World level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() == Blocks.GRASS && level.isAirBlock(pos.up());
    }

    // Bone meal grows whatever the vanilla item would grow, so its own gate is the selector.
    private static boolean isFertilizable(World level, BlockPos pos) {
        IBlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        return block instanceof IGrowable
                && ((IGrowable) block).canUseBonemeal(level, level.rand, pos, state);
    }

    static boolean isHarvestable(IBlockState state) {
        Block block = state.getBlock();
        if (block instanceof BlockCrops) {
            return ((BlockCrops) block).isMaxAge(state);
        }
        if (block instanceof BlockCocoa) {
            return state.getValue(BlockCocoa.AGE).intValue() >= 2;
        }
        return false;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        startPendingJobs();
        if (!ACTIVE_JOBS.isEmpty()) {
            for (ChainJob job : new ArrayList<>(ACTIVE_JOBS.values())) {
                job.tick();
            }
        }
        if (!ACTIVE_INTERACT_JOBS.isEmpty()) {
            for (InteractJob job : new ArrayList<>(ACTIVE_INTERACT_JOBS.values())) {
                job.tick();
            }
        }
    }

    private static void startPendingJobs() {
        if (PENDING_STARTS.isEmpty()) {
            return;
        }
        List<PendingStart> starts = new ArrayList<>(PENDING_STARTS.values());
        PENDING_STARTS.clear();
        for (PendingStart start : starts) {
            start.begin();
        }
    }

    static void setKeyHeld(EntityPlayer player, boolean held) {
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }

        if (held) {
            HELD_KEYS.add(player.getUniqueID());
        } else {
            HELD_KEYS.remove(player.getUniqueID());
        }
    }

    static void setMode(EntityPlayer player, int ordinal) {
        if (player instanceof EntityPlayerMP) {
            PLAYER_MODES.put(player.getUniqueID(), ChainMode.fromOrdinal(ordinal));
        }
    }

    private static void startAfterPrimaryBreak(WorldServer level, EntityPlayerMP player, BlockPos target,
            IBlockState originalState, EnumFacing face, ChainMode mode, DropBuffer drops, List<BlockPos> seeds) {
        UUID id = player.getUniqueID();
        if (PENDING_DROPS.get(id) == drops) {
            PENDING_DROPS.remove(id);
        }
        if (PENDING_SEEDS.get(id) == seeds) {
            PENDING_SEEDS.remove(id);
        }
        PENDING_DROP_ORIGINS.remove(id);
        PENDING_DOUBLE_PLANTS.remove(id);

        if (!level.isValid(target) || level.getBlockState(target).getBlock() == originalState.getBlock()) {
            drops.clear();
            return;
        }

        if (!HELD_KEYS.contains(id) || ACTIVE_JOBS.containsKey(id) || player.isSpectator()) {
            drops.flush(level, player);
            return;
        }

        ACTIVE_JOBS.put(id, new ChainJob(level, player, target, originalState, face, mode, drops, seeds));
        showProgress(player, 1);
    }

    // A negative block hardness is vanilla's "unbreakable" marker (bedrock and friends). Some mods
    // register mineable blocks that way while overriding the progress rules, so the marker alone is
    // not the answer: the block only counts as unbreakable when the player cannot make progress on
    // it right now and no tool class claims to harvest it. 1.12.2 has no mineable tags; the block's
    // own harvest tool is the same signal the tags carry on the newer versions.
    private static boolean isUnbreakable(WorldServer level, EntityPlayerMP player, BlockPos pos,
            IBlockState state) {
        Block block = state.getBlock();
        if (state.getBlockHardness(level, pos) >= 0.0F) {
            return false;
        }
        if (block.getPlayerRelativeBlockHardness(state, player, level, pos) > 0.0F) {
            return false;
        }
        return block.getHarvestTool(state) == null;
    }

    private static boolean isEligible(WorldServer level, EntityPlayerMP player, BlockPos pos, IBlockState state,
            Block targetBlock, int targetVariant, ChainMode mode) {
        // Most candidates in a search do not match the mode at all, so the cheap test runs before
        // the modifiable and hardness checks, which are the expensive ones.
        if (!matchesMode(mode, targetBlock, targetVariant, state)) {
            return false;
        }

        if (BlockBlacklist.contains(state) || state.getBlock().isAir(state, level, pos)
                || !level.isBlockModifiable(player, pos) || isUnbreakable(level, player, pos, state)) {
            return false;
        }

        return player.isCreative() || ForgeHooks.canHarvestBlock(state.getBlock(), player, level, pos);
    }

    // One definition for both the job's eligibility test and the section scan.
    private static boolean matchesMode(ChainMode mode, Block targetBlock, int targetVariant, IBlockState state) {
        switch (mode) {
            case BLAST_ANY:
                return true;
            case BLAST_ORES:
            case EXTREME_BLAST_ORES:
                return isOre(state);
            case BLAST_LOGS:
                return isLog(state);
            default:
                return sameKind(targetBlock, targetVariant, state);
        }
    }

    // 1.12.2 gives a whole block family one block and packs its variants into the metadata, so
    // comparing the block alone counts birch as the same kind as the oak it was chained from.
    // damageDropped is the meta the family's item carries: it names the variant and leaves the
    // axis out, which is the granularity a dedicated block per variant would give.
    private static boolean sameKind(IBlockState targetState, IBlockState state) {
        return state.getBlock() == targetState.getBlock()
                && state.getBlock().damageDropped(state) == targetState.getBlock().damageDropped(targetState);
    }

    // Same test as sameKind, with the target's block and variant resolved once per job instead of
    // once per candidate.
    private static boolean sameKind(Block targetBlock, int targetVariant, IBlockState state) {
        return state.getBlock() == targetBlock && targetBlock.damageDropped(state) == targetVariant;
    }

    // A blast search tests the same handful of states over and over, and the ore dictionary
    // contents cannot change during a session. The plain get() matters as much as the caching
    // does: with a single computeIfAbsent call the lookup itself becomes the largest cost
    // inside the scan.
    private static boolean isOre(IBlockState state) {
        Boolean cached = ORE_CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        boolean result = computeIsOre(state);
        ORE_CACHE.put(state, result);
        return result;
    }

    private static boolean computeIsOre(IBlockState state) {
        for (String name : oreNames(state)) {
            if (name.startsWith(ORE_PREFIX)) {
                return true;
            }
        }
        // Some mod packs register nothing at all for their ores.
        ResourceLocation id = state.getBlock().getRegistryName();
        if (id != null && id.getPath().endsWith("_ore")) {
            return true;
        }
        // GregTech marks its own ore blocks; the stone variants do not all reach the ore dictionary.
        return Loader.isModLoaded(GREGTECH_MOD_ID) && GregTechCompat.isOreBlock(state);
    }

    private static boolean isLog(IBlockState state) {
        Boolean cached = LOG_CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        for (String name : oreNames(state)) {
            if (LOG_ORE_NAME.equals(name)) {
                result = true;
                break;
            }
        }
        if (!result) {
            // Not every mod registers its wood on the ore dictionary. GregTech's rubber wood is
            // one of them, and a chain that only trusts the dictionary refuses to start on it.
            ResourceLocation id = state.getBlock().getRegistryName();
            String path = id == null ? "" : id.getPath();
            result = path.equals("log") || path.equals("log2")
                    || path.endsWith("_log") || path.endsWith("_wood");
        }
        LOG_CACHE.put(state, result);
        return result;
    }

    private static String[] oreNames(IBlockState state) {
        Block block = state.getBlock();
        Item item = Item.getItemFromBlock(block);
        // Item.getItemFromBlock answers Items.AIR, not null, for a block that has no item, and
        // the ore dictionary throws on an empty stack instead of returning no entries.
        if (item == null || item == Items.AIR || block == Blocks.AIR) {
            return new String[0];
        }
        // damageDropped is the meta the block's item carries, which is what the dictionary is
        // registered with. getMetaFromState can pack extra bits on top of that (BlockLog packs
        // the axis there), and an entry naming an exact meta would then never match.
        int meta = block.getMetaFromState(state);
        int dropMeta = block.damageDropped(state);
        String[] names = oreNames(new ItemStack(item, 1, meta));
        if (names.length > 0 || dropMeta == meta) {
            return names;
        }
        return oreNames(new ItemStack(item, 1, dropMeta));
    }

    private static String[] oreNames(ItemStack stack) {
        int[] ids = OreDictionary.getOreIDs(stack);
        String[] names = new String[ids.length];
        for (int index = 0; index < ids.length; index++) {
            names[index] = OreDictionary.getOreName(ids[index]);
        }
        return names;
    }

    // Every caller has already run isEligible on this exact state; doing it again here would
    // repeat the modifiable check and the harvest check for every single chained block.
    private static boolean breakOne(WorldServer level, EntityPlayerMP player, BlockPos pos, IBlockState state) {
        ChainJob job = ACTIVE_JOBS.get(player.getUniqueID());
        CaptureContext previous = CAPTURING_DROPS.get();
        CaptureContext context = null;
        if (job != null) {
            context = new CaptureContext(job.drops, pos.toImmutable(),
                    state.getBlock() instanceof BlockDoublePlant);
            CAPTURING_DROPS.set(context);
        }
        try {
            // Use the same server-side entry point as a real player break. This keeps the break
            // event, drops, tool damage, block entities, and client updates in sync.
            float exhaustion = player.getFoodStats().foodExhaustionLevel;
            boolean destroyed = player.interactionManager.tryHarvestBlock(pos);
            if (destroyed && context != null && context.experience > 0) {
                job.drops.addExperience(context.experience);
            }
            if (destroyed && Config.noHungerCost) {
                player.getFoodStats().foodExhaustionLevel = exhaustion;
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

    // The hit face points back toward the player, so the depth is taken along the opposite
    // direction. Combining both offsets into one add keeps this to one position per candidate.
    private static BlockPos areaCandidate(BlockPos origin, EnumFacing face, EnumFacing opposite,
            int first, int second, int depth) {
        int dx = opposite.getXOffset() * depth;
        int dy = opposite.getYOffset() * depth;
        int dz = opposite.getZOffset() * depth;
        switch (face.getAxis()) {
            case X:
                return origin.add(dx, first + dy, second + dz);
            case Y:
                return origin.add(first + dx, dy, second + dz);
            default:
                return origin.add(first + dx, second + dy, dz);
        }
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
        Collections.sort(offsets, new Comparator<BlockPos>() {
            @Override
            public int compare(BlockPos first, BlockPos second) {
                int result = Long.compare(squared(first), squared(second));
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(Math.abs(first.getY()), Math.abs(second.getY()));
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(Math.abs(first.getX()), Math.abs(second.getX()));
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(first.getY(), second.getY());
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(first.getX(), second.getX());
                return result != 0 ? result : Integer.compare(first.getZ(), second.getZ());
            }

            private long squared(BlockPos pos) {
                return (long) pos.getX() * pos.getX() + (long) pos.getY() * pos.getY()
                        + (long) pos.getZ() * pos.getZ();
            }
        });
        return Collections.unmodifiableList(offsets);
    }

    // The counter is sent to the client and drawn on its own HUD. The action bar is shared with
    // every other mod, and a second writer there makes the counter flicker or vanish entirely.
    private static void showProgress(EntityPlayerMP player, int count) {
        NetworkHandler.sendProgress(player, count, false);
    }

    private static void showInteractProgress(EntityPlayerMP player, int count) {
        NetworkHandler.sendProgress(player, count, true);
    }

    // 1.12.2 exposes the per-tick timings of the last hundred ticks but no averaged value.
    private static double getServerTps(WorldServer level) {
        MinecraftServer server = level.getMinecraftServer();
        long[] times = server.tickTimeArray;
        long total = 0L;
        for (long time : times) {
            total += time;
        }
        double averageMillis = (double) total / times.length / 1_000_000.0D;
        return averageMillis <= 0.0D ? 20.0D : Math.min(20.0D, 1_000.0D / averageMillis);
    }

    private static int roundedTps(double tps) {
        return Math.max(0, (int) Math.round(tps));
    }

    private static void showTpsMessage(EntityPlayerMP player, String translationKey, double tps) {
        player.sendStatusMessage(new TextComponentTranslation(translationKey, roundedTps(tps)), true);
    }

    private static final class DropBuffer {
        // One bucket per item and tag set, so a drop finds its total in a single lookup.
        // A long chain accumulates thousands of buckets; scanning all of them per drop made every
        // drop cost grow with the number already accumulated.
        private final Map<StackKey, Counter> counts = new HashMap<>();
        private int experience;

        private void add(ItemStack stack) {
            if (stack.isEmpty()) {
                return;
            }
            StackKey key = new StackKey(stack);
            Counter current = counts.get(key);
            if (current == null) {
                current = new Counter();
                counts.put(key, current);
            }
            current.value += stack.getCount();
        }

        private void addExperience(int amount) {
            experience += amount;
        }

        private void flush(WorldServer level, EntityPlayerMP player) {
            if (!level.getGameRules().getBoolean("doTileDrops")) {
                clear();
                return;
            }

            // The bound storage takes what it can; only what it does not accept is dropped. The
            // target is resolved once here, not once per stack.
            StorageRouter.Sink sink = null;
            if (Config.storageBinding) {
                StorageBindings bindings = StorageBindings.findIn(player);
                if (bindings != null) {
                    sink = StorageRouter.resolve(level, player, bindings);
                }
            }
            double x = player.posX;
            double y = player.posY;
            double z = player.posZ;
            for (Map.Entry<StackKey, Counter> entry : counts.entrySet()) {
                // A bucket holds one running total, so it has to be cut back to stack size before
                // it leaves the buffer.
                ItemStack template = entry.getKey().template;
                int maxStackSize = template.getMaxStackSize();
                long remaining = entry.getValue().value;
                while (remaining > 0L) {
                    int amount = (int) Math.min(maxStackSize, remaining);
                    remaining -= amount;
                    ItemStack stack = template.copy();
                    stack.setCount(amount);
                    if (sink != null) {
                        sink.insert(stack);
                        if (stack.isEmpty()) {
                            continue;
                        }
                    }
                    EntityItem item = new EntityItem(level, x, y, z, stack);
                    item.setDefaultPickupDelay();
                    item.motionX = 0.0D;
                    item.motionY = 0.0D;
                    item.motionZ = 0.0D;
                    level.spawnEntity(item);
                }
            }
            if (experience > 0) {
                level.spawnEntity(new EntityXPOrb(level, x, y, z, experience));
            }
            clear();
        }

        private void clear() {
            counts.clear();
            experience = 0;
        }

        // A mutable running total, so accumulating a drop does not box a Long.
        private static final class Counter {
            private long value;
        }

        // Identity of a bucket: the item plus every tag, the same pair the vanilla helper
        // compares. Count is deliberately excluded, it is what the bucket accumulates.
        private static final class StackKey {
            private final ItemStack template;
            private final int hash;

            private StackKey(ItemStack stack) {
                this.template = stack.copy();
                this.template.setCount(1);
                this.hash = 31 * Item.getIdFromItem(this.template.getItem())
                        + Objects.hashCode(this.template.getTagCompound());
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(Object other) {
                if (!(other instanceof StackKey)) {
                    return false;
                }
                StackKey key = (StackKey) other;
                return ItemStack.areItemsEqual(template, key.template)
                        && ItemStack.areItemStackTagsEqual(template, key.template);
            }
        }
    }

    // Runs the same server-side right click a manual one would reach, so tool damage, sounds and
    // mod interactions stay with vanilla and other mods. A mature crop is harvested directly
    // instead, because right click harvesting is not part of the vanilla interact pipeline.
    private static boolean interactOne(WorldServer level, EntityPlayerMP player, BlockPos pos) {
        IBlockState state = level.getBlockState(pos);
        if (isHarvestable(state)) {
            return !BlockBlacklist.contains(state) && harvestOne(level, player, pos, state);
        }

        ItemStack stack = player.getHeldItemMainhand();
        if (stack.isEmpty()) {
            return false;
        }

        float exhaustion = player.getFoodStats().foodExhaustionLevel;
        EnumActionResult result = player.interactionManager.processRightClickBlock(player, level, stack,
                EnumHand.MAIN_HAND, pos, EnumFacing.UP, 0.5F, 0.5F, 0.5F);
        if (Config.noHungerCost) {
            player.getFoodStats().foodExhaustionLevel = exhaustion;
        }
        return result == EnumActionResult.SUCCESS;
    }

    // Harvest and replant the way a right click harvester does it: the vanilla loot table decides
    // the drops, one of them pays for the replant, and the block is reset to its first growth
    // stage. The drops are rolled without a tool.
    private static boolean harvestOne(WorldServer level, EntityPlayerMP player, BlockPos pos,
            IBlockState state) {
        Block block = state.getBlock();
        TileEntity tileEntity = block.hasTileEntity(state) ? level.getTileEntity(pos) : null;
        for (ItemStack drop : block.getDrops(level, pos, state, 0)) {
            if (Block.getBlockFromItem(drop.getItem()) == block) {
                drop.shrink(1);
            }
            if (!drop.isEmpty()) {
                Block.spawnAsEntity(level, pos, drop);
            }
        }
        level.setBlockState(pos, replanted(state), 3);
        return true;
    }

    private static IBlockState replanted(IBlockState state) {
        Block block = state.getBlock();
        if (block instanceof BlockCrops) {
            return ((BlockCrops) block).withAge(0);
        }
        if (block instanceof BlockCocoa) {
            return state.withProperty(BlockCocoa.AGE, Integer.valueOf(0));
        }
        return state;
    }

    private static final class PendingStart {
        private final WorldServer level;
        private final EntityPlayerMP player;
        private final BlockPos target;
        private final IBlockState state;
        private final EnumFacing face;
        private final ChainMode mode;
        private final DropBuffer drops;
        private final List<BlockPos> seeds;

        private PendingStart(WorldServer level, EntityPlayerMP player, BlockPos target, IBlockState state,
                EnumFacing face, ChainMode mode, DropBuffer drops, List<BlockPos> seeds) {
            this.level = level;
            this.player = player;
            this.target = target;
            this.state = state;
            this.face = face;
            this.mode = mode;
            this.drops = drops;
            this.seeds = seeds;
        }

        private void begin() {
            startAfterPrimaryBreak(level, player, target, state, face, mode, drops, seeds);
        }
    }

    private static final class InteractJob {
        private final WorldServer level;
        private final EntityPlayerMP player;
        private final Deque<BlockPos> targets;
        private final boolean harvest;
        private int processedCount;

        private InteractJob(WorldServer level, EntityPlayerMP player, List<BlockPos> targets,
                boolean harvest) {
            this.level = level;
            this.player = player;
            this.targets = new ArrayDeque<>(targets);
            this.harvest = harvest;
        }

        private void tick() {
            if (!HELD_KEYS.contains(player.getUniqueID()) || player.isDead || player.isSpectator()
                    || player.getServerWorld() != level) {
                finish();
                return;
            }

            int processed = 0;
            int counted = 0;
            while (!targets.isEmpty() && processed < INTERACT_BLOCKS_PER_TICK) {
                processed++;
                if (interactOne(level, player, targets.poll())) {
                    processedCount++;
                    counted++;
                }
                // A consumed seed stack or a broken tool ends the chain. Harvesting never uses up
                // the held item, and is usually done bare handed, so it must not stop there.
                if (!harvest && player.getHeldItemMainhand().isEmpty()) {
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
            ACTIVE_INTERACT_JOBS.remove(player.getUniqueID(), this);
        }
    }

    private static final class ChainJob {
        private final WorldServer level;
        private final EntityPlayerMP player;
        private final BlockPos origin;
        private final Block targetBlock;
        private final int targetVariant;
        private final EnumFacing face;
        private final EnumFacing opposite;
        private final ChainMode mode;
        private final Set<BlockPos> examined = new HashSet<>();
        private final Deque<SearchNode> frontier = new ArrayDeque<>();
        private final Deque<BlockPos> sparseCenters = new ArrayDeque<>();
        private final PriorityQueue<SparseTarget> sparseTargets;
        // Section key -> matching positions inside that 16x16x16 section. Bucketing matters: a
        // chunk column holds up to 16 sections, while a scan sphere only ever reaches a couple
        // of them.
        private final Map<Long, List<BlockPos>> sparseSectionMatches = new HashMap<>();
        private final Set<Long> sparseScannedSections = new HashSet<>();
        private final Map<Long, Chunk> loadedChunks = new HashMap<>();
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
        private double lastTps = 20.0D;
        private Chunk lastChunk;
        private int lastChunkX;
        private int lastChunkZ;

        // Ordering for the sparse queue. The distance is measured from the job's fixed origin, so
        // it is stored on the entry instead of being recomputed on every comparison.
        private static final Comparator<SparseTarget> SPARSE_ORDER = new Comparator<SparseTarget>() {
            @Override
            public int compare(SparseTarget first, SparseTarget second) {
                int result = Long.compare(first.distance, second.distance);
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(first.pos.getY(), second.pos.getY());
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(first.pos.getX(), second.pos.getX());
                return result != 0 ? result : Integer.compare(first.pos.getZ(), second.pos.getZ());
            }
        };

        private ChainJob(WorldServer level, EntityPlayerMP player, BlockPos origin, IBlockState targetState,
                EnumFacing face, ChainMode mode, DropBuffer drops, List<BlockPos> seeds) {
            this.level = level;
            this.player = player;
            this.origin = origin;
            this.targetBlock = targetState.getBlock();
            this.targetVariant = targetState.getBlock().damageDropped(targetState);
            this.face = face;
            // The hit face points back toward the player; the area mode advances into the block.
            this.opposite = face.getOpposite();
            this.mode = mode;
            this.drops = drops;
            this.totalLimit = mode.isBlast() ? Config.maxBlastBlocks : Config.maxNormalBlocks;
            this.areaDepthLimit = Config.maxNormalBlocks;
            if (mode.isBlast()) {
                List<BlockPos> cached = BLAST_OFFSETS.get(Config.blastSearchDistance);
                if (cached == null) {
                    cached = createBlastOffsets(Config.blastSearchDistance);
                    BLAST_OFFSETS.put(Config.blastSearchDistance, cached);
                }
                this.graphOffsets = cached;
            } else {
                this.graphOffsets = NORMAL_OFFSETS;
            }
            // An extreme blast always scans by chunk: one palette pass beats the graph's per-node
            // offset walks for every block type, not just the scattered veins.
            this.sparseBlast = mode.isExtremeBlast() || mode == ChainMode.BLAST_ORES
                    || mode == ChainMode.BLAST_LOGS
                    || mode == ChainMode.BLAST_SAME && (isOre(targetState) || isLog(targetState));
            this.sparseTargets = new PriorityQueue<>(SPARSE_ORDER);

            // Every block the player's action broke is a start, so an area that was cleared in one
            // go is entered from its edge instead of from a single block whose neighbours are all
            // gone already.
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
            // The key state and the player's state can only change between ticks: the packet
            // handler runs on the server thread at a tick boundary, so one lookup covers the tick.
            if (!HELD_KEYS.contains(player.getUniqueID()) || player.isDead || player.isSpectator()
                    || player.getServerWorld() != level) {
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
            // Every non-blast mode shares the normal per-tick budget on purpose: one knob tunes
            // normal chains and both area sizes alike.
            int breakLimit = mode.isBlast() ? effectiveBlastBreakLimit() : Config.maxNormalBlocksPerTick;
            while (checks < checkLimit && breaks < breakLimit
                    && !frontier.isEmpty() && brokenCount < totalLimit) {
                SearchNode node = frontier.removeFirst();
                int centerChecks = 0;
                while (centerChecks < SEARCH_CHECKS_PER_CENTER
                        && checks < checkLimit
                        && breaks < breakLimit
                        && node.nextOffset < graphOffsets.size()) {
                    BlockPos offset = graphOffsets.get(node.nextOffset);
                    node.nextOffset++;
                    BlockPos candidate = node.position.add(offset.getX(), offset.getY(), offset.getZ());
                    checks++;
                    centerChecks++;
                    if (!examined.add(candidate) || !level.isValid(candidate)) {
                        continue;
                    }

                    IBlockState state = getBlockStateForSearch(candidate);
                    if (isEligible(level, player, candidate, state, targetBlock, targetVariant, mode)
                            && breakOne(level, player, candidate, state)) {
                        brokenCount++;
                        breaks++;
                        frontier.addLast(new SearchNode(candidate, 0));
                    }
                }
                if (node.nextOffset < graphOffsets.size()) {
                    frontier.addLast(node);
                }
            }

            afterTick(breaks, frontier.isEmpty() || brokenCount >= totalLimit);
        }

        private void tickArea() {
            int size = mode == ChainMode.AREA_1X1 ? 1 : 3;
            int planeSize = size * size;
            int breaks = 0;
            // Area modes share the normal per-tick budget on purpose: one knob tunes both speeds.
            int breakLimit = Config.maxNormalBlocksPerTick;
            while (breaks < breakLimit && areaDepth <= areaDepthLimit) {
                if (areaIndex >= planeSize) {
                    areaDepth++;
                    areaIndex = 0;
                    continue;
                }

                int startOffset = -(size / 2);
                int first = startOffset + areaIndex / size;
                int second = startOffset + areaIndex % size;
                areaIndex++;
                BlockPos candidate = areaCandidate(origin, face, opposite, first, second, areaDepth);
                if (!level.isValid(candidate) || !examined.add(candidate)) {
                    continue;
                }

                IBlockState state = getBlockStateForSearch(candidate);
                if (isEligible(level, player, candidate, state, targetBlock, targetVariant, mode)
                        && breakOne(level, player, candidate, state)) {
                    brokenCount++;
                    breaks++;
                }
            }

            afterTick(breaks, areaDepth > areaDepthLimit);
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
            while (breaks < breakLimit && brokenCount < totalLimit) {
                while (sparseTargets.isEmpty() && !sparseCenters.isEmpty() && scannedCenters < centerLimit) {
                    scanSparseCenter(sparseCenters.removeFirst());
                    scannedCenters++;
                }
                if (sparseTargets.isEmpty()) {
                    break;
                }

                BlockPos candidate = sparseTargets.poll().pos;
                IBlockState state = getBlockStateForSearch(candidate);
                if (isEligible(level, player, candidate, state, targetBlock, targetVariant, mode)
                        && breakOne(level, player, candidate, state)) {
                    brokenCount++;
                    breaks++;
                    sparseCenters.addLast(candidate);
                }
            }

            afterTick(breaks, sparseTargets.isEmpty() && sparseCenters.isEmpty()
                    || brokenCount >= totalLimit);
        }

        private void afterTick(int breaks, boolean exhausted) {
            if (breaks > 0) {
                showProgress(player, brokenCount);
            }
            if (exhausted) {
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
            lastTps = tps;
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
            int configured = Config.maxBlastBlocksPerTick;
            double tps = lastTps;
            if (tps < TPS_WARNING_THRESHOLD) {
                return Math.max(1, configured / 4);
            }
            if (tps < TPS_RECOVERY_THRESHOLD) {
                return Math.max(1, configured / 2);
            }
            return configured;
        }

        private int effectiveBlastSearchChecks() {
            double tps = lastTps;
            if (tps < TPS_WARNING_THRESHOLD) {
                return SEARCH_CHECKS_PER_TICK / 4;
            }
            if (tps < TPS_RECOVERY_THRESHOLD) {
                return SEARCH_CHECKS_PER_TICK / 2;
            }
            return SEARCH_CHECKS_PER_TICK;
        }

        // 1.12.2 has no Chunk.findBlocks, so a section is walked once and its matching positions
        // are kept for every later centre that reaches into it. Positions outside the sphere stay
        // in the bucket, positions that made it in are enqueued once and then dropped from it.
        private void scanSparseCenter(BlockPos center) {
            int distance = Config.blastSearchDistance;
            long maxSquaredDistance = (long) distance * distance;
            int minChunkX = (center.getX() - distance) >> 4;
            int maxChunkX = (center.getX() + distance) >> 4;
            int minChunkZ = (center.getZ() - distance) >> 4;
            int maxChunkZ = (center.getZ() + distance) >> 4;
            int minSectionY = Math.max(0, (center.getY() - distance) >> 4);
            int maxSectionY = Math.min(15, (center.getY() + distance) >> 4);

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                        long key = sectionKey(chunkX, sectionY, chunkZ);
                        List<BlockPos> matches;
                        if (sparseScannedSections.add(key)) {
                            matches = scanSection(chunkX, sectionY, chunkZ);
                            if (matches == null) {
                                // The chunk is not resident. Skipping it keeps the scan from
                                // generating terrain inside this tick, and the section stays
                                // unscanned so a later centre can pick it up once it is loaded.
                                sparseScannedSections.remove(key);
                                continue;
                            }
                            sparseSectionMatches.put(key, matches);
                        } else {
                            matches = sparseSectionMatches.get(key);
                        }

                        for (int index = 0; index < matches.size();) {
                            BlockPos pos = matches.get(index);
                            if (squaredDistance(center, pos) > maxSquaredDistance) {
                                index++;
                                continue;
                            }

                            matches.set(index, matches.get(matches.size() - 1));
                            matches.remove(matches.size() - 1);
                            if (examined.add(pos)) {
                                sparseTargets.add(new SparseTarget(pos, squaredDistance(origin, pos)));
                            }
                        }
                    }
                }
            }
        }

        private List<BlockPos> scanSection(int chunkX, int sectionY, int chunkZ) {
            Chunk chunk = chunkAt(chunkX, chunkZ);
            if (chunk == null) {
                return null;
            }
            List<BlockPos> matches = new ArrayList<>();
            int baseY = sectionY << 4;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (matchesSparseState(chunk.getBlockState(x, baseY + y, z))) {
                            matches.add(new BlockPos((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z));
                        }
                    }
                }
            }
            return matches;
        }

        private IBlockState getBlockStateForSearch(BlockPos pos) {
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            // Candidates are walked in runs inside one chunk, so the last lookup usually answers
            // the next one too.
            if (lastChunk != null && chunkX == lastChunkX && chunkZ == lastChunkZ) {
                return lastChunk.getBlockState(pos);
            }
            long key = chunkKey(chunkX, chunkZ);
            Chunk chunk = loadedChunks.get(key);
            if (chunk == null) {
                chunk = level.getChunk(chunkX, chunkZ);
                loadedChunks.put(key, chunk);
            }
            lastChunk = chunk;
            lastChunkX = chunkX;
            lastChunkZ = chunkZ;
            return chunk.getBlockState(pos);
        }

        // Only chunks that are already loaded are walked. Asking the provider for a chunk generates
        // it right inside this tick, and against fresh terrain the scan would keep finding new
        // ground to generate - one tick that never ends. Returns null when the chunk is not
        // resident, so the caller can skip it.
        private Chunk chunkAt(int chunkX, int chunkZ) {
            long key = chunkKey(chunkX, chunkZ);
            Chunk chunk = loadedChunks.get(key);
            if (chunk == null) {
                chunk = level.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
                if (chunk == null) {
                    return null;
                }
                loadedChunks.put(key, chunk);
            }
            return chunk;
        }

        private boolean matchesSparseState(IBlockState state) {
            return !BlockBlacklist.contains(state) && matchesMode(mode, targetBlock, targetVariant, state);
        }

        private void finish() {
            drops.flush(level, player);
            ACTIVE_JOBS.remove(player.getUniqueID(), this);
        }
    }

    private static long chunkKey(BlockPos pos) {
        return chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static long sectionKey(int chunkX, int sectionY, int chunkZ) {
        return (chunkKey(chunkX, chunkZ) * 31L) ^ sectionY;
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
    }

    private static final class SparseTarget {
        private final BlockPos pos;
        private final long distance;

        private SparseTarget(BlockPos pos, long distance) {
            this.pos = pos;
            this.distance = distance;
        }
    }

    private interface TargetFilter {
        boolean test(BlockPos pos);
    }

    private static final class BreakFace {
        private final BlockPos pos;
        private final EnumFacing face;

        private BreakFace(BlockPos pos, EnumFacing face) {
            this.pos = pos;
            this.face = face;
        }
    }

    private static final class CaptureContext {
        private final DropBuffer drops;
        private final BlockPos origin;
        private final boolean doublePlant;
        // Experience the break reported, kept back until the break is known to have succeeded.
        private int experience;

        private CaptureContext(DropBuffer drops, BlockPos origin, boolean doublePlant) {
            this.drops = drops;
            this.origin = origin;
            this.doublePlant = doublePlant;
        }
    }
}
