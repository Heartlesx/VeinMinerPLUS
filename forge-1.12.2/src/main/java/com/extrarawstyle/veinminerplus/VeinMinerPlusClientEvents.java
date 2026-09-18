package com.extrarawstyle.veinminerplus;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(Side.CLIENT)
public final class VeinMinerPlusClientEvents {
    private VeinMinerPlusClientEvents() {
    }

    @SubscribeEvent
    public static void onModelRegistry(ModelRegistryEvent event) {
        ModelLoader.setCustomModelResourceLocation(ModItems.STORAGE_BINDER, 0,
                new ModelResourceLocation(VeinMinerPlus.MODID + ":storage_binder", "inventory"));
    }

    @SubscribeEvent
    public static void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        VeinMinerPlusClient.resetSessionState();
        NetworkHandler.sendModeChange(VeinMinerPlusClient.clientMode);
        VeinMinerPlusClient.reportKeyBinding();
    }

    @SubscribeEvent
    public static void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        VeinMinerPlusClient.resetSessionState();
    }

    @SubscribeEvent
    public static void onMouse(MouseEvent event) {
        if (!VeinMinerPlusClient.isModeSelectorOpen() || event.getDwheel() == 0) {
            return;
        }

        int direction = event.getDwheel() > 0 ? -1 : 1;
        VeinMinerPlusClient.clientMode = ChainMode.cycle(VeinMinerPlusClient.clientMode, direction);
        NetworkHandler.sendModeChange(VeinMinerPlusClient.clientMode);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        VeinMinerPlusClient.renderModeMenu(event);
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        VeinMinerPlusClient.renderInteractPreview(event);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            VeinMinerPlusClient.syncKeyState();
        }
    }
}
