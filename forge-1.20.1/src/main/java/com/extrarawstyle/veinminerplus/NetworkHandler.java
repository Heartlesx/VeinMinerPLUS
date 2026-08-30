package com.extrarawstyle.veinminerplus;

import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler {
    private static final String PROTOCOL_VERSION = "2";
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

    static void openConfigScreen(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), ConfigSnapshotPayload.current());
    }

    static void sendConfigUpdate(ConfigUpdatePayload payload) {
        CHANNEL.sendToServer(payload);
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

            Config.MAX_NORMAL_BLOCKS.set(Mth.clamp(payload.maxNormalBlocks(), 32, 32767));
            Config.MAX_NORMAL_BLOCKS_PER_TICK.set(Mth.clamp(payload.maxNormalBlocksPerTick(), 1, 384));
            Config.MAX_BLAST_BLOCKS.set(Mth.clamp(payload.maxBlastBlocks(), 32, 32767));
            Config.MAX_BLAST_BLOCKS_PER_TICK.set(Mth.clamp(payload.maxBlastBlocksPerTick(), 1, 512));
            Config.BLAST_SEARCH_DISTANCE.set(Mth.clamp(payload.blastSearchDistance(), 3, 128));
            Config.NO_HUNGER_COST.set(payload.noHungerCost());
            Config.SPEC.save();
            player.displayClientMessage(Component.translatable("message.veinminerplus.config_saved"), false);
        });
        context.setPacketHandled(true);
    }

    private record KeyStatePayload(boolean held) {
    }

    private record ModeChangePayload(int mode) {
    }

    private record ConfigRequestPayload() {
    }

    public record ConfigSnapshotPayload(int maxNormalBlocks, int maxNormalBlocksPerTick,
            int maxBlastBlocks, int maxBlastBlocksPerTick, int blastSearchDistance,
            boolean noHungerCost) {
        static ConfigSnapshotPayload current() {
            return new ConfigSnapshotPayload(Config.MAX_NORMAL_BLOCKS.get(),
                    Config.MAX_NORMAL_BLOCKS_PER_TICK.get(), Config.MAX_BLAST_BLOCKS.get(),
                    Config.MAX_BLAST_BLOCKS_PER_TICK.get(), Config.BLAST_SEARCH_DISTANCE.get(),
                    Config.NO_HUNGER_COST.get());
        }
    }

    public record ConfigUpdatePayload(int maxNormalBlocks, int maxNormalBlocksPerTick,
            int maxBlastBlocks, int maxBlastBlocksPerTick, int blastSearchDistance,
            boolean noHungerCost) {
    }

    private static void writeConfigSnapshot(ConfigSnapshotPayload payload, FriendlyByteBuf buffer) {
        writeConfig(buffer, payload.maxNormalBlocks(), payload.maxNormalBlocksPerTick(), payload.maxBlastBlocks(),
                payload.maxBlastBlocksPerTick(), payload.blastSearchDistance(), payload.noHungerCost());
    }

    private static ConfigSnapshotPayload readConfigSnapshot(FriendlyByteBuf buffer) {
        return new ConfigSnapshotPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }

    private static void writeConfigUpdate(ConfigUpdatePayload payload, FriendlyByteBuf buffer) {
        writeConfig(buffer, payload.maxNormalBlocks(), payload.maxNormalBlocksPerTick(), payload.maxBlastBlocks(),
                payload.maxBlastBlocksPerTick(), payload.blastSearchDistance(), payload.noHungerCost());
    }

    private static ConfigUpdatePayload readConfigUpdate(FriendlyByteBuf buffer) {
        return new ConfigUpdatePayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }

    private static void writeConfig(FriendlyByteBuf buffer, int maxNormalBlocks,
            int maxNormalBlocksPerTick, int maxBlastBlocks, int maxBlastBlocksPerTick,
            int blastSearchDistance, boolean noHungerCost) {
        buffer.writeVarInt(maxNormalBlocks);
        buffer.writeVarInt(maxNormalBlocksPerTick);
        buffer.writeVarInt(maxBlastBlocks);
        buffer.writeVarInt(maxBlastBlocksPerTick);
        buffer.writeVarInt(blastSearchDistance);
        buffer.writeBoolean(noHungerCost);
    }
}
