package com.extrarawstyle.veinminerplus;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;

// The storage binding card holds a single binding: the kind of storage it was bound to and the block it
// points at. Binding another block replaces the old one, and whatever the bound target does not accept
// is still dropped on the ground.
public record StorageBindings(String type, BlockTarget target) {
    static final String TYPE_AE2 = "ae2";
    static final String TYPE_SOPHISTICATED = "sophisticated";
    static final String TYPE_FUNCTIONAL = "functional";

    static final StorageBindings EMPTY = new StorageBindings(null, null);
    private static final String KEY = "veinminerplus_bindings";
    // The binding is saved under the key of its type. Every key is read, so a card that was bound back
    // when the card held one binding per type keeps the first target it had instead of losing it.
    private static final List<String> TYPES = List.of(TYPE_AE2, TYPE_SOPHISTICATED, TYPE_FUNCTIONAL);
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
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return EMPTY;
        }
        CompoundTag saved = tag.getCompound(KEY);
        for (String type : TYPES) {
            BlockTarget target = load(saved, type);
            if (target != null) {
                return new StorageBindings(type, target);
            }
        }
        return EMPTY;
    }

    static void write(ItemStack stack, StorageBindings bindings) {
        if (bindings.isEmpty()) {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove(KEY);
            }
            return;
        }
        CompoundTag saved = new CompoundTag();
        saved.put(bindings.type(), bindings.target().save());
        stack.getOrCreateTag().put(KEY, saved);
    }

    // Returns the binding of the first card the player carries. It counts whether the card sits in the
    // inventory, inside a backpack, or is worn in a trinket slot.
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
        IItemHandler contents = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
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

    boolean isEmpty() {
        return target == null;
    }

    List<Component> describe() {
        if (isEmpty()) {
            return List.of(Component.translatable("item.veinminerplus.storage_binder.empty"));
        }
        return List.of(Component.translatable("item.veinminerplus.storage_binder." + type,
                Component.literal(target.pos().getX() + ", " + target.pos().getY() + ", "
                        + target.pos().getZ() + " @ " + target.dimension().location())));
    }

    private static BlockTarget load(CompoundTag tag, String key) {
        return tag.contains(key) ? BlockTarget.load(tag.getCompound(key)) : null;
    }
}
