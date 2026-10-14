package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

public final class VeinMinerConfigScreen extends Screen {
    private static final int PANEL_HEIGHT = 340;
    private static final int LIST_ROWS = 10;

    private final NetworkHandler.ConfigSnapshotPayload initial;
    private EditBox maxNormalBlocks;
    private EditBox maxNormalBlocksPerTick;
    private EditBox maxBlastBlocks;
    private EditBox maxBlastBlocksPerTick;
    private EditBox blastSearchDistance;
    private boolean noHungerCost;
    private Button noHungerCostButton;
    private boolean storageBinding;
    private Button storageBindingButton;
    private Button blacklistButton;
    private final List<String> blacklist = new ArrayList<>();
    private int page;
    private int listPage;
    private final Button[] rowButtons = new Button[LIST_ROWS];
    private Button prevButton;
    private Button nextButton;
    private Button addHeldButton;
    private Button listBackButton;
    private Button listSaveButton;
    private final List<AbstractWidget> mainWidgets = new ArrayList<>();
    private final List<AbstractWidget> listWidgets = new ArrayList<>();
    private Component error;

    VeinMinerConfigScreen(NetworkHandler.ConfigSnapshotPayload initial) {
        super(Component.translatable("veinminerplus.configuration.title"));
        this.initial = initial;
        this.noHungerCost = initial.noHungerCost();
        this.storageBinding = initial.storageBinding();
        this.blacklist.addAll(initial.blacklist());
    }

