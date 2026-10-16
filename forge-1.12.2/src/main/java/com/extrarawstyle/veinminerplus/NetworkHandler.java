package com.extrarawstyle.veinminerplus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import io.netty.buffer.ByteBuf;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
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
        channel.registerMessage(ProgressHandler.class, ProgressMessage.class, packetId++, Side.CLIENT);
        channel.registerMessage(BlacklistToggleHandler.class, BlacklistToggleMessage.class, packetId++, Side.SERVER);
        channel.registerMessage(BlacklistSyncHandler.class, BlacklistSyncMessage.class, packetId++, Side.CLIENT);
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

    // The progress cannot go through the action bar: in a large pack other mods write to the same
    // overlay while the chain is running, and two writers replacing each other turn the counter
    // into a flicker. The client draws it on its own HUD instead.
    static void sendProgress(EntityPlayerMP player, int count, boolean interact) {
        channel.sendTo(new ProgressMessage(count, interact), player);
    }

    static void sendBlacklistToggle(BlockPos pos) {
        channel.sendToServer(new BlacklistToggleMessage(pos));
    }

    static void sendBlacklistSync(EntityPlayerMP player) {
        channel.sendTo(new BlacklistSyncMessage(currentBlacklist()), player);
    }

    static void sendConfigUpdate(int maxNormalBlocks, int maxNormalBlocksPerTick, int maxBlastBlocks,
            int maxBlastBlocksPerTick, int blastSearchDistance, boolean noHungerCost, boolean storageBinding,
            List<String> blacklist) {
        channel.sendToServer(new ConfigUpdateMessage(maxNormalBlocks, maxNormalBlocksPerTick, maxBlastBlocks,
                maxBlastBlocksPerTick, blastSearchDistance, noHungerCost, storageBinding, blacklist));
    }

    private static List<String> currentBlacklist() {
        return new ArrayList<>(Arrays.asList(Config.blockBlacklist));
    }

    private static void broadcastBlacklist(EntityPlayerMP source) {
        List<String> list = currentBlacklist();
        for (EntityPlayerMP target : source.server.getPlayerList().getPlayers()) {
            channel.sendTo(new BlacklistSyncMessage(list), target);
        }
    }

    private static void applyConfig(EntityPlayerMP player, int maxNormalBlocks, int maxNormalBlocksPerTick,
            int maxBlastBlocks, int maxBlastBlocksPerTick, int blastSearchDistance, boolean noHungerCost,
            boolean storageBinding, List<String> blacklist) {
        Config.maxNormalBlocks = clamp(maxNormalBlocks, Config.MIN_MAX_NORMAL_BLOCKS,
                Config.MAX_MAX_NORMAL_BLOCKS);
        Config.maxNormalBlocksPerTick = clamp(maxNormalBlocksPerTick, Config.MIN_MAX_NORMAL_BLOCKS_PER_TICK,
                Config.MAX_MAX_NORMAL_BLOCKS_PER_TICK);
        Config.maxBlastBlocks = clamp(maxBlastBlocks, Config.MIN_MAX_BLAST_BLOCKS,
                Config.MAX_MAX_BLAST_BLOCKS);
        Config.maxBlastBlocksPerTick = clamp(maxBlastBlocksPerTick, Config.MIN_MAX_BLAST_BLOCKS_PER_TICK,
                Config.MAX_MAX_BLAST_BLOCKS_PER_TICK);
        Config.blastSearchDistance = clamp(blastSearchDistance, Config.MIN_BLAST_SEARCH_DISTANCE,
                Config.MAX_BLAST_SEARCH_DISTANCE);
        Config.noHungerCost = noHungerCost;
        Config.storageBinding = storageBinding;
        Config.blockBlacklist = sanitizeBlacklist(blacklist).toArray(new String[0]);
        Config.save();
        BlockBlacklist.applyServerConfig();
        broadcastBlacklist(player);
        player.sendStatusMessage(new TextComponentTranslation("message.veinminerplus.config_saved"), false);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return value < minimum ? minimum : Math.min(value, maximum);
    }

    private static boolean isOperator(EntityPlayerMP player) {
        return player.canUseCommand(2, "veinminerplus");
    }

    // The hotkey path: the client sends the block it aims at, the server revalidates everything
    // (permission, sneak posture, distance) before it touches the shared config.
    private static void handleBlacklistToggle(EntityPlayerMP player, BlockPos pos) {
        if (!isOperator(player)) {
            player.sendStatusMessage(
                    new TextComponentTranslation("message.veinminerplus.blacklist.no_permission"), true);
            return;
        }
        if (!player.isSneaking()) {
            return;
        }
        WorldServer level = player.getServerWorld();
        // The distance bound is what keeps a stale or forged packet from editing the list for a
        // block that is out of reach.
        if (!level.isValid(pos)
                || player.getPositionEyes(1.0F).squareDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D,
                        pos.getZ() + 0.5D) > 100.0D) {
            return;
        }
        IBlockState state = level.getBlockState(pos);
        if (state.getBlock().isAir(state, level, pos)) {
            return;
        }
        ResourceLocation id = state.getBlock().getRegistryName();
        if (id == null) {
            return;
        }

        List<String> list = new ArrayList<>(Arrays.asList(Config.blockBlacklist));
        boolean added;
        if (list.remove(id.toString())) {
            added = false;
        } else {
            if (list.size() >= BlockBlacklist.MAX_ENTRIES) {
                player.sendStatusMessage(new TextComponentTranslation(
                        "message.veinminerplus.blacklist.full", BlockBlacklist.MAX_ENTRIES), true);
                return;
            }
            list.add(id.toString());
            added = true;
        }

        Config.blockBlacklist = sanitizeBlacklist(list).toArray(new String[0]);
        Config.save();
        BlockBlacklist.applyServerConfig();
        broadcastBlacklist(player);
        player.sendStatusMessage(new TextComponentTranslation(
                added ? "message.veinminerplus.blacklist.added" : "message.veinminerplus.blacklist.removed",
                state.getBlock().getLocalizedName()), true);
    }

    // Entries with a broken syntax or a duplicate are dropped, and the list never grows past the
    // cap, so the config and the synced mirror stay canonical whatever the client sent.
    private static List<String> sanitizeBlacklist(List<String> entries) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String raw : entries) {
            if (clean.size() >= BlockBlacklist.MAX_ENTRIES) {
                break;
            }
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            try {
                clean.add(new ResourceLocation(raw.trim()).toString());
            } catch (RuntimeException exception) {
                continue;
            }
        }
        return new ArrayList<>(clean);
    }

    private static void writeBlacklist(ByteBuf buffer, List<String> entries) {
        int size = Math.min(entries.size(), BlockBlacklist.MAX_ENTRIES);
        buffer.writeInt(size);
        for (int index = 0; index < size; index++) {
            ByteBufUtils.writeUTF8String(buffer, entries.get(index));
        }
    }

    private static List<String> readBlacklist(ByteBuf buffer) {
        int size = Math.min(Math.max(buffer.readInt(), 0), BlockBlacklist.MAX_ENTRIES);
        List<String> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(ByteBufUtils.readUTF8String(buffer));
        }
        return entries;
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
        List<String> blacklist;

        public ConfigSnapshotMessage() {
        }

        private ConfigSnapshotMessage(int maxNormalBlocks, int maxNormalBlocksPerTick, int maxBlastBlocks,
                int maxBlastBlocksPerTick, int blastSearchDistance, boolean noHungerCost,
                boolean storageBinding, List<String> blacklist) {
            this.maxNormalBlocks = maxNormalBlocks;
            this.maxNormalBlocksPerTick = maxNormalBlocksPerTick;
            this.maxBlastBlocks = maxBlastBlocks;
            this.maxBlastBlocksPerTick = maxBlastBlocksPerTick;
            this.blastSearchDistance = blastSearchDistance;
            this.noHungerCost = noHungerCost;
            this.storageBinding = storageBinding;
            this.blacklist = blacklist;
        }

        static ConfigSnapshotMessage current() {
            return new ConfigSnapshotMessage(Config.maxNormalBlocks, Config.maxNormalBlocksPerTick,
                    Config.maxBlastBlocks, Config.maxBlastBlocksPerTick, Config.blastSearchDistance,
                    Config.noHungerCost, Config.storageBinding, currentBlacklist());
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
            blacklist = readBlacklist(buffer);
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
            writeBlacklist(buffer, blacklist);
        }
    }

    public static class ConfigSnapshotHandler implements IMessageHandler<ConfigSnapshotMessage, IMessage> {
        @Override
        public IMessage onMessage(ConfigSnapshotMessage message, MessageContext context) {
            VeinMinerPlusClient.openConfigScreen(message);
            return null;
        }
    }

    public static class ProgressMessage implements IMessage {
        private int count;
        private boolean interact;

        public ProgressMessage() {
        }

        ProgressMessage(int count, boolean interact) {
            this.count = count;
            this.interact = interact;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            count = buffer.readInt();
            interact = buffer.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeInt(count);
            buffer.writeBoolean(interact);
        }
    }

    public static class ProgressHandler implements IMessageHandler<ProgressMessage, IMessage> {
        @Override
        public IMessage onMessage(ProgressMessage message, MessageContext context) {
            VeinMinerPlusClient.onProgress(message.count, message.interact);
            return null;
        }
    }

    public static class BlacklistToggleMessage implements IMessage {
        private BlockPos pos;

        public BlacklistToggleMessage() {
        }

        private BlacklistToggleMessage(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            pos = new BlockPos(buffer.readInt(), buffer.readInt(), buffer.readInt());
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeInt(pos.getX());
            buffer.writeInt(pos.getY());
            buffer.writeInt(pos.getZ());
        }
    }

    public static class BlacklistToggleHandler implements IMessageHandler<BlacklistToggleMessage, IMessage> {
        @Override
        public IMessage onMessage(BlacklistToggleMessage message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            if (player == null) {
                return null;
            }
            BlockPos pos = message.pos;
            player.server.addScheduledTask(() -> handleBlacklistToggle(player, pos));
            return null;
        }
    }

    public static class BlacklistSyncMessage implements IMessage {
        private List<String> entries = new ArrayList<>();

        public BlacklistSyncMessage() {
        }

        BlacklistSyncMessage(List<String> entries) {
            this.entries = entries;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            entries = readBlacklist(buffer);
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            writeBlacklist(buffer, entries);
        }
    }

    public static class BlacklistSyncHandler implements IMessageHandler<BlacklistSyncMessage, IMessage> {
        @Override
        public IMessage onMessage(BlacklistSyncMessage message, MessageContext context) {
            VeinMinerPlusClient.applyBlacklist(message.entries);
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
        private List<String> blacklist;

        public ConfigUpdateMessage() {
        }

        ConfigUpdateMessage(int maxNormalBlocks, int maxNormalBlocksPerTick, int maxBlastBlocks,
                int maxBlastBlocksPerTick, int blastSearchDistance, boolean noHungerCost,
                boolean storageBinding, List<String> blacklist) {
            this.maxNormalBlocks = maxNormalBlocks;
            this.maxNormalBlocksPerTick = maxNormalBlocksPerTick;
            this.maxBlastBlocks = maxBlastBlocks;
            this.maxBlastBlocksPerTick = maxBlastBlocksPerTick;
            this.blastSearchDistance = blastSearchDistance;
            this.noHungerCost = noHungerCost;
            this.storageBinding = storageBinding;
            this.blacklist = blacklist;
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
            blacklist = readBlacklist(buffer);
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
            writeBlacklist(buffer, blacklist);
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
                        message.noHungerCost, message.storageBinding, message.blacklist);
            });
            return null;
        }
    }
}
