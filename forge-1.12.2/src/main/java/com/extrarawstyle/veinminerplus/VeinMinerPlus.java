package com.extrarawstyle.veinminerplus;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

import org.apache.logging.log4j.Logger;

@Mod(modid = VeinMinerPlus.MODID, name = VeinMinerPlus.NAME, version = VeinMinerPlus.VERSION,
        acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.12.2]")
public class VeinMinerPlus {
    public static final String MODID = "veinminerplus";
    public static final String NAME = "VeinMinerPlus";
    public static final String VERSION = "4.1.5-forge-1.12.2";

    public static Logger logger;

    // TEMPORARY: diagnostics for the 1.12.2 field test. Remove once chaining is confirmed in game.
    public static void debug(String format, Object... args) {
        if (logger != null) {
            logger.info("[vmp] " + format, args);
        }
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
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
    }
}
