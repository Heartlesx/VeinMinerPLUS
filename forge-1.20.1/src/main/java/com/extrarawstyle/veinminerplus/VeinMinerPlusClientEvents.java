package com.extrarawstyle.veinminerplus;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = VeinMinerPlus.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VeinMinerPlusClientEvents {
    private VeinMinerPlusClientEvents() {
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!VeinMinerPlusClient.CHAIN_KEY.matches(event.getKey(), event.getScanCode())) {
            return;
        }

        if (event.getAction() == GLFW.GLFW_RELEASE) {
            VeinMinerPlusClient.modeSelectorOpen = false;
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
            VeinMinerPlusClient.modeSelectorOpen = true;
        } else {
            NetworkHandler.sendKeyState(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!VeinMinerPlusClient.modeSelectorOpen
                || Minecraft.getInstance().screen != null
                || event.getScrollDelta() == 0.0D) {
            return;
        }

        int direction = event.getScrollDelta() > 0.0D ? -1 : 1;
        VeinMinerPlusClient.clientMode = ChainMode.cycle(VeinMinerPlusClient.clientMode, direction);
        NetworkHandler.sendModeChange(VeinMinerPlusClient.clientMode);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!VeinMinerPlusClient.modeSelectorOpen) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Component text = Component.translatable(VeinMinerPlusClient.clientMode.translationKey());
        graphics.drawString(Minecraft.getInstance().font, text, 8, 8, 0xFFFFFFFF);
    }
}
