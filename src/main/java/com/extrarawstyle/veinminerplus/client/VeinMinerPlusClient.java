package com.extrarawstyle.veinminerplus.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import com.extrarawstyle.veinminerplus.ChainMode;
import com.extrarawstyle.veinminerplus.NetworkHandler;
import com.extrarawstyle.veinminerplus.FastMineProgress;
import com.extrarawstyle.veinminerplus.VeinMinerPlus;

@Mod(value = VeinMinerPlus.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = VeinMinerPlus.MODID, value = Dist.CLIENT)
public final class VeinMinerPlusClient {
    private static final KeyMapping CHAIN_KEY = new KeyMapping(
            "key.veinminerplus.chain",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_GRAVE_ACCENT,
            "key.categories.veinminerplus");

    private static ChainMode clientMode = ChainMode.NORMAL;
    private static boolean keyStateSent;
    private static FastMineProgress fastMineProgress = new FastMineProgress(0, 0, 0, 0, 0, 0, 0, 0,
            FastMineProgress.State.IDLE);

    public VeinMinerPlusClient(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(VeinMinerPlusClient::registerKeyMappings);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CHAIN_KEY);
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        clientMode = ChainMode.NORMAL;
        fastMineProgress = new FastMineProgress(0, 0, 0, 0, 0, 0, 0, 0, FastMineProgress.State.IDLE);
        FastMineScreen.clearRememberedSelection();
        NetworkHandler.sendModeChange(clientMode);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clientMode = ChainMode.NORMAL;
        keyStateSent = false;
        fastMineProgress = new FastMineProgress(0, 0, 0, 0, 0, 0, 0, 0, FastMineProgress.State.IDLE);
        FastMineScreen.clearRememberedSelection();
    }

    public static void openConfigScreen(NetworkHandler.ConfigSnapshotPayload config) {
        Minecraft.getInstance().setScreen(new VeinMinerConfigScreen(config));
    }

    public static void updateFastMineProgress(FastMineProgress progress) {
        fastMineProgress = progress;
        if (Minecraft.getInstance().screen instanceof FastMineScreen screen) {
            screen.setProgress(progress);
        }
    }

    /**
     * Applies an immediate client-side terminal state while the server cancel
     * packet is in flight. The server remains authoritative and will overwrite
     * this snapshot with its final counters.
     */
    static void applyLocalFastMineProgress(FastMineProgress progress) {
        fastMineProgress = progress;
    }

    public static FastMineProgress fastMineProgress() {
        return fastMineProgress;
    }

    public static void openFastMineScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.level != null
                && !(minecraft.screen instanceof FastMineScreen)) {
            minecraft.setScreen(new FastMineScreen(fastMineProgress));
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!CHAIN_KEY.matches(event.getKey(), event.getScanCode())) {
            return;
        }

        if (event.getAction() == GLFW.GLFW_RELEASE) {
            releaseKeyState();
            return;
        }
        if (event.getAction() == GLFW.GLFW_PRESS) {
            syncKeyState();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen == null && !Screen.hasShiftDown() && clientMode.isFastMine()) {
                openFastMineScreen();
            }
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!isModeSelectorOpen() || event.getScrollDeltaY() == 0.0D) {
            return;
        }

        int direction = event.getScrollDeltaY() > 0.0D ? -1 : 1;
        clientMode = ChainMode.cycle(clientMode, direction);
        NetworkHandler.sendModeChange(clientMode);
        if (clientMode.isFastMine()) {
            openFastMineScreen();
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isChainKeyActive(minecraft)) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        if (Screen.hasShiftDown()) {
            renderAllModes(graphics, minecraft);
        } else {
            renderCurrentMode(graphics, minecraft);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        syncKeyState();
    }

    private static void syncKeyState() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean held = isChainKeyActive(minecraft) && !Screen.hasShiftDown();
        if (held == keyStateSent) {
            return;
        }

        NetworkHandler.sendKeyState(held);
        keyStateSent = held;
    }

    private static void releaseKeyState() {
        if (!keyStateSent) {
            return;
        }

        NetworkHandler.sendKeyState(false);
        keyStateSent = false;
    }

    private static boolean isModeSelectorOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        return isChainKeyActive(minecraft) && Screen.hasShiftDown();
    }

    private static boolean isChainKeyActive(Minecraft minecraft) {
        return minecraft.player != null
                && minecraft.level != null
                && minecraft.screen == null
                && CHAIN_KEY.isDown();
    }

    private static void renderCurrentMode(GuiGraphics graphics, Minecraft minecraft) {
        Component text = Component.translatable(clientMode.translationKey());
        int width = minecraft.font.width(text);
        graphics.fill(4, 4, 12 + width, 18, 0xA0000000);
        graphics.drawString(minecraft.font, text, 8, 8, 0xFFFFFFFF);
    }

    private static void renderAllModes(GuiGraphics graphics, Minecraft minecraft) {
        ChainMode[] modes = ChainMode.values();
        int width = 0;
        for (ChainMode mode : modes) {
            width = Math.max(width, minecraft.font.width(Component.translatable(mode.translationKey())));
        }

        int lineHeight = minecraft.font.lineHeight;
        graphics.fill(4, 4, 12 + width, 12 + modes.length * lineHeight, 0xA0000000);
        for (int index = 0; index < modes.length; index++) {
            ChainMode mode = modes[index];
            int color = mode == clientMode ? 0xFFFFFF55 : 0xFFFFFFFF;
            graphics.drawString(minecraft.font, Component.translatable(mode.translationKey()), 8,
                    8 + index * lineHeight, color);
        }
    }
}
