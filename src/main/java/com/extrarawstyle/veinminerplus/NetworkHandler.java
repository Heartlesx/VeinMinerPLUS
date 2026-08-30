package com.extrarawstyle.veinminerplus;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class NetworkHandler {
    private static final String PROTOCOL_VERSION = "2";

    private NetworkHandler() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(KeyStatePayload.TYPE, KeyStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ChainEvents.setKeyHeld(context.player(), payload.held())));
        registrar.playToServer(ModeChangePayload.TYPE, ModeChangePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ChainEvents.setMode(context.player(), payload.mode())));
        registrar.playToServer(ConfigRequestPayload.TYPE, ConfigRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        handleConfigRequest(player);
                    }
                }));
        registrar.playToClient(ConfigSnapshotPayload.TYPE, ConfigSnapshotPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> VeinMinerPlusClient.openConfigScreen(payload)));
        registrar.playToServer(ConfigUpdatePayload.TYPE, ConfigUpdatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        handleConfigUpdate(player, payload);
                    }
                }));
    }

    static void sendKeyState(boolean held) {
        PacketDistributor.sendToServer(new KeyStatePayload(held));
    }

    static void sendModeChange(ChainMode mode) {
        PacketDistributor.sendToServer(new ModeChangePayload(mode.ordinal()));
    }

    static void requestConfigScreen() {
        PacketDistributor.sendToServer(new ConfigRequestPayload());
    }

    static void sendConfigUpdate(ConfigUpdatePayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    static void openConfigScreen(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, ConfigSnapshotPayload.current());
    }

    private static void handleConfigRequest(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (player.hasPermissions(2)) {
            openConfigScreen(player);
        } else {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.veinminerplus.config_permission"), true);
        }
    }

    private static void handleConfigUpdate(ServerPlayer player, ConfigUpdatePayload payload) {
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
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.veinminerplus.config_saved"), false);
    }

    public record KeyStatePayload(boolean held) implements CustomPacketPayload {
        public static final Type<KeyStatePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "key_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, KeyStatePayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> buffer.writeBoolean(payload.held),
                buffer -> new KeyStatePayload(buffer.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ModeChangePayload(int mode) implements CustomPacketPayload {
        public static final Type<ModeChangePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "mode_change"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ModeChangePayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> buffer.writeVarInt(payload.mode),
                buffer -> new ModeChangePayload(buffer.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ConfigRequestPayload() implements CustomPacketPayload {
        public static final Type<ConfigRequestPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "config_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigRequestPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> {
                }, buffer -> new ConfigRequestPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ConfigSnapshotPayload(int maxNormalBlocks, int maxNormalBlocksPerTick,
            int maxBlastBlocks, int maxBlastBlocksPerTick, int blastSearchDistance,
            boolean noHungerCost) implements CustomPacketPayload {
        public static final Type<ConfigSnapshotPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "config_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSnapshotPayload> STREAM_CODEC = StreamCodec.of(
                NetworkHandler::writeConfigSnapshot, NetworkHandler::readConfigSnapshot);

        static ConfigSnapshotPayload current() {
            return new ConfigSnapshotPayload(Config.MAX_NORMAL_BLOCKS.getAsInt(),
                    Config.MAX_NORMAL_BLOCKS_PER_TICK.getAsInt(), Config.MAX_BLAST_BLOCKS.getAsInt(),
                    Config.MAX_BLAST_BLOCKS_PER_TICK.getAsInt(), Config.BLAST_SEARCH_DISTANCE.getAsInt(),
                    Config.NO_HUNGER_COST.getAsBoolean());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ConfigUpdatePayload(int maxNormalBlocks, int maxNormalBlocksPerTick,
            int maxBlastBlocks, int maxBlastBlocksPerTick, int blastSearchDistance,
            boolean noHungerCost) implements CustomPacketPayload {
        public static final Type<ConfigUpdatePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "config_update"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigUpdatePayload> STREAM_CODEC = StreamCodec.of(
                NetworkHandler::writeConfigUpdate, NetworkHandler::readConfigUpdate);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writeConfigSnapshot(RegistryFriendlyByteBuf buffer, ConfigSnapshotPayload payload) {
        writeConfig(buffer, payload.maxNormalBlocks(), payload.maxNormalBlocksPerTick(), payload.maxBlastBlocks(),
                payload.maxBlastBlocksPerTick(), payload.blastSearchDistance(), payload.noHungerCost());
    }

    private static ConfigSnapshotPayload readConfigSnapshot(RegistryFriendlyByteBuf buffer) {
        return new ConfigSnapshotPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }

    private static void writeConfigUpdate(RegistryFriendlyByteBuf buffer, ConfigUpdatePayload payload) {
        writeConfig(buffer, payload.maxNormalBlocks(), payload.maxNormalBlocksPerTick(), payload.maxBlastBlocks(),
                payload.maxBlastBlocksPerTick(), payload.blastSearchDistance(), payload.noHungerCost());
    }

    private static ConfigUpdatePayload readConfigUpdate(RegistryFriendlyByteBuf buffer) {
        return new ConfigUpdatePayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }

    private static void writeConfig(RegistryFriendlyByteBuf buffer, int maxNormalBlocks,
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
