package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

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
        registrar.playToServer(BlacklistTogglePayload.TYPE, BlacklistTogglePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        handleBlacklistToggle(player, payload.pos());
                    }
                }));
        registrar.playToClient(BlacklistSyncPayload.TYPE, BlacklistSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> BlockBlacklist.applyRemote(payload.entries())));
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

    static void sendBlacklistToggle(BlockPos pos) {
        PacketDistributor.sendToServer(new BlacklistTogglePayload(pos));
    }

    static void sendBlacklistSync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new BlacklistSyncPayload(currentBlacklist()));
    }

    private static List<String> currentBlacklist() {
        return List.copyOf(Config.BLOCK_BLACKLIST.get());
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
        Config.STORAGE_BINDING.set(payload.storageBinding());
        Config.BLOCK_BLACKLIST.set(sanitizeBlacklist(payload.blacklist()));
        Config.SPEC.save();
        BlockBlacklist.applyServerConfig();
        PacketDistributor.sendToAllPlayers(new BlacklistSyncPayload(currentBlacklist()));
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "message.veinminerplus.config_saved"), false);
    }

    // The hotkey path: the client sends the block it aims at, the server revalidates everything
    // (permission, sneak posture, distance) before it touches the shared config.
    private static void handleBlacklistToggle(ServerPlayer player, BlockPos pos) {
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
        PacketDistributor.sendToAllPlayers(new BlacklistSyncPayload(currentBlacklist()));
        player.displayClientMessage(Component.translatable(
                added ? "message.veinminerplus.blacklist.added" : "message.veinminerplus.blacklist.removed",
                state.getBlock().getName()), true);
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
            boolean noHungerCost, boolean storageBinding, List<String> blacklist) implements CustomPacketPayload {
        public static final Type<ConfigSnapshotPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "config_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSnapshotPayload> STREAM_CODEC = StreamCodec.of(
                NetworkHandler::writeConfigSnapshot, NetworkHandler::readConfigSnapshot);

        static ConfigSnapshotPayload current() {
            return new ConfigSnapshotPayload(Config.MAX_NORMAL_BLOCKS.getAsInt(),
                    Config.MAX_NORMAL_BLOCKS_PER_TICK.getAsInt(), Config.MAX_BLAST_BLOCKS.getAsInt(),
                    Config.MAX_BLAST_BLOCKS_PER_TICK.getAsInt(), Config.BLAST_SEARCH_DISTANCE.getAsInt(),
                    Config.NO_HUNGER_COST.getAsBoolean(), Config.STORAGE_BINDING.getAsBoolean(),
                    currentBlacklist());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ConfigUpdatePayload(int maxNormalBlocks, int maxNormalBlocksPerTick,
            int maxBlastBlocks, int maxBlastBlocksPerTick, int blastSearchDistance,
            boolean noHungerCost, boolean storageBinding, List<String> blacklist) implements CustomPacketPayload {
        public static final Type<ConfigUpdatePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "config_update"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConfigUpdatePayload> STREAM_CODEC = StreamCodec.of(
                NetworkHandler::writeConfigUpdate, NetworkHandler::readConfigUpdate);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BlacklistTogglePayload(BlockPos pos) implements CustomPacketPayload {
        public static final Type<BlacklistTogglePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "blacklist_toggle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BlacklistTogglePayload> STREAM_CODEC = StreamCodec
                .composite(BlockPos.STREAM_CODEC, BlacklistTogglePayload::pos, BlacklistTogglePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BlacklistSyncPayload(List<String> entries) implements CustomPacketPayload {
        public static final Type<BlacklistSyncPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(VeinMinerPlus.MODID, "blacklist_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BlacklistSyncPayload> STREAM_CODEC = StreamCodec.of(
                (buffer, payload) -> writeBlacklist(buffer, payload.entries()),
                buffer -> new BlacklistSyncPayload(readBlacklist(buffer)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writeConfigSnapshot(RegistryFriendlyByteBuf buffer, ConfigSnapshotPayload payload) {
        writeConfig(buffer, payload.maxNormalBlocks(), payload.maxNormalBlocksPerTick(), payload.maxBlastBlocks(),
                payload.maxBlastBlocksPerTick(), payload.blastSearchDistance(), payload.noHungerCost(),
                payload.storageBinding(), payload.blacklist());
    }

    private static ConfigSnapshotPayload readConfigSnapshot(RegistryFriendlyByteBuf buffer) {
        return new ConfigSnapshotPayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(),
                readBlacklist(buffer));
    }

    private static void writeConfigUpdate(RegistryFriendlyByteBuf buffer, ConfigUpdatePayload payload) {
        writeConfig(buffer, payload.maxNormalBlocks(), payload.maxNormalBlocksPerTick(), payload.maxBlastBlocks(),
                payload.maxBlastBlocksPerTick(), payload.blastSearchDistance(), payload.noHungerCost(),
                payload.storageBinding(), payload.blacklist());
    }

    private static ConfigUpdatePayload readConfigUpdate(RegistryFriendlyByteBuf buffer) {
        return new ConfigUpdatePayload(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(),
                readBlacklist(buffer));
    }

    private static void writeConfig(RegistryFriendlyByteBuf buffer, int maxNormalBlocks,
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

    private static void writeBlacklist(RegistryFriendlyByteBuf buffer, List<String> entries) {
        int size = Math.min(entries.size(), BlockBlacklist.MAX_ENTRIES);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            buffer.writeUtf(entries.get(index), 128);
        }
    }

    private static List<String> readBlacklist(RegistryFriendlyByteBuf buffer) {
        int size = Mth.clamp(buffer.readVarInt(), 0, BlockBlacklist.MAX_ENTRIES);
        List<String> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(buffer.readUtf(128));
        }
        return entries;
    }
}
