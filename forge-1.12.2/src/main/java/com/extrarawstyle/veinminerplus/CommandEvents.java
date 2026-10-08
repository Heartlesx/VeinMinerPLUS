package com.extrarawstyle.veinminerplus;

import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

// 1.12.2 has no command registration event, so the command is handed to the server while it starts.
public final class CommandEvents {
    public static void onServerStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandVeinMinerPlus());
    }

    private CommandEvents() {
    }

    public static final class CommandVeinMinerPlus extends CommandBase {
        @Override
        public String getName() {
            return "veinminerplus";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "commands.veinminerplus.usage";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 2;
        }

        @Override
        public List<String> getAliases() {
            return Collections.emptyList();
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length != 1 || !"gui".equals(args[0])) {
                throw new CommandException("commands.veinminerplus.usage");
            }
            if (!(sender.getCommandSenderEntity() instanceof EntityPlayerMP)) {
                sender.sendMessage(new TextComponentTranslation("message.veinminerplus.config_player_only"));
                return;
            }
            NetworkHandler.openConfigScreen((EntityPlayerMP) sender.getCommandSenderEntity());
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                BlockPos targetPos) {
            return args.length == 1 ? getListOfStringsMatchingLastWord(args, "gui") : Collections.emptyList();
        }
    }
}
