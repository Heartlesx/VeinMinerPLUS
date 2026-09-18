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
import net.minecraft.block.BlockFarmland;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemSeeds;
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
    private static final int BLOCK_BREAKS_PER_TICK = 8;
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

    private static final List<BlockPos> NORMAL_OFFSETS = createNormalOffsets();
    private static final Map<Integer, List<BlockPos>> BLAST_OFFSETS = new ConcurrentHashMap<>();
    private static final Map<IBlockState, Boolean> ORE_CACHE = new HashMap<>();
    private static final Map<IBlockState, Boolean> LOG_CACHE = new HashMap<>();
    private static final Map<UUID, ChainMode> PLAYER_MODES = new HashMap<>();
    private static final Set<UUID> HELD_KEYS = new HashSet<>();
    private static final Map<UUID, ChainJob> ACTIVE_JOBS = new HashMap<>();
    private static final Map<UUID, InteractJob> ACTIVE_INTERACT_JOBS = new HashMap<>();
    private static final Set<UUID> PENDING_JOBS = new HashSet<>();
    // 1.12.2 runs a task handed to MinecraftServer.addScheduledTask inline when it is called from
    // the server thread, so a job started there still sees the primary block in place and the
    // "was the block actually broken" check rejects every chain. Holding the start until the end
    // of the tick lets the break finish first.
    private static final Map<UUID, PendingStart> PENDING_STARTS = new HashMap<>();
    private static final Map<UUID, DropBuffer> PENDING_DROPS = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING_DROP_ORIGINS = new HashMap<>();
    private static final Map<UUID, Boolean> PENDING_DOUBLE_PLANTS = new HashMap<>();
    private static final Map<UUID, BreakFace> LAST_BREAK_FACES = new HashMap<>();
    private static final ThreadLocal<CaptureContext> CAPTURING_DROPS = new ThreadLocal<>();
    // TEMPORARY: diagnostics for the 1.12.2 field test. Remove once chaining is confirmed in game.
    private static final Map<UUID, Integer> TRACED_BREAKS = new HashMap<>();
    private static final Map<UUID, Integer> TRACED_FAILURES = new HashMap<>();
    private static final int TRACED_BREAK_LIMIT = 6;

    private static boolean traceOnce(Map<UUID, Integer> counter, EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        Integer seen = counter.get(id);
        int count = seen == null ? 0 : seen;
        if (count >= TRACED_BREAK_LIMIT) {
            return false;
        }
        counter.put(id, count + 1);
        return true;
    }

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
        PENDING_JOBS.remove(id);
        PENDING_STARTS.remove(id);
        PENDING_DROP_ORIGINS.remove(id);
        PENDING_DOUBLE_PLANTS.remove(id);
        LAST_BREAK_FACES.remove(id);
        TRACED_BREAKS.remove(id);
        TRACED_FAILURES.remove(id);
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
            if (event.getPlayer() instanceof EntityPlayerMP) {
                EntityPlayerMP rejected = (EntityPlayerMP) event.getPlayer();
                if (traceBreak(rejected)) {
                    VeinMinerPlus.debug("break {} rejected: canceled={} keyHeld={} jobActive={} pending={}",
                            event.getPos(), event.isCanceled(), HELD_KEYS.contains(rejected.getUniqueID()),
                            ACTIVE_JOBS.containsKey(rejected.getUniqueID()),
                            PENDING_JOBS.contains(rejected.getUniqueID()));
                }
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
        if (PENDING_JOBS.contains(id)) {
            return;
        }

        BlockPos target = event.getPos().toImmutable();
        IBlockState state = event.getState();
        ChainMode mode = PLAYER_MODES.containsKey(id) ? PLAYER_MODES.get(id) : ChainMode.NORMAL;
        if (mode.isInteraction() || !isEligible(level, player, target, state, state.getBlock(), mode)) {
            if (traceBreak(player)) {
                VeinMinerPlus.debug("break {} rejected: mode={} interaction={} reason={} block={}",
                        target, mode, mode.isInteraction(),
                        eligibilityFailure(level, player, target, state, state.getBlock(), mode),
                        state.getBlock().getRegistryName());
            }
            return;
        }
        if (traceBreak(player)) {
            VeinMinerPlus.debug("break {} accepted: mode={} block={} creative={}", target, mode,
                    state.getBlock().getRegistryName(), player.isCreative());
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
        PENDING_DROPS.put(id, drops);
        PENDING_DROP_ORIGINS.put(id, target);
        PENDING_DOUBLE_PLANTS.put(id, state.getBlock() instanceof BlockDoublePlant);
        PENDING_JOBS.add(id);
        PENDING_STARTS.put(id, new PendingStart(level, player, target, state, face, mode, drops));
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
        ChainMode mode = PLAYER_MODES.containsKey(id) ? PLAYER_MODES.get(id) : ChainMode.NORMAL;
        if (!HELD_KEYS.contains(id)
                || mode != ChainMode.SPECIAL_INTERACT
                || ACTIVE_INTERACT_JOBS.containsKey(id)
                || player.isSpectator()) {
            return;
        }

        WorldServer level = (WorldServer) world;
        List<BlockPos> targets = new ArrayList<>();
        if (!collectInteractTargets(level, event.getPos(), player.getHeldItemMainhand(),
                INTERACT_BLOCK_LIMIT, targets) || targets.isEmpty()) {
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
        if (isHarvestable(originState)) {
            spreadSquare(level, origin, limit, targets, pos -> isHarvestable(level.getBlockState(pos)));
            return true;
        }
        if (stack.getItem() instanceof ItemHoe) {
            spreadSquare(level, origin, limit, targets, pos -> isTillable(level, pos));
            return true;
        }
        if (stack.getItem() instanceof ItemSeeds) {
            spreadSquare(level, origin, limit, targets, pos -> isPlantable(level, pos));
            return true;
        }
        // An axe strips logs, so its neighbours of the same kind are collected. Anything the held
        // item does not apply to is skipped by the vanilla gate inside interactOne.
        if (isAppliedToBlock(stack, originState)) {
            Block originBlock = originState.getBlock();
            spreadSquare(level, origin, limit, targets, pos -> level.getBlockState(pos).getBlock() == originBlock);
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
                for (int dz = -radius; dz <= radius && targets.size() < limit; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
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
        return level.getBlockState(pos).getBlock() instanceof BlockDirt && level.isAirBlock(pos.up());
    }

    private static boolean isPlantable(World level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof BlockFarmland && level.isAirBlock(pos.up());
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
        for (ChainJob job : new ArrayList<>(ACTIVE_JOBS.values())) {
            job.tick();
        }
        for (InteractJob job : new ArrayList<>(ACTIVE_INTERACT_JOBS.values())) {
            job.tick();
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
        VeinMinerPlus.debug("server key state: player={} held={} heldSet={}", player.getName(), held,
                HELD_KEYS.contains(player.getUniqueID()));
    }

    static void setMode(EntityPlayer player, int ordinal) {
        if (player instanceof EntityPlayerMP) {
            PLAYER_MODES.put(player.getUniqueID(), ChainMode.fromOrdinal(ordinal));
        }
    }

    private static void startAfterPrimaryBreak(WorldServer level, EntityPlayerMP player, BlockPos target,
            IBlockState originalState, EnumFacing face, ChainMode mode, DropBuffer drops) {
        UUID id = player.getUniqueID();
        PENDING_JOBS.remove(id);
        if (PENDING_DROPS.get(id) == drops) {
            PENDING_DROPS.remove(id);
        }
        PENDING_DROP_ORIGINS.remove(id);
        PENDING_DOUBLE_PLANTS.remove(id);

        if (!level.isValid(target) || level.getBlockState(target).getBlock() == originalState.getBlock()) {
            drops.clear();
            VeinMinerPlus.debug("start {} aborted: valid={} blockNow={}", target, level.isValid(target),
                    level.getBlockState(target).getBlock().getRegistryName());
            return;
        }

        if (!HELD_KEYS.contains(id) || ACTIVE_JOBS.containsKey(id) || player.isSpectator()) {
            drops.flush(level, player);
            VeinMinerPlus.debug("start {} aborted: keyHeld={} jobActive={} spectator={}", target,
                    HELD_KEYS.contains(id), ACTIVE_JOBS.containsKey(id), player.isSpectator());
            return;
        }

        ACTIVE_JOBS.put(id, new ChainJob(level, player, target, originalState.getBlock(), face, mode, drops));
        showProgress(player, 1);
        VeinMinerPlus.debug("job started at {} mode={} block={}", target, mode,
                originalState.getBlock().getRegistryName());
    }

    // TEMPORARY: diagnostics for the 1.12.2 field test. Remove once chaining is confirmed in game.
    private static boolean traceBreak(EntityPlayerMP player) {
        return traceOnce(TRACED_BREAKS, player);
    }

    // Diagnostic twin of isEligible, reporting which condition refused the block. Kept separate so
    // the hot path in isEligible still allocates nothing.
    private static String eligibilityFailure(WorldServer level, EntityPlayerMP player, BlockPos pos,
            IBlockState state, Block targetBlock, ChainMode mode) {
        if (state.getBlock().isAir(state, level, pos)) {
            return "air";
        }
        if (!level.isBlockModifiable(player, pos)) {
            return "not-modifiable";
        }
        if (state.getBlockHardness(level, pos) < 0.0F) {
            return "hardness=" + state.getBlockHardness(level, pos);
        }
        boolean matches;
        switch (mode) {
            case BLAST_ANY:
                matches = true;
                break;
            case BLAST_ORES:
                matches = isOre(state);
                break;
            case BLAST_LOGS:
                matches = isLog(state);
                break;
            default:
                matches = state.getBlock() == targetBlock;
                break;
        }
        if (!matches) {
            return "mode-mismatch";
        }
        if (!player.isCreative() && !ForgeHooks.canHarvestBlock(state.getBlock(), player, level, pos)) {
            return "cannot-harvest";
        }
        return "eligible";
    }

    private static boolean isEligible(WorldServer level, EntityPlayerMP player, BlockPos pos, IBlockState state,
            Block targetBlock, ChainMode mode) {
        if (state.getBlock().isAir(state, level, pos) || !level.isBlockModifiable(player, pos)
                || state.getBlockHardness(level, pos) < 0.0F) {
            return false;
        }

        boolean matches;
        switch (mode) {
            case BLAST_ANY:
                matches = true;
                break;
            case BLAST_ORES:
                matches = isOre(state);
                break;
            case BLAST_LOGS:
                matches = isLog(state);
                break;
            default:
                matches = state.getBlock() == targetBlock;
                break;
        }
        return matches && (player.isCreative() || ForgeHooks.canHarvestBlock(state.getBlock(), player, level, pos));
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
        int[] ids = OreDictionary.getOreIDs(new ItemStack(item, 1, block.getMetaFromState(state)));
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
            if (!destroyed && traceOnce(TRACED_FAILURES, player)) {
                VeinMinerPlus.debug("chained break refused at {} block={}", pos,
                        state.getBlock().getRegistryName());
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

    private static BlockPos areaOffset(BlockPos start, EnumFacing face, int first, int second) {
        switch (face.getAxis()) {
            case X:
                return start.add(0, first, second);
            case Y:
                return start.add(first, 0, second);
            default:
                return start.add(first, second, 0);
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

    private static void showProgress(EntityPlayerMP player, int count) {
        player.sendStatusMessage(new TextComponentTranslation("message.veinminerplus.progress", count), true);
    }

    private static void showInteractProgress(EntityPlayerMP player, int count) {
        player.sendStatusMessage(new TextComponentTranslation("message.veinminerplus.interact_progress", count), true);
    }

    // 1.12.2 exposes the per-tick timings of the last hundred ticks but no averaged value.
    private static double getServerTps(WorldServer level) {
        MinecraftServer server = level.getMinecraftServer();
        long[] times = server.tickTimeArray;
        if (times.length == 0) {
            return 20.0D;
        }
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
        private final Map<StackKey, Long> counts = new HashMap<>();
        private int experience;

        private void add(ItemStack stack) {
            if (stack.isEmpty()) {
                return;
            }
            StackKey key = new StackKey(stack);
            Long current = counts.get(key);
            counts.put(key, current == null ? (long) stack.getCount() : current + stack.getCount());
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
            for (Map.Entry<StackKey, Long> entry : counts.entrySet()) {
                // A bucket holds one running total, so it has to be cut back to stack size before
                // it leaves the buffer.
                ItemStack template = entry.getKey().template;
                int maxStackSize = template.getMaxStackSize();
                long remaining = entry.getValue();
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
            return harvestOne(level, player, pos, state);
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

        private PendingStart(WorldServer level, EntityPlayerMP player, BlockPos target, IBlockState state,
                EnumFacing face, ChainMode mode, DropBuffer drops) {
            this.level = level;
            this.player = player;
            this.target = target;
            this.state = state;
            this.face = face;
            this.mode = mode;
            this.drops = drops;
        }

        private void begin() {
            startAfterPrimaryBreak(level, player, target, state, face, mode, drops);
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
            while (!targets.isEmpty() && processed < INTERACT_BLOCKS_PER_TICK
                    && HELD_KEYS.contains(player.getUniqueID())) {
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
        private final EnumFacing face;
        private final ChainMode mode;
        private final Set<BlockPos> examined = new HashSet<>();
        private final Deque<SearchNode> frontier = new ArrayDeque<>();
        private final Deque<BlockPos> sparseCenters = new ArrayDeque<>();
        private final PriorityQueue<BlockPos> sparseTargets;
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
        private int ticks;

        private ChainJob(WorldServer level, EntityPlayerMP player, BlockPos origin, Block targetBlock,
                EnumFacing face, ChainMode mode, DropBuffer drops) {
            this.level = level;
            this.player = player;
            this.origin = origin;
            this.targetBlock = targetBlock;
            this.face = face;
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
            IBlockState targetState = targetBlock.getDefaultState();
            this.sparseBlast = mode == ChainMode.BLAST_ORES
                    || mode == ChainMode.BLAST_LOGS
                    || mode == ChainMode.BLAST_SAME && (isOre(targetState) || isLog(targetState));
            this.sparseTargets = new PriorityQueue<>(new Comparator<BlockPos>() {
                @Override
                public int compare(BlockPos first, BlockPos second) {
                    int result = Long.compare(squaredDistance(origin, first), squaredDistance(origin, second));
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
            });

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
            if (!HELD_KEYS.contains(player.getUniqueID()) || player.isDead || player.isSpectator()
                    || player.getServerWorld() != level) {
                VeinMinerPlus.debug("job abandon: keyHeld={} dead={} spectator={} sameWorld={}",
                        HELD_KEYS.contains(player.getUniqueID()), player.isDead, player.isSpectator(),
                        player.getServerWorld() == level);
                finish();
                return;
            }

            if (!updateTpsSafety()) {
                return;
            }

            if (ticks < 3) {
                VeinMinerPlus.debug("job tick {}: mode={} frontier={} centers={} targets={} broken={}", ticks,
                        mode, frontier.size(), sparseCenters.size(), sparseTargets.size(), brokenCount);
            }
            ticks++;

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
            int breakLimit;
            if (mode.isBlast()) {
                breakLimit = effectiveBlastBreakLimit();
            } else if (mode == ChainMode.NORMAL) {
                breakLimit = Config.maxNormalBlocksPerTick;
            } else {
                breakLimit = BLOCK_BREAKS_PER_TICK;
            }
            while (checks < checkLimit && breaks < breakLimit
                    && !frontier.isEmpty() && brokenCount < totalLimit
                    && HELD_KEYS.contains(player.getUniqueID())) {
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

                    IBlockState state = getBlockStateForSearch(level, candidate, loadedChunks);
                    if (isEligible(level, player, candidate, state, targetBlock, mode)
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

            if (breaks > 0) {
                showProgress(player, brokenCount);
            }

            if (!HELD_KEYS.contains(player.getUniqueID()) || frontier.isEmpty() || brokenCount >= totalLimit) {
                finish();
            }
        }

        private void tickArea() {
            int size = mode == ChainMode.AREA_1X1 ? 1 : 3;
            int planeSize = size * size;
            int breaks = 0;
            while (breaks < BLOCK_BREAKS_PER_TICK && areaDepth <= areaDepthLimit
                    && HELD_KEYS.contains(player.getUniqueID())) {
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
                BlockPos candidate = areaOffset(origin, face, first, second)
                        .offset(face.getOpposite(), areaDepth);
                if (!level.isValid(candidate) || !examined.add(candidate)) {
                    continue;
                }

                IBlockState state = getBlockStateForSearch(level, candidate, loadedChunks);
                if (isEligible(level, player, candidate, state, targetBlock, mode)
                        && breakOne(level, player, candidate, state)) {
                    brokenCount++;
                    breaks++;
                }
            }

            if (breaks > 0) {
                showProgress(player, brokenCount);
            }

            if (!HELD_KEYS.contains(player.getUniqueID()) || areaDepth > areaDepthLimit) {
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
            while (breaks < breakLimit && brokenCount < totalLimit && HELD_KEYS.contains(player.getUniqueID())) {
                while (sparseTargets.isEmpty() && !sparseCenters.isEmpty() && scannedCenters < centerLimit) {
                    scanSparseCenter(sparseCenters.removeFirst());
                    scannedCenters++;
                }
                if (sparseTargets.isEmpty()) {
                    break;
                }

                BlockPos candidate = sparseTargets.poll();
                IBlockState state = getBlockStateForSearch(level, candidate, loadedChunks);
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
            if (!HELD_KEYS.contains(player.getUniqueID())
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
            int configured = Config.maxBlastBlocksPerTick;
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
                            sparseSectionMatches.put(key, matches);
                        } else {
                            matches = sparseSectionMatches.get(key);
                        }
                        if (matches == null) {
                            continue;
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
                                sparseTargets.add(pos);
                            }
                        }
                    }
                }
            }
        }

        private List<BlockPos> scanSection(int chunkX, int sectionY, int chunkZ) {
            Chunk chunk = chunkAt(chunkX, chunkZ);
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

        private Chunk chunkAt(int chunkX, int chunkZ) {
            long key = chunkKey(chunkX, chunkZ);
            Chunk chunk = loadedChunks.get(key);
            if (chunk == null) {
                chunk = level.getChunk(chunkX, chunkZ);
                loadedChunks.put(key, chunk);
            }
            return chunk;
        }

        private boolean matchesSparseState(IBlockState state) {
            switch (mode) {
                case BLAST_ORES:
                    return isOre(state);
                case BLAST_LOGS:
                    return isLog(state);
                default:
                    return state.getBlock() == targetBlock;
            }
        }

        private void finish() {
            drops.flush(level, player);
            ACTIVE_JOBS.remove(player.getUniqueID(), this);
            VeinMinerPlus.debug("job done: mode={} ticks={} brokenCount={} keyHeld={}", mode, ticks, brokenCount,
                    HELD_KEYS.contains(player.getUniqueID()));
        }
    }

    private static IBlockState getBlockStateForSearch(WorldServer level, BlockPos pos,
            Map<Long, Chunk> loadedChunks) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        long key = chunkKey(chunkX, chunkZ);
        Chunk chunk = loadedChunks.get(key);
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
