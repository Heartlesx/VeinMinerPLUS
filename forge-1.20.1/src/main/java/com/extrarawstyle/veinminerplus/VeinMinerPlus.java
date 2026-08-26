package com.extrarawstyle.veinminerplus;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(VeinMinerPlus.MODID)
public class VeinMinerPlus {
    public static final String MODID = "veinminerplus";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VeinMinerPlus() {
        NetworkHandler.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(new ChainEvents());
    }
}
