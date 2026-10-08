package com.extrarawstyle.veinminerplus;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public final class Config {
    private static Configuration config;

    public static int maxNormalBlocks = 1024;
    public static int maxNormalBlocksPerTick = 8;
    public static int maxBlastBlocks = 32767;
    public static int maxBlastBlocksPerTick = 64;
    public static int blastSearchDistance = 20;
    public static boolean noHungerCost = false;
    public static boolean storageBinding = true;

    private Config() {
    }

    public static void init(File file) {
        config = new Configuration(file);
        load();
    }

    private static void load() {
        maxNormalBlocks = config.getInt("maxNormalBlocks", "general", 1024, 32, 32767,
                "普通连锁模式的最大方块数。范围：32-32767。");
        maxNormalBlocksPerTick = config.getInt("maxNormalBlocksPerTick", "general", 8, 1, 384,
                "普通连锁模式每个服务端 Tick 的最大挖掘数。范围：1-384。");
        maxBlastBlocks = config.getInt("maxBlastBlocks", "general", 32767, 32, 32767,
                "爆破模式的最大方块数。范围：32-32767。");
        maxBlastBlocksPerTick = config.getInt("maxBlastBlocksPerTick", "general", 64, 1, 512,
                "爆破模式每个服务端 Tick 的最大挖掘数。范围：1-512。");
        blastSearchDistance = config.getInt("blastSearchDistance", "general", 20, 3, 128,
                "爆破模式从每个已发现方块搜索的最大欧氏距离。范围：3-128。");
        noHungerCost = config.getBoolean("noHungerCost", "general", false,
                "启用后，自动连锁挖掘的方块不会消耗饱食度；默认关闭。");
        storageBinding = config.getBoolean("storageBinding", "general", true,
                "启用后，连锁挖掘的掉落物优先送入存储绑定卡绑定的存储，塞不下的才掉在地上；默认开启。");

        if (config.hasChanged()) {
            config.save();
        }
    }

    public static void save() {
        if (config != null) {
            config.save();
        }
    }
}
