package com.extrarawstyle.veinminerplus;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Immutable server-validated rectangle of chunk coordinates. */
public record FastMineSelection(ResourceKey<Level> dimension, int minChunkX, int minChunkZ,
        int maxChunkX, int maxChunkZ) {
    public FastMineSelection {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension cannot be null");
        }
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException("chunk selection corners are not normalized");
        }
    }

    public static FastMineSelection rectangle(ResourceKey<Level> dimension, int firstChunkX, int firstChunkZ,
            int secondChunkX, int secondChunkZ) {
        return new FastMineSelection(dimension, Math.min(firstChunkX, secondChunkX),
                Math.min(firstChunkZ, secondChunkZ), Math.max(firstChunkX, secondChunkX),
                Math.max(firstChunkZ, secondChunkZ));
    }

    public long chunkCount() {
        long width = (long) maxChunkX - minChunkX + 1L;
        long depth = (long) maxChunkZ - minChunkZ + 1L;
        return width > Long.MAX_VALUE / depth ? Long.MAX_VALUE : width * depth;
    }

    public boolean contains(int chunkX, int chunkZ) {
        return chunkX >= minChunkX && chunkX <= maxChunkX
                && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }
}
