package com.extrarawstyle.veinminerplus;

import java.util.List;

import com.extrarawstyle.veinminerplus.compat.BindingResult;
import com.extrarawstyle.veinminerplus.compat.DropStorage;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

public final class ChainMemoryCardItem extends Item {
    public ChainMemoryCardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        Level level = context.getLevel();
        if (!level.isClientSide()) {
            BindingResult binding = DropStorage.findTarget(level, context.getClickedPos(), context.getClickedFace());
            if (binding.status() == BindingResult.Status.SUCCESS && binding.target() != null) {
                context.getItemInHand().set(ModDataComponents.AE_NETWORK_TARGET.get(), binding.target());
                player.displayClientMessage(Component.translatable("message.veinminerplus.ae_bound",
                        binding.target().dimension().toString(),
                        binding.target().pos().getX(), binding.target().pos().getY(), binding.target().pos().getZ(),
                        binding.target().side().getName()), true);
            } else {
                player.displayClientMessage(Component.translatable("message.veinminerplus.ae_invalid_node"), true);
            }
        }

        // Consume alternate-use on blocks so AE devices such as drives and terminals
        // cannot open their own screen before the card has a chance to bind.
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                clearBinding(stack, player);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        if (!DropStorage.isAvailable()) {
            lines.add(Component.translatable("tooltip.veinminerplus.chain_memory_card.unavailable")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        NetworkTarget target = stack.get(ModDataComponents.AE_NETWORK_TARGET.get());
        if (target == null) {
            lines.add(Component.translatable("tooltip.veinminerplus.chain_memory_card.unbound")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        lines.add(Component.translatable("tooltip.veinminerplus.chain_memory_card.dimension",
                target.dimension().toString()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.veinminerplus.chain_memory_card.position",
                target.pos().getX(), target.pos().getY(), target.pos().getZ()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.veinminerplus.chain_memory_card.side", target.side().getName())
                .withStyle(ChatFormatting.GRAY));
    }

    public static NetworkTarget findBoundTarget(Player player) {
        NetworkTarget target = targetOf(player.getMainHandItem());
        if (target != null) {
            return target;
        }

        target = targetOf(player.getOffhandItem());
        if (target != null) {
            return target;
        }

        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            if (slot == inventory.selected) {
                continue;
            }
            target = targetOf(inventory.getItem(slot));
            if (target != null) {
                return target;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            target = targetOf(inventory.getItem(slot));
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    private static NetworkTarget targetOf(ItemStack stack) {
        if (!stack.is(ModItems.CHAIN_MEMORY_CARD.get())) {
            return null;
        }
        return stack.get(ModDataComponents.AE_NETWORK_TARGET.get());
    }

    private static void clearBinding(ItemStack stack, Player player) {
        stack.remove(ModDataComponents.AE_NETWORK_TARGET.get());
        player.displayClientMessage(Component.translatable("message.veinminerplus.ae_cleared"), true);
    }
}
