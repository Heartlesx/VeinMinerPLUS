package com.extrarawstyle.veinminerplus;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public static final int MIN_CHAIN_BLOCKS = 1;
    public static final int MAX_CHAIN_BLOCKS = 2_100_000_000;
    public static final int MAX_BLOCKS_PER_TICK = 8192;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_NORMAL_BLOCKS = BUILDER
            .comment("普通连锁模式的最大方块数。范围：1-2100000000。")
            .translation("veinminerplus.configuration.maxNormalBlocks")
            .defineInRange("maxNormalBlocks", 1024, MIN_CHAIN_BLOCKS, MAX_CHAIN_BLOCKS);

    public static final ModConfigSpec.IntValue MAX_NORMAL_BLOCKS_PER_TICK = BUILDER
            .comment("普通连锁模式每个服务端 Tick 的最大挖掘数。范围：1-8192。")
            .translation("veinminerplus.configuration.maxNormalBlocksPerTick")
            .defineInRange("maxNormalBlocksPerTick", 8, 1, MAX_BLOCKS_PER_TICK);

    public static final ModConfigSpec.IntValue MAX_BLAST_BLOCKS = BUILDER
            .comment("爆破模式的最大方块数。范围：1-2100000000。")
            .translation("veinminerplus.configuration.maxBlastBlocks")
            .defineInRange("maxBlastBlocks", 32767, MIN_CHAIN_BLOCKS, MAX_CHAIN_BLOCKS);

    public static final ModConfigSpec.IntValue MAX_BLAST_BLOCKS_PER_TICK = BUILDER
            .comment("爆破模式每个服务端 Tick 的最大挖掘数。范围：1-8192。")
            .translation("veinminerplus.configuration.maxBlastBlocksPerTick")
            .defineInRange("maxBlastBlocksPerTick", 64, 1, MAX_BLOCKS_PER_TICK);

    public static final ModConfigSpec.IntValue BLAST_SEARCH_DISTANCE = BUILDER
            .comment("爆破模式从每个已发现方块搜索的最大欧氏距离。范围：3-128。")
            .translation("veinminerplus.configuration.blastSearchDistance")
            .defineInRange("blastSearchDistance", 20, 3, 128);

    public static final ModConfigSpec.BooleanValue NO_HUNGER_COST = BUILDER
            .comment("启用后，自动连锁挖掘的方块不会消耗饱食度；默认关闭。")
            .translation("veinminerplus.configuration.noHungerCost")
            .define("noHungerCost", false);
    public static final ModConfigSpec.BooleanValue STORE_DROPS_IN_AE = BUILDER
            .comment("启用后，绑定了 AE 网络的连锁挖掘掉落物会优先存入网络。")
            .translation("veinminerplus.configuration.storeDropsInAe")
            .define("storeDropsInAe", true);

    public static final ModConfigSpec.BooleanValue ENABLE_PERFORMANCE_LOG = BUILDER
            .comment("启用 VMP 连锁挖掘性能检测日志。")
            .translation("veinminerplus.configuration.enablePerformanceLog")
            .define("enablePerformanceLog", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
