package com.extrarawstyle.veinminerplus;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

@Mod(modid = VeinMinerPlus.MODID, name = VeinMinerPlus.NAME, version = VeinMinerPlus.VERSION,
        acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.12.2]")
public class VeinMinerPlus {
    public static final String MODID = "veinminerplus";
    public static final String NAME = "VeinMinerPlus";
    public static final String VERSION = "4.2.6-dev6-forge-1.12.2";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Config.init(event.getSuggestedConfigurationFile());
        NetworkHandler.register();
        MinecraftForge.EVENT_BUS.register(new ChainEvents());
        MinecraftForge.EVENT_BUS.register(new StorageBindingEvents());
        MinecraftForge.EVENT_BUS.register(ModItems.class);
        // Key bindings must exist before the options are loaded, and the branch keeps the client
        // class out of a dedicated server.
        if (FMLCommonHandler.instance().getSide().isClient()) {
            VeinMinerPlusClient.registerKeyBinding();
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        CommandEvents.onServerStarting(event);
        // The block registry is only complete once the registry events have run, so the blacklist
        // is resolved here rather than while the config file is read.
        BlockBlacklist.applyServerConfig();
    }
}
