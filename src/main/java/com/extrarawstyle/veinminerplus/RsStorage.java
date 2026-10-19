package com.extrarawstyle.veinminerplus;

import java.util.Optional;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.NetworkNode;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.root.RootStorage;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.storage.PlayerActor;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.BlockCapability;

// Everything that touches Refined Storage classes lives in this one class, so the rest of the mod
// still loads when RS is not installed. RS does not expose its network through a vanilla item
// handler: network blocks advertise a node container provider instead, and only a few of them
// (disk drive, disk interface, pattern grid) expose their slot inventories as item handlers.
final class RsStorage {
    // Compile-time constant, so referencing it does not load any RS class.
    static final String MOD_ID = "refinedstorage";

    private static final BlockCapability<NetworkNodeContainerProvider, Direction> NETWORK_CAPABILITY =
            RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability();

    private RsStorage() {
    }

    // True when the block really is part of a running RS network, so a block that merely hosts a
    // node cannot be bound to a network that does not exist yet.
    static boolean canBind(Level level, BlockPos pos) {
        return networkAt(level, pos) != null;
    }

    // True when the block is built to join an RS network, whether or not it is connected right now.
    // This is how an unconnected RS block reports itself, mirroring Ae2Storage.canHostGrid.
    static boolean canHostNetwork(Level level, BlockPos pos) {
        return providerAt(level, pos) != null;
    }

    // Resolves the network once and hands back a sink that keeps inserting into it for the rest of
    // the flush. Returns null when that position is not part of a network right now.
    static StorageRouter.Sink sink(Level level, BlockPos pos, Player player) {
        Network network = networkAt(level, pos);
        if (network == null) {
            return null;
        }
        RootStorage storage = network.getComponent(StorageNetworkComponent.class);
        Actor actor = new PlayerActor(player);
        return stack -> {
            Optional<ResourceAmount> amount = RefinedStorageApi.INSTANCE.getItemResourceFactory().create(stack);
            if (amount.isEmpty()) {
                return;
            }
            long inserted = storage.insert(amount.get().resource(), amount.get().amount(), Action.EXECUTE, actor);
            if (inserted > 0) {
                stack.shrink((int) Math.min(inserted, stack.getCount()));
            }
        };
    }

    // The bound block is resolved again on every flush, so a moved, broken or disconnected target
    // simply stops working.
    private static Network networkAt(Level level, BlockPos pos) {
        NetworkNodeContainerProvider provider = providerAt(level, pos);
        if (provider == null) {
            return null;
        }
        for (InWorldNetworkNodeContainer container : provider.getContainers()) {
            NetworkNode node = container.getNode();
            Network network = node == null ? null : node.getNetwork();
            if (network != null) {
                return network;
            }
        }
        return null;
    }

    // Some blocks only expose their capabilities for a specific side, so a side-less lookup failing
    // is not the end of it.
    private static NetworkNodeContainerProvider providerAt(Level level, BlockPos pos) {
        NetworkNodeContainerProvider provider = level.getCapability(NETWORK_CAPABILITY, pos, null);
        if (provider != null) {
            return provider;
        }
        for (Direction side : Direction.values()) {
            provider = level.getCapability(NETWORK_CAPABILITY, pos, side);
            if (provider != null) {
                return provider;
            }
        }
        return null;
    }
}
