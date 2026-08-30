package com.extrarawstyle.veinminerplus;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

@Mod(VeinMinerPlus.MODID)
public class VeinMinerPlus {
    public static final String MODID = "veinminerplus";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VeinMinerPlus(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(NetworkHandler::register);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(new ChainEvents());
        NeoForge.EVENT_BUS.register(new CommandEvents());
    }
}
