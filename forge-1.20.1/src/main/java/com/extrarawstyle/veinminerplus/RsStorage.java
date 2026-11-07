package com.extrarawstyle.veinminerplus;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

// Everything that touches Refined Storage classes lives in this one class, so the rest of the mod
// still loads when RS is not installed. RS 1.x has no published API jar, so the release jar is the
// compile-time target; every type used here is from its public api package.
//
// Unlike RS2, RS 1.x does not hand out an item handler that reaches the network: a disk drive and a
// pattern grid expose their own slots as one, which is why the RS check runs before the generic
// item handler. The network is reached through the node the block entity hosts.
final class RsStorage {
    // Compile-time constant, so referencing it does not load any RS class.
    static final String MOD_ID = "refinedstorage";

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
        return nodeAt(level, pos) != null;
    }

    // Resolves the network once and hands back a sink that keeps inserting into it for the rest of
    // the flush. Returns null when that position is not part of a network right now, or when the
    // network cannot run (no energy, or its redstone mode says off): RS 1.x inserts without asking
    // the energy storage, so without this gate a stopped network would still swallow the drops.
    static StorageRouter.Sink sink(Level level, BlockPos pos) {
        INetwork network = networkAt(level, pos);
        if (network == null || !network.canRun()) {
            return null;
        }
        return stack -> {
            // Tracked insert is what RS's own interface and importer use: an autocrafting task that
            // is waiting for this item takes it first, and the rest still lands in storage.
            ItemStack remainder = network.insertItemTracked(stack, stack.getCount());
            stack.setCount(remainder.getCount());
        };
    }

    // The bound block is resolved again on every flush, so a moved, broken or disconnected target
    // simply stops working.
    private static INetwork networkAt(Level level, BlockPos pos) {
        INetworkNode node = nodeAt(level, pos);
        return node == null ? null : node.getNetwork();
    }

    // RS 1.x answers this through a capability, but the block entity interface is enough and does not
    // depend on the capability's implementation class. The instanceof check is not optional: getNode
    // is self-healing and creates a node (or, on a controller, a whole network) plus a warning log
    // for a position that is not one of its own, so it must never be called on an unknown block.
    private static INetworkNode nodeAt(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            blockEntity = level.getBlockEntity(pos);
        }
        if (!(blockEntity instanceof INetworkNodeProxy<?> proxy)) {
            return null;
        }
        return proxy.getNode();
    }
}
