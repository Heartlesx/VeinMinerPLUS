package com.extrarawstyle.veinminerplus;

import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    // Defaults and limits live here once: the file is loaded with them, the config screen resets
    // to and validates against them, and the settings packet is clamped to them. With one copy per
    // value the three cannot drift apart.
    public static final int DEFAULT_MAX_NORMAL_BLOCKS = 1024;
    public static final int MIN_MAX_NORMAL_BLOCKS = 32;
    public static final int MAX_MAX_NORMAL_BLOCKS = 32767;
    public static final int DEFAULT_MAX_NORMAL_BLOCKS_PER_TICK = 8;
    public static final int MIN_MAX_NORMAL_BLOCKS_PER_TICK = 1;
    public static final int MAX_MAX_NORMAL_BLOCKS_PER_TICK = 384;
    public static final int DEFAULT_MAX_BLAST_BLOCKS = 32767;
    public static final int MIN_MAX_BLAST_BLOCKS = 32;
    public static final int MAX_MAX_BLAST_BLOCKS = 32767;
    public static final int DEFAULT_MAX_BLAST_BLOCKS_PER_TICK = 64;
    public static final int MIN_MAX_BLAST_BLOCKS_PER_TICK = 1;
    public static final int MAX_MAX_BLAST_BLOCKS_PER_TICK = 512;
    public static final int DEFAULT_BLAST_SEARCH_DISTANCE = 20;
    public static final int MIN_BLAST_SEARCH_DISTANCE = 3;
    public static final int MAX_BLAST_SEARCH_DISTANCE = 128;
    public static final boolean DEFAULT_NO_HUNGER_COST = false;
    public static final boolean DEFAULT_STORAGE_BINDING = true;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue MAX_NORMAL_BLOCKS = BUILDER
            .comment("普通连锁模式的最大方块数。范围：32-32767。")
            .translation("veinminerplus.configuration.maxNormalBlocks")
            .defineInRange("maxNormalBlocks", DEFAULT_MAX_NORMAL_BLOCKS, MIN_MAX_NORMAL_BLOCKS,
                    MAX_MAX_NORMAL_BLOCKS);

    public static final ForgeConfigSpec.IntValue MAX_NORMAL_BLOCKS_PER_TICK = BUILDER
            .comment("普通连锁与 1x1/3x3 范围模式每个服务端 Tick 的最大挖掘数。范围：1-384。")
            .translation("veinminerplus.configuration.maxNormalBlocksPerTick")
            .defineInRange("maxNormalBlocksPerTick", DEFAULT_MAX_NORMAL_BLOCKS_PER_TICK,
                    MIN_MAX_NORMAL_BLOCKS_PER_TICK, MAX_MAX_NORMAL_BLOCKS_PER_TICK);

    public static final ForgeConfigSpec.IntValue MAX_BLAST_BLOCKS = BUILDER
            .comment("爆破模式的最大方块数。范围：32-32767。")
            .translation("veinminerplus.configuration.maxBlastBlocks")
            .defineInRange("maxBlastBlocks", DEFAULT_MAX_BLAST_BLOCKS, MIN_MAX_BLAST_BLOCKS,
                    MAX_MAX_BLAST_BLOCKS);

    public static final ForgeConfigSpec.IntValue MAX_BLAST_BLOCKS_PER_TICK = BUILDER
            .comment("爆破模式每个服务端 Tick 的最大挖掘数。范围：1-512。")
            .translation("veinminerplus.configuration.maxBlastBlocksPerTick")
            .defineInRange("maxBlastBlocksPerTick", DEFAULT_MAX_BLAST_BLOCKS_PER_TICK,
                    MIN_MAX_BLAST_BLOCKS_PER_TICK, MAX_MAX_BLAST_BLOCKS_PER_TICK);

    public static final ForgeConfigSpec.IntValue BLAST_SEARCH_DISTANCE = BUILDER
            .comment("爆破模式从每个已发现方块搜索的最大欧氏距离。范围：3-128。")
            .translation("veinminerplus.configuration.blastSearchDistance")
            .defineInRange("blastSearchDistance", DEFAULT_BLAST_SEARCH_DISTANCE,
                    MIN_BLAST_SEARCH_DISTANCE, MAX_BLAST_SEARCH_DISTANCE);

    public static final ForgeConfigSpec.BooleanValue NO_HUNGER_COST = BUILDER
            .comment("启用后，自动连锁挖掘的方块不会消耗饱食度；默认关闭。")
            .translation("veinminerplus.configuration.noHungerCost")
            .define("noHungerCost", DEFAULT_NO_HUNGER_COST);

    public static final ForgeConfigSpec.BooleanValue STORAGE_BINDING = BUILDER
            .comment("启用后，连锁挖掘的掉落物优先送入存储绑定卡绑定的存储，塞不下的才掉在地上；默认开启。")
            .translation("veinminerplus.configuration.storageBinding")
            .define("storageBinding", DEFAULT_STORAGE_BINDING);

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLOCK_BLACKLIST = BUILDER
            .comment("方块黑名单：列表中的方块不会被连锁/爆破破坏，也不会触发连锁。填写方块 ID，例如 ae2:cable_bus。")
            .translation("veinminerplus.configuration.blockBlacklist")
            .defineList("blockBlacklist", List.of(),
                    entry -> entry instanceof String value
                            && (value.isBlank() || ResourceLocation.tryParse(value.trim()) != null));

    static final ForgeConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
