package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalDouble;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
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

    private static ChainMode clientMode = ChainMode.NORMAL;
    private static boolean keyStateSent;
    private static int modeKeyHoldTicks;
    private static int modeMenuTicks;
    private static List<BlockPos> previewTargets = List.of();
    private static ItemStack previewItem = ItemStack.EMPTY;
    // The aimed block the current preview was built for; a frame that aims at the same block reuses
    // the result instead of rebuilding it.
    private static BlockPos previewOrigin;
    // The set used for the outline geometry (targets plus the aimed block) and the aimed block whose
    // own box edges are hidden, cached next to the targets.
    private static List<BlockPos> previewOutlineArea = List.of();
    private static BlockPos previewHiddenOrigin;

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
        clearPreview();
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
            // A switch that just happened shows the full list too: on a key-path switch the list
            // must appear even when Shift cannot act as the selector modifier (chain key = Shift).
            if (isSelectorModifierHeld() || modeMenuTicks > 0) {
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
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (clientMode != ChainMode.SPECIAL_INTERACT) {
            // Leaving the mode drops the cached area as well, so it cannot reappear later.
            clearPreview();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!isChainKeyActive(minecraft)) {
            // Releasing the key ends the preview outright, so pressing it again starts from the
            // current aim instead of bringing the area from the last press back.
            clearPreview();
            return;
        }

        List<BlockPos> targets = resolvePreviewTargets(minecraft);
        if (targets.isEmpty()) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(PREVIEW_LINES);
        // The vertices are already camera-relative (see InteractOutline.emit), so the camera offset
        // must not be applied a second time here.
        poseStack.pushPose();
        InteractOutline.render(poseStack, lines, new LinkedHashSet<>(targets), previewHiddenOrigin,
                camera, 1.0F, 1.0F, 1.0F, 1.0F);
        poseStack.popPose();
        buffers.endBatch(PREVIEW_LINES);
    }

    // One physical pixel: the stock LINES type leaves the line width unset, which the driver rounds
    // up to at least 2.5 px. Every other state matches LINES, so the preview keeps its occlusion and
    // camera handling and only the width differs.
    private static final RenderType PREVIEW_LINES = RenderType.create(
            "veinminerplus_preview_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(1.0)))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    // The outline follows the crosshair and is recomputed every frame.
    //
    // A ray that hit nothing still arrives as a BlockHitResult, as does a hit on an entity, so the
    // type has to be checked: `instanceof BlockHitResult` alone would treat the far end of the ray
    // as an aimed block and walk the area around a patch of air. Anything that is not a real block
    // hit drops the preview immediately; there is no hold-over, so aiming at the sky stops the
    // outline at once instead of leaving the last area on screen.
    //
    // The cache is keyed on the aimed block and the held item, not on the frame: an unchanged aim
    // reuses the previous result, so the outline cannot drift relative to the world it describes
    // while the camera moves. A new valid aim replaces it at once.
    private static List<BlockPos> resolvePreviewTargets(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null
                || !(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            clearPreview();
            return List.of();
        }

        BlockPos aim = hit.getBlockPos().immutable();
        BlockState aimState = minecraft.level.getBlockState(aim);
        if (aimState.isAir()) {
            // A block hit on air cannot be acted on, and letting it seed the search would collect the
            // surrounding air as a region.
            clearPreview();
            return List.of();
        }

        ItemStack held = minecraft.player.getMainHandItem();
        if (aim.equals(previewOrigin) && ItemStack.isSameItemSameComponents(previewItem, held)
                && !previewTargets.isEmpty() && held.getItem() == previewItem.getItem()) {
            return previewTargets;
        }

        List<BlockPos> targets = new ArrayList<>();
        if (ChainEvents.collectInteractTargets(minecraft.level, aim, held,
                ChainEvents.INTERACT_PREVIEW_LIMIT, targets) && !targets.isEmpty()) {
            ChainEvents.InteractAction action = ChainEvents.interactAction(minecraft.level, aim, held);
            previewOrigin = aim;
            previewTargets = targets;
            previewItem = held.copy();
            // Only an action that leaves the aimed block out of the area hides its box. The outline
            // topology still includes it, so the region has no fake hole in the middle.
            previewHiddenOrigin = action != null && !action.originCounts() ? aim : null;
            return targets;
        }
        // A real block hit that the held item has no rule for also clears the old area, so a stale
        // outline never stands in for what the new aim would do.
        clearPreview();
        return List.of();
    }

    // A stale preview must never come back when the key is pressed again: releasing the key, leaving
    // the mode or swapping the item throws the cached area away instead of keeping it around.
    private static void tickPreviewValidity() {
        if (!previewTargets.isEmpty()) {
            Minecraft minecraft = Minecraft.getInstance();
            boolean stillValid = clientMode == ChainMode.SPECIAL_INTERACT
                    && minecraft.player != null
                    && minecraft.player.getMainHandItem().getItem() == previewItem.getItem();
            if (!stillValid) {
                clearPreview();
            }
        }
    }

    // Resets every cached piece of the preview. Plain field writes only: this runs from the client
    // logging-out event during world load and from several render paths, so it must not touch the
    // player, the level or the GPU, and it must be safe to call in any state, repeatedly.
    private static void clearPreview() {
        previewTargets = List.of();
        previewItem = ItemStack.EMPTY;
        previewOrigin = null;
        previewOutlineArea = List.of();
        previewHiddenOrigin = null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        syncKeyState();
        tickModeKeys();
        tickPreviewValidity();
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
        boolean held = isChainKeyActive(minecraft) && !isSelectorModifierHeld();
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

    // The wheel selector's gate is "chain key held + Shift". When the player binds the chain key
    // to a Shift key itself, that gate would be open all the time, so the selector gives way to
    // chaining: the wheel no longer opens it, and Shift+arrow switching keeps working.
    private static boolean isSelectorModifierHeld() {
        return Screen.hasShiftDown() && !KeyModifier.SHIFT.matches(CHAIN_KEY.getKey());
    }

    private static boolean isModeSelectorOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        return isChainKeyActive(minecraft) && isSelectorModifierHeld();
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
