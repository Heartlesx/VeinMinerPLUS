package com.extrarawstyle.veinminerplus.dispatchharness;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import dev.architectury.event.EventFactory;
import dev.architectury.event.PackageListener;

/**
 * Proves that EventFactory's original helper has package access that a VMP-side lookup does not.
 *
 * Run from this directory with the Architectury jar on the class path:
 * javac -cp <architectury-jar> -d out lookup/PackageListener.java lookup/LookupContextHarness.java
 * java -cp <architectury-jar>;out com.extrarawstyle.veinminerplus.dispatchharness.LookupContextHarness
 */
public final class LookupContextHarness {
    private LookupContextHarness() {
    }

    public static void main(String[] args) throws Throwable {
        PackageListener listener = new PackageListener();
        Method method = PackageListener.class.getDeclaredMethod("packageVisible", String.class);

        Object original = invokeOriginalHelper(listener, method, new Object[] {"value"});
        expect("EventFactory helper keeps package access", "ok:value", original);

        Throwable redirectedFailure = null;
        try {
            MethodHandles.lookup().unreflect(method).bindTo(listener).invokeWithArguments("value");
        } catch (Throwable failure) {
            redirectedFailure = failure;
        }
        expect("VMP-side lookup does not keep EventFactory package access", true,
                redirectedFailure instanceof IllegalAccessException);
    }

    private static Object invokeOriginalHelper(Object listener, Method method, Object[] args)
            throws Throwable {
        Method helper = EventFactory.class.getDeclaredMethod("invokeMethod", Object.class,
                Method.class, Object[].class);
        helper.setAccessible(true);
        try {
            return helper.invoke(null, listener, method, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static void expect(String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
        System.out.println("ok   " + label);
    }
}
