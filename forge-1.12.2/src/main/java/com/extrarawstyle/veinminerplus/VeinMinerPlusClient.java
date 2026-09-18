package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class VeinMinerPlusClient {
    static final KeyBinding CHAIN_KEY = new KeyBinding("key.veinminerplus.chain",
            Keyboard.KEY_GRAVE, "key.categories.veinminerplus");

    static ChainMode clientMode = ChainMode.NORMAL;
    private static boolean keyStateSent;

    private VeinMinerPlusClient() {
    }

    static void registerKeyBinding() {
        ClientRegistry.registerKeyBinding(CHAIN_KEY);
    }

    static void openConfigScreen(NetworkHandler.ConfigSnapshotMessage config) {
        // Network messages arrive on the netty thread, so the screen is opened on the client thread.
        Minecraft.getMinecraft().addScheduledTask(
                () -> Minecraft.getMinecraft().displayGuiScreen(new VeinMinerConfigScreen(config)));
    }

    static void resetSessionState() {
        clientMode = ChainMode.NORMAL;
        keyStateSent = false;
    }

    static void reportKeyBinding() {
        VeinMinerPlus.debug("client session start: chainKey='{}' keyCode={} defaultCode={} mode={}",
                CHAIN_KEY.getKeyDescription(), CHAIN_KEY.getKeyCode(), CHAIN_KEY.getKeyCodeDefault(),
                clientMode);
    }

    static void syncKeyState() {
        boolean held = isChainKeyActive(Minecraft.getMinecraft()) && !GuiScreen.isShiftKeyDown();
        if (held == keyStateSent) {
            return;
        }

        NetworkHandler.sendKeyState(held);
        keyStateSent = held;
        VeinMinerPlus.debug("client key -> held={} bindingDown={} lwjglDown={} keyCode={}", held,
                CHAIN_KEY.isKeyDown(), Keyboard.isKeyDown(CHAIN_KEY.getKeyCode()), CHAIN_KEY.getKeyCode());
    }

    static void releaseKeyState() {
        if (!keyStateSent) {
            return;
        }

        NetworkHandler.sendKeyState(false);
        keyStateSent = false;
    }

    static boolean isModeSelectorOpen() {
        return isChainKeyActive(Minecraft.getMinecraft()) && GuiScreen.isShiftKeyDown();
    }

    static void renderModeMenu(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!isChainKeyActive(minecraft)) {
            return;
        }

        if (GuiScreen.isShiftKeyDown()) {
            renderAllModes(minecraft);
        } else {
            renderCurrentMode(minecraft);
        }
    }

    // Outlines the positions the interaction mode would touch. The list comes from the same
    // helper the server job uses, so the preview cannot disagree with what actually happens.
    static void renderInteractPreview(RenderWorldLastEvent event) {
        if (clientMode != ChainMode.SPECIAL_INTERACT) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (!isChainKeyActive(minecraft) || minecraft.objectMouseOver == null
                || minecraft.objectMouseOver.typeOfHit != RayTraceResult.Type.BLOCK) {
            return;
        }

        List<BlockPos> targets = new ArrayList<>();
        if (!ChainEvents.collectInteractTargets(minecraft.world, minecraft.objectMouseOver.getBlockPos(),
                minecraft.player.getHeldItemMainhand(), ChainEvents.INTERACT_PREVIEW_LIMIT, targets)
                || targets.isEmpty()) {
            return;
        }

        Entity view = minecraft.getRenderViewEntity();
        float partialTicks = event.getPartialTicks();
        double viewX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double viewY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double viewZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.glLineWidth(2.0F);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);
        for (BlockPos pos : targets) {
            RenderGlobal.drawSelectionBoundingBox(
                    new AxisAlignedBB(pos).grow(0.002D).offset(-viewX, -viewY, -viewZ),
                    1.0F, 1.0F, 1.0F, 1.0F);
        }
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    private static boolean isChainKeyActive(Minecraft minecraft) {
        return minecraft.player != null && minecraft.world != null && minecraft.currentScreen == null
                && CHAIN_KEY.isKeyDown();
    }

    private static String modeName(ChainMode mode) {
        return new TextComponentTranslation(mode.translationKey()).getUnformattedText();
    }

    private static void renderCurrentMode(Minecraft minecraft) {
        String text = modeName(clientMode);
        int width = minecraft.fontRenderer.getStringWidth(text);
        Gui.drawRect(4, 4, 12 + width, 18, 0xA0000000);
        minecraft.fontRenderer.drawStringWithShadow(text, 8, 8, 0xFFFFFFFF);
    }

    private static void renderAllModes(Minecraft minecraft) {
        ChainMode[] modes = ChainMode.values();
        int width = 0;
        for (ChainMode mode : modes) {
            width = Math.max(width, minecraft.fontRenderer.getStringWidth(modeName(mode)));
        }

        int lineHeight = minecraft.fontRenderer.FONT_HEIGHT;
        Gui.drawRect(4, 4, 12 + width, 12 + modes.length * lineHeight, 0xA0000000);
        for (int index = 0; index < modes.length; index++) {
            int color = modes[index] == clientMode ? 0xFFFF55 : 0xFFFFFF;
            minecraft.fontRenderer.drawStringWithShadow(modeName(modes[index]), 8, 8 + index * lineHeight, color);
        }
    }
}
