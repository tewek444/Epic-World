package me.nyxia.epicworld.client;

import me.nyxia.epicworld.block.ModBlocks;
import net.minecraft.client.renderer.Sheets;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only setup. */
public final class EpicClient {
    private EpicClient() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(EpicClient::setup);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(DebugOverlay::onDebugText);
    }

    /** Closes the game like the quit button (used by the automatic benchmark). */
    public static void quit() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(mc::stop);
    }

    private static void setup(FMLClientSetupEvent event) {
        // Sign and hanging sign textures of the willow wood type.
        event.enqueueWork(() -> Sheets.addWoodType(ModBlocks.WILLOW_TYPE));
    }
}
