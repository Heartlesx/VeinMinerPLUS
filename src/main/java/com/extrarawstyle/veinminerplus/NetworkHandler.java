package com.extrarawstyle.veinminerplus;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";

    private NetworkHandler() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(KeyStatePayload.TYPE, KeyStatePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ChainEvents.setKeyHeld(context.player(), payload.held())));
        registrar.playToServer(ModeChangePayload.TYPE, ModeChangePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ChainEvents.setMode(context.player(), payload.mode())));
    }

    static void sendKeyState(boolean held) {
        PacketDistributor.sendToServer(new KeyStatePayload(held));
    }

    static void sendModeChange(ChainMode mode) {
        PacketDistributor.sendToServer(new ModeChangePayload(mode.ordinal()));
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
}
