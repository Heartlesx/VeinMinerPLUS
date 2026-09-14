package com.extrarawstyle.veinminerplus;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.items.IItemHandler;

import top.theillusivec4.curios.api.CuriosApi;

// Everything that touches Curios classes lives here, so the rest of the mod still loads without it.
final class CuriosLookup {
    private CuriosLookup() {
    }

    // The trinket slots as a plain item handler, so a worn backpack can be read like any container.
    static IItemHandler equipped(Player player) {
        return CuriosApi.getCuriosInventory(player).map(handler -> handler.getEquippedCurios())
                .orElse(null);
    }
}
