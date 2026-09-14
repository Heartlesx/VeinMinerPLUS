package com.extrarawstyle.veinminerplus;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(VeinMinerPlus.MODID)
public class VeinMinerPlus {
    public static final String MODID = "veinminerplus";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VeinMinerPlus(IEventBus modEventBus, ModContainer modContainer) {
        ModDataComponents.register(modEventBus);
        ModItems.register(modEventBus);
        modEventBus.addListener(NetworkHandler::register);
        modEventBus.addListener(VeinMinerPlus::addCreativeTabContents);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        NeoForge.EVENT_BUS.register(new ChainEvents());
        NeoForge.EVENT_BUS.register(new CommandEvents());
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (ModList.get().isLoaded("ae2") && event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.CHAIN_MEMORY_CARD.get());
        }
    }
}
