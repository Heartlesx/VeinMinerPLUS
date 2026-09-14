package com.extrarawstyle.veinminerplus.compat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

public record StorageResult(long inserted, List<ItemStack> remaining, Status status) {
    public StorageResult {
        inserted = Math.max(0, inserted);
        remaining = copyStacks(remaining);
    }

    public static StorageResult available(long inserted, List<ItemStack> remaining) {
        return new StorageResult(inserted, remaining, Status.AVAILABLE);
    }

    public static StorageResult unsupported(List<ItemStack> items) {
        return new StorageResult(0, items, Status.UNSUPPORTED);
    }

    public static StorageResult temporarilyUnavailable(List<ItemStack> items) {
        return new StorageResult(0, items, Status.TEMPORARILY_UNAVAILABLE);
    }

    public static StorageResult failed(List<ItemStack> items) {
        return new StorageResult(0, items, Status.FAILED);
    }

    public static StorageResult failed(long inserted, List<ItemStack> remaining) {
        return new StorageResult(inserted, remaining, Status.FAILED);
    }

    public boolean retryable() {
        return status == Status.TEMPORARILY_UNAVAILABLE || status == Status.FAILED;
    }

    private static List<ItemStack> copyStacks(List<ItemStack> items) {
        List<ItemStack> copies = new ArrayList<>(items.size());
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                copies.add(stack.copy());
            }
        }
        return List.copyOf(copies);
    }

    public enum Status {
        /** The network was reached. Any remainder is caused by capacity or power. */
        AVAILABLE,
        /** AE2 is not installed or the optional integration could not be loaded. */
        UNSUPPORTED,
        /** The dimension, chunk, node or grid is temporarily unavailable. */
        TEMPORARILY_UNAVAILABLE,
        /** An integration/API call failed. It may be retried for a bounded time. */
        FAILED
    }
}
