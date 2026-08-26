package com.extrarawstyle.veinminerplus;

import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
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
    }

    static void sendKeyState(boolean held) {
        CHANNEL.sendToServer(new KeyStatePayload(held));
    }

    static void sendModeChange(ChainMode mode) {
        CHANNEL.sendToServer(new ModeChangePayload(mode.ordinal()));
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

    private record KeyStatePayload(boolean held) {
    }

    private record ModeChangePayload(int mode) {
    }
}
