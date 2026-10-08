package com.extrarawstyle.veinminerplus;

import java.lang.reflect.Method;
import java.util.Optional;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.items.IItemHandler;


// Everything that touches Curios classes lives here, so the rest of the mod still loads without it.
final class CuriosLookup {
    private static volatile boolean initialized;
    private static Method getCuriosInventory;
    private static Method getEquippedCurios;

    private CuriosLookup() {
    }

    // The trinket slots as a plain item handler, so a worn backpack can be read like any container.
    static IItemHandler equipped(Player player) {
        try {
            initialize();
            if (getCuriosInventory == null) return null;
            Optional<?> optional = (Optional<?>) getCuriosInventory.invoke(null, player);
            Object inventory = optional.orElse(null);
            if (inventory == null) return null;
            if (getEquippedCurios == null) {
                synchronized (CuriosLookup.class) {
                    if (getEquippedCurios == null) {
                        getEquippedCurios = inventory.getClass().getMethod("getEquippedCurios");
                    }
                }
            }
            return (IItemHandler) getEquippedCurios.invoke(inventory);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError ignored) {
            return null;
        }
    }

    private static void initialize() throws ReflectiveOperationException {
        if (initialized) return;
        synchronized (CuriosLookup.class) {
            if (initialized) return;
            try {
                Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
                // Curios exposes the lookup for every LivingEntity, not specifically Player.
                // Looking up the narrower parameter silently made equipped backpacks invisible.
                getCuriosInventory = api.getMethod("getCuriosInventory", LivingEntity.class);
            } catch (ClassNotFoundException e) {
                getCuriosInventory = null;
                getEquippedCurios = null;
            } finally {
                initialized = true;
            }
        }
    }
}
