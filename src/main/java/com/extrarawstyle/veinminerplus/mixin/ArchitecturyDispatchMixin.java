package com.extrarawstyle.veinminerplus.mixin;

import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.extrarawstyle.veinminerplus.compat.ArchitecturyDispatch;

import dev.architectury.event.EventFactory;

/**
 * Replaces the reflective per-listener call in Architectury's result-event dispatch with a direct
 * interface call.
 *
 * Architectury 13.0.11's {@code EventFactory$2.handleInvocation} resolves every registered listener
 * through {@code EventFactory.invokeMethod}, which allocates a fresh {@code MethodHandles.lookup()},
 * unreflects the same {@code Method} and binds it again for every block of a chain. A spark profile
 * of one real blast put that preparation cost above the listeners' own work.
 *
 * Only the dispatch call is redirected. The listener list, registration and unbinding, the
 * {@code requireNonNull} result check, {@code interruptsFurtherEvaluation()} and the final
 * {@code pass()} stay exactly as Architectury wrote them, so cancellation order and semantics do
 * not move. Anything {@link ArchitecturyDispatch} does not recognise keeps the original reflection
 * path.
 */
@Mixin(targets = "dev.architectury.event.EventFactory$2", remap = false)
public abstract class ArchitecturyDispatchMixin {

    @Redirect(
            method = "handleInvocation",
            at = @At(value = "INVOKE",
                    target = "Ldev/architectury/event/EventFactory;invokeMethod(Ljava/lang/Object;"
                            + "Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;"),
            remap = false)
    private Object veinminerplus$dispatchDirect(Object listener, Method method, Object[] args) throws Throwable {
        return ArchitecturyDispatch.invoke(listener, method, args);
    }
}
