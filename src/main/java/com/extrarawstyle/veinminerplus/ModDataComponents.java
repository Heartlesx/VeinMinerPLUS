package com.extrarawstyle.veinminerplus;

import java.util.function.Consumer;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(
            Registries.DATA_COMPONENT_TYPE, VeinMinerPlus.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<NetworkTarget>> AE_NETWORK_TARGET =
            register("ae_network_target", builder -> builder.persistent(NetworkTarget.CODEC)
                    .networkSynchronized(NetworkTarget.STREAM_CODEC));

    private ModDataComponents() {
    }

    public static void register(IEventBus eventBus) {
        COMPONENTS.register(eventBus);
    }

    private static <T> DeferredHolder<DataComponentType<?>, DataComponentType<T>> register(String name,
            Consumer<DataComponentType.Builder<T>> customizer) {
        DataComponentType.Builder<T> builder = DataComponentType.builder();
        customizer.accept(builder);
        return COMPONENTS.register(name, builder::build);
    }
}
