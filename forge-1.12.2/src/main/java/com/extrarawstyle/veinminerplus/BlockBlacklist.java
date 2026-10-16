package com.extrarawstyle.veinminerplus;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;

// Blocks listed in the blacklist are never chained, never blasted and never seed a chain. The list
// lives in the server config; clients keep a mirror of the server's list so the interaction preview
// cannot disagree with what the server job will actually do.
public final class BlockBlacklist {
    public static final int MAX_ENTRIES = 256;

    private static volatile Set<Block> blocks = Collections.emptySet();

    private BlockBlacklist() {
    }

    public static boolean contains(IBlockState state) {
        return blocks.contains(state.getBlock());
    }

    // Server side: rebuild from the configured id list.
    public static void applyServerConfig() {
        blocks = resolve(Arrays.asList(Config.blockBlacklist));
    }

    // Client side: rebuild from the list the server sent.
    public static void applyRemote(List<String> ids) {
        blocks = resolve(ids);
    }

    private static Set<Block> resolve(List<String> ids) {
        Set<Block> resolved = new HashSet<>();
        for (String raw : ids) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            ResourceLocation id;
            try {
                id = new ResourceLocation(raw.trim());
            } catch (RuntimeException exception) {
                // An id with a broken syntax is simply never a match.
                continue;
            }
            // Ids of mods that are not installed simply never match anything.
            Block block = Block.REGISTRY.getObject(id);
            if (block != null) {
                resolved.add(block);
            }
        }
        return resolved;
    }
}
