package com.extrarawstyle.veinminerplus.compat;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.utils.value.IntValue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Direct dispatch for Architectury's {@code BlockEvent.BREAK} listeners.
 *
 * Architectury 13.0.11 prepares every listener invocation through
 * {@code MethodHandles.lookup().unreflect(method).bindTo(listener)}. That preparation repeats once
 * per listener per block, and across a 512-block tick it cost more than the listener bodies.
 *
 * Only the known break signature is called directly. Anything else - another event interface, an
 * unexpected signature, or a listener that is not a {@code BlockEvent.Break} - falls back to
 * Architectury's own reflective path, so unrelated events keep their behaviour.
 *
 * Semantics preserved deliberately:
 * - The argument array is unpacked positionally, so every listener receives the same
 *   {@code IntValue} instance and in-place experience edits stay visible to later listeners.
 * - A listener exception is rethrown unchanged. Architectury's reflected call reports it wrapped in
 *   {@code InvocationTargetException}, which Guava's handler unwraps again, so the observable
 *   throwable is the same either way.
 * - Nothing is cached across calls; the listener and arguments come from Architectury each time.
 */
public final class ArchitecturyDispatch {

    private ArchitecturyDispatch() {
    }

    public static Object invoke(Object listener, Method method, Object[] args) throws Throwable {
        if (listener instanceof BlockEvent.Break breakListener && args.length == 5
                && args[0] instanceof Level level && args[1] instanceof BlockPos pos
                && args[2] instanceof BlockState state && args[3] instanceof ServerPlayer player
                && args[4] instanceof IntValue experience) {
            return breakListener.breakBlock(level, pos, state, player, experience);
        }
        return MethodHandles.lookup().unreflect(method).bindTo(listener).invokeWithArguments(args);
    }
}
