package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
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
    private static final KeyBinding BLACKLIST_KEY = new KeyBinding("key.veinminerplus.blacklist",
            Keyboard.KEY_ADD, "key.categories.veinminerplus");
    // Shift + arrow keys switch modes. 1.12.2 Forge has no key modifier facility, so the shift part
    // of these bindings cannot live in the binding itself; it is checked in the client tick and the
    // control screen shows them as plain arrow keys.
    private static final KeyBinding MODE_UP_KEY = new KeyBinding("key.veinminerplus.mode_up",
            Keyboard.KEY_UP, "key.categories.veinminerplus");
    private static final KeyBinding MODE_DOWN_KEY = new KeyBinding("key.veinminerplus.mode_down",
            Keyboard.KEY_DOWN, "key.categories.veinminerplus");

    private static final int PROGRESS_TICKS = 60;
    // A held mode key steps every 0.5 s; the OS repeat rate would race through the list.
    private static final int MODE_REPEAT_TICKS = 10;
    // The full mode list lingers for 0.5 s after the latest switch.
    private static final int MODE_MENU_LINGER_TICKS = 10;
    // The last valid preview outline is held for 0.4 s so a briefly invalid aim does not strobe.
    private static final int PREVIEW_HOLD_TICKS = 8;

    static ChainMode clientMode = ChainMode.NORMAL;
    private static boolean keyStateSent;
    private static boolean blacklistKeyDown;
    private static boolean modeKeyDown;
    private static int modeKeyHoldTicks;
    private static int modeMenuTicks;
    private static int progressCount;
    private static boolean progressInteract;
    private static int progressTicks;
    private static List<BlockPos> previewTargets = Collections.emptyList();
    private static ItemStack previewItem = ItemStack.EMPTY;
    private static int previewHoldTicks;
    // The region walk is bounded by the job's 1024 positions, which is too much work to repeat for
    // every frame while the player does not move. The aim is resolved at most once per client tick;
    // a different aimed block, face or held item resolves immediately.
    private static int previewTick;
    private static int previewResolvedTick = -1;
    private static BlockPos previewOrigin;
    private static EnumFacing previewFace;

    private VeinMinerPlusClient() {
    }

    static void registerKeyBinding() {
        ClientRegistry.registerKeyBinding(CHAIN_KEY);
        ClientRegistry.registerKeyBinding(BLACKLIST_KEY);
        ClientRegistry.registerKeyBinding(MODE_UP_KEY);
        ClientRegistry.registerKeyBinding(MODE_DOWN_KEY);
    }

    static void openConfigScreen(NetworkHandler.ConfigSnapshotMessage config) {
        // Network messages arrive on the netty thread, so the screen is opened on the client thread.
        Minecraft.getMinecraft().addScheduledTask(
                () -> Minecraft.getMinecraft().displayGuiScreen(new VeinMinerConfigScreen(config)));
    }

    // The server's blacklist arrives on the netty thread, and rebuilding the mirror touches the
    // block registry, so it is done on the client thread.
    static void applyBlacklist(List<String> entries) {
        Minecraft.getMinecraft().addScheduledTask(() -> BlockBlacklist.applyRemote(entries));
    }

    static void resetSessionState() {
        clientMode = ChainMode.NORMAL;
        keyStateSent = false;
        modeKeyDown = false;
        modeKeyHoldTicks = 0;
        modeMenuTicks = 0;
        progressTicks = 0;
        previewTargets = Collections.emptyList();
        previewItem = ItemStack.EMPTY;
        previewHoldTicks = 0;
        previewResolvedTick = -1;
        previewOrigin = null;
        previewFace = null;
    }

    // Network messages arrive on the netty thread, so the fields are written on the client thread.
    static void onProgress(int count, boolean interact) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            progressCount = count;
            progressInteract = interact;
            progressTicks = PROGRESS_TICKS;
        });
    }

    static void tickProgress() {
        if (progressTicks > 0) {
            progressTicks--;
        }
    }

    static void tickPreviewHold() {
        if (previewHoldTicks > 0) {
            previewHoldTicks--;
        }
        previewTick++;
    }

    // Shift + arrow keys walk the mode list. The press switches immediately and the held key steps
    // every 0.5 s; the OS repeat rate would race through the list instead.
    static void tickModeKeys() {
        if (modeMenuTicks > 0) {
            modeMenuTicks--;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null || minecraft.currentScreen != null || !GuiScreen.isShiftKeyDown()) {
            modeKeyDown = false;
            modeKeyHoldTicks = 0;
            return;
        }

        boolean up = MODE_UP_KEY.isKeyDown();
        boolean down = MODE_DOWN_KEY.isKeyDown();
        int direction = up == down ? 0 : up ? -1 : 1;
        if (direction == 0) {
            modeKeyDown = false;
            modeKeyHoldTicks = 0;
            return;
        }

        if (!modeKeyDown) {
            modeKeyDown = true;
            modeKeyHoldTicks = 0;
            switchMode(direction);
            return;
        }

        modeKeyHoldTicks++;
        if (modeKeyHoldTicks >= MODE_REPEAT_TICKS) {
            modeKeyHoldTicks = 0;
            switchMode(direction);
        }
    }

    // Sneak + blacklist key toggles the aimed block in the shared blacklist. The server
    // revalidates permission, the sneak posture and the distance before it changes anything.
    static void tickBlacklistKey() {
        boolean down = BLACKLIST_KEY.isKeyDown();
        if (down == blacklistKeyDown) {
            return;
        }
        blacklistKeyDown = down;
        if (!down) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null || minecraft.currentScreen != null || !minecraft.player.isSneaking()) {
            return;
        }
        if (minecraft.objectMouseOver != null
                && minecraft.objectMouseOver.typeOfHit == RayTraceResult.Type.BLOCK) {
            NetworkHandler.sendBlacklistToggle(minecraft.objectMouseOver.getBlockPos());
        } else {
            minecraft.player.sendStatusMessage(
                    new TextComponentTranslation("message.veinminerplus.blacklist.no_target"), true);
        }
    }

    private static void switchMode(int direction) {
        clientMode = ChainMode.cycle(clientMode, direction);
        NetworkHandler.sendModeChange(clientMode);
        modeMenuTicks = MODE_MENU_LINGER_TICKS;
    }

    static void syncKeyState() {
        boolean held = isChainKeyActive(Minecraft.getMinecraft()) && !isSelectorModifierHeld();
        if (held == keyStateSent) {
            return;
        }

        NetworkHandler.sendKeyState(held);
        keyStateSent = held;
    }

    // The wheel selector's gate is "chain key held + Shift". When the player binds the chain key to
    // a Shift key itself, that gate would be open all the time, so the selector gives way to
    // chaining: the wheel no longer opens it, and Shift+arrow switching keeps working.
    private static boolean isSelectorModifierHeld() {
        return GuiScreen.isShiftKeyDown() && !isChainKeyShiftBound();
    }

    // 1.12.2 Forge ships no key modifier facility, so the two Shift key codes are the whole check.
    private static boolean isChainKeyShiftBound() {
        int keyCode = CHAIN_KEY.getKeyCode();
        return keyCode == Keyboard.KEY_LSHIFT || keyCode == Keyboard.KEY_RSHIFT;
    }

    static boolean isModeSelectorOpen() {
        return isChainKeyActive(Minecraft.getMinecraft()) && isSelectorModifierHeld();
    }

    static void renderHud(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        renderProgress(event);
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!isChainKeyActive(minecraft)) {
            // A switch that just happened shows the full list for a moment on its own.
            if (modeMenuTicks > 0 && minecraft.player != null && minecraft.currentScreen == null) {
                renderAllModes(minecraft);
            }
            return;
        }

        // A switch that just happened shows the full list too: on a key-path switch the list must
        // appear even when Shift cannot act as the selector modifier (chain key = Shift).
        if (isSelectorModifierHeld() || modeMenuTicks > 0) {
            renderAllModes(minecraft);
        } else {
            renderCurrentMode(minecraft);
        }
    }

    // Drawn where the vanilla action bar sits, so the counter reads the same as it did before.
    private static void renderProgress(RenderGameOverlayEvent.Post event) {
        if (progressTicks <= 0) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        String key = progressInteract
                ? "message.veinminerplus.interact_progress"
                : "message.veinminerplus.progress";
        String text = new TextComponentTranslation(key, progressCount).getUnformattedText();
        ScaledResolution resolution = event.getResolution();
        int x = (resolution.getScaledWidth() - minecraft.fontRenderer.getStringWidth(text)) / 2;
        minecraft.fontRenderer.drawStringWithShadow(text, x, resolution.getScaledHeight() - 72, 0xFFFFFF);
    }

    // Outlines the positions the interaction mode would touch. The list comes from the same
    // helper the server job uses, so the preview cannot disagree with what actually happens.
    static void renderInteractPreview(RenderWorldLastEvent event) {
        if (clientMode != ChainMode.SPECIAL_INTERACT) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (!isChainKeyActive(minecraft)) {
            return;
        }

        List<BlockPos> targets = resolvePreviewTargets(minecraft);
        if (targets.isEmpty()) {
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
        // The thinnest line the driver offers; the outline is one boundary of the region instead of
        // one box per block, so it does not need the thicker line to stay readable.
        GlStateManager.glLineWidth(1.0F);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);
        // The aimed block is drawn by the vanilla selection box; this outline leaves it out so the
        // two never stack. When the aim is currently invalid the held position is not advertised,
        // so no new gap is invented for it.
        InteractOutline.draw(targets, currentAimBlock(minecraft), viewX, viewY, viewZ);
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    // The outline follows the crosshair and is recomputed every frame. While the player moves the
    // aimed block can fall out of the valid set for single frames, so the last valid preview is
    // held briefly instead of blinking off; a fresh valid aim replaces it immediately.
    private static List<BlockPos> resolvePreviewTargets(Minecraft minecraft) {
        ItemStack held = minecraft.player.getHeldItemMainhand();
        if (minecraft.world != null && minecraft.objectMouseOver != null
                && minecraft.objectMouseOver.typeOfHit == RayTraceResult.Type.BLOCK) {
            BlockPos origin = minecraft.objectMouseOver.getBlockPos();
            EnumFacing face = minecraft.objectMouseOver.sideHit;
            boolean sameAim = origin.equals(previewOrigin) && face == previewFace
                    && sameHeldItem(previewItem, held);
            if (sameAim && previewResolvedTick == previewTick && previewHoldTicks > 0) {
                return previewTargets;
            }
            List<BlockPos> targets = new ArrayList<>();
            previewResolvedTick = previewTick;
            previewOrigin = origin;
            previewFace = face;
            if (ChainEvents.collectInteractTargets(minecraft.world, origin, face, held,
                    ChainEvents.INTERACT_PREVIEW_LIMIT, targets)
                    && !targets.isEmpty()) {
                previewTargets = targets;
                if (!sameHeldItem(previewItem, held)) {
                    previewItem = held.copy();
                }
                previewHoldTicks = PREVIEW_HOLD_TICKS;
                return targets;
            }
            // The aim is valid but nothing can be done with it, so the old outline is dropped
            // instead of lingering over an area the job would not touch.
            previewTargets = Collections.emptyList();
            previewHoldTicks = 0;
            return previewTargets;
        }
        // A changed held item invalidates the last preview: the outline would no longer match what
        // using that item would do.
        if (previewHoldTicks > 0 && sameHeldItem(previewItem, held)) {
            return previewTargets;
        }
        return Collections.emptyList();
    }

    private static boolean sameHeldItem(ItemStack first, ItemStack second) {
        return ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second);
    }

    // The block the crosshair is on right now, or null when there is no real block hit. The held
    // preview keeps its old outline but must not claim a block it is no longer aiming at.
    private static BlockPos currentAimBlock(Minecraft minecraft) {
        if (minecraft.objectMouseOver == null
                || minecraft.objectMouseOver.typeOfHit != RayTraceResult.Type.BLOCK) {
            return null;
        }
        return minecraft.objectMouseOver.getBlockPos();
    }

    private static boolean isChainKeyActive(Minecraft minecraft) {
        return minecraft.player != null && minecraft.world != null && minecraft.currentScreen == null
                && CHAIN_KEY.isKeyDown();
    }

    private static String modeName(ChainMode mode) {
        return I18n.format(mode.translationKey());
    }

    private static void renderCurrentMode(Minecraft minecraft) {
        String text = modeName(clientMode);
        int width = minecraft.fontRenderer.getStringWidth(text);
        Gui.drawRect(4, 4, 12 + width, 18, 0xA0000000);
        minecraft.fontRenderer.drawStringWithShadow(text, 8, 8, 0xFFFFFFFF);
    }

    private static void renderAllModes(Minecraft minecraft) {
        ChainMode[] modes = ChainMode.MODES;
        String[] names = new String[modes.length];
        int width = 0;
        for (int index = 0; index < modes.length; index++) {
            names[index] = modeName(modes[index]);
            width = Math.max(width, minecraft.fontRenderer.getStringWidth(names[index]));
        }

        int lineHeight = minecraft.fontRenderer.FONT_HEIGHT;
        Gui.drawRect(4, 4, 12 + width, 12 + modes.length * lineHeight, 0xA0000000);
        for (int index = 0; index < modes.length; index++) {
            int color = modes[index] == clientMode ? 0xFFFF55 : 0xFFFFFF;
            minecraft.fontRenderer.drawStringWithShadow(names[index], 8, 8 + index * lineHeight, color);
        }
    }
}
