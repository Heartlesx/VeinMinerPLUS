package com.extrarawstyle.veinminerplus;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = VeinMinerPlus.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VeinMinerPlusClientEvents {
    private VeinMinerPlusClientEvents() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        VeinMinerPlusClient.resetSessionState();
        NetworkHandler.sendModeChange(VeinMinerPlusClient.clientMode);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        VeinMinerPlusClient.resetSessionState();
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (VeinMinerPlusClient.BLACKLIST_KEY.matches(event.getKey(), event.getScanCode())) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                VeinMinerPlusClient.toggleBlacklist();
            }
            return;
        }

        // Mode switching reacts to the press only; the held repeat is timed in the client
        // tick because GLFW repeat events fire at the OS rate.
        if (event.getAction() == GLFW.GLFW_PRESS && Minecraft.getInstance().player != null) {
            InputConstants.Key pressed = InputConstants.getKey(event.getKey(), event.getScanCode());
            if (VeinMinerPlusClient.MODE_UP_KEY.isActiveAndMatches(pressed)) {
                VeinMinerPlusClient.switchMode(-1);
                VeinMinerPlusClient.modeKeyHoldTicks = 0;
                return;
            }
            if (VeinMinerPlusClient.MODE_DOWN_KEY.isActiveAndMatches(pressed)) {
                VeinMinerPlusClient.switchMode(1);
                VeinMinerPlusClient.modeKeyHoldTicks = 0;
                return;
            }
        }

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
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        VeinMinerPlusClient.renderInteractPreview(event);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            VeinMinerPlusClient.syncKeyState();
            VeinMinerPlusClient.tickModeKeys();
            if (VeinMinerPlusClient.previewHoldTicks > 0) {
                VeinMinerPlusClient.previewHoldTicks--;
            }
        }
    }
}
