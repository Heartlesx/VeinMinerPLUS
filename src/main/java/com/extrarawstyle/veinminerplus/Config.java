package com.extrarawstyle.veinminerplus;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_NORMAL_BLOCKS = BUILDER
            .comment("Maximum blocks in normal connected mode. Range: 32-32767.")
            .defineInRange("maxNormalBlocks", 1024, 32, 32767);

    public static final ModConfigSpec.IntValue MAX_NORMAL_BLOCKS_PER_TICK = BUILDER
            .comment("Maximum normal chain blocks broken per server tick. Range: 1-384.")
            .defineInRange("maxNormalBlocksPerTick", 8, 1, 384);

    public static final ModConfigSpec.IntValue MAX_BLAST_BLOCKS = BUILDER
            .comment("Maximum blocks in blast modes. Range: 32-32767.")
            .defineInRange("maxBlastBlocks", 32767, 32, 32767);

    public static final ModConfigSpec.IntValue MAX_BLAST_BLOCKS_PER_TICK = BUILDER
            .comment("Maximum blast blocks broken per server tick. Range: 1-256.")
            .defineInRange("maxBlastBlocksPerTick", 32, 1, 256);

    public static final ModConfigSpec.IntValue BLAST_SEARCH_DISTANCE = BUILDER
            .comment("Maximum Manhattan distance searched from each found block in blast modes. Range: 3-32.")
            .defineInRange("blastSearchDistance", 20, 3, 32);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