    @Override
    protected void init() {
        mainWidgets.clear();
        listWidgets.clear();

        int left = panelLeft() + 15;
        int right = left + columnWidth() + 10;
        int top = panelTop();
        int fieldWidth = columnWidth();
        int rowWidth = panelWidth() - 30;

        maxNormalBlocks = addField("veinminerplus.configuration.maxNormalBlocks", left, top + 70,
                fieldWidth, initial.maxNormalBlocks());
        maxNormalBlocksPerTick = addField("veinminerplus.configuration.maxNormalBlocksPerTick", right, top + 70,
                fieldWidth, initial.maxNormalBlocksPerTick());
        maxBlastBlocks = addField("veinminerplus.configuration.maxBlastBlocks", left, top + 134,
                fieldWidth, initial.maxBlastBlocks());
        maxBlastBlocksPerTick = addField("veinminerplus.configuration.maxBlastBlocksPerTick", right, top + 134,
                fieldWidth, initial.maxBlastBlocksPerTick());
        blastSearchDistance = addField("veinminerplus.configuration.blastSearchDistance", left, top + 198,
                fieldWidth, initial.blastSearchDistance());

        noHungerCostButton = addRenderableWidget(Button.builder(noHungerText(), button -> {
            noHungerCost = !noHungerCost;
            button.setMessage(noHungerText());
        }).bounds(right, top + 198, fieldWidth, 20).build());
        mainWidgets.add(noHungerCostButton);

        storageBindingButton = addRenderableWidget(Button.builder(storageBindingText(), button -> {
            storageBinding = !storageBinding;
            button.setMessage(storageBindingText());
        }).bounds(left, top + 222, rowWidth, 20).build());
        mainWidgets.add(storageBindingButton);

        blacklistButton = addRenderableWidget(Button.builder(blacklistButtonText(), button -> {
            page = 1;
            applyPage();
        }).bounds(left, top + 252, rowWidth, 20).build());
        mainWidgets.add(blacklistButton);

        Button resetButton = addRenderableWidget(Button.builder(
                Component.translatable("veinminerplus.configuration.reset"), button -> reset())
                .bounds(left, top + 280, 116, 20).build());
        mainWidgets.add(resetButton);
        Button cancelButton = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                button -> onClose()).bounds(left + 122, top + 280, 96, 20).build());
        mainWidgets.add(cancelButton);
        Button saveButton = addRenderableWidget(Button.builder(
                Component.translatable("veinminerplus.configuration.save"), button -> save())
                .bounds(right + columnWidth() - 96, top + 280, 96, 20).build());
        mainWidgets.add(saveButton);

        for (int slot = 0; slot < LIST_ROWS; slot++) {
            int slotIndex = slot;
            Button button = Button.builder(Component.translatable("veinminerplus.configuration.blacklist.remove"),
                    clicked -> removeEntry(slotIndex))
                    .bounds(left + rowWidth - 44, top + 57 + slot * 20, 44, 18).build();
            rowButtons[slot] = button;
            addRenderableWidget(button);
            listWidgets.add(button);
        }

        prevButton = addRenderableWidget(Button.builder(Component.literal("<"), button -> changeListPage(-1))
                .bounds(left, top + 264, 44, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), button -> changeListPage(1))
                .bounds(left + rowWidth - 44, top + 264, 44, 20).build());
        addHeldButton = addRenderableWidget(Button.builder(
                Component.translatable("veinminerplus.configuration.blacklist.add_held"), button -> addHeldBlock())
                .bounds(left + 50, top + 264, rowWidth - 100, 20).build());
        listBackButton = addRenderableWidget(Button.builder(
                Component.translatable("veinminerplus.configuration.blacklist.back"), button -> {
                    page = 0;
                    applyPage();
                }).bounds(left, top + 288, 110, 20).build());
        listSaveButton = addRenderableWidget(Button.builder(
                Component.translatable("veinminerplus.configuration.save"), button -> save())
                .bounds(left + rowWidth - 110, top + 288, 110, 20).build());
        listWidgets.add(prevButton);
        listWidgets.add(nextButton);
        listWidgets.add(addHeldButton);
        listWidgets.add(listBackButton);
        listWidgets.add(listSaveButton);

        applyPage();
    }

    private EditBox addField(String labelKey, int x, int y, int width, int value) {
        EditBox field = new EditBox(this.font, x, y, width, 20, Component.translatable(labelKey));
        field.setValue(Integer.toString(value));
        field.setFilter(valueText -> valueText.length() <= 5 && valueText.chars().allMatch(Character::isDigit));
        addRenderableWidget(field);
        mainWidgets.add(field);
        return field;
    }

    private void applyPage() {
        boolean main = page == 0;
        for (AbstractWidget widget : mainWidgets) {
            widget.visible = main;
            widget.active = main;
        }
        for (AbstractWidget widget : listWidgets) {
            widget.visible = !main;
            widget.active = !main;
        }
        if (main) {
            blacklistButton.setMessage(blacklistButtonText());
        } else {
            refreshRows();
        }
    }

    // Row widgets stay alive across pages; only their visibility follows the current slice, so
    // deleting an entry never rebuilds the screen.
    private void refreshRows() {
        listPage = Mth.clamp(listPage, 0, maxListPage());
        int start = listPage * LIST_ROWS;
        for (int slot = 0; slot < LIST_ROWS; slot++) {
            int index = start + slot;
            boolean visible = index < blacklist.size();
            rowButtons[slot].visible = visible;
            rowButtons[slot].active = visible;
            if (visible) {
                rowButtons[slot].setTooltip(Tooltip.create(Component.literal(blacklist.get(index))));
            }
        }
        prevButton.active = listPage > 0;
        nextButton.active = listPage < maxListPage();
        addHeldButton.active = canAddHeld();
    }

    private int maxListPage() {
        return Math.max(0, (blacklist.size() - 1) / LIST_ROWS);
    }

    private Component blacklistButtonText() {
        return Component.translatable("veinminerplus.configuration.blacklist.button", blacklist.size());
    }

    private boolean canAddHeld() {
        if (minecraft == null || minecraft.player == null || blacklist.size() >= BlockBlacklist.MAX_ENTRIES) {
            return false;
        }
        if (!(minecraft.player.getMainHandItem().getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
        return id != null && !blacklist.contains(id.toString());
    }

    private void addHeldBlock() {
        if (minecraft == null || minecraft.player == null
                || !(minecraft.player.getMainHandItem().getItem() instanceof BlockItem blockItem)) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
        if (id == null || blacklist.contains(id.toString()) || blacklist.size() >= BlockBlacklist.MAX_ENTRIES) {
            return;
        }
        blacklist.add(id.toString());
        listPage = maxListPage();
        refreshRows();
    }

    private void removeEntry(int slot) {
        int index = listPage * LIST_ROWS + slot;
        if (index >= blacklist.size()) {
            return;
        }
        blacklist.remove(index);
        refreshRows();
    }

    private void changeListPage(int delta) {
        listPage = Mth.clamp(listPage + delta, 0, maxListPage());
        refreshRows();
    }

    private Component noHungerText() {
        return Component.translatable("veinminerplus.configuration.noHungerCost.value", noHungerCost
                ? Component.translatable("options.on") : Component.translatable("options.off"));
    }

    private Component storageBindingText() {
        return Component.translatable("veinminerplus.configuration.storageBinding.value", storageBinding
                ? Component.translatable("options.on") : Component.translatable("options.off"));
    }

    private void reset() {
        maxNormalBlocks.setValue("1024");
        maxNormalBlocksPerTick.setValue("8");
        maxBlastBlocks.setValue("32767");
        maxBlastBlocksPerTick.setValue("64");
        blastSearchDistance.setValue("20");
        noHungerCost = false;
        noHungerCostButton.setMessage(noHungerText());
        storageBinding = true;
        storageBindingButton.setMessage(storageBindingText());
        blacklist.clear();
        listPage = 0;
        refreshRows();
        blacklistButton.setMessage(blacklistButtonText());
        error = null;
    }

    private void save() {
        Integer maxNormal = parse(maxNormalBlocks, 32, 32767);
        Integer normalPerTick = parse(maxNormalBlocksPerTick, 1, 384);
        Integer maxBlast = parse(maxBlastBlocks, 32, 32767);
        Integer blastPerTick = parse(maxBlastBlocksPerTick, 1, 512);
        Integer distance = parse(blastSearchDistance, 3, 128);
        if (maxNormal == null || normalPerTick == null || maxBlast == null || blastPerTick == null || distance == null) {
            page = 0;
            applyPage();
            return;
        }

        NetworkHandler.sendConfigUpdate(new NetworkHandler.ConfigUpdatePayload(maxNormal, normalPerTick, maxBlast,
                blastPerTick, distance, noHungerCost, storageBinding, List.copyOf(blacklist)));
        onClose();
    }

    private Integer parse(EditBox field, int min, int max) {
        try {
            int value = Integer.parseInt(field.getValue());
            if (value < min || value > max) {
                error = Component.translatable("veinminerplus.configuration.range", min, max);
                return null;
            }
            return Mth.clamp(value, min, max);
        } catch (NumberFormatException exception) {
            error = Component.translatable("veinminerplus.configuration.invalid");
            return null;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = panelLeft();
        int right = left + panelWidth();
        int top = panelTop();
        int columnLeft = left + 15;
        int columnRight = columnLeft + columnWidth() + 10;
        graphics.fill(left, top, right, top + PANEL_HEIGHT, 0xB0101010);
        graphics.fill(left + 1, top + 1, right - 1, top + 2, 0xFF707070);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, top + 8, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("veinminerplus.configuration.subtitle"), this.width / 2, top + 22,
                0xFFB0B0B0);
        if (page == 0) {
            drawSection(graphics, "veinminerplus.configuration.section.common", top + 42);
            drawLabel(graphics, "veinminerplus.configuration.maxNormalBlocks", columnLeft, top + 58);
            drawLabel(graphics, "veinminerplus.configuration.maxNormalBlocksPerTick", columnRight, top + 58);
            drawSection(graphics, "veinminerplus.configuration.section.blast", top + 106);
            drawLabel(graphics, "veinminerplus.configuration.maxBlastBlocks", columnLeft, top + 122);
            drawLabel(graphics, "veinminerplus.configuration.maxBlastBlocksPerTick", columnRight, top + 122);
            drawSection(graphics, "veinminerplus.configuration.section.player", top + 170);
            drawLabel(graphics, "veinminerplus.configuration.blastSearchDistance", columnLeft, top + 186);
            if (error != null) {
                graphics.drawCenteredString(this.font, error, this.width / 2, top + 306, 0xFFFF5555);
            }
        } else {
            renderBlacklistPage(graphics);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderBlacklistPage(GuiGraphics graphics) {
        int left = panelLeft();
        int top = panelTop();
        int innerLeft = left + 15;
        int innerWidth = panelWidth() - 30;
        graphics.drawString(this.font, Component.translatable("veinminerplus.configuration.blacklist.title",
                blacklist.size(), BlockBlacklist.MAX_ENTRIES), innerLeft, top + 42, 0xFFFFFF55);

        if (blacklist.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("veinminerplus.configuration.blacklist.empty"),
                    this.width / 2, top + 120, 0xFFB0B0B0);
        }

        int start = listPage * LIST_ROWS;
        for (int slot = 0; slot < LIST_ROWS; slot++) {
            int index = start + slot;
            if (index >= blacklist.size()) {
                break;
            }
            int rowTop = top + 58 + slot * 20;
            String raw = blacklist.get(index);
            ResourceLocation id = ResourceLocation.tryParse(raw);
            Block block = id != null && BuiltInRegistries.BLOCK.containsKey(id)
                    ? BuiltInRegistries.BLOCK.get(id) : null;
            if (block != null) {
                ItemStack icon = new ItemStack(block.asItem());
                if (!icon.isEmpty()) {
                    graphics.renderItem(icon, innerLeft, rowTop - 2);
                }
                graphics.drawString(this.font, block.getName(), innerLeft + 22, rowTop + 3, 0xFFFFFFFF);
            } else {
                graphics.drawString(this.font, Component.literal(raw), innerLeft + 22, rowTop + 3, 0xFF909090);
            }
        }

        Component pageText = Component.translatable(
                "veinminerplus.configuration.blacklist.page", listPage + 1, maxListPage() + 1);
        graphics.drawString(this.font, pageText,
                innerLeft + innerWidth - this.font.width(pageText), top + 42, 0xFFB0B0B0);
        graphics.drawCenteredString(this.font,
                Component.translatable("veinminerplus.configuration.blacklist.hint"),
                this.width / 2, top + 316, 0xFF909090);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    private void drawSection(GuiGraphics graphics, String key, int y) {
        graphics.fill(panelLeft() + 10, y - 5, panelLeft() + panelWidth() - 10, y - 4, 0xFF505050);
        graphics.drawString(this.font, Component.translatable(key), panelLeft() + 10, y, 0xFFFFFF55);
    }

    private void drawLabel(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(this.font, Component.translatable(key), x, y, 0xFFFFFFFF);
    }

    private int panelWidth() {
        return Math.min(400, this.width - 20);
    }

    private int panelLeft() {
        return (this.width - panelWidth()) / 2;
    }

    private int panelTop() {
        return Math.max(8, (this.height - PANEL_HEIGHT) / 2);
    }

    private int columnWidth() {
        return (panelWidth() - 40) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
