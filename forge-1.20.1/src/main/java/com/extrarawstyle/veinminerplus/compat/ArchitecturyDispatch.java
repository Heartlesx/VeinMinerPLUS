package com.extrarawstyle.veinminerplus.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import dev.architectury.event.EventFactory;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.utils.value.IntValue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Direct dispatch for the two Architectury listeners a chain spends most of its time in.
 *
 * Architectury 9.2.14 prepares every listener call through
 * {@code MethodHandles.lookup().unreflect(method).bindTo(listener).invokeWithArguments(args)}. That
 * preparation repeats once per listener per block, and across a 512-block tick it costs more than
 * the listener bodies themselves.
 *
 * Only these two exact signatures are called directly:
 * - {@code BlockEvent.Break.breakBlock(Level, BlockPos, BlockState, ServerPlayer, IntValue)}
 * - {@code EntityEvent.Add.add(Entity, Level)}
 *
 * Everything else keeps Architectury's own reflective path. The check is on the method's declaring
 * class and name, not on "the listener implements Break", so a listener that happens to implement
 * several interfaces still gets its other methods routed reflectively.
 *
 * Behaviour that has to survive, and does:
 * - Arguments are unpacked positionally, so every listener receives the same {@code IntValue} and
 *   in-place experience edits stay visible to the listeners after it.
 * - A listener exception propagates as the same object. Architectury's reflected call reports it
 *   wrapped in {@code InvocationTargetException}, which Guava's proxy handler unwraps again, so what
 *   reaches the caller does not change.
 * - Nothing is cached between calls: the listener and the arguments come from Architectury each
 *   time, so registration, unregistration and listener reuse behave exactly as before.
 */
public final class ArchitecturyDispatch {

    private static final MethodHandle ORIGINAL_INVOKE = findOriginalInvoker();

    private ArchitecturyDispatch() {
    }

    /**
     * Called in place of {@code EventFactory.invokeMethod}.
     *
     * The branch is chosen by whether the signature matches, never by what the listener returned.
     * A matched listener's result is returned as-is, including {@code null}: the original dispatch
     * loop calls {@code Objects.requireNonNull} on it, and letting that reject a null is the correct
     * behaviour. Using null as both "no match" and "matched, returned null" would call the listener a
     * second time reflectively - changing how many times its side effects ran and possibly hiding
     * the illegal null behind a successful second call.
     */
    public static Object invoke(Object listener, Method method, Object[] args) throws Throwable {
        if (isBreakCall(listener, method, args)) {
            return ((BlockEvent.Break) listener).breakBlock(
                    (Level) args[0], (BlockPos) args[1], (BlockState) args[2],
                    (ServerPlayer) args[3], (IntValue) args[4]);
        }
        if (isAddCall(listener, method, args)) {
            return ((EntityEvent.Add) listener).add((Entity) args[0], (Level) args[1]);
        }
        // Call the original private helper itself. Repeating its expression from this class would
        // use VMP's lookup context and could lose EventFactory's package access for an otherwise
        // valid listener method.
        return (Object) ORIGINAL_INVOKE.invokeExact(listener, method, args);
    }

    private static MethodHandle findOriginalInvoker() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(EventFactory.class,
                    MethodHandles.lookup());
            return lookup.findStatic(EventFactory.class, "invokeMethod",
                    MethodType.methodType(Object.class, Object.class, Method.class, Object[].class));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /**
     * Whether this call is exactly {@code BlockEvent.Break.breakBlock}.
     *
     * The check is on the method's declaring class and name, not on "the listener implements Break",
     * so a listener that happens to implement several interfaces still routes its other methods
     * reflectively.
     */
    private static boolean isBreakCall(Object listener, Method method, Object[] args) {
        return listener instanceof BlockEvent.Break
                && method.getDeclaringClass() == BlockEvent.Break.class
                && "breakBlock".equals(method.getName())
                && args.length == 5
                && args[0] instanceof Level
                && args[1] instanceof BlockPos
                && args[2] instanceof BlockState
                && args[3] instanceof ServerPlayer
                && args[4] instanceof IntValue;
    }

    /** Whether this call is exactly {@code EntityEvent.Add.add}. */
    private static boolean isAddCall(Object listener, Method method, Object[] args) {
        return listener instanceof EntityEvent.Add
                && method.getDeclaringClass() == EntityEvent.Add.class
                && "add".equals(method.getName())
                && args.length == 2
                && args[0] instanceof Entity
                && args[1] instanceof Level;
    }
}
