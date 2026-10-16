package com.extrarawstyle.veinminerplus;

import java.io.File;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

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

    private static Configuration config;

    public static int maxNormalBlocks = DEFAULT_MAX_NORMAL_BLOCKS;
    public static int maxNormalBlocksPerTick = DEFAULT_MAX_NORMAL_BLOCKS_PER_TICK;
    public static int maxBlastBlocks = DEFAULT_MAX_BLAST_BLOCKS;
    public static int maxBlastBlocksPerTick = DEFAULT_MAX_BLAST_BLOCKS_PER_TICK;
    public static int blastSearchDistance = DEFAULT_BLAST_SEARCH_DISTANCE;
    public static boolean noHungerCost = DEFAULT_NO_HUNGER_COST;
    public static boolean storageBinding = DEFAULT_STORAGE_BINDING;
    public static String[] blockBlacklist = new String[0];

    private Config() {
    }

    public static void init(File file) {
        config = new Configuration(file);
        load();
    }

    private static void load() {
        maxNormalBlocks = config.getInt("maxNormalBlocks", "general", DEFAULT_MAX_NORMAL_BLOCKS,
                MIN_MAX_NORMAL_BLOCKS, MAX_MAX_NORMAL_BLOCKS,
                "普通连锁模式的最大方块数。范围：32-32767。");
        maxNormalBlocksPerTick = config.getInt("maxNormalBlocksPerTick", "general",
                DEFAULT_MAX_NORMAL_BLOCKS_PER_TICK, MIN_MAX_NORMAL_BLOCKS_PER_TICK,
                MAX_MAX_NORMAL_BLOCKS_PER_TICK,
                "普通连锁与 1x1/3x3 范围模式每个服务端 Tick 的最大挖掘数。范围：1-384。");
        maxBlastBlocks = config.getInt("maxBlastBlocks", "general", DEFAULT_MAX_BLAST_BLOCKS,
                MIN_MAX_BLAST_BLOCKS, MAX_MAX_BLAST_BLOCKS,
                "爆破模式的最大方块数。范围：32-32767。");
        maxBlastBlocksPerTick = config.getInt("maxBlastBlocksPerTick", "general",
                DEFAULT_MAX_BLAST_BLOCKS_PER_TICK, MIN_MAX_BLAST_BLOCKS_PER_TICK,
                MAX_MAX_BLAST_BLOCKS_PER_TICK,
                "爆破模式每个服务端 Tick 的最大挖掘数。范围：1-512。");
        blastSearchDistance = config.getInt("blastSearchDistance", "general", DEFAULT_BLAST_SEARCH_DISTANCE,
                MIN_BLAST_SEARCH_DISTANCE, MAX_BLAST_SEARCH_DISTANCE,
                "爆破模式从每个已发现方块搜索的最大欧氏距离。范围：3-128。");
        noHungerCost = config.getBoolean("noHungerCost", "general", DEFAULT_NO_HUNGER_COST,
                "启用后，自动连锁挖掘的方块不会消耗饱食度；默认关闭。");
        storageBinding = config.getBoolean("storageBinding", "general", DEFAULT_STORAGE_BINDING,
                "启用后，连锁挖掘的掉落物优先送入存储绑定卡绑定的存储，塞不下的才掉在地上；默认开启。");
        blockBlacklist = config.getStringList("blockBlacklist", "general", new String[0],
                "方块黑名单：列表中的方块不会被连锁/爆破破坏，也不会触发连锁。填写方块 ID，例如 appliedenergistics2:cable_bus。");

        if (config.hasChanged()) {
            config.save();
        }
    }

    public static void save() {
        if (config == null) {
            return;
        }
        // The file is written from the Configuration's own properties, not from the statics above,
        // so the two have to be brought together first. Without this a change made in game is stored
        // as the value the file was loaded with, and the next launch reads that old value back.
        setInt("maxNormalBlocks", maxNormalBlocks);
        setInt("maxNormalBlocksPerTick", maxNormalBlocksPerTick);
        setInt("maxBlastBlocks", maxBlastBlocks);
        setInt("maxBlastBlocksPerTick", maxBlastBlocksPerTick);
        setInt("blastSearchDistance", blastSearchDistance);
        setBoolean("noHungerCost", noHungerCost);
        setBoolean("storageBinding", storageBinding);
        setStringList("blockBlacklist", blockBlacklist);
        config.save();
    }

    // Written through the property the file was read with, so the entry keeps its type.
    private static void setInt(String key, int value) {
        Property property = config.getCategory("general").get(key);
        if (property != null) {
            property.set(value);
        }
    }

    private static void setBoolean(String key, boolean value) {
        Property property = config.getCategory("general").get(key);
        if (property != null) {
            property.set(value);
        }
    }

    private static void setStringList(String key, String[] value) {
        Property property = config.getCategory("general").get(key);
        if (property != null) {
            property.set(value);
        }
    }
}
