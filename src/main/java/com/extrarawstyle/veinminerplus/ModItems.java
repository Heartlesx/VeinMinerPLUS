package com.extrarawstyle.veinminerplus;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(VeinMinerPlus.MODID);

    public static final DeferredItem<StorageBindingCardItem> STORAGE_BINDER = ITEMS.registerItem(
            "storage_binder", StorageBindingCardItem::new, new Item.Properties().stacksTo(1));

    private ModItems() {
    }

    static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(ModItems::addToCreativeTabs);
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(STORAGE_BINDER);
        }
    }
}
