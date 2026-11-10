package com.extrarawstyle.veinminerplus;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/** Uses production mods, a real survival break, real drops and the real chain scheduler. */
@EventBusSubscriber(modid = VeinMinerPlus.MODID)
public final class HammerRegression {
    private static final BlockPos ORIGIN = new BlockPos(64, 160, 64);
    private static ServerPlayer player;
    private static int ticks;
    private static int scenario;
    private static int failures;
    private static int dropEvents;
    private static final String[] NAMES = {"manual-compressed", "blast-compressed-one",
            "blast-compressed-eight", "normal-compressed-eight", "blast-hammer-off", "manual-vanilla-pickaxe"};

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (!Boolean.getBoolean("veinminerplus.hammerRegression")) return;
        ServerLevel level = event.getServer().overworld();
        level.setChunkForced(4, 4, true);
        player = FakePlayerFactory.get(level, new GameProfile(
                UUID.fromString("f1a9f400-0317-41c4-8095-acbff7310282"), "HammerRegression"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(64.5, 160, 68.5);
        Config.BLAST_SEARCH_DISTANCE.set(3);
        Config.MAX_BLAST_BLOCKS_PER_TICK.set(512);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (player == null) return;
        try {
            if (++ticks == 5) begin();
            if (ticks == 35) {
                verify();
                if (++scenario == NAMES.length) {
                    VeinMinerPlus.LOGGER.info("HAMMER REGRESSION {}: {} scenarios, {} failures",
                            failures == 0 ? "PASSED" : "FAILED", scenario, failures);
                    player = null;
                    event.getServer().halt(false);
                } else ticks = 0;
            }
        } catch (Throwable failure) {
            VeinMinerPlus.LOGGER.error("HAMMER REGRESSION FAILED: test setup/execution", failure);
            player = null;
            event.getServer().halt(false);
        }
    }

    @SuppressWarnings("unchecked")
    private static void begin() throws Exception {
        dropEvents = 0;
        ServerLevel level = player.serverLevel();
        for (int i = 0; i < 8; i++) {
            level.setBlockAndUpdate(ORIGIN.east(i), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(ORIGIN.east(i).below(), Blocks.STONE.defaultBlockState());
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(ORIGIN).inflate(24))) item.discard();
        player.getInventory().clearContent();
        ItemStack tool = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(
                scenario == 5 ? "minecraft:netherite_pickaxe" : "useless_stretcher:wondrous_staff")));
        if (scenario != 5) {
            Object holder = Class.forName("com.sorrowmist.useless.core.component.UComponents")
                    .getField("ExDeorumCompressedHammerComponent").get(null);
            DataComponentType<Boolean> type = (DataComponentType<Boolean>) holder.getClass().getMethod("get").invoke(holder);
            tool.set(type, scenario != 4);
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        int count = scenario <= 1 || scenario == 5 ? 1 : 8;
        for (int i = 0; i < count; i++) level.setBlockAndUpdate(ORIGIN.east(i),
                BuiltInRegistries.BLOCK.get(ResourceLocation.parse("exdeorum:compressed_cobblestone")).defaultBlockState());
        ChainEvents.setMode(player, (scenario == 3 ? ChainMode.NORMAL : ChainMode.BLAST_SAME).ordinal());
        ChainEvents.setKeyHeld(player, scenario != 0 && scenario != 5);
        player.gameMode.destroyBlock(ORIGIN);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void countDrops(BlockDropsEvent event) {
        if (player == null || event.getBreaker() != player) return;
        dropEvents++;
    }

    private static void verify() {
        Map<String, Integer> drops = new HashMap<>();
        for (ItemEntity item : player.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(ORIGIN).inflate(24)))
            add(drops, item.getItem());
        for (ItemStack stack : player.getInventory().items) add(drops, stack);
        int compressed = drops.getOrDefault("exdeorum:compressed_cobblestone", 0);
        int gravel = drops.getOrDefault("minecraft:gravel", 0);
        int remaining = 0;
        int count = scenario <= 1 || scenario == 5 ? 1 : 8;
        for (int i = 0; i < count; i++) if (!player.serverLevel().isEmptyBlock(ORIGIN.east(i))) remaining++;
        boolean hammer = scenario < 4;
        boolean ok = remaining == 0 && dropEvents == count
                && (hammer ? compressed == 0 && gravel == count * 9 : compressed == count && gravel == 0);
        if (!ok) failures++;
        VeinMinerPlus.LOGGER.info("HAMMER CASE {} {}: placed={}, remaining={}, compressed={}, gravel={}, events={}, allDrops={}",
                NAMES[scenario], ok ? "PASS" : "FAIL", count, remaining, compressed, gravel, dropEvents, drops);
        ChainEvents.setKeyHeld(player, false);
    }

    private static void add(Map<String, Integer> drops, ItemStack stack) {
        if (!stack.isEmpty()) drops.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    }
}
