package com.extrarawstyle.veinminerplus;

import java.io.IOException;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class VeinMinerConfigScreen extends GuiScreen {
    private final NetworkHandler.ConfigSnapshotMessage initial;
    private GuiTextField maxNormalBlocks;
    private GuiTextField maxNormalBlocksPerTick;
    private GuiTextField maxBlastBlocks;
    private GuiTextField maxBlastBlocksPerTick;
    private GuiTextField blastSearchDistance;
    private boolean noHungerCost;
    private GuiButton noHungerCostButton;
    private boolean storageBinding;
    private GuiButton storageBindingButton;
    private String error;

    VeinMinerConfigScreen(NetworkHandler.ConfigSnapshotMessage initial) {
        this.initial = initial;
        this.noHungerCost = initial.noHungerCost;
        this.storageBinding = initial.storageBinding;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int left = panelLeft() + 15;
        int right = left + columnWidth() + 10;
        int top = panelTop();
        int fieldWidth = columnWidth();

        maxNormalBlocks = addField(left, top + 70, fieldWidth, initial.maxNormalBlocks);
        maxNormalBlocksPerTick = addField(right, top + 70, fieldWidth, initial.maxNormalBlocksPerTick);
        maxBlastBlocks = addField(left, top + 134, fieldWidth, initial.maxBlastBlocks);
        maxBlastBlocksPerTick = addField(right, top + 134, fieldWidth, initial.maxBlastBlocksPerTick);
        blastSearchDistance = addField(left, top + 198, fieldWidth, initial.blastSearchDistance);

        noHungerCostButton = new GuiButton(1, right, top + 198, fieldWidth, 20, noHungerText());
        buttonList.add(noHungerCostButton);
        storageBindingButton = new GuiButton(2, left, top + 222, panelWidth() - 30, 20, storageBindingText());
        buttonList.add(storageBindingButton);
        buttonList.add(new GuiButton(3, left, top + 252, 116, 20,
                I18n.format("veinminerplus.configuration.reset")));
        buttonList.add(new GuiButton(4, left + 122, top + 252, 96, 20, I18n.format("gui.cancel")));
        buttonList.add(new GuiButton(5, right + columnWidth() - 96, top + 252, 96, 20,
                I18n.format("veinminerplus.configuration.save")));
    }

    private GuiTextField addField(int x, int y, int width, int value) {
        GuiTextField field = new GuiTextField(0, fontRenderer, x, y, width, 20);
        field.setText(Integer.toString(value));
        field.setMaxStringLength(5);
        return field;
    }

    private String noHungerText() {
        return I18n.format("veinminerplus.configuration.noHungerCost.value", onOff(noHungerCost));
    }

    private String storageBindingText() {
        return I18n.format("veinminerplus.configuration.storageBinding.value", onOff(storageBinding));
    }

    private static String onOff(boolean value) {
        return I18n.format(value ? "options.on" : "options.off");
    }

    private void reset() {
        maxNormalBlocks.setText("1024");
        maxNormalBlocksPerTick.setText("8");
        maxBlastBlocks.setText("32767");
        maxBlastBlocksPerTick.setText("64");
        blastSearchDistance.setText("20");
        noHungerCost = false;
        noHungerCostButton.displayString = noHungerText();
        storageBinding = true;
        storageBindingButton.displayString = storageBindingText();
        error = null;
    }

    private void save() {
        Integer maxNormal = parse(maxNormalBlocks, 32, 32767);
        Integer normalPerTick = parse(maxNormalBlocksPerTick, 1, 384);
        Integer maxBlast = parse(maxBlastBlocks, 32, 32767);
        Integer blastPerTick = parse(maxBlastBlocksPerTick, 1, 512);
        Integer distance = parse(blastSearchDistance, 3, 128);
        if (maxNormal == null || normalPerTick == null || maxBlast == null || blastPerTick == null
                || distance == null) {
            return;
        }

        NetworkHandler.sendConfigUpdate(maxNormal, normalPerTick, maxBlast, blastPerTick, distance,
                noHungerCost, storageBinding);
        mc.displayGuiScreen(null);
    }

    private Integer parse(GuiTextField field, int min, int max) {
        try {
            int value = Integer.parseInt(field.getText());
            if (value < min || value > max) {
                error = I18n.format("veinminerplus.configuration.range", min, max);
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            error = I18n.format("veinminerplus.configuration.invalid");
            return null;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 1:
                noHungerCost = !noHungerCost;
                noHungerCostButton.displayString = noHungerText();
                break;
            case 2:
                storageBinding = !storageBinding;
                storageBindingButton.displayString = storageBindingText();
                break;
            case 3:
                reset();
                break;
            case 4:
                mc.displayGuiScreen(null);
                break;
            case 5:
                save();
                break;
            default:
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            mc.displayGuiScreen(null);
            return;
        }
        maxNormalBlocks.textboxKeyTyped(typedChar, keyCode);
        maxNormalBlocksPerTick.textboxKeyTyped(typedChar, keyCode);
        maxBlastBlocks.textboxKeyTyped(typedChar, keyCode);
        maxBlastBlocksPerTick.textboxKeyTyped(typedChar, keyCode);
        blastSearchDistance.textboxKeyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        maxNormalBlocks.mouseClicked(mouseX, mouseY, mouseButton);
        maxNormalBlocksPerTick.mouseClicked(mouseX, mouseY, mouseButton);
        maxBlastBlocks.mouseClicked(mouseX, mouseY, mouseButton);
        maxBlastBlocksPerTick.mouseClicked(mouseX, mouseY, mouseButton);
        blastSearchDistance.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        maxNormalBlocks.updateCursorCounter();
        maxNormalBlocksPerTick.updateCursorCounter();
        maxBlastBlocks.updateCursorCounter();
        maxBlastBlocksPerTick.updateCursorCounter();
        blastSearchDistance.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int left = panelLeft();
        int right = left + panelWidth();
        int top = panelTop();
        int columnLeft = left + 15;
        int columnRight = columnLeft + columnWidth() + 10;

        drawRect(left, top, right, top + 324, 0xB0101010);
        drawRect(left + 1, top + 1, right - 1, top + 2, 0xFF707070);
        drawCenteredString(fontRenderer, I18n.format("veinminerplus.configuration.title"), width / 2, top + 8,
                0xFFFFFF);
        drawCenteredString(fontRenderer, I18n.format("veinminerplus.configuration.subtitle"), width / 2, top + 22,
                0xB0B0B0);
        drawSection("veinminerplus.configuration.section.common", top + 42);
        drawLabel("veinminerplus.configuration.maxNormalBlocks", columnLeft, top + 58);
        drawLabel("veinminerplus.configuration.maxNormalBlocksPerTick", columnRight, top + 58);
        drawSection("veinminerplus.configuration.section.blast", top + 106);
        drawLabel("veinminerplus.configuration.maxBlastBlocks", columnLeft, top + 122);
        drawLabel("veinminerplus.configuration.maxBlastBlocksPerTick", columnRight, top + 122);
        drawSection("veinminerplus.configuration.section.player", top + 170);
        drawLabel("veinminerplus.configuration.blastSearchDistance", columnLeft, top + 186);

        maxNormalBlocks.drawTextBox();
        maxNormalBlocksPerTick.drawTextBox();
        maxBlastBlocks.drawTextBox();
        maxBlastBlocksPerTick.drawTextBox();
        blastSearchDistance.drawTextBox();

        if (error != null) {
            drawCenteredString(fontRenderer, error, width / 2, top + 292, 0xFF5555);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawSection(String key, int y) {
        drawRect(panelLeft() + 10, y - 5, panelLeft() + panelWidth() - 10, y - 4, 0xFF505050);
        fontRenderer.drawStringWithShadow(I18n.format(key), panelLeft() + 10, y, 0xFFFF55);
    }

    private void drawLabel(String key, int x, int y) {
        fontRenderer.drawStringWithShadow(I18n.format(key), x, y, 0xFFFFFF);
    }

    private int panelWidth() {
        return Math.min(400, width - 20);
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int panelTop() {
        return Math.max(8, (height - 324) / 2);
    }

    private int columnWidth() {
        return (panelWidth() - 40) / 2;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
