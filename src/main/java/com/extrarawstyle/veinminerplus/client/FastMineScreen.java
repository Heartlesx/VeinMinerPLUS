package com.extrarawstyle.veinminerplus.client;

import com.extrarawstyle.veinminerplus.FastMineProgress;
import com.extrarawstyle.veinminerplus.NetworkHandler;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.math.Size;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Fast-mining overview built as one LDLib2 tree, keeping map and controls in the same z-order. */
final class FastMineScreen extends ModularUIScreen {
    private static final int INITIAL_WIDTH = 820;
    private static final int INITIAL_HEIGHT = 500;

    private final UiState state;
    private static SelectionMemory rememberedSelection;
    private static final Map<ResourceLocation, ItemStack> ORE_ICON_CACHE = new LinkedHashMap<>();

    FastMineScreen(FastMineProgress progress) { this(new UiState(progress)); }

    private FastMineScreen(UiState state) {
        super(createUi(state), Component.translatable("screen.veinminerplus.fast_mine"));
        this.state = state;
    }

    void setProgress(FastMineProgress progress) {
        state.progress = progress;
        state.submitted = isTaskActive(progress.state());
        state.refreshLabels();
    }

    private static boolean isTaskActive(FastMineProgress.State state) {
        return state == FastMineProgress.State.LOADING || state == FastMineProgress.State.SCANNING
                || state == FastMineProgress.State.MINING || state == FastMineProgress.State.PAUSED;
    }

    static void clearRememberedSelection() {
        rememberedSelection = null;
    }

    private static ModularUI createUi(UiState state) {
        UIElement root = new UIElement().setId("fast_mine_root")
                .layout(layout -> layout.width(INITIAL_WIDTH).height(INITIAL_HEIGHT).flexDirection(FlexDirection.COLUMN))
                .addClass("fm-shell");
        UIElement content = new UIElement().setId("fast_mine_content")
                .layout(layout -> layout.flex(1).widthPercent(100).flexDirection(FlexDirection.ROW).gapColumn(6))
                .addClass("fm-content");

        UIElement mapCard = new UIElement().setId("fast_mine_map_card")
                .layout(layout -> layout.flex(1).heightPercent(100).flexDirection(FlexDirection.COLUMN).gapRow(3))
                .addClass("fm-card fm-map-card");
        UIElement mapHeader = new UIElement().layout(layout -> layout.widthPercent(100).height(34)
                .flexDirection(FlexDirection.COLUMN).justifyContent(AlignContent.CENTER)).addClass("fm-card-header");
        mapHeader.addChild(label("screen.veinminerplus.overview.title", "fm-title"));
        mapHeader.addChild(label("screen.veinminerplus.overview.subtitle", "fm-subtitle"));
        mapCard.addChild(mapHeader);

        UIElement mapInfo = new UIElement().layout(layout -> layout.widthPercent(100).height(20)
                .flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER)).addClass("fm-map-info");
        Label playerPosition = label("screen.veinminerplus.player_position", "fm-map-position");
        playerPosition.layout(layout -> layout.flex(1).height(18));
        state.playerPositionLabel = playerPosition;
        Label zoom = label("screen.veinminerplus.map.zoom", "fm-map-zoom");
        zoom.layout(layout -> layout.width(140).height(18));
        state.zoomLabel = zoom;
        mapInfo.addChildren(new UIElement[]{playerPosition, zoom});
        mapCard.addChild(mapInfo);

        UIElement mapControls = new UIElement().setId("fast_mine_map_controls")
                .layout(layout -> layout.widthPercent(100).height(28).flexDirection(FlexDirection.ROW)
                        .alignItems(AlignItems.CENTER).gapColumn(4)).addClass("fm-map-controls");
        Button single = actionButton("screen.veinminerplus.single", "fm-tool-button");
        Button rectangle = actionButton("screen.veinminerplus.rectangle", "fm-tool-button");
        Button clear = actionButton("screen.veinminerplus.clear", "fm-tool-button");
        single.layout(layout -> layout.width(52).height(22));
        rectangle.layout(layout -> layout.width(58).height(22));
        clear.layout(layout -> layout.width(46).height(22));
        single.setOnClick(event -> state.setMode(SelectMode.SINGLE, single, rectangle));
        rectangle.setOnClick(event -> state.setMode(SelectMode.RECTANGLE, single, rectangle));
        clear.setOnClick(event -> state.clearSelection());
        mapControls.addChildren(new UIElement[]{single, rectangle, clear});
        state.syncModeButtons(single, rectangle);
        mapCard.addChild(mapControls);

