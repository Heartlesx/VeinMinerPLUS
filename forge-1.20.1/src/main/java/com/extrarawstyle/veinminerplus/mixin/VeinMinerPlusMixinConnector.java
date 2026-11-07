package com.extrarawstyle.veinminerplus.mixin;

import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

/**
 * Registers this mod's mixin config with the mixin service.
 *
 * Forge 1.20.1 does not read the {@code MixinConfigs} manifest attribute for mod jars, and the
 * {@code [[mixins]]} metadata block is a NeoForge addition. A connector is the loader-independent
 * route: the mixin service instantiates it from {@code META-INF/services} and this class adds the
 * config explicitly.
 */
public final class VeinMinerPlusMixinConnector implements IMixinConnector {

    @Override
    public void connect() {
        Mixins.addConfiguration("veinminerplus.mixins.json");
    }
}
