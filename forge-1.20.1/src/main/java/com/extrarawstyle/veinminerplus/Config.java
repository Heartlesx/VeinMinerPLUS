package com.extrarawstyle.veinminerplus;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue MAX_NORMAL_BLOCKS = BUILDER
            .comment("Maximum blocks in normal connected mode. Range: 32-32767.")
            .defineInRange("maxNormalBlocks", 1024, 32, 32767);

    public static final ForgeConfigSpec.IntValue MAX_NORMAL_BLOCKS_PER_TICK = BUILDER
            .comment("Maximum normal chain blocks broken per server tick. Range: 1-384.")
            .defineInRange("maxNormalBlocksPerTick", 8, 1, 384);

    public static final ForgeConfigSpec.IntValue MAX_BLAST_BLOCKS = BUILDER
            .comment("Maximum blocks in blast modes. Range: 32-32767.")
            .defineInRange("maxBlastBlocks", 32767, 32, 32767);

    public static final ForgeConfigSpec.IntValue MAX_BLAST_BLOCKS_PER_TICK = BUILDER
            .comment("Maximum blast blocks broken per server tick. Range: 1-512.")
            .defineInRange("maxBlastBlocksPerTick", 64, 1, 512);

    public static final ForgeConfigSpec.IntValue BLAST_SEARCH_DISTANCE = BUILDER
            .comment("Maximum Euclidean radius searched from each found block in blast modes. Range: 3-128.")
            .defineInRange("blastSearchDistance", 20, 3, 128);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
