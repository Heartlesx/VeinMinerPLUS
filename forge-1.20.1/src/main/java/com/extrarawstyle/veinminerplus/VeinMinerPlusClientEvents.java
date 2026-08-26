package com.extrarawstyle.veinminerplus;

import org.lwjgl.glfw.GLFW;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
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
            VeinMinerPlusClient.releaseKeyState();
            return;
        }
        if (event.getAction() == GLFW.GLFW_PRESS) {
            VeinMinerPlusClient.syncKeyState();
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!VeinMinerPlusClient.isModeSelectorOpen()
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
        VeinMinerPlusClient.renderModeMenu(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            VeinMinerPlusClient.syncKeyState();
        }
    }
}
