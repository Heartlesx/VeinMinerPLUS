package com.extrarawstyle.veinminerplus;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

// The three slots of the storage binding card. They are fixed per mod: an AE2 network, Sophisticated
// Storage (a barrel/chest or a backpack placed on the ground) and Functional Storage. Chains are
// inserted in that order and whatever none of them accepts is still dropped on the ground.
public record StorageBindings(BlockTarget ae2, BlockTarget sophisticated, BlockTarget functional) {
    static final StorageBindings EMPTY = new StorageBindings(null, null, null);
    private static final String KEY = "veinminerplus_bindings";
    private static final String CURIOS_MOD_ID = "curios";

    // A target is remembered by dimension and position, so it survives a logout or a restart. A
    // backpack that is picked up again simply leaves nothing behind at that position.
    public record BlockTarget(ResourceKey<Level> dimension, BlockPos pos) {
        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension.location().toString());
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            return tag;
        }

        private static BlockTarget load(CompoundTag tag) {
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("dimension"));
            if (id == null) {
                return null;
            }
            return new BlockTarget(ResourceKey.create(Registries.DIMENSION, id),
                    new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
        }
    }

    static StorageBindings read(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return EMPTY;
        }
        CompoundTag tag = data.copyTag().getCompound(KEY);
        return new StorageBindings(load(tag, "ae2"), load(tag, "sophisticated"), load(tag, "functional"));
    }

    static void write(ItemStack stack, StorageBindings bindings) {
        if (bindings.isEmpty()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(KEY));
            return;
        }
        CompoundTag saved = new CompoundTag();
        save(saved, "ae2", bindings.ae2());
        save(saved, "sophisticated", bindings.sophisticated());
        save(saved, "functional", bindings.functional());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(KEY, saved));
    }

    // Returns the bindings of the first card the player carries. It counts whether the card sits in
    // the inventory, inside a backpack, or is worn in a trinket slot.
    static StorageBindings findIn(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            StorageBindings bindings = bindingsIn(inventory.getItem(slot));
            if (bindings != null) {
                return bindings;
            }
        }
        if (ModList.get().isLoaded(CURIOS_MOD_ID)) {
            return bindingsIn(CuriosLookup.equipped(player));
        }
        return null;
    }

    private static StorageBindings bindingsIn(IItemHandler container) {
        if (container == null) {
            return null;
        }
        for (int slot = 0; slot < container.getSlots(); slot++) {
            StorageBindings bindings = bindingsIn(container.getStackInSlot(slot));
            if (bindings != null) {
                return bindings;
            }
        }
        return null;
    }

    // A card inside an item that can hold items, such as a backpack, counts as well. The search stops
    // after a single nesting level on purpose, so a container that refers to itself cannot loop.
    private static StorageBindings bindingsIn(ItemStack stack) {
        if (stack.is(ModItems.STORAGE_BINDER.get())) {
            StorageBindings bindings = read(stack);
            if (!bindings.isEmpty()) {
                return bindings;
            }
        }
        IItemHandler contents = stack.getCapability(Capabilities.ItemHandler.ITEM);
        if (contents == null) {
            return null;
        }
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            ItemStack inner = contents.getStackInSlot(slot);
            if (inner.is(ModItems.STORAGE_BINDER.get())) {
                StorageBindings bindings = read(inner);
                if (!bindings.isEmpty()) {
                    return bindings;
                }
            }
        }
        return null;
    }

    StorageBindings withAe2(BlockTarget target) {
        return new StorageBindings(target, sophisticated, functional);
    }

    StorageBindings withSophisticated(BlockTarget target) {
        return new StorageBindings(ae2, target, functional);
    }

    StorageBindings withFunctional(BlockTarget target) {
        return new StorageBindings(ae2, sophisticated, target);
    }

    boolean isEmpty() {
        return ae2 == null && sophisticated == null && functional == null;
    }

    List<Component> describe() {
        return List.of(line("ae2", ae2), line("sophisticated", sophisticated),
                line("functional", functional));
    }

    private static Component line(String slot, BlockTarget target) {
        return Component.translatable("item.veinminerplus.storage_binder." + slot,
                target == null ? Component.translatable("item.veinminerplus.storage_binder.empty")
                        : Component.literal(target.pos().getX() + ", " + target.pos().getY() + ", "
                                + target.pos().getZ() + " @ " + target.dimension().location()));
    }

    private static void save(CompoundTag tag, String key, BlockTarget target) {
        if (target != null) {
            tag.put(key, target.save());
        }
    }

    private static BlockTarget load(CompoundTag tag, String key) {
        return tag.contains(key) ? BlockTarget.load(tag.getCompound(key)) : null;
    }
}
