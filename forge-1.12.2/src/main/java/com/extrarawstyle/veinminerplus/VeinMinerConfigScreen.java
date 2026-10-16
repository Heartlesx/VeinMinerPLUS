package com.extrarawstyle.veinminerplus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class VeinMinerConfigScreen extends GuiScreen {
    private static final int PANEL_HEIGHT = 340;
    private static final int LIST_ROWS = 10;

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
    private GuiButton blacklistButton;
    private final List<String> blacklist = new ArrayList<>();
    private int page;
    private int listPage;
    private final GuiButton[] rowButtons = new GuiButton[LIST_ROWS];
    private GuiButton prevButton;
    private GuiButton nextButton;
    private GuiButton addHeldButton;
    private final List<GuiButton> mainWidgets = new ArrayList<>();
    private final List<GuiButton> listWidgets = new ArrayList<>();
    private String error;

    VeinMinerConfigScreen(NetworkHandler.ConfigSnapshotMessage initial) {
        this.initial = initial;
        this.noHungerCost = initial.noHungerCost;
        this.storageBinding = initial.storageBinding;
        this.blacklist.addAll(initial.blacklist);
    }

    @Override
    public void initGui() {
        buttonList.clear();
        mainWidgets.clear();
        listWidgets.clear();
        int left = panelLeft() + 15;
        int right = left + columnWidth() + 10;
        int top = panelTop();
        int fieldWidth = columnWidth();
        int rowWidth = panelWidth() - 30;

        maxNormalBlocks = addField(left, top + 70, fieldWidth, initial.maxNormalBlocks);
        maxNormalBlocksPerTick = addField(right, top + 70, fieldWidth, initial.maxNormalBlocksPerTick);
        maxBlastBlocks = addField(left, top + 134, fieldWidth, initial.maxBlastBlocks);
        maxBlastBlocksPerTick = addField(right, top + 134, fieldWidth, initial.maxBlastBlocksPerTick);
        blastSearchDistance = addField(left, top + 198, fieldWidth, initial.blastSearchDistance);

        noHungerCostButton = addMainButton(1, right, top + 198, fieldWidth, 20, noHungerText());
        storageBindingButton = addMainButton(2, left, top + 222, rowWidth, 20, storageBindingText());
        blacklistButton = addMainButton(6, left, top + 252, rowWidth, 20, blacklistButtonText());
        addMainButton(3, left, top + 280, 116, 20, I18n.format("veinminerplus.configuration.reset"));
        addMainButton(4, left + 122, top + 280, 96, 20, I18n.format("gui.cancel"));
        addMainButton(5, right + columnWidth() - 96, top + 280, 96, 20,
                I18n.format("veinminerplus.configuration.save"));

        for (int slot = 0; slot < LIST_ROWS; slot++) {
            rowButtons[slot] = addListButton(20 + slot, left + rowWidth - 44, top + 57 + slot * 20, 44, 18,
                    I18n.format("veinminerplus.configuration.blacklist.remove"));
        }
        prevButton = addListButton(7, left, top + 264, 44, 20, "<");
        nextButton = addListButton(8, left + rowWidth - 44, top + 264, 44, 20, ">");
        addHeldButton = addListButton(9, left + 50, top + 264, rowWidth - 100, 20,
                I18n.format("veinminerplus.configuration.blacklist.add_held"));
        addListButton(10, left, top + 288, 110, 20, I18n.format("veinminerplus.configuration.blacklist.back"));
        addListButton(11, left + rowWidth - 110, top + 288, 110, 20,
                I18n.format("veinminerplus.configuration.save"));

        applyPage();
    }

    private GuiButton addMainButton(int id, int x, int y, int width, int height, String label) {
        GuiButton button = new GuiButton(id, x, y, width, height, label);
        buttonList.add(button);
        mainWidgets.add(button);
        return button;
    }

    private GuiButton addListButton(int id, int x, int y, int width, int height, String label) {
        GuiButton button = new GuiButton(id, x, y, width, height, label);
        buttonList.add(button);
        listWidgets.add(button);
        return button;
    }

    private GuiTextField addField(int x, int y, int width, int value) {
        GuiTextField field = new GuiTextField(0, fontRenderer, x, y, width, 20);
        field.setText(Integer.toString(value));
        field.setMaxStringLength(5);
        return field;
    }

    private void applyPage() {
        boolean main = page == 0;
        maxNormalBlocks.setVisible(main);
        maxNormalBlocks.setEnabled(main);
        maxNormalBlocksPerTick.setVisible(main);
        maxNormalBlocksPerTick.setEnabled(main);
        maxBlastBlocks.setVisible(main);
        maxBlastBlocks.setEnabled(main);
        maxBlastBlocksPerTick.setVisible(main);
        maxBlastBlocksPerTick.setEnabled(main);
        blastSearchDistance.setVisible(main);
        blastSearchDistance.setEnabled(main);
        for (GuiButton button : mainWidgets) {
            button.visible = main;
            button.enabled = main;
        }
        for (GuiButton button : listWidgets) {
            button.visible = !main;
            button.enabled = !main;
        }
        if (main) {
            blacklistButton.displayString = blacklistButtonText();
        } else {
            refreshRows();
        }
    }

    // Row widgets stay alive across pages; only their visibility follows the current slice, so
    // deleting an entry never rebuilds the screen.
    private void refreshRows() {
        listPage = MathHelper.clamp(listPage, 0, maxListPage());
        int start = listPage * LIST_ROWS;
        for (int slot = 0; slot < LIST_ROWS; slot++) {
            boolean visible = start + slot < blacklist.size();
            rowButtons[slot].visible = visible;
            rowButtons[slot].enabled = visible;
        }
        prevButton.enabled = listPage > 0;
        nextButton.enabled = listPage < maxListPage();
        addHeldButton.enabled = canAddHeld();
    }

    private int maxListPage() {
        return Math.max(0, (blacklist.size() - 1) / LIST_ROWS);
    }

    private String blacklistButtonText() {
        return I18n.format("veinminerplus.configuration.blacklist.button", blacklist.size());
    }

    private boolean canAddHeld() {
        if (mc == null || mc.player == null || blacklist.size() >= BlockBlacklist.MAX_ENTRIES) {
            return false;
        }
        Block held = heldBlock();
        return held != null && held.getRegistryName() != null
                && !blacklist.contains(held.getRegistryName().toString());
    }

    private void addHeldBlock() {
        if (mc == null || mc.player == null) {
            return;
        }
        Block held = heldBlock();
        if (held == null || held.getRegistryName() == null
                || blacklist.size() >= BlockBlacklist.MAX_ENTRIES
                || blacklist.contains(held.getRegistryName().toString())) {
            return;
        }
        blacklist.add(held.getRegistryName().toString());
        listPage = maxListPage();
        refreshRows();
    }

    // A plain block item resolves straight to its block. AE2 part items (cables, terminals, ...)
    // are not block items but all place the one shared cable bus block, so AE2 is asked for those.
    private Block heldBlock() {
        ItemStack held = mc.player.getHeldItemMainhand();
        if (held.getItem() instanceof ItemBlock) {
            return ((ItemBlock) held.getItem()).getBlock();
        }
        if (Loader.isModLoaded(Ae2Storage.MOD_ID)) {
            return Ae2Storage.blacklistBlock(held);
        }
        return null;
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
        listPage = MathHelper.clamp(listPage + delta, 0, maxListPage());
        refreshRows();
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
        maxNormalBlocks.setText(Integer.toString(Config.DEFAULT_MAX_NORMAL_BLOCKS));
        maxNormalBlocksPerTick.setText(Integer.toString(Config.DEFAULT_MAX_NORMAL_BLOCKS_PER_TICK));
        maxBlastBlocks.setText(Integer.toString(Config.DEFAULT_MAX_BLAST_BLOCKS));
        maxBlastBlocksPerTick.setText(Integer.toString(Config.DEFAULT_MAX_BLAST_BLOCKS_PER_TICK));
        blastSearchDistance.setText(Integer.toString(Config.DEFAULT_BLAST_SEARCH_DISTANCE));
        noHungerCost = Config.DEFAULT_NO_HUNGER_COST;
        noHungerCostButton.displayString = noHungerText();
        storageBinding = Config.DEFAULT_STORAGE_BINDING;
        storageBindingButton.displayString = storageBindingText();
        blacklist.clear();
        listPage = 0;
        refreshRows();
        blacklistButton.displayString = blacklistButtonText();
        error = null;
    }

    private void save() {
        Integer maxNormal = parse(maxNormalBlocks, Config.MIN_MAX_NORMAL_BLOCKS, Config.MAX_MAX_NORMAL_BLOCKS);
        Integer normalPerTick = parse(maxNormalBlocksPerTick, Config.MIN_MAX_NORMAL_BLOCKS_PER_TICK,
                Config.MAX_MAX_NORMAL_BLOCKS_PER_TICK);
        Integer maxBlast = parse(maxBlastBlocks, Config.MIN_MAX_BLAST_BLOCKS, Config.MAX_MAX_BLAST_BLOCKS);
        Integer blastPerTick = parse(maxBlastBlocksPerTick, Config.MIN_MAX_BLAST_BLOCKS_PER_TICK,
                Config.MAX_MAX_BLAST_BLOCKS_PER_TICK);
        Integer distance = parse(blastSearchDistance, Config.MIN_BLAST_SEARCH_DISTANCE,
                Config.MAX_BLAST_SEARCH_DISTANCE);
        if (maxNormal == null || normalPerTick == null || maxBlast == null || blastPerTick == null
                || distance == null) {
            page = 0;
            applyPage();
            return;
        }

        NetworkHandler.sendConfigUpdate(maxNormal, normalPerTick, maxBlast, blastPerTick, distance,
                noHungerCost, storageBinding, new ArrayList<>(blacklist));
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
            case 6:
                page = 1;
                applyPage();
                break;
            case 7:
                changeListPage(-1);
                break;
            case 8:
                changeListPage(1);
                break;
            case 9:
                addHeldBlock();
                break;
            case 10:
                page = 0;
                applyPage();
                break;
            case 11:
                save();
                break;
            default:
                if (button.id >= 20 && button.id < 20 + LIST_ROWS) {
                    removeEntry(button.id - 20);
                }
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            mc.displayGuiScreen(null);
            return;
        }
        if (page == 0) {
            maxNormalBlocks.textboxKeyTyped(typedChar, keyCode);
            maxNormalBlocksPerTick.textboxKeyTyped(typedChar, keyCode);
            maxBlastBlocks.textboxKeyTyped(typedChar, keyCode);
            maxBlastBlocksPerTick.textboxKeyTyped(typedChar, keyCode);
            blastSearchDistance.textboxKeyTyped(typedChar, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (page == 0) {
            maxNormalBlocks.mouseClicked(mouseX, mouseY, mouseButton);
            maxNormalBlocksPerTick.mouseClicked(mouseX, mouseY, mouseButton);
            maxBlastBlocks.mouseClicked(mouseX, mouseY, mouseButton);
            maxBlastBlocksPerTick.mouseClicked(mouseX, mouseY, mouseButton);
            blastSearchDistance.mouseClicked(mouseX, mouseY, mouseButton);
        }
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

        drawRect(left, top, right, top + PANEL_HEIGHT, 0xB0101010);
        drawRect(left + 1, top + 1, right - 1, top + 2, 0xFF707070);
        drawCenteredString(fontRenderer, I18n.format("veinminerplus.configuration.title"), width / 2, top + 8,
                0xFFFFFF);
        drawCenteredString(fontRenderer, I18n.format("veinminerplus.configuration.subtitle"), width / 2, top + 22,
                0xB0B0B0);
        if (page == 0) {
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
                drawCenteredString(fontRenderer, error, width / 2, top + 306, 0xFF5555);
            }
        } else {
            drawBlacklistPage(top);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (page != 0) {
            drawRowTooltip(mouseX, mouseY);
        }
    }

    private void drawBlacklistPage(int top) {
        int innerLeft = panelLeft() + 15;
        int innerWidth = panelWidth() - 30;
        fontRenderer.drawStringWithShadow(
                I18n.format("veinminerplus.configuration.blacklist.title", blacklist.size(),
                        BlockBlacklist.MAX_ENTRIES),
                innerLeft, top + 42, 0xFFFF55);

        if (blacklist.isEmpty()) {
            drawCenteredString(fontRenderer, I18n.format("veinminerplus.configuration.blacklist.empty"),
                    width / 2, top + 120, 0xB0B0B0);
        }

        int start = listPage * LIST_ROWS;
        for (int slot = 0; slot < LIST_ROWS; slot++) {
            int index = start + slot;
            if (index >= blacklist.size()) {
                break;
            }
            int rowTop = top + 58 + slot * 20;
            String raw = blacklist.get(index);
            Block block = resolveBlock(raw);
            if (block != null) {
                Item item = Item.getItemFromBlock(block);
                if (item != null && item != Items.AIR) {
                    mc.getRenderItem().renderItemAndEffectIntoGUI(new ItemStack(item), innerLeft, rowTop - 2);
                }
                fontRenderer.drawStringWithShadow(block.getLocalizedName(), innerLeft + 22, rowTop + 3, 0xFFFFFF);
            } else {
                fontRenderer.drawStringWithShadow(raw, innerLeft + 22, rowTop + 3, 0x909090);
            }
        }

        String pageText = I18n.format("veinminerplus.configuration.blacklist.page", listPage + 1,
                maxListPage() + 1);
        fontRenderer.drawStringWithShadow(pageText,
                innerLeft + innerWidth - fontRenderer.getStringWidth(pageText), top + 42, 0xB0B0B0);
        drawCenteredString(fontRenderer, I18n.format("veinminerplus.configuration.blacklist.hint"), width / 2,
                top + 316, 0x909090);
    }

    // The row shows the localised name, the tooltip the raw id, so two blocks that share a name can
    // still be told apart.
    private void drawRowTooltip(int mouseX, int mouseY) {
        int start = listPage * LIST_ROWS;
        for (int slot = 0; slot < LIST_ROWS; slot++) {
            GuiButton button = rowButtons[slot];
            if (button.visible && mouseX >= button.x && mouseY >= button.y && mouseX < button.x + button.width
                    && mouseY < button.y + button.height) {
                drawHoveringText(Collections.singletonList(blacklist.get(start + slot)), mouseX, mouseY);
                return;
            }
        }
    }

    private static Block resolveBlock(String raw) {
        try {
            return Block.REGISTRY.getObject(new ResourceLocation(raw));
        } catch (RuntimeException exception) {
            return null;
        }
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
        return Math.max(8, (height - PANEL_HEIGHT) / 2);
    }

    private int columnWidth() {
        return (panelWidth() - 40) / 2;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
