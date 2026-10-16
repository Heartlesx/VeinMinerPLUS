package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler {
    private static final String PROTOCOL_VERSION = "4";
    private static int packetId;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private NetworkHandler() {
    }

    static void register() {
        CHANNEL.registerMessage(packetId++, KeyStatePayload.class,
                (payload, buffer) -> buffer.writeBoolean(payload.held()),
                buffer -> new KeyStatePayload(buffer.readBoolean()),
                NetworkHandler::handleKeyState,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, ModeChangePayload.class,
                (payload, buffer) -> buffer.writeVarInt(payload.mode()),
                buffer -> new ModeChangePayload(buffer.readVarInt()),
                NetworkHandler::handleModeChange,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, ConfigRequestPayload.class,
                (payload, buffer) -> {
                },
                buffer -> new ConfigRequestPayload(),
                NetworkHandler::handleConfigRequest,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, ConfigSnapshotPayload.class,
                NetworkHandler::writeConfigSnapshot, NetworkHandler::readConfigSnapshot,
                NetworkHandler::handleConfigSnapshot,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(packetId++, ConfigUpdatePayload.class,
                NetworkHandler::writeConfigUpdate, NetworkHandler::readConfigUpdate,
                NetworkHandler::handleConfigUpdate,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, BlacklistTogglePayload.class,
                (payload, buffer) -> buffer.writeBlockPos(payload.pos()),
                buffer -> new BlacklistTogglePayload(buffer.readBlockPos()),
                NetworkHandler::handleBlacklistToggle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(packetId++, BlacklistSyncPayload.class,
                NetworkHandler::writeBlacklistSync, NetworkHandler::readBlacklistSync,
                NetworkHandler::handleBlacklistSync,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static void sendKeyState(boolean held) {
        CHANNEL.sendToServer(new KeyStatePayload(held));
    }

    static void sendModeChange(ChainMode mode) {
        CHANNEL.sendToServer(new ModeChangePayload(mode.ordinal()));
    }

    static void requestConfigScreen() {
        CHANNEL.sendToServer(new ConfigRequestPayload());
    }

    static void sendConfigUpdate(ConfigUpdatePayload payload) {
        CHANNEL.sendToServer(payload);
    }

    static void sendBlacklistToggle(BlockPos pos) {
        CHANNEL.sendToServer(new BlacklistTogglePayload(pos));
    }

    static void sendBlacklistSync(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new BlacklistSyncPayload(currentBlacklist()));
    }

    private static List<String> currentBlacklist() {
        return List.copyOf(Config.BLOCK_BLACKLIST.get());
    }

    static void openConfigScreen(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), ConfigSnapshotPayload.current());
    }

    private static void handleKeyState(KeyStatePayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ChainEvents.setKeyHeld(player, payload.held());
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleModeChange(ModeChangePayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ChainEvents.setMode(player, payload.mode());
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleConfigSnapshot(ConfigSnapshotPayload payload,
            Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> VeinMinerPlusClient.openConfigScreen(payload));
        context.setPacketHandled(true);
    }

    private static void handleConfigRequest(ConfigRequestPayload payload,
            Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (player.hasPermissions(2)) {
                openConfigScreen(player);
            } else {
                player.displayClientMessage(Component.translatable("message.veinminerplus.config_permission"), true);
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleConfigUpdate(ConfigUpdatePayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !player.hasPermissions(2)) {
                return;
            }

            Config.MAX_NORMAL_BLOCKS.set(Mth.clamp(payload.maxNormalBlocks(),
                    Config.MIN_MAX_NORMAL_BLOCKS, Config.MAX_MAX_NORMAL_BLOCKS));
            Config.MAX_NORMAL_BLOCKS_PER_TICK.set(Mth.clamp(payload.maxNormalBlocksPerTick(),
                    Config.MIN_MAX_NORMAL_BLOCKS_PER_TICK, Config.MAX_MAX_NORMAL_BLOCKS_PER_TICK));
            Config.MAX_BLAST_BLOCKS.set(Mth.clamp(payload.maxBlastBlocks(),
                    Config.MIN_MAX_BLAST_BLOCKS, Config.MAX_MAX_BLAST_BLOCKS));
            Config.MAX_BLAST_BLOCKS_PER_TICK.set(Mth.clamp(payload.maxBlastBlocksPerTick(),
                    Config.MIN_MAX_BLAST_BLOCKS_PER_TICK, Config.MAX_MAX_BLAST_BLOCKS_PER_TICK));
            Config.BLAST_SEARCH_DISTANCE.set(Mth.clamp(payload.blastSearchDistance(),
                    Config.MIN_BLAST_SEARCH_DISTANCE, Config.MAX_BLAST_SEARCH_DISTANCE));
            Config.NO_HUNGER_COST.set(payload.noHungerCost());
            Config.STORAGE_BINDING.set(payload.storageBinding());
            Config.BLOCK_BLACKLIST.set(sanitizeBlacklist(payload.blacklist()));
            Config.SPEC.save();
            BlockBlacklist.applyServerConfig();
            // Every client mirrors the list, so the interaction preview agrees with the server.
            CHANNEL.send(PacketDistributor.ALL.noArg(), new BlacklistSyncPayload(currentBlacklist()));
            player.displayClientMessage(Component.translatable("message.veinminerplus.config_saved"), false);
        });
        context.setPacketHandled(true);
    }

    // The hotkey path: the client sends the block it aims at, the server revalidates everything
    // (permission, sneak posture, distance) before it touches the shared config.
    private static void handleBlacklistToggle(BlacklistTogglePayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                toggleBlacklist(player, payload.pos());
            }
        });
        context.setPacketHandled(true);
    }

    private static void toggleBlacklist(ServerPlayer player, BlockPos pos) {
        if (!player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable(
                    "message.veinminerplus.blacklist.no_permission"), true);
            return;
        }
        if (!player.isShiftKeyDown()) {
            return;
        }

        ServerLevel level = player.serverLevel();
        if (!level.isInWorldBounds(pos)
                || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > 100.0D) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            return;
        }

        List<String> list = new ArrayList<>(Config.BLOCK_BLACKLIST.get());
        boolean added;
        if (list.remove(id.toString())) {
            added = false;
        } else {
            if (list.size() >= BlockBlacklist.MAX_ENTRIES) {
                player.displayClientMessage(Component.translatable(
                        "message.veinminerplus.blacklist.full", BlockBlacklist.MAX_ENTRIES), true);
                return;
            }
            list.add(id.toString());
            added = true;
        }

        Config.BLOCK_BLACKLIST.set(list);
        Config.SPEC.save();
        BlockBlacklist.applyServerConfig();
        CHANNEL.send(PacketDistributor.ALL.noArg(), new BlacklistSyncPayload(currentBlacklist()));
        player.displayClientMessage(Component.translatable(
                added ? "message.veinminerplus.blacklist.added" : "message.veinminerplus.blacklist.removed",
                state.getBlock().getName()), true);
    }

    private static void handleBlacklistSync(BlacklistSyncPayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> BlockBlacklist.applyRemote(payload.entries()));
        context.setPacketHandled(true);
    }

    private static List<String> sanitizeBlacklist(List<String> entries) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String raw : entries) {
            if (clean.size() >= BlockBlacklist.MAX_ENTRIES) {
                break;
            }
            if (raw == null) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(raw.trim());
            if (id != null) {
                clean.add(id.toString());
            }
        }
        return List.copyOf(clean);
    }

    private record KeyStatePayload(boolean held) {
    }

    private record ModeChangePayload(int mode) {
    }

    private record ConfigRequestPayload() {
    }

    public record ConfigSnapshotPayload(int maxNormalBlocks, int maxNormalBlocksPerTick,
            int maxBlastBlocks, int maxBlastBlocksPerTick, int blastSearchDistance,
            boolean noHungerCost, boolean storageBinding, List<String> blacklist) {
        static ConfigSnapshotPayload current() {
            return new ConfigSnapshotPayload(Config.MAX_NORMAL_BLOCKS.get(),
                    Config.MAX_NORMAL_BLOCKS_PER_TICK.get(), Config.MAX_BLAST_BLOCKS.get(),
                    Config.MAX_BLAST_BLOCKS_PER_TICK.get(), Config.BLAST_SEARCH_DISTANCE.get(),
                    Config.NO_HUNGER_COST.get(), Config.STORAGE_BINDING.get(), currentBlacklist());
        }
    }

    public record ConfigUpdatePayload(int maxNormalBlocks, int maxNormalBlocksPerTick,
            int maxBlastBlocks, int maxBlastBlocksPerTick, int blastSearchDistance,
            boolean noHungerCost, boolean storageBinding, List<String> blacklist) {
    }

    private record BlacklistTogglePayload(BlockPos pos) {
    }

    private record BlacklistSyncPayload(List<String> entries) {
    }

    private static void writeConfigSnapshot(ConfigSnapshotPayload payload, FriendlyByteBuf buffer) {
        writeConfig(buffer, payload.maxNormalBlocks(), payload.maxNormalBlocksPerTick(), payload.maxBlastBlocks(),
                payload.maxBlastBlocksPerTick(), payload.blastSearchDistance(), payload.noHungerCost(),
                payload.storageBinding(), payload.blacklist());
    }

    private static ConfigSnapshotPayload readConfigSnapshot(FriendlyByteBuf buffer) {
        return new ConfigSnapshotPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(),
                readBlacklist(buffer));
    }

    private static void writeConfigUpdate(ConfigUpdatePayload payload, FriendlyByteBuf buffer) {
        writeConfig(buffer, payload.maxNormalBlocks(), payload.maxNormalBlocksPerTick(), payload.maxBlastBlocks(),
                payload.maxBlastBlocksPerTick(), payload.blastSearchDistance(), payload.noHungerCost(),
                payload.storageBinding(), payload.blacklist());
    }

    private static ConfigUpdatePayload readConfigUpdate(FriendlyByteBuf buffer) {
        return new ConfigUpdatePayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(),
                readBlacklist(buffer));
    }

    private static void writeConfig(FriendlyByteBuf buffer, int maxNormalBlocks,
            int maxNormalBlocksPerTick, int maxBlastBlocks, int maxBlastBlocksPerTick,
            int blastSearchDistance, boolean noHungerCost, boolean storageBinding, List<String> blacklist) {
        buffer.writeVarInt(maxNormalBlocks);
        buffer.writeVarInt(maxNormalBlocksPerTick);
        buffer.writeVarInt(maxBlastBlocks);
        buffer.writeVarInt(maxBlastBlocksPerTick);
        buffer.writeVarInt(blastSearchDistance);
        buffer.writeBoolean(noHungerCost);
        buffer.writeBoolean(storageBinding);
        writeBlacklist(buffer, blacklist);
    }

    private static void writeBlacklistSync(BlacklistSyncPayload payload, FriendlyByteBuf buffer) {
        writeBlacklist(buffer, payload.entries());
    }

    private static BlacklistSyncPayload readBlacklistSync(FriendlyByteBuf buffer) {
        return new BlacklistSyncPayload(readBlacklist(buffer));
    }

    private static void writeBlacklist(FriendlyByteBuf buffer, List<String> entries) {
        int size = Math.min(entries.size(), BlockBlacklist.MAX_ENTRIES);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            buffer.writeUtf(entries.get(index), 128);
        }
    }

    private static List<String> readBlacklist(FriendlyByteBuf buffer) {
        int size = Mth.clamp(buffer.readVarInt(), 0, BlockBlacklist.MAX_ENTRIES);
        List<String> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(buffer.readUtf(128));
        }
        return entries;
    }
}
