package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;

import net.minecraft.world.item.ItemStack;

/**
 * Aggregates item stacks by item and data components without losing enchantments,
 * damage, container contents, custom names or other component data.
 */
final class ItemStackAccumulator {
    private final Object2LongLinkedOpenHashMap<StackKey> counts = new Object2LongLinkedOpenHashMap<>();
    private StackKey lastKey;
    private long totalCount;

    void add(ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() <= 0) {
            return;
        }
        int amount = stack.getCount();
        if (lastKey != null && lastKey.matches(stack)) {
            counts.addTo(lastKey, amount);
            totalCount += amount;
            return;
        }

        StackKey key = new StackKey(stack);
        counts.addTo(key, amount);
        lastKey = key;
        totalCount += amount;
    }

    void addAll(List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            add(stack);
        }
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
                total += stack.getCount();
            }
        }
        return total;
    }

    static List<ItemStack> splitForEntities(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            int remaining = stack.getCount();
            int maxStackSize = Math.max(1, stack.getMaxStackSize());
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
