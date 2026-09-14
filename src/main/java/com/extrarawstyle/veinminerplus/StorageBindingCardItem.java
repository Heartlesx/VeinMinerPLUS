package com.extrarawstyle.veinminerplus;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class StorageBindingCardItem extends Item {
    StorageBindingCardItem(Item.Properties properties) {
        super(properties);
    }

    // Sneaking in the air clears every binding. Binding a target is handled by StorageBindingEvents,
    // because blocks like the ME controller would otherwise swallow the click to open their own screen.
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()
                || isAimingAtBlock(level, player)
                || StorageBindings.read(stack).isEmpty()) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            StorageBindings.write(stack, StorageBindings.EMPTY);
            serverPlayer.displayClientMessage(Component.translatable("message.veinminerplus.binder.cleared"), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    // A block that does not consume the interaction makes the client send a right click in the air as
    // well, so this path also runs when a block was clicked. Clearing must not fire in that case.
    private static boolean isAimingAtBlock(Level level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
        return level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
                .getType() != HitResult.Type.MISS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        for (Component line : StorageBindings.read(stack).describe()) {
            tooltip.add(line.copy().withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("item.veinminerplus.storage_binder.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
