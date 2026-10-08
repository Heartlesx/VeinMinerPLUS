package com.extrarawstyle.veinminerplus;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class NetworkHandler {
    // Built inside register() on purpose. A static initializer would create the channel at whatever
    // moment the class happens to be loaded, which can be before FML has finished building its own
    // network registry, and the channel would then be registered against a half-built registry.
    private static SimpleNetworkWrapper channel;
    private static int packetId;

    private NetworkHandler() {
    }

    static void register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(VeinMinerPlus.MODID);
        channel.registerMessage(KeyStateHandler.class, KeyStateMessage.class, packetId++, Side.SERVER);
        channel.registerMessage(ModeChangeHandler.class, ModeChangeMessage.class, packetId++, Side.SERVER);
        channel.registerMessage(ConfigRequestHandler.class, ConfigRequestMessage.class, packetId++, Side.SERVER);
        channel.registerMessage(ConfigSnapshotHandler.class, ConfigSnapshotMessage.class, packetId++, Side.CLIENT);
        channel.registerMessage(ConfigUpdateHandler.class, ConfigUpdateMessage.class, packetId++, Side.SERVER);
    }

    static void sendKeyState(boolean held) {
        channel.sendToServer(new KeyStateMessage(held));
    }

    static void sendModeChange(ChainMode mode) {
        channel.sendToServer(new ModeChangeMessage(mode.ordinal()));
    }

    static void requestConfigScreen() {
        channel.sendToServer(new ConfigRequestMessage());
    }

    static void openConfigScreen(EntityPlayerMP player) {
        channel.sendTo(ConfigSnapshotMessage.current(), player);
    }

    static void sendConfigUpdate(int maxNormalBlocks, int maxNormalBlocksPerTick, int maxBlastBlocks,
            int maxBlastBlocksPerTick, int blastSearchDistance, boolean noHungerCost, boolean storageBinding) {
        channel.sendToServer(new ConfigUpdateMessage(maxNormalBlocks, maxNormalBlocksPerTick, maxBlastBlocks,
                maxBlastBlocksPerTick, blastSearchDistance, noHungerCost, storageBinding));
    }

    private static void applyConfig(EntityPlayerMP player, int maxNormalBlocks, int maxNormalBlocksPerTick,
            int maxBlastBlocks, int maxBlastBlocksPerTick, int blastSearchDistance, boolean noHungerCost,
            boolean storageBinding) {
        Config.maxNormalBlocks = clamp(maxNormalBlocks, 32, 32767);
        Config.maxNormalBlocksPerTick = clamp(maxNormalBlocksPerTick, 1, 384);
        Config.maxBlastBlocks = clamp(maxBlastBlocks, 32, 32767);
        Config.maxBlastBlocksPerTick = clamp(maxBlastBlocksPerTick, 1, 512);
        Config.blastSearchDistance = clamp(blastSearchDistance, 3, 128);
        Config.noHungerCost = noHungerCost;
        Config.storageBinding = storageBinding;
        Config.save();
        player.sendStatusMessage(new TextComponentTranslation("message.veinminerplus.config_saved"), false);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return value < minimum ? minimum : Math.min(value, maximum);
    }

    private static boolean isOperator(EntityPlayerMP player) {
        return player.canUseCommand(2, "veinminerplus");
    }

    public static class KeyStateMessage implements IMessage {
        private boolean held;

        public KeyStateMessage() {
        }

        private KeyStateMessage(boolean held) {
            this.held = held;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            held = buffer.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeBoolean(held);
        }
    }

    public static class KeyStateHandler implements IMessageHandler<KeyStateMessage, IMessage> {
        @Override
        public IMessage onMessage(KeyStateMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            if (player == null) {
                return null;
            }
            boolean held = message.held;
            player.server.addScheduledTask(() -> ChainEvents.setKeyHeld(player, held));
            return null;
        }
    }

    public static class ModeChangeMessage implements IMessage {
        private int mode;

        public ModeChangeMessage() {
        }

        private ModeChangeMessage(int mode) {
            this.mode = mode;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            mode = buffer.readInt();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeInt(mode);
        }
    }

    public static class ModeChangeHandler implements IMessageHandler<ModeChangeMessage, IMessage> {
        @Override
        public IMessage onMessage(ModeChangeMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            if (player == null) {
                return null;
            }
            int mode = message.mode;
            player.server.addScheduledTask(() -> ChainEvents.setMode(player, mode));
            return null;
        }
    }

    public static class ConfigRequestMessage implements IMessage {
        @Override
        public void fromBytes(ByteBuf buffer) {
        }

        @Override
        public void toBytes(ByteBuf buffer) {
        }
    }

    public static class ConfigRequestHandler implements IMessageHandler<ConfigRequestMessage, IMessage> {
        @Override
        public IMessage onMessage(ConfigRequestMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            if (player == null) {
                return null;
            }
            player.server.addScheduledTask(() -> {
                if (isOperator(player)) {
                    openConfigScreen(player);
                } else {
                    player.sendStatusMessage(
                            new TextComponentTranslation("message.veinminerplus.config_permission"), true);
                }
            });
            return null;
        }
    }

    public static class ConfigSnapshotMessage implements IMessage {
        int maxNormalBlocks;
        int maxNormalBlocksPerTick;
        int maxBlastBlocks;
        int maxBlastBlocksPerTick;
        int blastSearchDistance;
        boolean noHungerCost;
        boolean storageBinding;

        public ConfigSnapshotMessage() {
        }

        private ConfigSnapshotMessage(int maxNormalBlocks, int maxNormalBlocksPerTick, int maxBlastBlocks,
                int maxBlastBlocksPerTick, int blastSearchDistance, boolean noHungerCost,
                boolean storageBinding) {
            this.maxNormalBlocks = maxNormalBlocks;
            this.maxNormalBlocksPerTick = maxNormalBlocksPerTick;
            this.maxBlastBlocks = maxBlastBlocks;
            this.maxBlastBlocksPerTick = maxBlastBlocksPerTick;
            this.blastSearchDistance = blastSearchDistance;
            this.noHungerCost = noHungerCost;
            this.storageBinding = storageBinding;
        }

        static ConfigSnapshotMessage current() {
            return new ConfigSnapshotMessage(Config.maxNormalBlocks, Config.maxNormalBlocksPerTick,
                    Config.maxBlastBlocks, Config.maxBlastBlocksPerTick, Config.blastSearchDistance,
                    Config.noHungerCost, Config.storageBinding);
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            maxNormalBlocks = buffer.readInt();
            maxNormalBlocksPerTick = buffer.readInt();
            maxBlastBlocks = buffer.readInt();
            maxBlastBlocksPerTick = buffer.readInt();
            blastSearchDistance = buffer.readInt();
            noHungerCost = buffer.readBoolean();
            storageBinding = buffer.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeInt(maxNormalBlocks);
            buffer.writeInt(maxNormalBlocksPerTick);
            buffer.writeInt(maxBlastBlocks);
            buffer.writeInt(maxBlastBlocksPerTick);
            buffer.writeInt(blastSearchDistance);
            buffer.writeBoolean(noHungerCost);
            buffer.writeBoolean(storageBinding);
        }
    }

    public static class ConfigSnapshotHandler implements IMessageHandler<ConfigSnapshotMessage, IMessage> {
        @Override
        public IMessage onMessage(ConfigSnapshotMessage message, MessageContext context) {
            VeinMinerPlusClient.openConfigScreen(message);
            return null;
        }
    }

    public static class ConfigUpdateMessage implements IMessage {
        private int maxNormalBlocks;
        private int maxNormalBlocksPerTick;
        private int maxBlastBlocks;
        private int maxBlastBlocksPerTick;
        private int blastSearchDistance;
        private boolean noHungerCost;
        private boolean storageBinding;

        public ConfigUpdateMessage() {
        }

        ConfigUpdateMessage(int maxNormalBlocks, int maxNormalBlocksPerTick, int maxBlastBlocks,
                int maxBlastBlocksPerTick, int blastSearchDistance, boolean noHungerCost,
                boolean storageBinding) {
            this.maxNormalBlocks = maxNormalBlocks;
            this.maxNormalBlocksPerTick = maxNormalBlocksPerTick;
            this.maxBlastBlocks = maxBlastBlocks;
            this.maxBlastBlocksPerTick = maxBlastBlocksPerTick;
            this.blastSearchDistance = blastSearchDistance;
            this.noHungerCost = noHungerCost;
            this.storageBinding = storageBinding;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            maxNormalBlocks = buffer.readInt();
            maxNormalBlocksPerTick = buffer.readInt();
            maxBlastBlocks = buffer.readInt();
            maxBlastBlocksPerTick = buffer.readInt();
            blastSearchDistance = buffer.readInt();
            noHungerCost = buffer.readBoolean();
            storageBinding = buffer.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeInt(maxNormalBlocks);
            buffer.writeInt(maxNormalBlocksPerTick);
            buffer.writeInt(maxBlastBlocks);
            buffer.writeInt(maxBlastBlocksPerTick);
            buffer.writeInt(blastSearchDistance);
            buffer.writeBoolean(noHungerCost);
            buffer.writeBoolean(storageBinding);
        }
    }

    public static class ConfigUpdateHandler implements IMessageHandler<ConfigUpdateMessage, IMessage> {
        @Override
        public IMessage onMessage(ConfigUpdateMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            if (player == null) {
                return null;
            }
            player.server.addScheduledTask(() -> {
                if (!isOperator(player)) {
                    return;
                }
                applyConfig(player, message.maxNormalBlocks, message.maxNormalBlocksPerTick,
                        message.maxBlastBlocks, message.maxBlastBlocksPerTick, message.blastSearchDistance,
                        message.noHungerCost, message.storageBinding);
            });
            return null;
        }
    }
}
