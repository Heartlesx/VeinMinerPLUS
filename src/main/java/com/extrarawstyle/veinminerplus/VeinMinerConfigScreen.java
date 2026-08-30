package com.extrarawstyle.veinminerplus;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public final class VeinMinerConfigScreen extends Screen {
    private final NetworkHandler.ConfigSnapshotPayload initial;
    private EditBox maxNormalBlocks;
    private EditBox maxNormalBlocksPerTick;
    private EditBox maxBlastBlocks;
    private EditBox maxBlastBlocksPerTick;
    private EditBox blastSearchDistance;
    private boolean noHungerCost;
    private Button noHungerCostButton;
    private Component error;

    VeinMinerConfigScreen(NetworkHandler.ConfigSnapshotPayload initial) {
        super(Component.translatable("veinminerplus.configuration.title"));
        this.initial = initial;
        this.noHungerCost = initial.noHungerCost();
    }

    @Override
    protected void init() {
        int left = panelLeft() + 15;
        int right = left + columnWidth() + 10;
        int top = panelTop();
        int fieldWidth = columnWidth();

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

        addRenderableWidget(Button.builder(Component.translatable("veinminerplus.configuration.reset"), button -> reset())
                .bounds(left, top + 244, 116, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 122, top + 244, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("veinminerplus.configuration.save"), button -> save())
                .bounds(right + columnWidth() - 96, top + 244, 96, 20).build());
    }

    private EditBox addField(String labelKey, int x, int y, int width, int value) {
        EditBox field = new EditBox(this.font, x, y, width, 20, Component.translatable(labelKey));
        field.setValue(Integer.toString(value));
        field.setFilter(valueText -> valueText.length() <= 5 && valueText.chars().allMatch(Character::isDigit));
        addRenderableWidget(field);
        return field;
    }

    private Component noHungerText() {
        return Component.translatable("veinminerplus.configuration.noHungerCost.value", noHungerCost
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
        error = null;
    }

    private void save() {
        Integer maxNormal = parse(maxNormalBlocks, 32, 32767);
        Integer normalPerTick = parse(maxNormalBlocksPerTick, 1, 384);
        Integer maxBlast = parse(maxBlastBlocks, 32, 32767);
        Integer blastPerTick = parse(maxBlastBlocksPerTick, 1, 512);
        Integer distance = parse(blastSearchDistance, 3, 128);
        if (maxNormal == null || normalPerTick == null || maxBlast == null || blastPerTick == null || distance == null) {
            return;
        }

        NetworkHandler.sendConfigUpdate(new NetworkHandler.ConfigUpdatePayload(maxNormal, normalPerTick, maxBlast,
                blastPerTick, distance, noHungerCost));
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
        graphics.fill(left, top, right, top + 314, 0xB0101010);
        graphics.fill(left + 1, top + 1, right - 1, top + 2, 0xFF707070);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, top + 8, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("veinminerplus.configuration.subtitle"), this.width / 2, top + 22,
                0xFFB0B0B0);
        drawSection(graphics, "veinminerplus.configuration.section.common", top + 42);
        drawLabel(graphics, "veinminerplus.configuration.maxNormalBlocks", columnLeft, top + 58);
        drawLabel(graphics, "veinminerplus.configuration.maxNormalBlocksPerTick", columnRight, top + 58);
        drawSection(graphics, "veinminerplus.configuration.section.blast", top + 106);
        drawLabel(graphics, "veinminerplus.configuration.maxBlastBlocks", columnLeft, top + 122);
        drawLabel(graphics, "veinminerplus.configuration.maxBlastBlocksPerTick", columnRight, top + 122);
        drawSection(graphics, "veinminerplus.configuration.section.player", top + 170);
        drawLabel(graphics, "veinminerplus.configuration.blastSearchDistance", columnLeft, top + 186);
        if (error != null) {
            graphics.drawCenteredString(this.font, error, this.width / 2, top + 282, 0xFFFF5555);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
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
        return Math.max(8, (this.height - 314) / 2);
    }

    private int columnWidth() {
        return (panelWidth() - 40) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
