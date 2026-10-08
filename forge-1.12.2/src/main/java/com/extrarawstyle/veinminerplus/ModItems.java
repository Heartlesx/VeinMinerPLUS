package com.extrarawstyle.veinminerplus;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class ModItems {
    public static final Item STORAGE_BINDER = new StorageBindingCardItem()
            .setRegistryName(VeinMinerPlus.MODID, "storage_binder")
            .setTranslationKey(VeinMinerPlus.MODID + ".storage_binder")
            .setMaxStackSize(1)
            .setCreativeTab(CreativeTabs.TOOLS);

    private ModItems() {
    }

    @SubscribeEvent
    public static void onRegisterItems(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(STORAGE_BINDER);
    }
}
