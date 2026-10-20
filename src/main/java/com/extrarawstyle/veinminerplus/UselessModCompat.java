package com.extrarawstyle.veinminerplus;

import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

// Useless Mod applies its tool modes (auto-smelt) after the vanilla drop list has been produced.
// Capturing the drops for a chain pulls them out before that step, so the same helper the tool uses
// for its own breaks is called here. The mod publishes no API artifact, so the call is reflected
// and silently skipped when a future build moves or renames the helper.
final class UselessModCompat {
    static final String MOD_ID = "useless_mod";
    private static final String HELPER = "com.sorrowmist.useless.utils.mining.AutoSmeltHelper";

    private static Method smeltDrops;
    private static boolean unavailable;

    private UselessModCompat() {
    }

    static List<ItemStack> smeltDrops(Level level, List<ItemStack> drops, ItemStack tool) {
        if (drops.isEmpty() || !ModList.get().isLoaded(MOD_ID)) {
            return drops;
        }
        Method method = resolve();
        if (method == null) {
            return drops;
        }
        try {
            @SuppressWarnings("unchecked")
            List<ItemStack> smelted = (List<ItemStack>) method.invoke(null, level, drops, tool);
            return smelted;
        } catch (ReflectiveOperationException failure) {
            return drops;
        }
    }

    private static Method resolve() {
        if (smeltDrops != null) {
            return smeltDrops;
        }
        if (unavailable) {
            return null;
        }
        try {
            smeltDrops = Class.forName(HELPER)
                    .getMethod("smeltDrops", Level.class, List.class, ItemStack.class);
            return smeltDrops;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError failure) {
            unavailable = true;
            return null;
        }
    }
}
