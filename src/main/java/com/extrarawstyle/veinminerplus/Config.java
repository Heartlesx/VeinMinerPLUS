package com.extrarawstyle.veinminerplus;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_NORMAL_BLOCKS = BUILDER
            .comment("普通连锁模式的最大方块数。范围：32-32767。")
            .translation("veinminerplus.configuration.maxNormalBlocks")
            .defineInRange("maxNormalBlocks", 1024, 32, 32767);

    public static final ModConfigSpec.IntValue MAX_NORMAL_BLOCKS_PER_TICK = BUILDER
            .comment("普通连锁模式每个服务端 Tick 的最大挖掘数。范围：1-384。")
            .translation("veinminerplus.configuration.maxNormalBlocksPerTick")
            .defineInRange("maxNormalBlocksPerTick", 8, 1, 384);

    public static final ModConfigSpec.IntValue MAX_BLAST_BLOCKS = BUILDER
            .comment("爆破模式的最大方块数。范围：32-32767。")
            .translation("veinminerplus.configuration.maxBlastBlocks")
            .defineInRange("maxBlastBlocks", 32767, 32, 32767);

    public static final ModConfigSpec.IntValue MAX_BLAST_BLOCKS_PER_TICK = BUILDER
            .comment("爆破模式每个服务端 Tick 的最大挖掘数。范围：1-512。")
            .translation("veinminerplus.configuration.maxBlastBlocksPerTick")
            .defineInRange("maxBlastBlocksPerTick", 64, 1, 512);

    public static final ModConfigSpec.IntValue BLAST_SEARCH_DISTANCE = BUILDER
            .comment("爆破模式从每个已发现方块搜索的最大欧氏距离。范围：3-128。")
            .translation("veinminerplus.configuration.blastSearchDistance")
            .defineInRange("blastSearchDistance", 20, 3, 128);

    public static final ModConfigSpec.BooleanValue NO_HUNGER_COST = BUILDER
            .comment("启用后，自动连锁挖掘的方块不会消耗饱食度；默认关闭。")
            .translation("veinminerplus.configuration.noHungerCost")
            .define("noHungerCost", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
