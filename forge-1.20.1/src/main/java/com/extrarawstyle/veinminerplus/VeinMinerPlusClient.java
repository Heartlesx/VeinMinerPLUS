package com.extrarawstyle.veinminerplus;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = VeinMinerPlus.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VeinMinerPlusClient {
    static final KeyMapping CHAIN_KEY = new KeyMapping(
            "key.veinminerplus.chain",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_GRAVE_ACCENT,
            "key.categories.veinminerplus");

    static ChainMode clientMode = ChainMode.NORMAL;
    static boolean modeSelectorOpen;

    private VeinMinerPlusClient() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CHAIN_KEY);
    }
}
