package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = VeinMinerPlus.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VeinMinerPlusClient {
    static final KeyMapping CHAIN_KEY = new KeyMapping(
            "key.veinminerplus.chain",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_GRAVE_ACCENT,
            "key.categories.veinminerplus");
    static final KeyMapping BLACKLIST_KEY = new KeyMapping(
            "key.veinminerplus.blacklist",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_KP_ADD,
            "key.categories.veinminerplus");
    // Shift + arrow keys switch modes on their own, without the chain key. Both are
    // rebindable, and the shift part belongs to the binding instead of a hardcoded check.
    static final KeyMapping MODE_UP_KEY = new KeyMapping(
            "key.veinminerplus.mode_up",
            KeyConflictContext.IN_GAME,
            KeyModifier.SHIFT,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UP,
            "key.categories.veinminerplus");
    static final KeyMapping MODE_DOWN_KEY = new KeyMapping(
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

    static ChainMode clientMode = ChainMode.NORMAL;
    private static boolean keyStateSent;
    static int modeKeyHoldTicks;
    static int modeMenuTicks;
    static int previewHoldTicks;
    private static List<BlockPos> previewTargets = List.of();
    private static ItemStack previewItem = ItemStack.EMPTY;
    // The outline is rebuilt only when the target set changes, so a large area costs nothing per
    // frame. Kept alongside the targets it was built from, and the aimed block the hidden edges
    // belong to: the same targets with a different aim produce different hidden edges.
    private static List<BlockPos> outlineCacheKey = List.of();
    private static BlockPos outlineCacheAim;
    private static List<double[]> outlineCache = List.of();
    // The aimed block the current preview was built for. It is part of the held preview too, so a
    // held outline keeps hiding its own block's edges.
    private static BlockPos previewAim;

    private VeinMinerPlusClient() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CHAIN_KEY);
        event.register(BLACKLIST_KEY);
        event.register(MODE_UP_KEY);
        event.register(MODE_DOWN_KEY);
    }

    static void openConfigScreen(NetworkHandler.ConfigSnapshotPayload config) {
        Minecraft.getInstance().setScreen(new VeinMinerConfigScreen(config));
    }

    static void resetSessionState() {
        clientMode = ChainMode.NORMAL;
        keyStateSent = false;
        modeKeyHoldTicks = 0;
        modeMenuTicks = 0;
        previewTargets = List.of();
        previewItem = ItemStack.EMPTY;
        previewHoldTicks = 0;
        previewAim = null;
        outlineCacheKey = List.of();
        outlineCacheAim = null;
        outlineCache = List.of();
    }

    static void syncKeyState() {
        boolean held = isChainKeyActive(Minecraft.getInstance()) && !isSelectorModifierHeld();
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

    // The wheel selector's gate is "chain key held + Shift". When the player binds the chain key
    // to a Shift key itself, that gate would be open all the time, so the selector gives way to
    // chaining and the wheel is left alone.
    private static boolean isSelectorModifierHeld() {
        return Screen.hasShiftDown() && !KeyModifier.SHIFT.matches(CHAIN_KEY.getKey());
    }

    static boolean isModeSelectorOpen() {
        return isChainKeyActive(Minecraft.getInstance()) && isSelectorModifierHeld();
    }

    static void renderModeMenu(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }

        // An open selector or a fresh switch shows the full list; the chain key alone shows
        // the current-mode banner.
        if (isModeSelectorOpen() || modeMenuTicks > 0) {
            renderAllModes(graphics, minecraft);
        } else if (isChainKeyActive(minecraft)) {
            renderCurrentMode(graphics, minecraft);
        }
    }

    // The key path shows the full list for a moment after each switch; the wheel path
    // keeps its own selector, which stays open while the chain key and shift are held.
    static void switchMode(int direction) {
        clientMode = ChainMode.cycle(clientMode, direction);
        NetworkHandler.sendModeChange(clientMode);
        modeMenuTicks = MODE_MENU_LINGER_TICKS;
    }

    static void tickModeKeys() {
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

    // Sneak + blacklist key toggles the aimed block in the shared blacklist. The server
    // revalidates permission, the sneak posture and the distance before it changes anything.
    static void toggleBlacklist() {
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

    // Outlines the selected area with its own outer silhouette instead of one box per block: the
    // top and bottom rims of every exposed side, and one vertical line per outline corner. Shared
    // faces and inner corners therefore draw nothing, a hole or an island gets its own ring, and a
    // diagonal touch only meets at a corner. The aimed block keeps vanilla's own selection box, so
    // its own edges are left out of this outline; the rest of the area still draws around it.
    static void renderInteractPreview(RenderLevelStageEvent event) {
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

        // The aimed block keeps vanilla's selection box, but it is not cut out of the area: a line
        // can be both that block's edge and the area's outer boundary, and hiding it by coordinate
        // tore a one-block gap in the outline right where the player looks. So the aim is only used
        // as a hiding position when the area does not contain it at all - the flint preview, where
        // the block was never a candidate and its neighbours would otherwise ring a fake hole. A
        // lone aimed block draws nothing rather than turning into a full white box.
        boolean aimedBlockIsSelected = previewAim != null && targets.contains(previewAim);
        if (aimedBlockIsSelected && targets.size() == 1) {
            return;
        }
        BlockPos hiddenAim = aimedBlockIsSelected ? null : previewAim;
        List<double[]> segments = outlineSegments(targets, hiddenAim);
        if (segments.isEmpty()) {
            return;
        }

        // TEMP DIAG: flicker sampling. Remove together with diagSummary once the cause is known.
        diagFrame(targets);
        if (diagFrames % 100 == 0) {
            diagSummary(segments, event);
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(PREVIEW_LINES);
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (double[] segment : segments) {
            // Two vertices per segment; the normal is the segment direction, the way vanilla's own
            // line box writes it.
            float nx = (float) (segment[3] - segment[0]);
            float ny = (float) (segment[4] - segment[1]);
            float nz = (float) (segment[5] - segment[2]);
            float length = Math.max(1.0E-4F, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
            nx /= length;
            ny /= length;
            nz /= length;
            vertex(lines, poseStack, segment[0], segment[1], segment[2], nx, ny, nz);
            vertex(lines, poseStack, segment[3], segment[4], segment[5], nx, ny, nz);
        }
        poseStack.popPose();
        // This shard treats 1.0 as "keep the current width", so the width is set right before the
        // batch is drawn; the shader reads it back when it draws.
        RenderSystem.lineWidth(1.0F);
        buffers.endBatch(PREVIEW_LINES);
    }

    private static void vertex(VertexConsumer consumer, PoseStack poseStack, double x, double y, double z,
            float nx, float ny, float nz) {
        consumer.vertex(poseStack.last().pose(), (float) x, (float) y, (float) z)
                .color(1.0F, 1.0F, 1.0F, 1.0F)
                .normal(poseStack.last().normal(), nx, ny, nz)
                .endVertex();
    }

    // TEMP DIAG: counts how the preview inputs change from frame to frame. A flicker frame shows up
    // as a change in the target signature while the world and the aim stayed put.
    private static int diagFrames;
    private static int diagSubmits;
    private static int diagTargetChanges;
    private static int diagAimChanges;
    private static int diagGeomChanges;
    private static int diagGeometryInputs;
    private static int diagHoldFrames;
    private static String diagLastTargets = "";
    private static String diagLastAim = "";
    private static String diagLastGeom = "";

    private static void diagFrame(List<BlockPos> targets) {
        diagFrames++;
        String aim = String.valueOf(previewAim);
        String signature = diagSignature(targets);
        if (!signature.equals(diagLastTargets)) {
            diagTargetChanges++;
            diagLastTargets = signature;
        }
        if (!aim.equals(diagLastAim)) {
            diagAimChanges++;
            diagLastAim = aim;
        }
    }

    private static void diagSummary(List<double[]> segments, RenderLevelStageEvent event) {
        diagSubmits++;
        String geometry = segments.size() + ":" + diagGeometryHash(segments);
        if (!geometry.equals(diagLastGeom)) {
            diagGeomChanges++;
            diagLastGeom = geometry;
        }
        Vec3 camera = event.getCamera().getPosition();
        VeinMinerPlus.LOGGER.info(
                "[vmp-diag] frame={} submit={} hold={} targets={} targetChanges={} aim={} aimChanges={} "
                        + "geom=({}, inputs={}) geomChanges={} camera=({},{},{}) partial={} lw={}",
                diagFrames, diagSubmits, previewHoldTicks, diagLastTargets, diagTargetChanges,
                diagLastAim, diagAimChanges, diagLastGeom, diagGeometryInputs, diagGeomChanges,
                String.format("%.3f", camera.x), String.format("%.3f", camera.y),
                String.format("%.3f", camera.z), String.format("%.3f", event.getPartialTick()),
                RenderSystem.getShaderLineWidth());
    }

    // Order-insensitive: the same area reached in a different walk order is the same geometry.
    private static String diagSignature(List<BlockPos> targets) {
        long sum = 0L;
        for (BlockPos pos : targets) {
            sum = sum * 31 + pos.asLong();
        }
        return targets.size() + "/" + Long.toHexString(sum);
    }

    private static String diagGeometryHash(List<double[]> segments) {
        long sum = 0L;
        diagGeometryInputs = 0;
        for (double[] segment : segments) {
            for (double value : segment) {
                sum = sum * 31 + Double.hashCode(value);
                diagGeometryInputs++;
            }
        }
        return Long.toHexString(sum);
    }

    // Builds the outline once per (target set, aimed block) pair. The area is solved with the aimed
    // block counted as selected even when it is not an execution candidate, so a flint preview does
    // not gain a fake hole where the player's own click already applied. The aimed block's own
    // edges are then dropped, because vanilla draws its selection box there. Only exposed sides
    // contribute: a side whose neighbour is selected is interior and draws nothing, so a solid area
    // has no inner grid, a real hole or a detached island gets its own ring, and a diagonal touch
    // only meets at a corner. Rim runs are merged, so a wide area costs a handful of long lines
    // instead of one short line per block.
    private static List<double[]> outlineSegments(List<BlockPos> targets, BlockPos aim) {
        if (targets.equals(outlineCacheKey) && Objects.equals(aim, outlineCacheAim)) {
            return outlineCache;
        }

        Set<BlockPos> selected = new HashSet<>(targets);
        // The aimed block takes part in the silhouette so its neighbours do not draw a ring around
        // a hole that is not there, but it is not a candidate and never contributes its own lines.
        if (aim != null) {
            selected.add(aim);
        }

        // Rim pieces keyed by the line they lie on, merged after the walk.
        Map<RimKey, List<int[]>> rims = new HashMap<>();
        Set<Corner> corners = new HashSet<>();
        for (BlockPos pos : selected) {
            if (pos.equals(aim)) {
                continue;
            }
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            boolean north = !selected.contains(pos.north());
            boolean south = !selected.contains(pos.south());
            boolean east = !selected.contains(pos.east());
            boolean west = !selected.contains(pos.west());

            // A rim is the line where the exposed side meets the top or bottom face of its block.
            if (north) {
                addRim(rims, RimKey.AXIS_X, z, y, x);
            }
            if (south) {
                addRim(rims, RimKey.AXIS_X, z + 1, y, x);
            }
            if (west) {
                addRim(rims, RimKey.AXIS_Z, x, y, z);
            }
            if (east) {
                addRim(rims, RimKey.AXIS_Z, x + 1, y, z);
            }

            // A vertical line only sits where the silhouette turns, so the rim is tested per
            // vertex below rather than per side. Candidate vertices are the four block corners.
            corners.add(new Corner(x, y, z));
            corners.add(new Corner(x + 1, y, z));
            corners.add(new Corner(x, y, z + 1));
            corners.add(new Corner(x + 1, y, z + 1));
        }

        List<double[]> segments = new ArrayList<>();
        for (Map.Entry<RimKey, List<int[]>> entry : rims.entrySet()) {
            List<int[]> runs = entry.getValue();
            runs.sort(Comparator.comparingInt(run -> run[0]));
            int start = runs.get(0)[0];
            int end = runs.get(0)[1];
            for (int index = 1; index < runs.size(); index++) {
                int[] run = runs.get(index);
                if (run[0] <= end) {
                    end = Math.max(end, run[1]);
                } else {
                    addRimSegments(segments, entry.getKey(), start, end);
                    start = run[0];
                    end = run[1];
                }
            }
            addRimSegments(segments, entry.getKey(), start, end);
        }
        for (Corner corner : corners) {
            if (isOutlineCorner(selected, corner)) {
                segments.add(new double[]{corner.x(), corner.y(), corner.z(),
                        corner.x(), corner.y() + 1.0, corner.z()});
            }
        }
        if (aim != null) {
            segments = withoutAimedEdges(segments, aim);
        }

        outlineCacheKey = List.copyOf(targets);
        outlineCacheAim = aim;
        outlineCache = segments;
        return segments;
    }

    // Drops the parts of the outline that lie on the aimed block's own 12 edges, leaving the
    // neighbouring blocks' outline intact. Segments are already merged along one axis, so a long
    // run that merely touches the aimed block is split at the block's boundary instead of being
    // removed whole.
    private static List<double[]> withoutAimedEdges(List<double[]> segments, BlockPos aim) {
        double minX = aim.getX();
        double minY = aim.getY();
        double minZ = aim.getZ();
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;
        List<double[]> kept = new ArrayList<>(segments.size());
        for (double[] segment : segments) {
            // A vertical line keeps its x and z and varies in y, so those are the two axes that
            // have to match for it to be one of the aimed block's own vertical edges.
            if (segment[0] == segment[3] && segment[2] == segment[5]) {
                boolean onCorner = (segment[0] == minX || segment[0] == maxX)
                        && (segment[2] == minZ || segment[2] == maxZ);
                boolean onSpan = segment[1] <= maxY && segment[4] >= minY;
                if (onCorner && onSpan) {
                    // The line can run past the block; only its own part is dropped.
                    if (segment[1] < minY) {
                        kept.add(new double[]{segment[0], segment[1], segment[2],
                                segment[0], minY, segment[2]});
                    }
                    if (segment[4] > maxY) {
                        kept.add(new double[]{segment[0], maxY, segment[2],
                                segment[3], segment[4], segment[5]});
                    }
                    continue;
                }
                kept.add(segment);
                continue;
            }

            // A horizontal rim belongs to the aimed block when it lies on one of its faces and
            // within its horizontal footprint.
            boolean horizontalAlongX = segment[1] == segment[4] && segment[2] == segment[5];
            boolean onAimedY = segment[1] == minY || segment[1] == maxY;
            boolean onAimedXFace = segment[2] == minZ || segment[2] == maxZ;
            boolean withinX = Math.min(segment[0], segment[3]) >= minX
                    && Math.max(segment[0], segment[3]) <= maxX;
            if (horizontalAlongX && onAimedY && onAimedXFace && withinX) {
                kept.addAll(cutXRun(segment, minX, maxX));
                continue;
            }

            boolean horizontalAlongZ = segment[1] == segment[4] && segment[0] == segment[3];
            boolean onAimedZFace = segment[0] == minX || segment[0] == maxX;
            boolean withinZ = Math.min(segment[2], segment[5]) >= minZ
                    && Math.max(segment[2], segment[5]) <= maxZ;
            if (horizontalAlongZ && onAimedY && onAimedZFace && withinZ) {
                kept.addAll(cutZRun(segment, minZ, maxZ));
                continue;
            }

            // Anything else does not belong to the aimed block's own edges.
            kept.add(segment);
        }
        return kept;
    }

    // Splits a run that runs along X into the parts outside [from, to].
    private static List<double[]> cutXRun(double[] segment, double from, double to) {
        List<double[]> parts = new ArrayList<>(2);
        double start = Math.min(segment[0], segment[3]);
        double end = Math.max(segment[0], segment[3]);
        if (start < from) {
            parts.add(new double[]{start, segment[1], segment[2], from, segment[1], segment[2]});
        }
        if (end > to) {
            parts.add(new double[]{to, segment[1], segment[2], end, segment[1], segment[2]});
        }
        return parts;
    }

    // Splits a run that runs along Z into the parts outside [from, to].
    private static List<double[]> cutZRun(double[] segment, double from, double to) {
        List<double[]> parts = new ArrayList<>(2);
        double start = Math.min(segment[2], segment[5]);
        double end = Math.max(segment[2], segment[5]);
        if (start < from) {
            parts.add(new double[]{segment[0], segment[1], start, segment[0], segment[1], from});
        }
        if (end > to) {
            parts.add(new double[]{segment[0], segment[1], to, segment[0], segment[1], end});
        }
        return parts;
    }

    // A vertex turns where the four blocks around it are not split by a straight or a full edge.
    // One or three filled blocks is an L (a convex or concave turn), so a vertical line belongs
    // there. Two filled in a straight line stay on that line, and two filled diagonally only touch
    // at the point - both are left to the rims that already meet there.
    private static boolean isOutlineCorner(Set<BlockPos> selected, Corner corner) {
        int x = corner.x();
        int y = corner.y();
        int z = corner.z();
        int filled = 0;
        if (selected.contains(new BlockPos(x - 1, y, z - 1))) {
            filled++;
        }
        if (selected.contains(new BlockPos(x, y, z - 1))) {
            filled++;
        }
        if (selected.contains(new BlockPos(x - 1, y, z))) {
            filled++;
        }
        if (selected.contains(new BlockPos(x, y, z))) {
            filled++;
        }
        return filled == 1 || filled == 3;
    }

    private record Corner(int x, int y, int z) {
    }

    // A rim lies on the line "start..end along axis, at fixed on the other horizontal axis, at the
    // block's own Y"; it is drawn on both the bottom and the top face of that block.
    private record RimKey(int axis, int fixed, int y) {
        static final int AXIS_X = 0;
        static final int AXIS_Z = 1;
    }

    private static void addRim(Map<RimKey, List<int[]>> rims, int axis, int fixed, int y, int start) {
        rims.computeIfAbsent(new RimKey(axis, fixed, y), key -> new ArrayList<>())
                .add(new int[]{start, start + 1});
    }

    private static void addRimSegments(List<double[]> segments, RimKey key, int start, int end) {
        double fixed = key.fixed();
        double bottom = key.y();
        double top = bottom + 1.0;
        if (key.axis() == RimKey.AXIS_X) {
            segments.add(new double[]{start, bottom, fixed, end, bottom, fixed});
            segments.add(new double[]{start, top, fixed, end, top, fixed});
        } else {
            segments.add(new double[]{fixed, bottom, start, fixed, bottom, end});
            segments.add(new double[]{fixed, top, start, fixed, top, end});
        }
    }

    // A one-pixel line type. Vanilla LINES leaves the width to the window scale, which is at least
    // 2.5 physical pixels; the explicit 1.0 shard keeps that shader, blending, depth and layering
    // behaviour and only replaces the width. The width itself is applied right before the batch is
    // drawn, because this shard treats 1.0 as "leave the current value alone".
    private static final RenderType PREVIEW_LINES = createPreviewLines();

    private static RenderType createPreviewLines() {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getRendertypeLinesShader))
                .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(1.0)))
                .setLayeringState(new RenderStateShard.LayeringStateShard("view_offset_z_layering",
                        () -> {
                            PoseStack modelView = RenderSystem.getModelViewStack();
                            modelView.pushPose();
                            modelView.scale(0.99975586F, 0.99975586F, 0.99975586F);
                            RenderSystem.applyModelViewMatrix();
                        },
                        () -> {
                            RenderSystem.getModelViewStack().popPose();
                            RenderSystem.applyModelViewMatrix();
                        }))
                .setTransparencyState(new RenderStateShard.TransparencyStateShard("translucent_transparency",
                        () -> {
                            RenderSystem.enableBlend();
                            RenderSystem.blendFuncSeparate(
                                    GlStateManager.SourceFactor.SRC_ALPHA,
                                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                                    GlStateManager.SourceFactor.ONE,
                                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                        },
                        () -> {
                            RenderSystem.disableBlend();
                            RenderSystem.defaultBlendFunc();
                        }))
                .setCullState(new RenderStateShard.CullStateShard(false))
                .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, true))
                .createCompositeState(false);
        return RenderType.create("veinminerplus_preview_lines",
                DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 256, false, false, state);
    }

    // The outline follows the crosshair and is recomputed every frame. While the player moves the
    // aimed block can fall out of the valid set for single frames, so the last valid preview is
    // held briefly instead of blinking off; a fresh valid aim replaces it immediately. The held
    // outline keeps the world positions it was built from, so it never drifts with the player.
    private static List<BlockPos> resolvePreviewTargets(Minecraft minecraft) {
        if (minecraft.level != null && minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            ChainEvents.InteractRule rule = ChainEvents.resolveInteractRule(minecraft.level,
                    hit.getBlockPos(), minecraft.player.getMainHandItem());
            if (rule != null) {
                List<BlockPos> targets = new ArrayList<>();
                ChainEvents.spreadConnected(minecraft.level, hit.getBlockPos(),
                        ChainEvents.INTERACT_PREVIEW_LIMIT, rule.includeOrigin(), targets, rule.valid());
                if (!targets.isEmpty()) {
                    previewTargets = targets;
                    previewAim = hit.getBlockPos().immutable();
                    previewItem = minecraft.player.getMainHandItem().copy();
                    previewHoldTicks = PREVIEW_HOLD_TICKS;
                    return targets;
                }
            }
        }
        if (previewHoldTicks > 0
                && ItemStack.isSameItemSameTags(previewItem, minecraft.player.getMainHandItem())) {
            return previewTargets;
        }
        return List.of();
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
