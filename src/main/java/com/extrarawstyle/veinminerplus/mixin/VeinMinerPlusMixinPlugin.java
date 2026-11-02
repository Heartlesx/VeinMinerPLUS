package com.extrarawstyle.veinminerplus.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Enables each compatibility mixin only when the mod it patches is present at the exact version the
 * patch was written against.
 *
 * The patches reach into other mods' internals, so a version bump there can move the injection
 * point. Gating here means an unfamiliar build keeps the unpatched behaviour instead of failing the
 * injection and taking the game down with it.
 *
 * A version that matches but still fails to inject is left to fail loudly: the mixin config sets
 * defaultRequire = 1, so a broken patch cannot silently masquerade as an applied optimisation.
 */
public final class VeinMinerPlusMixinPlugin implements IMixinConfigPlugin {

    private static final System.Logger LOG = System.getLogger("veinminerplus/mixin");

    // Mixin class simple name -> the mod id and version range it was written for.
    private static final String ARCHITECTURY_MIXIN = "ArchitecturyDispatchMixin";
    private static final String HOSTILE_MIXIN = "HostileCuriosLookupMixin";
    private static final String FLD_MIXIN = "FastLeafDecaySchedulerMixin";

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
        switch (simple) {
            case ARCHITECTURY_MIXIN -> {
                return require("architectury", "13.0.11", ARCHITECTURY_MIXIN);
            }
            case HOSTILE_MIXIN -> {
                // Both mods take part: Curios supplies the overload, Hostile makes the call.
                return require("curios", "9.5.1", HOSTILE_MIXIN)
                        && require("hostilenetworks", "6.5.1", HOSTILE_MIXIN);
            }
            case FLD_MIXIN -> {
                return require("fastleafdecay", "35", FLD_MIXIN);
            }
            default -> {
                return false;
            }
        }
    }

    // Prefix match rather than equality: loaders report versions with extra segments such as
    // "9.5.1+1.21.1" for a 9.5.1 build, and requiring an exact string silently disabled a patch.
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
        // "9.5.1+1.21.1" style: the patch was written for 9.5.1 and this build reports that base.
        return actual.startsWith(expected + "+") || actual.startsWith(expected + "-");
    }

    // Read through the loader so this works before the mod list is frozen, and returns null rather
    // than throwing when the mod is missing.
    private static String modVersion(String modId) {
        try {
            Class<?> loader = Class.forName("net.neoforged.fml.loading.LoadingModList");
            Object list = loader.getMethod("get").invoke(null);
            Object info = list.getClass().getMethod("getModFileById", String.class).invoke(list, modId);
            if (info == null) {
                return null;
            }
            Object version = info.getClass().getMethod("versionString").invoke(info);
            return version == null ? null : version.toString();
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
    }
}