        FastMineMapElement map = new FastMineMapElement(state);
        map.setId("fast_mine_map");
        map.layout(layout -> layout.flex(1).widthPercent(100).heightPercent(100).minHeight(100));
        mapCard.addChild(map);
        content.addChild(mapCard);

        ScrollerView sidebar = new ScrollerView();
        sidebar.setId("fast_mine_sidebar");
        // Keep the information rail proportional to the window so the map and
        // cards share the available width instead of leaving a dead gutter.
        sidebar.layout(layout -> layout.widthPercent(31).heightPercent(100).minWidth(220));
        sidebar.addClass("fm-sidebar-scroll");
        sidebar.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL)
                .verticalScrollDisplay(ScrollDisplay.AUTO).horizontalScrollDisplay(ScrollDisplay.NEVER)
                .scrollerViewStyle(2.0f).minScrollPixel(8.0f).maxScrollPixel(22.0f));
        sidebar.viewPort(view -> view.layout(layout -> layout.paddingAll(0.0f)));
        sidebar.viewContainer(view -> view.layout(layout -> layout.widthPercent(100)
                .flexDirection(FlexDirection.COLUMN).gapRow(5)));
        sidebar.verticalScroller(scroller -> { scroller.headButton.setDisplay(false); scroller.tailButton.setDisplay(false); });

        UIElement taskCard = card("fm-info-card fm-task-card", 165);
        taskCard.addChild(label("screen.veinminerplus.task.title", "fm-card-title"));
        Label progressState = label("screen.veinminerplus.state", "fm-state");
        progressState.layout(layout -> layout.widthPercent(100).height(16));
        state.progressStateLabel = progressState;
        taskCard.addChild(progressState);
        Label dimension = label("screen.veinminerplus.dimension", "fm-task-meta");
        dimension.layout(layout -> layout.widthPercent(100).height(16));
        state.dimensionLabel = dimension;
        taskCard.addChild(dimension);
        Label selection = label("screen.veinminerplus.selection", "fm-task-meta");
        selection.layout(layout -> layout.widthPercent(100).height(18));
        state.selectionLabel = selection;
        taskCard.addChild(selection);
        ProgressBar progressBar = progressBar("fm-progress-primary");
        progressBar.layout(layout -> layout.widthPercent(100).height(12));
        state.progressBar = progressBar;
        taskCard.addChild(progressBar);
        Label progressValue = label("screen.veinminerplus.task.progress", "fm-metric-primary");
        progressValue.layout(layout -> layout.widthPercent(100).height(17));
        state.progressValueLabel = progressValue;
        taskCard.addChild(progressValue);
        UIElement taskButtons = new UIElement().layout(layout -> layout.widthPercent(100).height(32)
                .flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapColumn(5)).addClass("fm-task-buttons");
        Button cancel = actionButton("screen.veinminerplus.cancel", "fm-button-secondary");
        Button confirm = actionButton("screen.veinminerplus.confirm", "fm-button-primary");
        cancel.layout(layout -> layout.flex(1).height(23));
        confirm.layout(layout -> layout.flex(1).height(23));
        cancel.setOnClick(event -> state.cancelTask());
        confirm.setOnClick(event -> state.confirmTask());
        state.cancelButton = cancel;
        state.confirmButton = confirm;
        taskButtons.addChildren(new UIElement[]{cancel, confirm});
        taskCard.addChild(taskButtons);
        sidebar.addScrollViewChild(taskCard);

        UIElement statsCard = card("fm-info-card fm-stats-card", 180);
        statsCard.addChild(label("screen.veinminerplus.stats.title", "fm-card-title"));
        Label chunks = label("screen.veinminerplus.stats.chunks", "fm-metric");
        Label blocks = label("screen.veinminerplus.stats.blocks", "fm-metric");
        Label scanned = label("screen.veinminerplus.stats.scanned", "fm-metric");
        Label ores = label("screen.veinminerplus.stats.ores", "fm-metric");
        chunks.layout(layout -> layout.widthPercent(100).height(15));
        ProgressBar chunkProgress = progressBar("fm-progress-secondary");
        chunkProgress.layout(layout -> layout.widthPercent(100).height(10));
        blocks.layout(layout -> layout.widthPercent(100).height(25));
        ProgressBar blockProgress = progressBar("fm-progress-secondary");
        blockProgress.layout(layout -> layout.widthPercent(100).height(10));
        scanned.layout(layout -> layout.widthPercent(100).height(25));
        ProgressBar scanProgress = progressBar("fm-progress-secondary");
        scanProgress.layout(layout -> layout.widthPercent(100).height(10));
        ores.layout(layout -> layout.widthPercent(100).height(15));
        ProgressBar oreProgress = progressBar("fm-progress-secondary");
        oreProgress.layout(layout -> layout.widthPercent(100).height(10));
        state.chunkStatsLabel = chunks; state.blockStatsLabel = blocks;
        state.scannedStatsLabel = scanned; state.oreStatsLabel = ores;
        state.chunkProgressBar = chunkProgress; state.blockProgressBar = blockProgress;
        state.scanProgressBar = scanProgress; state.oreProgressBar = oreProgress;
        statsCard.addChildren(new UIElement[]{chunks, chunkProgress, blocks, blockProgress,
                scanned, scanProgress, ores, oreProgress});
        sidebar.addScrollViewChild(statsCard);

        UIElement oreCard = card("fm-info-card fm-ore-card", 180);
        UIElement oreHeader = new UIElement().layout(layout -> layout.widthPercent(100).height(22)
                .flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        Label oreTitle = label("screen.veinminerplus.ores.title", "fm-card-title fm-ore-header-title");
        oreTitle.layout(layout -> layout.flex(1).minWidth(0).height(20));
        oreHeader.addChild(oreTitle);
        Label oreTotal = label("screen.veinminerplus.ores.total", "fm-ore-total");
        oreTotal.layout(layout -> layout.width(70).height(18));
        state.oreTotalLabel = oreTotal;
        oreHeader.addChild(oreTotal);
        oreCard.addChild(oreHeader);
        ScrollerView oreList = new ScrollerView();
        oreList.setId("fast_mine_ore_list");
        oreList.layout(layout -> layout.flex(1).widthPercent(100));
        oreList.addClass("fm-ore-list");
        oreList.scrollerStyle(style -> style.mode(ScrollerMode.VERTICAL)
                .verticalScrollDisplay(ScrollDisplay.AUTO).horizontalScrollDisplay(ScrollDisplay.NEVER)
                .scrollerViewStyle(2.0f).minScrollPixel(6.0f).maxScrollPixel(14.0f));
        oreList.viewPort(view -> view.layout(layout -> layout.paddingAll(0.0f)));
        oreList.viewContainer(view -> view.layout(layout -> layout.widthPercent(100).flexDirection(FlexDirection.COLUMN).gapRow(1)));
        oreList.verticalScroller(scroller -> {
            scroller.headButton.setDisplay(false);
            scroller.tailButton.setDisplay(false);
            // Keep wheel/drag scrolling available while removing the visual rail.
            scroller.setDisplay(false);
        });
        state.oreList = oreList;
        oreCard.addChild(oreList);
        sidebar.addScrollViewChild(oreCard);

        content.addChild(sidebar);
        root.addChild(content);
        state.refreshLabels();
        return ModularUI.of(UI.of(root, List.of(StylesheetManager.INSTANCE.getMergedStylesheetsSafe("lss/miui.lss")), FastMineScreen::responsiveSize));
    }
    private static Size responsiveSize(Size screenSize) {
        int availableWidth = Math.max(1, screenSize.width - 16);
        int availableHeight = Math.max(1, screenSize.height - 16);
        int width = Math.min(INITIAL_WIDTH, Math.max(600, Math.round(availableWidth * .94f)));
        int height = Math.min(INITIAL_HEIGHT, Math.max(390, Math.round(availableHeight * .92f)));
        return Size.of(Math.min(width, availableWidth), Math.min(height, availableHeight));
    }

    private static UIElement card(String classes, float height) {
        UIElement card = new UIElement().layout(layout -> layout.widthPercent(100).height(height).flexDirection(FlexDirection.COLUMN).gapRow(4));
        for (String className : classes.split(" ")) card.addClass(className);
        return card;
    }

    private static Button actionButton(String key, String classes) {
        Button button = new Button();
        button.setText(Component.translatable(key));
        for (String className : classes.split(" ")) button.addClass(className);
        return button;
    }

    private static ProgressBar progressBar(String classes) {
        ProgressBar bar = new ProgressBar();
        bar.setRange(0.0f, 1.0f);
        bar.label.setDisplay(false);
        bar.addClass(classes);
        return bar;
    }

    private static Label label(String key, String classes) {
        Label label = new Label();
        label.setText(Component.translatable(key).withStyle(ChatFormatting.BOLD));
        label.setOverflowVisible(false);
        label.textStyle(style -> style.textWrap(TextWrap.HIDE));
        label.addClass(classes);
        return label;
    }

    private static final class UiState {
        private FastMineProgress progress;
        private final int centerChunkX, centerChunkZ;
        private int firstX, firstZ, secondX, secondZ, viewRadius = 8;
        private SelectMode mode = SelectMode.RECTANGLE;
        private boolean dragging, submitted;
        private Label dimensionLabel, selectionLabel, playerPositionLabel, zoomLabel, progressStateLabel, progressValueLabel, chunkStatsLabel, blockStatsLabel, scannedStatsLabel, oreStatsLabel, oreTotalLabel;
        private ProgressBar progressBar, chunkProgressBar, blockProgressBar, scanProgressBar, oreProgressBar;
        private Button confirmButton, cancelButton;
        private ScrollerView oreList;
        private Map<ResourceLocation, Long> renderedOreBreakdown = Map.of();

        private UiState(FastMineProgress progress) {
            this.progress = progress;
            submitted = isTaskActive(progress.state());
            ChunkPos chunk = new ChunkPos(Minecraft.getInstance().player.blockPosition());
            centerChunkX = chunk.x; centerChunkZ = chunk.z;
            firstX = secondX = centerChunkX; firstZ = secondZ = centerChunkZ;
            if (rememberedSelection != null && rememberedSelection.matches(Minecraft.getInstance())) {
                firstX = rememberedSelection.firstX;
                firstZ = rememberedSelection.firstZ;
                secondX = rememberedSelection.secondX;
                secondZ = rememberedSelection.secondZ;
                viewRadius = rememberedSelection.viewRadius;
                mode = rememberedSelection.mode;
            }
        }

        private void rememberSelection() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null && minecraft.level != null) {
                rememberedSelection = new SelectionMemory(minecraft.player.getUUID(),
                        minecraft.level.dimension().location(), firstX, firstZ, secondX, secondZ,
                        viewRadius, mode);
            }
        }

        private void setMode(SelectMode mode, Button single, Button rectangle) {
            if (submitted) return;
            this.mode = mode;
            syncModeButtons(single, rectangle);
            if (mode == SelectMode.SINGLE) { secondX = firstX; secondZ = firstZ; }
            rememberSelection();
            refreshLabels();
        }

        private void syncModeButtons(Button single, Button rectangle) {
            single.removeClass("fm-tool-selected");
            rectangle.removeClass("fm-tool-selected");
            (mode == SelectMode.SINGLE ? single : rectangle).addClass("fm-tool-selected");
        }

        private void clearSelection() {
            if (submitted) return;
            firstX = secondX = centerChunkX; firstZ = secondZ = centerChunkZ; refreshLabels();
            rememberSelection();
        }

        private void confirmTask() {
            Minecraft minecraft = Minecraft.getInstance();
            if (submitted || minecraft.level == null) return;
            NetworkHandler.sendFastMineSelection(minecraft.level.dimension().location(), firstX, firstZ, secondX, secondZ);
            submitted = true;
            rememberSelection();
            if (confirmButton != null) confirmButton.setActive(false);
            refreshLabels();
        }

        private void cancelTask() {
            if (!submitted && !isTaskActive(progress.state())) return;
            NetworkHandler.sendFastMineCancel();
            submitted = true;
            progress = new FastMineProgress(progress.loadedChunks(), progress.totalChunks(),
                    progress.scannedBlocks(), progress.totalBlocks(), progress.brokenBlocks(),
                    progress.foundOres(), progress.targetBlocks(), progress.processedBlocks(),
                    progress.oreBreakdown(), FastMineProgress.State.CANCELLED);
            VeinMinerPlusClient.applyLocalFastMineProgress(progress);
            refreshLabels();
        }

        private void adjustRadius(float delta) {
            if (!submitted && delta != 0) {
                viewRadius = Mth.clamp(viewRadius + (delta > 0 ? -1 : 1), 4, 50);
                rememberSelection();
                refreshLabels();
            }
        }

        private void refreshLabels() {
            if (selectionLabel == null) return;
            int width = Math.abs(secondX - firstX) + 1;
            int depth = Math.abs(secondZ - firstZ) + 1;
            long selectedChunks = (long) width * depth;
            selectionLabel.setText(Component.translatable("screen.veinminerplus.selection",
                    width, depth, selectedChunks).withStyle(ChatFormatting.BOLD));
            if (Minecraft.getInstance().level != null) dimensionLabel.setText(Component.translatable("screen.veinminerplus.dimension", Minecraft.getInstance().level.dimension().location().toString()).withStyle(ChatFormatting.BOLD));
            ChunkPos playerChunk = Minecraft.getInstance().player == null ? new ChunkPos(centerChunkX, centerChunkZ) : new ChunkPos(Minecraft.getInstance().player.blockPosition());
            playerPositionLabel.setText(Component.translatable("screen.veinminerplus.player_position", playerChunk.x, playerChunk.z).withStyle(ChatFormatting.BOLD));
            zoomLabel.setText(Component.translatable("screen.veinminerplus.map.zoom",
                    viewRadius * 2 + 1, viewRadius * 2 + 1).withStyle(ChatFormatting.BOLD));
            progressStateLabel.setText(Component.translatable("screen.veinminerplus.state",
                    Component.translatable(stateKey(progress.state())).withStyle(ChatFormatting.BOLD)).withStyle(ChatFormatting.BOLD));
            progressValueLabel.setText(Component.translatable("screen.veinminerplus.task.progress", Math.round(progress.completion() * 100)).withStyle(ChatFormatting.BOLD));
            chunkStatsLabel.setText(Component.translatable("screen.veinminerplus.stats.chunks", progress.loadedChunks(), progress.totalChunks()).withStyle(ChatFormatting.BOLD));
            blockStatsLabel.setText(Component.translatable("screen.veinminerplus.stats.blocks",
                    progress.brokenBlocks(), progress.processedBlocks(), progress.targetBlocks())
                    .withStyle(ChatFormatting.BOLD));
            scannedStatsLabel.setText(Component.translatable("screen.veinminerplus.stats.scanned",
                    progress.scannedBlocks(), progress.totalBlocks()).withStyle(ChatFormatting.BOLD));
            oreStatsLabel.setText(Component.translatable("screen.veinminerplus.stats.ores", progress.foundOres()).withStyle(ChatFormatting.BOLD));
            oreTotalLabel.setText(Component.translatable("screen.veinminerplus.ores.total", progress.foundOres()).withStyle(ChatFormatting.BOLD));
            refreshOreRows();
            if (progressBar != null) progressBar.setProgress(progress.completion());
            if (chunkProgressBar != null) chunkProgressBar.setProgress(fraction(progress.loadedChunks(), progress.totalChunks()));
            long blockTotal = progress.targetBlocks() > 0 ? progress.targetBlocks() : progress.totalBlocks();
            long blockValue = progress.targetBlocks() > 0 ? progress.processedBlocks() : progress.scannedBlocks();
            if (blockProgressBar != null) blockProgressBar.setProgress(fraction(blockValue, blockTotal));
            if (scanProgressBar != null) scanProgressBar.setProgress(fraction(progress.scannedBlocks(), progress.totalBlocks()));
            if (oreProgressBar != null) oreProgressBar.setProgress(fraction(progress.foundOres(), progress.targetBlocks()));
            if (confirmButton != null) confirmButton.setActive(!submitted);
            // Keep cancel available during the short client/server hand-off after
            // pressing Start, but disable it once the server reports a terminal
            // state so a completed task cannot be cancelled again.
            boolean waitingForServer = submitted && progress.state() == FastMineProgress.State.IDLE;
            if (cancelButton != null) cancelButton.setActive(waitingForServer || isTaskActive(progress.state()));
        }

        private void refreshOreRows() {
            if (oreList == null || renderedOreBreakdown.equals(progress.oreBreakdown())) return;
            oreList.viewContainer.clearAllExternalChildren();
            if (progress.oreBreakdown().isEmpty()) {
                Label empty = label("screen.veinminerplus.ores.pending", "fm-ore-empty");
                empty.layout(layout -> layout.widthPercent(100).height(22));
                oreList.addScrollViewChild(empty);
            } else {
                for (Map.Entry<ResourceLocation, Long> entry : progress.oreBreakdown().entrySet()) {
                    ItemStack stack = cachedOreIcon(entry.getKey());
                    UIElement row = new UIElement().layout(layout -> layout.widthPercent(100).height(25)
                            .flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapColumn(5))
                            .addClass("fm-ore-row");
                    ItemSlot icon = new ItemSlot().setItem(stack, false);
                    icon.getStyle().backgroundTexture(IGuiTexture.EMPTY);
                    icon.layout(layout -> layout.width(22).height(22));
                    Label name = new Label();
                    name.setText(stack.getHoverName().copy().withStyle(ChatFormatting.BOLD));
                    name.setOverflowVisible(false);
                    name.layout(layout -> layout.flex(1).height(20));
                    name.addClass("fm-ore-name");
                    Label count = new Label();
                    count.setText(Component.literal(Long.toString(entry.getValue())).withStyle(ChatFormatting.BOLD));
                    count.layout(layout -> layout.width(45).height(20));
                    count.addClass("fm-ore-count");
                    row.addChildren(new UIElement[]{icon, name, count});
                    oreList.addScrollViewChild(row);
                }
            }
            renderedOreBreakdown = new LinkedHashMap<>(progress.oreBreakdown());
        }

        private static ItemStack cachedOreIcon(ResourceLocation id) {
            ItemStack cached = ORE_ICON_CACHE.get(id);
            if (cached != null) return cached.copy();
            Item item = BuiltInRegistries.ITEM.get(id);
            // Most blocks use the same registry id for their item form.  A few
            // modded blocks do not, so fall back through the block registry before
            // using a visible placeholder.  The resolved stack is cached because
            // this list is refreshed on every progress packet.
            if (item == null || item == Items.AIR) {
                Block block = BuiltInRegistries.BLOCK.get(id);
                if (block != null && block != Blocks.AIR && block.asItem() != Items.AIR) {
                    item = block.asItem();
                }
            }
            ItemStack stack = new ItemStack(item == null || item == Items.AIR ? Items.DIAMOND : item);
            if (ORE_ICON_CACHE.size() >= 128) ORE_ICON_CACHE.remove(ORE_ICON_CACHE.keySet().iterator().next());
            ORE_ICON_CACHE.put(id, stack);
            return stack.copy();
        }

        private static float fraction(long value, long total) {
            return total <= 0 ? 0.0f : Mth.clamp((float) value / (float) total, 0.0f, 1.0f);
        }

        private static String stateKey(FastMineProgress.State state) {
            return "screen.veinminerplus.state." + state.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private record SelectionMemory(java.util.UUID playerId, ResourceLocation dimension,
            int firstX, int firstZ, int secondX, int secondZ, int viewRadius, SelectMode mode) {
        private boolean matches(Minecraft minecraft) {
            return minecraft.player != null && minecraft.level != null
                    && playerId.equals(minecraft.player.getUUID())
                    && dimension.equals(minecraft.level.dimension().location());
        }
    }

    private enum SelectMode { SINGLE, RECTANGLE }

    private static final class FastMineMapElement extends UIElement {
        private final UiState state;
        private FastMineMapElement(UiState state) {
            this.state = state; addClass("fm-map");
            addEventListener(UIEvents.MOUSE_DOWN, this::onMouseDown);
            addEventListener(UIEvents.MOUSE_UP, event -> { if (event.button == 0) state.dragging = false; });
            addEventListener(UIEvents.DRAG_UPDATE, this::onDragUpdate);
            addEventListener(UIEvents.MOUSE_WHEEL, event -> state.adjustRadius(event.deltaY));
        }

        private void onMouseDown(UIEvent event) {
            if (state.submitted || event.button != 0 || event.target != this) return;
            select(event.x, event.y, true); state.dragging = state.mode == SelectMode.RECTANGLE; startDrag(null, null); event.stopPropagation();
        }
        private void onDragUpdate(UIEvent event) { if (!state.submitted && state.dragging) { select(event.x, event.y, false); event.stopPropagation(); } }

        private void select(float mouseX, float mouseY, boolean start) {
            Vector2f local = getLocalMouse(mouseX, mouseY);
            float mapX = local.x - getPositionX(), mapY = local.y - getPositionY();
            int cells = state.viewRadius * 2 + 1;
            float mapSize = Math.min(getSizeWidth(), getSizeHeight());
            float cell = mapSize / cells;
            float originX = (getSizeWidth() - mapSize) * 0.5f;
            float originY = (getSizeHeight() - mapSize) * 0.5f;
            if (mapX < originX || mapY < originY || mapX >= originX + cell * cells || mapY >= originY + cell * cells) return;
            int x = state.centerChunkX - state.viewRadius + Mth.clamp((int)((mapX - originX) / cell), 0, cells - 1);
            int z = state.centerChunkZ - state.viewRadius + Mth.clamp((int)((mapY - originY) / cell), 0, cells - 1);
            if (start || state.mode == SelectMode.SINGLE) { state.firstX = state.secondX = x; state.firstZ = state.secondZ = z; }
            else { state.secondX = x; state.secondZ = z; }
            state.rememberSelection();
            state.refreshLabels();
        }

        @Override public void drawBackgroundAdditional(GUIContext context) {
            GuiGraphics graphics = context.graphics;
            int left = Mth.floor(getPositionX()), top = Mth.floor(getPositionY()), width = Math.max(1, Mth.floor(getSizeWidth())), height = Math.max(1, Mth.floor(getSizeHeight()));
            // The map itself is the surface.  Do not paint a second opaque tile
            // background over it: each chunk is rendered from the actual loaded
            // terrain below, as in FTB Chunks.
            graphics.fill(left, top, left + width, top + height, 0xFFD9DEE5);
            int cells = state.viewRadius * 2 + 1;
            // Keep each chunk square. The map is centered in its card and the
            // surrounding surface remains visible as a deliberate margin.
            float mapSize = Math.min(width, height);
            float originX = left + (width - mapSize) * 0.5f;
            float originY = top + (height - mapSize) * 0.5f;
            float cellWidth = mapSize / cells, cellHeight = cellWidth;
            Minecraft minecraft = Minecraft.getInstance();
            for (int z = -state.viewRadius; z <= state.viewRadius; z++) for (int x = -state.viewRadius; x <= state.viewRadius; x++) {
                int chunkX = state.centerChunkX + x, chunkZ = state.centerChunkZ + z;
                boolean loaded = minecraft.level != null && minecraft.level.hasChunk(chunkX, chunkZ), selected = isSelected(chunkX, chunkZ);
                int cellLeft = Mth.floor(originX + (x + state.viewRadius) * cellWidth), cellTop = Mth.floor(originY + (z + state.viewRadius) * cellHeight);
                int cellRight = Mth.floor(originX + (x + state.viewRadius + 1) * cellWidth), cellBottom = Mth.floor(originY + (z + state.viewRadius + 1) * cellHeight);
                if (loaded && minecraft.level != null) {
                    drawTerrainChunk(graphics, minecraft.level, chunkX, chunkZ,
                            cellLeft, cellTop, cellRight, cellBottom, cellWidth, cellHeight);
                } else {
                    graphics.fill(cellLeft, cellTop, cellRight, cellBottom, 0xFFCBD1D8);
                }
                // Keep chunk boundaries subtle.  They provide the same spatial
                // reference as FTB without turning every chunk into a card.
                graphics.fill(cellRight - 1, cellTop, cellRight, cellBottom, 0x3329343F);
                graphics.fill(cellLeft, cellBottom - 1, cellRight, cellBottom, 0x3329343F);
                if (selected) {
                    graphics.fill(cellLeft, cellTop, cellRight, cellBottom, 0x5A3482FF);
                }
            }
            if (minecraft.player != null) {
                ChunkPos playerChunk = new ChunkPos(minecraft.player.blockPosition());
                double fx = Mth.positiveModulo(minecraft.player.getX(), 16) / 16.0, fz = Mth.positiveModulo(minecraft.player.getZ(), 16) / 16.0;
                float px = originX + (playerChunk.x - state.centerChunkX + state.viewRadius + (float)fx) * cellWidth, pz = originY + (playerChunk.z - state.centerChunkZ + state.viewRadius + (float)fz) * cellHeight;
                if (px >= left && px <= left + width && pz >= top && pz <= top + height) {
                    int size = Math.max(4, Mth.floor(Math.min(12, Math.min(cellWidth, cellHeight) * .30f))), cx = Mth.floor(px), cy = Mth.floor(pz);
                    int body = 0xFF245C70, highlight = 0xFF6DB6D4;
                    graphics.fill(cx - size / 3, cy - size, cx + size / 3 + 1, cy - size / 3, highlight);
                    graphics.fill(cx - size / 2, cy - size / 3, cx + size / 2 + 1, cy + size / 2 + 1, body);
                    graphics.fill(cx - size, cy - size / 4, cx - size / 2, cy + size / 4 + 1, body);
                    graphics.fill(cx + size / 2 + 1, cy - size / 4, cx + size, cy + size / 4 + 1, body);
                    graphics.fill(cx - size / 3, cy + size / 2 + 1, cx - 1, cy + size, body);
                    graphics.fill(cx + 1, cy + size / 2 + 1, cx + size / 3 + 1, cy + size, body);
                }
            }
        }
        private static void drawTerrainChunk(GuiGraphics graphics, Level level, int chunkX, int chunkZ,
                int left, int top, int right, int bottom, float cellWidth, float cellHeight) {
            int samples = cellWidth >= 28.0f && cellHeight >= 28.0f ? 8
                    : cellWidth >= 12.0f && cellHeight >= 12.0f ? 4 : 1;
            int sampleWidth = Math.max(1, (right - left) / samples);
            int sampleHeight = Math.max(1, (bottom - top) / samples);
            int baseX = chunkX << 4, baseZ = chunkZ << 4;
            for (int sz = 0; sz < samples; sz++) for (int sx = 0; sx < samples; sx++) {
                int worldX = baseX + Math.min(15, (sx * 16 + 8) / samples);
                int worldZ = baseZ + Math.min(15, (sz * 16 + 8) / samples);
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
                int color = terrainColor(level, worldX, surfaceY, worldZ);
                int px = left + sx * sampleWidth;
                int pz = top + sz * sampleHeight;
                int px2 = sx == samples - 1 ? right : Math.min(right, px + sampleWidth + 1);
                int pz2 = sz == samples - 1 ? bottom : Math.min(bottom, pz + sampleHeight + 1);
                graphics.fill(px, pz, px2, pz2, color);
            }
        }

        private static int terrainColor(Level level, int x, int y, int z) {
            if (y < level.getMinBuildHeight()) return 0xFF9EA8B2;
            Block block = level.getBlockState(new BlockPos(x, y, z)).getBlock();
            MapColor mapColor = block.defaultMapColor();
            int rgb = mapColor == null || mapColor == MapColor.NONE ? 0x8E969E : mapColor.col;
            // A tiny height shade makes otherwise flat map colors read as terrain
            // while keeping Minecraft's block palette recognizable.
            int shade = Mth.clamp(150 + (y - level.getMinBuildHeight()) * 2, 150, 255);
            int r = ((rgb >> 16) & 255) * shade / 255;
            int g = ((rgb >> 8) & 255) * shade / 255;
            int b = (rgb & 255) * shade / 255;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        private boolean isSelected(int x, int z) { return state.mode == SelectMode.SINGLE ? x == state.firstX && z == state.firstZ : x >= Math.min(state.firstX, state.secondX) && x <= Math.max(state.firstX, state.secondX) && z >= Math.min(state.firstZ, state.secondZ) && z <= Math.max(state.firstZ, state.secondZ); }
    }
}

