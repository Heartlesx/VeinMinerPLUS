package com.extrarawstyle.veinminerplus.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.extrarawstyle.veinminerplus.compat.RemoveObservation;

/**
 * Enables each patch, and gates the two that reach into other mods on their exact versions.
 *
 * The removal observation is not optional: it is the only place that sees what vanilla's removal
 * call really returned, so the chain counter depends on it. It is applied unconditionally - there is
 * no target mod and no version to gate on.
 *
 * The compatibility patches are different. They are written against another mod's bytecode, so a
 * version bump there can move the injection point. Gating means an unfamiliar build keeps the
 * unpatched behaviour instead of failing the injection and taking the game down with it. A version
 * that matches but still fails to inject is left to fail loudly: the config sets
 * {@code defaultRequire = 1}, so a broken patch cannot pass as an applied optimisation.
 */
public final class VeinMinerPlusMixinPlugin implements IMixinConfigPlugin {

    private static final System.Logger LOG = System.getLogger("veinminerplus/mixin");

    private static final String OBSERVATION_MIXIN = "ServerPlayerGameModeMixin";
    private static final String ARCHITECTURY_MIXIN = "ArchitecturyDispatchMixin";
    private static final String DELIGHTFUL_MIXIN = "DelightfulEnabledConditionMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        return switch (simple) {
            case ARCHITECTURY_MIXIN -> require("architectury", "9.2.14", ARCHITECTURY_MIXIN);
            case DELIGHTFUL_MIXIN -> require("delightful", "3.7.6", DELIGHTFUL_MIXIN);
            default -> true;
        };
    }

    // Prefix match rather than equality: loaders report versions with extra segments such as
    // "3.7.6-forge" or "9.2.14+1.20.1" for a 3.7.6 / 9.2.14 build, and requiring the exact string
    // silently disabled a patch.
    private static boolean require(String modId, String expected, String mixinName) {
        String actual = modVersion(modId);
        if (actual != null && matchesVersion(actual, expected)) {
            return true;
        }
        LOG.log(System.Logger.Level.INFO, "VeinMinerPlus: {0} not applied (needs {1} {2}, found {3})",
                mixinName, modId, expected, actual == null ? "absent" : actual);
        return false;
    }

    private static boolean matchesVersion(String actual, String expected) {
        if (actual.equals(expected)) {
            return true;
        }
        return actual.startsWith(expected + "+")
                || actual.startsWith(expected + "-")
                || actual.startsWith(expected + ".");
    }

    /**
     * Reads a mod's version through the loader.
     *
     * Forge's own loading API is used rather than a mod-list lookup at runtime, because this runs
     * while mixin configs are being selected - before the mod list is frozen. A missing mod returns
     * null instead of throwing.
     */
    private static String modVersion(String modId) {
        try {
            Class<?> loader = Class.forName("net.minecraftforge.fml.loading.LoadingModList");
            Object list = loader.getMethod("get").invoke(null);
            if (list == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            List<Object> mods = (List<Object>) list.getClass().getMethod("getMods").invoke(list);
            for (Object info : mods) {
                Object id = info.getClass().getMethod("getModId").invoke(info);
                if (modId.equals(String.valueOf(id))) {
                    Object version = info.getClass().getMethod("getVersion").invoke(info);
                    return version == null ? null : version.toString();
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (OBSERVATION_MIXIN.equals(simple)) {
            // Reaching postApply means the injections were applied: a missing injection point throws
            // before this runs. This is the load-time fact the chain code checks, rather than
            // inferring it from whether a block happened to report a removal.
            RemoveObservation.markPatchApplied();
        }
        LOG.log(System.Logger.Level.INFO, "VeinMinerPlus: {0} applied to {1}",
                simple, targetClassName);
    }
}
