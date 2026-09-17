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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
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
    private static boolean keyStateSent;

    private VeinMinerPlusClient() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CHAIN_KEY);
    }

    static void openConfigScreen(NetworkHandler.ConfigSnapshotPayload config) {
        Minecraft.getInstance().setScreen(new VeinMinerConfigScreen(config));
    }

    static void resetSessionState() {
        clientMode = ChainMode.NORMAL;
        keyStateSent = false;
    }

    static void syncKeyState() {
        boolean held = isChainKeyActive(Minecraft.getInstance()) && !Screen.hasShiftDown();
        if (held == keyStateSent) {
            return;
        }

        NetworkHandler.sendKeyState(held);
        keyStateSent = held;
    }

    static void releaseKeyState() {
        if (!keyStateSent) {
            return;
        }

        NetworkHandler.sendKeyState(false);
        keyStateSent = false;
    }

    static boolean isModeSelectorOpen() {
        return isChainKeyActive(Minecraft.getInstance()) && Screen.hasShiftDown();
    }

    static void renderModeMenu(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isChainKeyActive(minecraft)) {
            return;
        }

        if (Screen.hasShiftDown()) {
            renderAllModes(graphics, minecraft);
        } else {
            renderCurrentMode(graphics, minecraft);
        }
    }

    // Outlines the positions the interaction mode would touch. The list comes from the same
    // helper the server job uses, so the preview cannot disagree with what actually happens.
    static void renderInteractPreview(RenderLevelStageEvent event) {
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

    private static boolean isChainKeyActive(Minecraft minecraft) {
        return minecraft.player != null && minecraft.level != null && minecraft.screen == null && CHAIN_KEY.isDown();
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
