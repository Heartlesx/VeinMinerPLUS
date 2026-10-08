package com.extrarawstyle.veinminerplus;

import com.extrarawstyle.veinminerplus.client.VeinMinerPlusClient;
import com.extrarawstyle.veinminerplus.ChainEvents;
import com.extrarawstyle.veinminerplus.ChainMode;
import com.extrarawstyle.veinminerplus.Config;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NetworkHandler {
    private static final String PROTOCOL_VERSION = "5";

    private NetworkHandler() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(KeyStatePayload.TYPE, KeyStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ChainEvents.setKeyHeld(context.player(), payload.held())));
        registrar.playToServer(ModeChangePayload.TYPE, ModeChangePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ChainEvents.setMode(context.player(), payload.mode())));
        registrar.playToServer(FastMineSelectionPayload.TYPE, FastMineSelectionPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player && ChainEvents.isFastMine(player)) {
                        ResourceKey<net.minecraft.world.level.Level> dimension = ResourceKey.create(
                                Registries.DIMENSION, payload.dimension());
                        FastMineTaskManager.begin(player, FastMineSelection.rectangle(dimension,
                                payload.firstChunkX(), payload.firstChunkZ(), payload.secondChunkX(),
                                payload.secondChunkZ()));
                    }
                }));
        registrar.playToServer(FastMineCancelPayload.TYPE, FastMineCancelPayload.STREAM_CODEC,
                (payload, context) -> {
                    // Cancellation must not wait behind an overloaded server task
                    // queue.  Only the UUID is recorded here; world mutation and
                    // ticket cleanup are still performed by the server thread.
                    if (context.player() instanceof ServerPlayer player) {
                        FastMineTaskManager.requestCancel(player.getUUID());
                    }
                });
        registrar.playToServer(ConfigRequestPayload.TYPE, ConfigRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        handleConfigRequest(player);
                    }
                }));
        registrar.playToClient(ConfigSnapshotPayload.TYPE, ConfigSnapshotPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> VeinMinerPlusClient.openConfigScreen(payload)));
        registrar.playToClient(FastMineProgressPayload.TYPE, FastMineProgressPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> VeinMinerPlusClient.updateFastMineProgress(
                        new FastMineProgress(payload.loadedChunks(), payload.totalChunks(), payload.scannedBlocks(),
                                payload.totalBlocks(), payload.brokenBlocks(), payload.foundOres(),
                                payload.targetBlocks(), payload.processedBlocks(), payload.oreBreakdown(),
                                FastMineProgress.State.values()[Mth.clamp(payload.state(), 0,
                                        FastMineProgress.State.values().length - 1)]))));
        registrar.playToServer(ConfigUpdatePayload.TYPE, ConfigUpdatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        handleConfigUpdate(player, payload);
                    }
                }));
    }

    public static void sendKeyState(boolean held) {
        PacketDistributor.sendToServer(new KeyStatePayload(held));
    }

    public static void sendModeChange(ChainMode mode) {
        PacketDistributor.sendToServer(new ModeChangePayload(mode.ordinal()));
    }

    public static void sendFastMineSelection(ResourceLocation dimension, int firstChunkX, int firstChunkZ,
            int secondChunkX, int secondChunkZ) {
        PacketDistributor.sendToServer(new FastMineSelectionPayload(dimension, firstChunkX, firstChunkZ,
                secondChunkX, secondChunkZ));
    }

    public static void sendFastMineCancel() {
        PacketDistributor.sendToServer(new FastMineCancelPayload());
    }

    static void sendFastMineProgress(ServerPlayer player, FastMineProgress progress) {
        PacketDistributor.sendToPlayer(player, new FastMineProgressPayload(progress.loadedChunks(),
                progress.totalChunks(), progress.scannedBlocks(), progress.totalBlocks(), progress.brokenBlocks(),
                progress.foundOres(), progress.targetBlocks(), progress.processedBlocks(), progress.oreBreakdown(),
                progress.state().ordinal()));
    }

    static void requestConfigScreen() {
        PacketDistributor.sendToServer(new ConfigRequestPayload());
    }

    public static void sendConfigUpdate(ConfigUpdatePayload payload) {
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

        Config.MAX_NORMAL_BLOCKS.set(Mth.clamp(payload.maxNormalBlocks(),
                Config.MIN_CHAIN_BLOCKS, Config.MAX_CHAIN_BLOCKS));
        Config.MAX_NORMAL_BLOCKS_PER_TICK.set(Mth.clamp(payload.maxNormalBlocksPerTick(), 1, Config.MAX_BLOCKS_PER_TICK));
        Config.MAX_BLAST_BLOCKS.set(Mth.clamp(payload.maxBlastBlocks(),
                Config.MIN_CHAIN_BLOCKS, Config.MAX_CHAIN_BLOCKS));
        Config.MAX_BLAST_BLOCKS_PER_TICK.set(Mth.clamp(payload.maxBlastBlocksPerTick(), 1, Config.MAX_BLOCKS_PER_TICK));
        Config.BLAST_SEARCH_DISTANCE.set(Mth.clamp(payload.blastSearchDistance(), 3, 128));
        Config.NO_HUNGER_COST.set(payload.noHungerCost());
        Config.STORE_DROPS_IN_AE.set(payload.storeDropsInAe());
        Config.ENABLE_PERFORMANCE_LOG.set(payload.enablePerformanceLog());
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

    public record FastMineSelectionPayload(ResourceLocation dimension, int firstChunkX, int firstChunkZ,
            int secondChunkX, int secondChunkZ) implements CustomPacketPayload {
        public static final Type<FastMineSelectionPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "fast_mine_selection"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FastMineSelectionPayload> STREAM_CODEC =
                StreamCodec.of(NetworkHandler::writeFastMineSelection, NetworkHandler::readFastMineSelection);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record FastMineProgressPayload(long loadedChunks, long totalChunks, long scannedBlocks, long totalBlocks,
            long brokenBlocks, long foundOres, long targetBlocks, long processedBlocks,
            Map<ResourceLocation, Long> oreBreakdown, int state) implements CustomPacketPayload {
        public static final Type<FastMineProgressPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "fast_mine_progress"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FastMineProgressPayload> STREAM_CODEC =
                StreamCodec.of(NetworkHandler::writeFastMineProgress, NetworkHandler::readFastMineProgress);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record FastMineCancelPayload() implements CustomPacketPayload {
        public static final Type<FastMineCancelPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "fast_mine_cancel"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FastMineCancelPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> {}, buffer -> new FastMineCancelPayload());

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
            boolean noHungerCost, boolean storeDropsInAe, boolean enablePerformanceLog) implements CustomPacketPayload {
        public static final Type<ConfigSnapshotPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "config_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSnapshotPayload> STREAM_CODEC = StreamCodec.of(
                NetworkHandler::writeConfigSnapshot, NetworkHandler::readConfigSnapshot);

        static ConfigSnapshotPayload current() {
            return new ConfigSnapshotPayload(Config.MAX_NORMAL_BLOCKS.getAsInt(),
                    Config.MAX_NORMAL_BLOCKS_PER_TICK.getAsInt(), Config.MAX_BLAST_BLOCKS.getAsInt(),
                    Config.MAX_BLAST_BLOCKS_PER_TICK.getAsInt(), Config.BLAST_SEARCH_DISTANCE.getAsInt(),
                    Config.NO_HUNGER_COST.getAsBoolean(), Config.STORE_DROPS_IN_AE.getAsBoolean(),
                    Config.ENABLE_PERFORMANCE_LOG.getAsBoolean());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ConfigUpdatePayload(int maxNormalBlocks, int maxNormalBlocksPerTick,
            int maxBlastBlocks, int maxBlastBlocksPerTick, int blastSearchDistance,
            boolean noHungerCost, boolean storeDropsInAe, boolean enablePerformanceLog) implements CustomPacketPayload {
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
                payload.maxBlastBlocksPerTick(), payload.blastSearchDistance(), payload.noHungerCost(),
                payload.storeDropsInAe(), payload.enablePerformanceLog());
    }

    private static void writeFastMineSelection(RegistryFriendlyByteBuf buffer, FastMineSelectionPayload payload) {
        ResourceLocation.STREAM_CODEC.encode(buffer, payload.dimension());
        buffer.writeInt(payload.firstChunkX());
        buffer.writeInt(payload.firstChunkZ());
        buffer.writeInt(payload.secondChunkX());
        buffer.writeInt(payload.secondChunkZ());
    }

    private static FastMineSelectionPayload readFastMineSelection(RegistryFriendlyByteBuf buffer) {
        return new FastMineSelectionPayload(ResourceLocation.STREAM_CODEC.decode(buffer), buffer.readInt(),
                buffer.readInt(), buffer.readInt(), buffer.readInt());
    }

    private static void writeFastMineProgress(RegistryFriendlyByteBuf buffer, FastMineProgressPayload payload) {
        buffer.writeVarLong(payload.loadedChunks());
        buffer.writeVarLong(payload.totalChunks());
        buffer.writeVarLong(payload.scannedBlocks());
        buffer.writeVarLong(payload.totalBlocks());
        buffer.writeVarLong(payload.brokenBlocks());
        buffer.writeVarLong(payload.foundOres());
        buffer.writeVarLong(payload.targetBlocks());
        buffer.writeVarLong(payload.processedBlocks());
        buffer.writeVarInt(Math.min(128, payload.oreBreakdown().size()));
        int written = 0;
        for (Map.Entry<ResourceLocation, Long> entry : payload.oreBreakdown().entrySet()) {
            if (written++ >= 128) break;
            ResourceLocation.STREAM_CODEC.encode(buffer, entry.getKey());
            buffer.writeVarLong(entry.getValue());
        }
        buffer.writeVarInt(payload.state());
    }

    private static FastMineProgressPayload readFastMineProgress(RegistryFriendlyByteBuf buffer) {
        // Keep the read order identical to writeFastMineProgress.  The payload is
        // length-prefixed only after the scalar counters; reading the ore count
        // first shifts every field and makes the client render a frozen/invalid UI.
        long loadedChunks = buffer.readVarLong();
        long totalChunks = buffer.readVarLong();
        long scannedBlocks = buffer.readVarLong();
        long totalBlocks = buffer.readVarLong();
        long brokenBlocks = buffer.readVarLong();
        long foundOres = buffer.readVarLong();
        long targetBlocks = buffer.readVarLong();
        long processedBlocks = buffer.readVarLong();
        Map<ResourceLocation, Long> ores = new LinkedHashMap<>();
        int oreCount = Mth.clamp(buffer.readVarInt(), 0, 128);
        for (int i = 0; i < oreCount; i++) {
            ores.put(ResourceLocation.STREAM_CODEC.decode(buffer), buffer.readVarLong());
        }
        return new FastMineProgressPayload(loadedChunks, totalChunks, scannedBlocks, totalBlocks,
                brokenBlocks, foundOres, targetBlocks, processedBlocks, ores, buffer.readVarInt());
    }

    private static ConfigSnapshotPayload readConfigSnapshot(RegistryFriendlyByteBuf buffer) {
        return new ConfigSnapshotPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
    }

    private static void writeConfigUpdate(RegistryFriendlyByteBuf buffer, ConfigUpdatePayload payload) {
        writeConfig(buffer, payload.maxNormalBlocks(), payload.maxNormalBlocksPerTick(), payload.maxBlastBlocks(),
                payload.maxBlastBlocksPerTick(), payload.blastSearchDistance(), payload.noHungerCost(),
                payload.storeDropsInAe(), payload.enablePerformanceLog());
    }

    private static ConfigUpdatePayload readConfigUpdate(RegistryFriendlyByteBuf buffer) {
        return new ConfigUpdatePayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
    }

    private static void writeConfig(RegistryFriendlyByteBuf buffer, int maxNormalBlocks,
            int maxNormalBlocksPerTick, int maxBlastBlocks, int maxBlastBlocksPerTick,
            int blastSearchDistance, boolean noHungerCost, boolean storeDropsInAe, boolean enablePerformanceLog) {
        buffer.writeVarInt(maxNormalBlocks);
        buffer.writeVarInt(maxNormalBlocksPerTick);
        buffer.writeVarInt(maxBlastBlocks);
        buffer.writeVarInt(maxBlastBlocksPerTick);
        buffer.writeVarInt(blastSearchDistance);
        buffer.writeBoolean(noHungerCost);
        buffer.writeBoolean(storeDropsInAe);
        buffer.writeBoolean(enablePerformanceLog);
    }
}
