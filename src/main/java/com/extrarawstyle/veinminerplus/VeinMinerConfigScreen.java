package com.extrarawstyle.veinminerplus;

import com.extrarawstyle.veinminerplus.NetworkHandler;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SDFRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.math.Size;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class VeinMinerConfigScreen
extends ModularUIScreen {
    // Modern UI at 250% magnifies every logical pixel. Keep the logical viewport compact
    // so the complete shell remains inside the physical screen.
    private static final int INITIAL_WIDTH = 650;
    private static final int INITIAL_HEIGHT = 380;
    private static final int SIDEBAR_WIDTH = 104;
    private static final int SETTINGS_MAX_WIDTH = INITIAL_WIDTH - SIDEBAR_WIDTH;
    private static final int SCREEN_INSET = 10;

    VeinMinerConfigScreen(NetworkHandler.ConfigSnapshotPayload initial) {
        super(VeinMinerConfigScreen.createUi(initial), (Component)Component.translatable((String)"veinminerplus.configuration.title"));
    }

    private static ModularUI createUi(NetworkHandler.ConfigSnapshotPayload config) {
        UIElement root = new UIElement().setId("root")
                .layout(layout -> layout.width((float) INITIAL_WIDTH).height((float) INITIAL_HEIGHT).flexDirection(FlexDirection.ROW))
                .addClass("vmp-shell");

        UIElement sidebar = new UIElement().setId("sidebar")
                .layout(layout -> layout.width((float) SIDEBAR_WIDTH).heightPercent(100.0f).flexDirection(FlexDirection.COLUMN).alignItems(AlignItems.CENTER))
                .addClass("vmp-sidebar");
        UIElement brand = new UIElement()
                .layout(layout -> layout.width(88.0f).height(42.0f).flexDirection(FlexDirection.COLUMN)
                        .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER).gapAll(4.0f))
                .addClass("vmp-brand-block");
        brand.addChild(VeinMinerConfigScreen.label("brand", "VeinMinerPlus", "vmp-brand"));
        brand.addChild(VeinMinerConfigScreen.label("brand_subtitle", "veinminerplus.configuration.subtitle", "vmp-brand-subtitle"));
        sidebar.addChild(brand);
        sidebar.addChild(VeinMinerConfigScreen.label(null, "veinminerplus.configuration.navigation", "vmp-navigation-caption"));

        Button chainNav = VeinMinerConfigScreen.navButton("veinminerplus.configuration.nav.chain", "chain_nav", true);
        Button aeNav = VeinMinerConfigScreen.navButton("veinminerplus.configuration.nav.ae", "ae_nav", false);
        Button performanceNav = VeinMinerConfigScreen.navButton("veinminerplus.configuration.nav.performance", "performance_nav", false);
        sidebar.addChildren(new UIElement[]{chainNav, aeNav, performanceNav});

        UIElement content = new UIElement().setId("content")
                .layout(layout -> layout.flex(1.0f).heightPercent(100.0f).flexDirection(FlexDirection.COLUMN))
                .addClass("vmp-content");
        UIElement header = new UIElement()
                .layout(layout -> layout.widthPercent(100.0f).maxWidth((float) SETTINGS_MAX_WIDTH).height(33.0f).flexDirection(FlexDirection.COLUMN).justifyContent(AlignContent.CENTER))
                .addClass("vmp-header");
        header.addChild(VeinMinerConfigScreen.label(null, "veinminerplus.configuration.title", "vmp-title"));
        content.addChild(header);

        ScrollerView pages = new ScrollerView();
        pages.setId("pages");
        pages.addClass("vmp-pages");
        pages.layout(layout -> layout.flex(1.0f).widthPercent(100.0f).maxWidth((float) SETTINGS_MAX_WIDTH));
        pages.scrollerStyle(style -> style
                .mode(ScrollerMode.VERTICAL)
                .verticalScrollDisplay(ScrollDisplay.AUTO)
                .horizontalScrollDisplay(ScrollDisplay.NEVER)
                .scrollerViewStyle(3.0f)
                .minScrollPixel(8.0f)
                .maxScrollPixel(22.0f));
        pages.viewPort(view -> view.layout(layout -> layout.paddingAll(0.0f)));
        pages.viewContainer(view -> view.layout(layout -> layout.widthPercent(100.0f).flexDirection(FlexDirection.COLUMN)));
        pages.verticalScroller(scroller -> {
            scroller.headButton.setDisplay(false);
            scroller.tailButton.setDisplay(false);
        });

        UIElement chainPage = VeinMinerConfigScreen.createChainPage(config);
        UIElement aePage = VeinMinerConfigScreen.createAePage(config);
        UIElement performancePage = VeinMinerConfigScreen.createPerformancePage(config);
        chainPage.setId("chain_page");
        aePage.setId("ae_page");
        performancePage.setId("performance_page");
        aePage.setDisplay(false);
        performancePage.setDisplay(false);
        pages.addScrollViewChildren(chainPage, aePage, performancePage);
        content.addChild(pages);

        UIElement footer = new UIElement()
                .layout(layout -> layout.widthPercent(100.0f).maxWidth((float) SETTINGS_MAX_WIDTH).height(40.0f).flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER))
                .addClass("vmp-footer");
        Label error = new Label();
        error.setId("error");
        error.setText("");
        error.addClass("vmp-error");
        error.layout(layout -> layout.flex(1.0f).height(16.0f));
        footer.addChild(error);

        UIElement buttonGroup = new UIElement()
                .layout(layout -> layout.width(202.0f).height(28.0f).flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER))
                .addClass("vmp-button-group");
        Button reset = VeinMinerConfigScreen.button(Component.translatable("veinminerplus.configuration.reset"), "reset", "vmp-button-secondary");
        reset.layout(layout -> layout.width(78.0f).height(24.0f));
        reset.setOnClick(event -> VeinMinerConfigScreen.resetUi(root));
        buttonGroup.addChild(reset);
        Button cancel = VeinMinerConfigScreen.button(Component.translatable("gui.cancel"), "cancel", "vmp-button-secondary");
        cancel.layout(layout -> layout.width(56.0f).height(24.0f));
        cancel.setOnClick(event -> Minecraft.getInstance().setScreen(null));
        buttonGroup.addChild(cancel);
        Button save = VeinMinerConfigScreen.button(Component.translatable("veinminerplus.configuration.save"), "save", "vmp-button-primary");
        save.layout(layout -> layout.width(56.0f).height(24.0f));
        save.setOnClick(event -> VeinMinerConfigScreen.saveUi(root));
        buttonGroup.addChild(save);
        footer.addChild(buttonGroup);
        content.addChild(footer);

        chainNav.setOnClick(event -> VeinMinerConfigScreen.showPage(root, pages, "chain"));
        aeNav.setOnClick(event -> VeinMinerConfigScreen.showPage(root, pages, "ae"));
        performanceNav.setOnClick(event -> VeinMinerConfigScreen.showPage(root, pages, "performance"));
        root.addChildren(new UIElement[]{sidebar, content});
        return ModularUI.of(UI.of(root, List.of(StylesheetManager.INSTANCE.getMergedStylesheetsSafe("lss/miui.lss")), VeinMinerConfigScreen::responsiveSize));
    }

    /**
     * Use the logical GUI dimensions supplied by Minecraft. This remains compact at Modern UI 250%.
     */
    private static Size responsiveSize(Size screenSize) {
        int availableWidth = Math.max(1, screenSize.width - SCREEN_INSET * 2);
        int availableHeight = Math.max(1, screenSize.height - SCREEN_INSET * 2);
        int width = Math.min(INITIAL_WIDTH, Math.max(460, Math.round(availableWidth * 0.94f)));
        int height = Math.min(INITIAL_HEIGHT, Math.max(300, Math.round(availableHeight * 0.90f)));
        width = Math.min(width, availableWidth);
        height = Math.min(height, availableHeight);
        return Size.of(width, height);
    }
    private static Label label(String id, String keyOrText, String styleClass) {
        Label label = new Label();
        if (id != null) {
            label.setId(id);
        }
        if (keyOrText.startsWith("veinminerplus.")) {
            label.setText((Component)Component.translatable((String)keyOrText));
        } else {
            label.setText(keyOrText);
        }
        label.addClass(styleClass);
        return label;
    }

    private static Button button(Component text, String id, String styleClass) {
        Button button = new Button();
        button.setText(text);
        button.setId(id);
        button.addClass(styleClass);
        return button;
    }

    private static Button navButton(String key, String id, boolean selected) {
        Button button = new Button();
        button.setText(Component.translatable(key));
        button.setId(id);
        button.addClass("vmp-nav");
        button.layout(layout -> layout.width(88.0f).height(24.0f)
                .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        if (selected) {
            button.addClass("vmp-nav-selected");
        }
        return button;
    }
    private static UIElement createChainPage(NetworkHandler.ConfigSnapshotPayload config) {
        UIElement page = VeinMinerConfigScreen.page("chain");
        page.addChild(VeinMinerConfigScreen.sectionTitle("veinminerplus.configuration.section.common"));
        UIElement grid = VeinMinerConfigScreen.grid();
        grid.addChildren(new UIElement[]{VeinMinerConfigScreen.fieldCard("veinminerplus.configuration.maxNormalBlocks", "max_normal", config.maxNormalBlocks(), 1, 2100000000), VeinMinerConfigScreen.fieldCard("veinminerplus.configuration.maxNormalBlocksPerTick", "max_normal_tick", config.maxNormalBlocksPerTick(), 1, 8192)});
        page.addChild(grid);
        page.addChild(VeinMinerConfigScreen.sectionTitle("veinminerplus.configuration.section.blast"));
        grid = VeinMinerConfigScreen.grid();
        grid.addChildren(new UIElement[]{VeinMinerConfigScreen.fieldCard("veinminerplus.configuration.maxBlastBlocks", "max_blast", config.maxBlastBlocks(), 1, 2100000000), VeinMinerConfigScreen.fieldCard("veinminerplus.configuration.maxBlastBlocksPerTick", "max_blast_tick", config.maxBlastBlocksPerTick(), 1, 8192)});
        page.addChild(grid);
        page.addChild(VeinMinerConfigScreen.sectionTitle("veinminerplus.configuration.section.player"));
        UIElement bottom = new UIElement()
                .layout(layout -> layout.widthPercent(100.0f).height(54.0f).flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER))
                .addClass("vmp-grid");
        UIElement distance = VeinMinerConfigScreen.fieldCard("veinminerplus.configuration.blastSearchDistance", "blast_distance", config.blastSearchDistance(), 3, 128);
        bottom.addChild(distance);
        Toggle hunger = VeinMinerConfigScreen.toggle((Component)Component.translatable((String)"veinminerplus.configuration.noHungerCost"), "no_hunger", config.noHungerCost(), "vmp-toggle-card");
        hunger.layout(layout -> layout.flex(1.0f).height(50.0f).minHeight(50.0f).maxHeight(50.0f));
        bottom.addChild((UIElement)hunger);
        page.addChild(bottom);
        return page;
    }

    private static UIElement createAePage(NetworkHandler.ConfigSnapshotPayload config) {
        UIElement page = VeinMinerConfigScreen.page("ae");
        page.addChild(VeinMinerConfigScreen.sectionTitle("veinminerplus.configuration.ae_section"));
        UIElement card = new UIElement().layout(layout -> layout.widthPercent(100.0f).height(78.0f).flexDirection(FlexDirection.COLUMN)).addClass("vmp-setting-card");
        Toggle store = VeinMinerConfigScreen.toggle((Component)Component.translatable((String)"veinminerplus.configuration.storeDropsInAe"), "store_ae", config.storeDropsInAe(), "vmp-setting-toggle");
        store.layout(layout -> layout.widthPercent(100.0f).height(24.0f));
        card.addChild((UIElement)store);
        card.addChild((UIElement)VeinMinerConfigScreen.label(null, "veinminerplus.configuration.storeDropsInAe.description", "vmp-description"));
        page.addChild(card);
        page.addChild(VeinMinerConfigScreen.infoCard("veinminerplus.configuration.ae_hint"));
        return page;
    }

    private static UIElement createPerformancePage(NetworkHandler.ConfigSnapshotPayload config) {
        UIElement page = VeinMinerConfigScreen.page("performance");
        page.addChild(VeinMinerConfigScreen.sectionTitle("veinminerplus.configuration.performance_section"));
        UIElement card = new UIElement().layout(layout -> layout.widthPercent(100.0f).height(78.0f).flexDirection(FlexDirection.COLUMN)).addClass("vmp-setting-card");
        Toggle performance = VeinMinerConfigScreen.toggle((Component)Component.translatable((String)"veinminerplus.configuration.enablePerformanceLog"), "performance_log", config.enablePerformanceLog(), "vmp-setting-toggle");
        performance.layout(layout -> layout.widthPercent(100.0f).height(24.0f));
        card.addChild((UIElement)performance);
        card.addChild((UIElement)VeinMinerConfigScreen.label(null, "veinminerplus.configuration.enablePerformanceLog.description", "vmp-description"));
        page.addChild(card);
        page.addChild(VeinMinerConfigScreen.infoCard("veinminerplus.configuration.performance_hint"));
        return page;
    }

    private static Toggle toggle(Component text, String id, boolean on, String styleClass) {
        Toggle toggle = new Toggle();
        toggle.setText(text);
        toggle.setId(id);
        toggle.addClass(styleClass);
        toggle.toggleButton(button -> button.layout(layout -> layout.width(16.0f).height(16.0f)));

        IGuiTexture emptyCircle = new SDFRectTexture()
                .setColor(0xFF505050)
                .setRadius(8.0f);
        // Miuix Checkbox.kt draws a circular fill and a rounded 3-point check path.
        // Render that shape as a high-resolution sprite instead of LDLib2's pixel-art check.
        IGuiTexture selectedCircle = SpriteTexture.of("veinminerplus:textures/gui/checkbox_checked.png");
        toggle.toggleStyle(style -> style
                .baseTexture(IGuiTexture.EMPTY)
                .hoverTexture(new SDFRectTexture().setColor(0x305F5F5F).setRadius(8.0f))
                .unmarkTexture(emptyCircle)
                .markTexture(selectedCircle));
        toggle.setOn(on);
        return toggle;
    }

    private static UIElement page(String name) {
        return new UIElement().addClass("vmp-page").addClass("vmp-page-" + name).layout(layout -> layout.widthPercent(100.0f).flexDirection(FlexDirection.COLUMN));
    }

    private static UIElement grid() {
        return new UIElement().addClass("vmp-grid").layout(layout -> layout.widthPercent(100.0f).height(54.0f).minHeight(54.0f).maxHeight(54.0f).flexDirection(FlexDirection.ROW));
    }

    private static UIElement fieldCard(String labelKey, String id, int value, int min, int max) {
        TextField field = new TextField();
        field.setId(id);
        field.setText(Integer.toString(value));
        field.setNumbersOnlyInt(min, max);
        field.addClass("vmp-field");
        field.layout(layout -> layout.widthPercent(100.0f).height(18.0f));
        UIElement card = new UIElement().addClass("vmp-field-card").layout(layout -> layout.flex(1.0f).height(50.0f).minHeight(50.0f).maxHeight(50.0f).flexDirection(FlexDirection.COLUMN));
        card.addChild((UIElement)VeinMinerConfigScreen.label(null, labelKey, "vmp-field-label"));
        card.addChild((UIElement)field);
        return card;
    }

    private static UIElement sectionTitle(String key) {
        Label title = VeinMinerConfigScreen.label(null, key, "vmp-section-title");
        title.layout(layout -> layout.widthPercent(100.0f).height(18.0f));
        return title;
    }

    private static UIElement infoCard(String key) {
        UIElement card = new UIElement().addClass("vmp-info-card").layout(layout -> layout.widthPercent(100.0f).height(40.0f));
        card.addChild((UIElement)VeinMinerConfigScreen.label(null, key, "vmp-description"));
        return card;
    }

    private static void showPage(UIElement root, ScrollerView pages, String page) {
        UIElement chain = (UIElement)root.selectId("chain_page").findFirst().orElseThrow();
        UIElement ae = (UIElement)root.selectId("ae_page").findFirst().orElseThrow();
        UIElement performance = (UIElement)root.selectId("performance_page").findFirst().orElseThrow();
        chain.setDisplay("chain".equals(page));
        ae.setDisplay("ae".equals(page));
        performance.setDisplay("performance".equals(page));
        VeinMinerConfigScreen.selectNav(root, "chain_nav", "chain".equals(page));
        VeinMinerConfigScreen.selectNav(root, "ae_nav", "ae".equals(page));
        VeinMinerConfigScreen.selectNav(root, "performance_nav", "performance".equals(page));
        pages.verticalScroller.setNormalizedValue(0.0f, false);
    }

    private static void selectNav(UIElement root, String id, boolean selected) {
        UIElement element = (UIElement)root.selectId(id).findFirst().orElseThrow();
        if (selected) {
            element.addClass("vmp-nav-selected");
        } else {
            element.removeClass("vmp-nav-selected");
        }
    }

    private static void resetUi(UIElement root) {
        VeinMinerConfigScreen.setField(root, "max_normal", 1024);
        VeinMinerConfigScreen.setField(root, "max_normal_tick", 8);
        VeinMinerConfigScreen.setField(root, "max_blast", Short.MAX_VALUE);
        VeinMinerConfigScreen.setField(root, "max_blast_tick", 64);
        VeinMinerConfigScreen.setField(root, "blast_distance", 20);
        VeinMinerConfigScreen.toggleAt(root, "no_hunger").setOn(false);
        VeinMinerConfigScreen.toggleAt(root, "store_ae").setOn(true);
        VeinMinerConfigScreen.toggleAt(root, "performance_log").setOn(false);
        VeinMinerConfigScreen.labelAt(root, "error").setText("");
    }

    private static void setField(UIElement root, String id, int value) {
        TextField field = (TextField)root.selectId(id).findFirst().orElseThrow();
        field.setText(Integer.toString(value));
    }

    private static Toggle toggleAt(UIElement root, String id) {
        return (Toggle)root.selectId(id).findFirst().orElseThrow();
    }

    private static Label labelAt(UIElement root, String id) {
        return (Label)root.selectId(id).findFirst().orElseThrow();
    }

    private static void saveUi(UIElement root) {
        try {
            int maxNormal = VeinMinerConfigScreen.parse(root, "max_normal", 1, 2100000000);
            int normalTick = VeinMinerConfigScreen.parse(root, "max_normal_tick", 1, 8192);
            int maxBlast = VeinMinerConfigScreen.parse(root, "max_blast", 1, 2100000000);
            int blastTick = VeinMinerConfigScreen.parse(root, "max_blast_tick", 1, 8192);
            int distance = VeinMinerConfigScreen.parse(root, "blast_distance", 3, 128);
            NetworkHandler.sendConfigUpdate(new NetworkHandler.ConfigUpdatePayload(maxNormal, normalTick, maxBlast, blastTick, distance, Boolean.TRUE.equals(VeinMinerConfigScreen.toggleAt(root, "no_hunger").getValue()), Boolean.TRUE.equals(VeinMinerConfigScreen.toggleAt(root, "store_ae").getValue()), Boolean.TRUE.equals(VeinMinerConfigScreen.toggleAt(root, "performance_log").getValue())));
            Minecraft.getInstance().setScreen(null);
        }
        catch (ConfigValueException exception) {
            VeinMinerConfigScreen.labelAt(root, "error").setText(exception.message);
        }
    }

    private static int parse(UIElement root, String id, int min, int max) throws ConfigValueException {
        try {
            int value = Integer.parseInt(((TextField)root.selectId(id).findFirst().orElseThrow()).getValue());
            if (value < min || value > max) {
                throw new ConfigValueException((Component)Component.translatable((String)"veinminerplus.configuration.range", (Object[])new Object[]{min, max}));
            }
            return value;
        }
        catch (NumberFormatException exception) {
            throw new ConfigValueException((Component)Component.translatable((String)"veinminerplus.configuration.invalid"));
        }
    }

    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, -16249837);
        graphics.fillGradient(0, 0, this.width, this.height, -15721687, -16315888);
    }

    private static final class ConfigValueException
    extends Exception {
        private final Component message;

        private ConfigValueException(Component message) {
            this.message = message;
        }
    }
}
