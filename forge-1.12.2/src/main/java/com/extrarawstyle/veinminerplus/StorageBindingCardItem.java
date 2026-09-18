package com.extrarawstyle.veinminerplus;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

public class StorageBindingCardItem extends Item {
    // Sneaking in the air clears the binding. Binding a target is handled by StorageBindingEvents,
    // because blocks like the ME controller would otherwise swallow the click to open their own screen.
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (hand != EnumHand.MAIN_HAND || !player.isSneaking()
                || isAimingAtBlock(world, player)
                || StorageBindings.read(stack).isEmpty()) {
            return new ActionResult<>(EnumActionResult.PASS, stack);
        }
        if (!world.isRemote) {
            StorageBindings.write(stack, StorageBindings.EMPTY);
            player.sendStatusMessage(new TextComponentTranslation("message.veinminerplus.binder.cleared"), true);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    // A block that does not consume the interaction makes the client send a right click in the air as
    // well, so this path also runs when a block was clicked. Clearing must not fire in that case.
    private static boolean isAimingAtBlock(World world, EntityPlayer player) {
        Vec3d eye = player.getPositionEyes(1.0F);
        double reach = player.getEntityAttribute(EntityPlayer.REACH_DISTANCE).getAttributeValue();
        Vec3d end = eye.add(player.getLookVec().x * reach, player.getLookVec().y * reach,
                player.getLookVec().z * reach);
        return world.rayTraceBlocks(eye, end, false, true, false) != null
                && world.rayTraceBlocks(eye, end, false, true, false).typeOfHit == RayTraceResult.Type.BLOCK;
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, net.minecraft.client.util.ITooltipFlag flag) {
        for (ITextComponent line : StorageBindings.read(stack).describe()) {
            tooltip.add(TextFormatting.GRAY + line.getUnformattedText());
        }
        tooltip.add(TextFormatting.DARK_GRAY
                + new TextComponentTranslation("item.veinminerplus.storage_binder.hint").getUnformattedText());
    }
}
