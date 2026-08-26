package com.extrarawstyle.veinminerplus;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = VeinMinerPlus.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = VeinMinerPlus.MODID, value = Dist.CLIENT)
public final class VeinMinerPlusClient {
    private static final KeyMapping CHAIN_KEY = new KeyMapping(
            "key.veinminerplus.chain",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_GRAVE_ACCENT,
            "key.categories.veinminerplus");

    private static ChainMode clientMode = ChainMode.NORMAL;
    private static boolean modeSelectorOpen;

    public VeinMinerPlusClient(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(VeinMinerPlusClient::registerKeyMappings);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CHAIN_KEY);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!CHAIN_KEY.matches(event.getKey(), event.getScanCode())) {
            return;
        }

        if (event.getAction() == GLFW.GLFW_RELEASE) {
            modeSelectorOpen = false;
            NetworkHandler.sendKeyState(false);
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        if ((event.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0) {
            modeSelectorOpen = true;
        } else {
            NetworkHandler.sendKeyState(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!modeSelectorOpen || Minecraft.getInstance().screen != null || event.getScrollDeltaY() == 0.0D) {
            return;
        }

        int direction = event.getScrollDeltaY() > 0.0D ? -1 : 1;
        clientMode = ChainMode.cycle(clientMode, direction);
        NetworkHandler.sendModeChange(clientMode);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!modeSelectorOpen) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Component text = Component.translatable(clientMode.translationKey());
        graphics.drawString(Minecraft.getInstance().font, text, 8, 8, 0xFFFFFFFF);
    }
}
