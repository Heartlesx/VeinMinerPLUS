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
import net.minecraft.world.item.ItemStack;
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
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

@Mod(value = VeinMinerPlus.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = VeinMinerPlus.MODID, value = Dist.CLIENT)
public final class VeinMinerPlusClient {
    private static final KeyMapping CHAIN_KEY = new KeyMapping(
            "key.veinminerplus.chain",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_GRAVE_ACCENT,
            "key.categories.veinminerplus");
    private static final KeyMapping BLACKLIST_KEY = new KeyMapping(
            "key.veinminerplus.blacklist",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_ADD,
            "key.categories.veinminerplus");
    // Shift + arrow keys switch modes on their own, without the chain key. Both are
    // rebindable, and the shift part belongs to the binding instead of a hardcoded check.
    private static final KeyMapping MODE_UP_KEY = new KeyMapping(
            "key.veinminerplus.mode_up",
            KeyConflictContext.IN_GAME,
            KeyModifier.SHIFT,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UP,
            "key.categories.veinminerplus");
    private static final KeyMapping MODE_DOWN_KEY = new KeyMapping(
            "key.veinminerplus.mode_down",
            KeyConflictContext.IN_GAME,
            KeyModifier.SHIFT,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_DOWN,
            "key.categories.veinminerplus");

    // A held mode key steps every 0.5 s; the OS repeat rate would race through the list.
    private static final int MODE_REPEAT_TICKS = 10;
    // The full mode list lingers for 0.5 s after the latest switch.
    private static final int MODE_MENU_LINGER_TICKS = 10;
    // The last valid preview outline is held for 0.4 s so a briefly invalid aim does not strobe.
    private static final int PREVIEW_HOLD_TICKS = 8;

    private static ChainMode clientMode = ChainMode.NORMAL;
    private static boolean keyStateSent;
    private static int modeKeyHoldTicks;
    private static int modeMenuTicks;
    private static List<BlockPos> previewTargets = List.of();
    private static ItemStack previewItem = ItemStack.EMPTY;
    private static int previewHoldTicks;

    public VeinMinerPlusClient(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(VeinMinerPlusClient::registerKeyMappings);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CHAIN_KEY);
        event.register(BLACKLIST_KEY);
        event.register(MODE_UP_KEY);
        event.register(MODE_DOWN_KEY);
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        clientMode = ChainMode.NORMAL;
        modeKeyHoldTicks = 0;
        modeMenuTicks = 0;
        NetworkHandler.sendModeChange(clientMode);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clientMode = ChainMode.NORMAL;
        keyStateSent = false;
        modeKeyHoldTicks = 0;
        modeMenuTicks = 0;
        previewTargets = List.of();
        previewItem = ItemStack.EMPTY;
        previewHoldTicks = 0;
    }

    static void openConfigScreen(NetworkHandler.ConfigSnapshotPayload config) {
        Minecraft.getInstance().setScreen(new VeinMinerConfigScreen(config));
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (BLACKLIST_KEY.matches(event.getKey(), event.getScanCode())) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                toggleBlacklist();
            }
            return;
        }

        // Mode switching reacts to the press only; the held repeat is timed in the client
        // tick because GLFW repeat events fire at the OS rate.
        if (event.getAction() == GLFW.GLFW_PRESS && Minecraft.getInstance().player != null) {
            InputConstants.Key pressed = InputConstants.getKey(event.getKey(), event.getScanCode());
            if (MODE_UP_KEY.isActiveAndMatches(pressed)) {
                switchMode(-1);
                modeKeyHoldTicks = 0;
                return;
            }
            if (MODE_DOWN_KEY.isActiveAndMatches(pressed)) {
                switchMode(1);
                modeKeyHoldTicks = 0;
                return;
            }
        }

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

    // Sneak + blacklist key toggles the aimed block in the shared blacklist. The server
    // revalidates permission, the sneak posture and the distance before it changes anything.
    private static void toggleBlacklist() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || !minecraft.player.isShiftKeyDown()) {
            return;
        }

        if (minecraft.hitResult instanceof BlockHitResult hit) {
            NetworkHandler.sendBlacklistToggle(hit.getBlockPos());
        } else {
            minecraft.player.displayClientMessage(
                    Component.translatable("message.veinminerplus.blacklist.no_target"), true);
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
        GuiGraphics graphics = event.getGuiGraphics();

        if (isChainKeyActive(minecraft)) {
            if (Screen.hasShiftDown()) {
                renderAllModes(graphics, minecraft);
            } else {
                renderCurrentMode(graphics, minecraft);
            }
            return;
        }

        if (modeMenuTicks > 0 && minecraft.player != null && minecraft.screen == null) {
            renderAllModes(graphics, minecraft);
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
        if (!isChainKeyActive(minecraft)) {
            return;
        }

        List<BlockPos> targets = resolvePreviewTargets(minecraft);
        if (targets.isEmpty()) {
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

    // The outline follows the crosshair and is recomputed every frame. While the player moves the
    // aimed block can fall out of the valid set for single frames, so the last valid preview is
    // held briefly instead of blinking off; a fresh valid aim replaces it immediately.
    private static List<BlockPos> resolvePreviewTargets(Minecraft minecraft) {
        if (minecraft.level != null && minecraft.hitResult instanceof BlockHitResult hit) {
            List<BlockPos> targets = new ArrayList<>();
            if (ChainEvents.collectInteractTargets(minecraft.level, hit.getBlockPos(),
                    minecraft.player.getMainHandItem(), ChainEvents.INTERACT_PREVIEW_LIMIT, targets)
                    && !targets.isEmpty()) {
                previewTargets = targets;
                previewItem = minecraft.player.getMainHandItem().copy();
                previewHoldTicks = PREVIEW_HOLD_TICKS;
                return targets;
            }
        }
        if (previewHoldTicks > 0
                && ItemStack.isSameItemSameComponents(previewItem, minecraft.player.getMainHandItem())) {
            return previewTargets;
        }
        return List.of();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        syncKeyState();
        tickModeKeys();
        if (previewHoldTicks > 0) {
            previewHoldTicks--;
        }
    }

    private static void tickModeKeys() {
        if (modeMenuTicks > 0) {
            modeMenuTicks--;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            modeKeyHoldTicks = 0;
            return;
        }

        int direction = 0;
        if (MODE_UP_KEY.isDown()) {
            direction--;
        }
        if (MODE_DOWN_KEY.isDown()) {
            direction++;
        }

        if (direction == 0) {
            modeKeyHoldTicks = 0;
            return;
        }

        modeKeyHoldTicks++;
        if (modeKeyHoldTicks >= MODE_REPEAT_TICKS) {
            modeKeyHoldTicks = 0;
            switchMode(direction);
        }
    }

    // The key path shows the full list for a moment after each switch; the wheel path
    // keeps its own selector, which stays open while the chain key and shift are held.
    private static void switchMode(int direction) {
        clientMode = ChainMode.cycle(clientMode, direction);
        NetworkHandler.sendModeChange(clientMode);
        modeMenuTicks = MODE_MENU_LINGER_TICKS;
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
