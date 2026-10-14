package com.extrarawstyle.veinminerplus;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.event.config.ModConfigEvent;

// Blocks listed in the blacklist are never chained, never blasted and never seed a chain. The
// list lives in the common config on the server; clients keep a mirror of the server's list so
// the interaction preview cannot disagree with what the server job will actually do.
public final class BlockBlacklist {
    public static final int MAX_ENTRIES = 256;

    private static volatile Set<Block> blocks = Set.of();

    private BlockBlacklist() {
    }

    public static boolean contains(BlockState state) {
        return blocks.contains(state.getBlock());
    }

    public static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == Config.SPEC) {
            applyServerConfig();
        }
    }

    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == Config.SPEC) {
            applyServerConfig();
        }
    }

    // Server side: rebuild from the configured id list.
    public static void applyServerConfig() {
        blocks = resolve(Config.BLOCK_BLACKLIST.get());
    }

    // Client side: rebuild from the list the server sent.
    public static void applyRemote(List<String> ids) {
        blocks = resolve(ids);
    }

    private static Set<Block> resolve(List<? extends String> ids) {
        Set<Block> resolved = new HashSet<>();
        for (String raw : ids) {
            if (raw == null) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(raw.trim());
            // Ids of mods that are not installed simply never match anything.
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                continue;
            }
            resolved.add(BuiltInRegistries.BLOCK.get(id));
        }
        return resolved;
    }
}
