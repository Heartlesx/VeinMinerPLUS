package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
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
    private static boolean keyStateSent;

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
        NetworkHandler.sendModeChange(clientMode);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clientMode = ChainMode.NORMAL;
        keyStateSent = false;
    }

    static void openConfigScreen(NetworkHandler.ConfigSnapshotPayload config) {
        Minecraft.getInstance().setScreen(new VeinMinerConfigScreen(config));
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

    // Outlines the positions the interaction mode would touch. The list comes from the same
    // helper the server job uses, so the preview cannot disagree with what actually happens.
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || clientMode != ChainMode.SPECIAL_INTERACT) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!isChainKeyActive(minecraft) || !(minecraft.hitResult instanceof BlockHitResult hit)) {
            return;
        }

        List<BlockPos> targets = new ArrayList<>();
        if (!ChainEvents.collectInteractTargets(minecraft.level, hit.getBlockPos(),
                minecraft.player.getMainHandItem(), ChainEvents.INTERACT_PREVIEW_LIMIT, targets)
                || targets.isEmpty()) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (BlockPos pos : targets) {
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos), 1.0F, 1.0F, 1.0F, 1.0F);
        }
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
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
