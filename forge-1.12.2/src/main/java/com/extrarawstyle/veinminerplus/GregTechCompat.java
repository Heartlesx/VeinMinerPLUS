package com.extrarawstyle.veinminerplus;

import gregtech.api.util.IBlockOre;

import net.minecraft.block.state.IBlockState;

// Everything that touches GregTech classes lives in this one class, so the rest of the mod still
// loads when GregTech is not installed.
final class GregTechCompat {
    private GregTechCompat() {
    }

    // GregTech marks its own ore blocks, including the ones for granite, diorite and the other
    // stone variants that do not all reach the ore dictionary.
    static boolean isOreBlock(IBlockState state) {
        return state.getBlock() instanceof IBlockOre;
    }
}
