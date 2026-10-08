package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;

import net.minecraft.world.item.ItemStack;

/**
 * Aggregates item stacks by item and data components without losing enchantments,
 * damage, container contents, custom names or other component data. Counts use a
 * saturating long: the largest selectable area multiplied by the maximum fortune
 * level is far below Long.MAX_VALUE, while BigInteger would add allocation and
 * arithmetic overhead without fitting Minecraft's int-sized ItemStack count API.
 */
final class ItemStackAccumulator {
    /* Keep fallback output compact. ItemEntity accepts a larger count than the
     * item's normal inventory stack limit, and fewer entities are dramatically
     * cheaper for both the server tick and the renderer. */
    private static final int ENTITY_STACK_MULTIPLIER = 64;
    private final Object2LongLinkedOpenHashMap<StackKey> counts = new Object2LongLinkedOpenHashMap<>();
    private StackKey lastKey;
    private long totalCount;

    void add(ItemStack stack) {
        add(stack, 1L);
    }

    void add(ItemStack stack, long multiplier) {
        if (stack.isEmpty() || stack.getCount() <= 0) {
            return;
        }
        long safeMultiplier = Math.max(1L, multiplier);
        long amount = saturatingMultiply(stack.getCount(), safeMultiplier);
        if (lastKey != null && lastKey.matches(stack)) {
            counts.addTo(lastKey, amount);
            totalCount = saturatingAdd(totalCount, amount);
            return;
        }

        StackKey key = new StackKey(stack);
        counts.addTo(key, amount);
        lastKey = key;
        totalCount = saturatingAdd(totalCount, amount);
    }

    void addAll(List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            add(stack);
        }
    }

    /**
     * Merges another accumulator without materialising an intermediate list of stacks.
     * Fast mining uses a short-lived per-block accumulator; converting that accumulator
     * to a list for every block creates substantial allocation and hashing pressure.
     */
    void addAll(ItemStackAccumulator other) {
        if (other == this || other.counts.isEmpty()) {
            return;
        }
        for (var entry : other.counts.object2LongEntrySet()) {
            StackKey key = entry.getKey();
            long amount = entry.getLongValue();
            counts.addTo(key, amount);
            totalCount = saturatingAdd(totalCount, amount);
        }
        if (!other.counts.isEmpty()) {
            lastKey = other.lastKey;
        }
    }

    void multiply(long multiplier) {
        if (multiplier <= 1L || counts.isEmpty()) return;
        long bounded = Math.min(Integer.MAX_VALUE, multiplier);
        for (var entry : counts.object2LongEntrySet()) {
            long current = entry.getLongValue();
            entry.setValue(saturatingMultiply(current, bounded));
        }
        totalCount = saturatingMultiply(totalCount, bounded);
    }

    long totalCount() {
        return totalCount;
    }

    boolean isEmpty() {
        return totalCount == 0;
    }

    List<ItemStack> toAggregatedStacks() {
        List<ItemStack> result = new ArrayList<>(counts.size());
        for (var entry : counts.object2LongEntrySet()) {
            long remaining = entry.getLongValue();
            while (remaining > 0) {
                int amount = (int) Math.min(Integer.MAX_VALUE, remaining);
                result.add(entry.getKey().representative.copyWithCount(amount));
                remaining -= amount;
            }
        }
        return result;
    }

    void clear() {
        counts.clear();
        lastKey = null;
        totalCount = 0;
    }

    static List<ItemStack> copyAndAggregate(List<ItemStack> stacks) {
        ItemStackAccumulator accumulator = new ItemStackAccumulator();
        accumulator.addAll(stacks);
        return accumulator.toAggregatedStacks();
    }

    static long count(List<ItemStack> stacks) {
        long total = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                total = saturatingAdd(total, stack.getCount());
            }
        }
        return total;
    }

    private static long saturatingAdd(long first, long second) {
        if (second <= 0L) {
            return first;
        }
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static long saturatingMultiply(long first, long second) {
        if (first <= 0L || second <= 0L) {
            return 0L;
        }
        return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
    }

    static List<ItemStack> splitForEntities(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            int remaining = stack.getCount();
            int maxStackSize = Math.max(1,
                    Math.min(Integer.MAX_VALUE, stack.getMaxStackSize() * ENTITY_STACK_MULTIPLIER));
            while (remaining > 0) {
                int amount = Math.min(maxStackSize, remaining);
                result.add(stack.copyWithCount(amount));
                remaining -= amount;
            }
        }
        return result;
    }

    private static final class StackKey {
        private final ItemStack representative;
        private final int hash;

        private StackKey(ItemStack stack) {
            this.representative = stack.copyWithCount(1);
            this.hash = ItemStack.hashItemAndComponents(representative);
        }

        private boolean matches(ItemStack stack) {
            return ItemStack.isSameItemSameComponents(representative, stack);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StackKey key
                    && ItemStack.isSameItemSameComponents(representative, key.representative);
        }
    }
}
