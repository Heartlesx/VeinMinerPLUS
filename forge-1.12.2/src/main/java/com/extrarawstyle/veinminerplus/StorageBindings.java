package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.items.IItemHandler;

// The storage binding card holds a single binding: the kind of storage it was bound to and the block it
// points at. Binding another block replaces the old one, and whatever the bound target does not accept
// is still dropped on the ground.
public final class StorageBindings {
    static final String TYPE_AE2 = "ae2";
    static final String TYPE_SOPHISTICATED = "sophisticated";
    static final String TYPE_FUNCTIONAL = "functional";
    // Any other block that exposes an item handler, such as a vanilla chest.
    static final String TYPE_BLOCK = "block";

    static final StorageBindings EMPTY = new StorageBindings(null, null);
    private static final String KEY = "veinminerplus_bindings";
    // The binding is saved under the key of its type. Every key is read, so a card that was bound back
    // when the card held one binding per type keeps the first target it had instead of losing it.
    private static final String[] TYPES = {TYPE_AE2, TYPE_SOPHISTICATED, TYPE_FUNCTIONAL, TYPE_BLOCK};

    @CapabilityInject(IItemHandler.class)
    private static Capability<IItemHandler> itemHandler = null;

    private final String type;
    private final BlockTarget target;

    StorageBindings(String type, BlockTarget target) {
        this.type = type;
        this.target = target;
    }

    String type() {
        return type;
    }

    BlockTarget target() {
        return target;
    }

    boolean isEmpty() {
        return target == null;
    }

    List<ITextComponent> describe() {
        List<ITextComponent> lines = new ArrayList<>(1);
        if (isEmpty()) {
            lines.add(new TextComponentTranslation("item.veinminerplus.storage_binder.empty"));
            return lines;
        }
        lines.add(new TextComponentTranslation("item.veinminerplus.storage_binder." + type,
                target.describe()));
        return lines;
    }

    // A target is remembered by dimension and position, so it survives a logout or a restart. A
    // backpack that is picked up again simply leaves nothing behind at that position.
    static final class BlockTarget {
        private final int dimension;
        private final int x;
        private final int y;
        private final int z;

        BlockTarget(int dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        int dimension() {
            return dimension;
        }

        int x() {
            return x;
        }

        int y() {
            return y;
        }

        int z() {
            return z;
        }

        private NBTTagCompound save() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("dimension", dimension);
            tag.setInteger("x", x);
            tag.setInteger("y", y);
            tag.setInteger("z", z);
            return tag;
        }

        private String describe() {
            return x + ", " + y + ", " + z + " @ " + dimension;
        }

        private static BlockTarget load(NBTTagCompound tag) {
            return new BlockTarget(tag.getInteger("dimension"), tag.getInteger("x"), tag.getInteger("y"),
                    tag.getInteger("z"));
        }
    }

    static StorageBindings read(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            return EMPTY;
        }
        NBTTagCompound saved = tag.getCompoundTag(KEY);
        for (String type : TYPES) {
            if (saved.hasKey(type)) {
                return new StorageBindings(type, BlockTarget.load(saved.getCompoundTag(type)));
            }
        }
        return EMPTY;
    }

    static void write(ItemStack stack, StorageBindings bindings) {
        if (bindings.isEmpty()) {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag != null) {
                tag.removeTag(KEY);
            }
            return;
        }
        NBTTagCompound saved = new NBTTagCompound();
        saved.setTag(bindings.type(), bindings.target().save());
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setTag(KEY, saved);
    }

    // Returns the binding of the first card the player carries, whether it sits in the inventory or
    // inside a carried container.
    static StorageBindings findIn(EntityPlayer player) {
        int size = player.inventory.getSizeInventory();
        for (int slot = 0; slot < size; slot++) {
            StorageBindings bindings = bindingsIn(player.inventory.getStackInSlot(slot));
            if (bindings != null) {
                return bindings;
            }
        }
        return null;
    }

    // A card inside an item that can hold items, such as a backpack, counts as well. The search stops
    // after a single nesting level on purpose, so a container that refers to itself cannot loop.
    private static StorageBindings bindingsIn(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() == ModItems.STORAGE_BINDER) {
            StorageBindings bindings = read(stack);
            if (!bindings.isEmpty()) {
                return bindings;
            }
        }
        IItemHandler contents = itemHandler == null || !stack.hasCapability(itemHandler, null)
                ? null
                : stack.getCapability(itemHandler, null);
        if (contents == null) {
            return null;
        }
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            ItemStack inner = contents.getStackInSlot(slot);
            if (!inner.isEmpty() && inner.getItem() == ModItems.STORAGE_BINDER) {
                StorageBindings bindings = read(inner);
                if (!bindings.isEmpty()) {
                    return bindings;
                }
            }
        }
        return null;
    }
}
