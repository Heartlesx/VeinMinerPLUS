package com.extrarawstyle.veinminerplus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

public record NetworkTarget(ResourceLocation dimension, BlockPos pos, Direction side) {
    public static final Codec<NetworkTarget> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(NetworkTarget::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(NetworkTarget::pos),
            Direction.CODEC.fieldOf("side").forGetter(NetworkTarget::side))
            .apply(instance, NetworkTarget::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkTarget> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, NetworkTarget::dimension,
            BlockPos.STREAM_CODEC, NetworkTarget::pos,
            Direction.STREAM_CODEC, NetworkTarget::side,
            NetworkTarget::new);

    public NetworkTarget {
        pos = pos.immutable();
    }
}
