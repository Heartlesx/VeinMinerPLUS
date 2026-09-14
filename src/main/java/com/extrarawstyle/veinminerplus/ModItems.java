package com.extrarawstyle.veinminerplus;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(VeinMinerPlus.MODID);

    public static final DeferredItem<ChainMemoryCardItem> CHAIN_MEMORY_CARD = ITEMS.registerItem(
            "chain_memory_card", ChainMemoryCardItem::new, new Item.Properties().stacksTo(1));

    private ModItems() {
    }

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
