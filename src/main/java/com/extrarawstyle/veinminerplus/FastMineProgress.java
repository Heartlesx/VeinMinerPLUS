package com.extrarawstyle.veinminerplus;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-authoritative progress snapshot sent to the selection screen. */
public record FastMineProgress(long loadedChunks, long totalChunks, long scannedBlocks, long totalBlocks,
        long brokenBlocks, long foundOres, long targetBlocks, long processedBlocks,
        Map<ResourceLocation, Long> oreBreakdown, State state) {
    public FastMineProgress(long loadedChunks, long totalChunks, long scannedBlocks, long totalBlocks,
            long brokenBlocks, long foundOres, long targetBlocks, long processedBlocks, State state) {
        this(loadedChunks, totalChunks, scannedBlocks, totalBlocks, brokenBlocks, foundOres,
                targetBlocks, processedBlocks, Map.of(), state);
    }

    public FastMineProgress {
        loadedChunks = Math.max(0L, loadedChunks);
        totalChunks = Math.max(0L, totalChunks);
        scannedBlocks = Math.max(0L, scannedBlocks);
        totalBlocks = Math.max(0L, totalBlocks);
        brokenBlocks = Math.max(0L, brokenBlocks);
        foundOres = Math.max(0L, foundOres);
        targetBlocks = Math.max(0L, targetBlocks);
        processedBlocks = Math.max(0L, processedBlocks);
        Map<ResourceLocation, Long> normalizedOres = new LinkedHashMap<>();
        if (oreBreakdown != null) {
            oreBreakdown.forEach((id, count) -> {
                if (id != null && count != null && count > 0L && normalizedOres.size() < 128) {
                    normalizedOres.put(id, count);
                }
            });
        }
        oreBreakdown = Map.copyOf(normalizedOres);
        state = state == null ? State.IDLE : state;
    }

    public float completion() {
        if (state == State.COMPLETED) return 1.0F;
        if (state == State.LOADING || totalBlocks <= 0) {
            return fraction(loadedChunks, totalChunks) * 0.1F;
        }
        float scanned = fraction(scannedBlocks, totalBlocks);
        float progress = scanned * 0.5F + fraction(processedBlocks, targetBlocks) * 0.5F;
        return Math.min(0.99F, Math.max(0.0F, progress));
    }

    private static float fraction(long value, long total) {
        return total <= 0 ? 0.0F : Math.min(1.0F, Math.max(0.0F, (float) value / total));
    }

    public enum State {
        IDLE,
        LOADING,
        SCANNING,
        MINING,
        PAUSED,
        COMPLETED,
        CANCELLED,
        FAILED
    }
}
