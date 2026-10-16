package me.nyxia.epicworld;

import com.mojang.logging.LogUtils;
import me.nyxia.epicworld.block.ModBlocks;
import me.nyxia.epicworld.data.RuntimePack;
import me.nyxia.epicworld.data.WorldSettingsStore;
import me.nyxia.epicworld.datagen.EpicDataGen;
import me.nyxia.epicworld.registry.ModRegistries;
import me.nyxia.epicworld.worldgen.EpicChunkGenerator;
import me.nyxia.epicworld.worldgen.EpicCommands;
import me.nyxia.epicworld.worldgen.LegacyIndex;
import me.nyxia.epicworld.worldgen.feature.EpicFeatureTypes;
import me.nyxia.epicworld.mixin.VillagerTypeAccessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(EpicWorld.MODID)
public final class EpicWorld {
    public static final String MODID = "epicworld";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EpicWorld(IEventBus modBus) {
        ModRegistries.register(modBus);
        ModBlocks.register(modBus);
        EpicFeatureTypes.register(modBus);
        modBus.addListener(RuntimePack::register);
        modBus.addListener(EpicDataGen::gather);
        modBus.addListener(EpicWorld::commonSetup);
        NeoForge.EVENT_BUS.addListener(EpicWorld::levelLoad);
        NeoForge.EVENT_BUS.addListener(EpicWorld::serverStarted);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> LegacyIndex.clear());
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.dev.GenerationBenchmark::tick);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> EpicCommands.register(event.getDispatcher()));
        WorldSettingsStore.ensureGlobalConfig();
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Villagers born in Epic biomes wear the same outfits as in their vanilla counterparts.
            var map = VillagerTypeAccessor.epicworld$byBiome();
            for (var entry : java.util.List.copyOf(map.entrySet())) {
                if (!entry.getKey().location().getNamespace().equals("minecraft")) continue;
                map.put(ResourceKey.create(Registries.BIOME, ModRegistries.id(entry.getKey().location().getPath())), entry.getValue());
            }
            map.put(ResourceKey.create(Registries.BIOME, ModRegistries.id("willow_swamp")), net.minecraft.world.entity.npc.VillagerType.SWAMP);
            map.put(ResourceKey.create(Registries.BIOME, ModRegistries.id("atoll")), net.minecraft.world.entity.npc.VillagerType.DESERT);
            map.put(ResourceKey.create(Registries.BIOME, ModRegistries.id("giant_stone_forest")), net.minecraft.world.entity.npc.VillagerType.JUNGLE);
        });
    }

    public static boolean isEpic(ServerLevel level) {
        return level.getChunkSource().getGenerator() instanceof EpicChunkGenerator;
    }

    private static void levelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && isEpic(level)) {
            var settings = level.registryAccess().registryOrThrow(ModRegistries.WORLD_SETTINGS).get(ModRegistries.ACTIVE_SETTINGS);
            if (settings != null) LegacyIndex.load(level, settings);
        }
    }

    private static void serverStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!isEpic(server.overworld())) return;
        var settings = server.registryAccess().registryOrThrow(ModRegistries.WORLD_SETTINGS).get(ModRegistries.ACTIVE_SETTINGS);
        if (settings != null) WorldSettingsStore.persist(server.getWorldPath(LevelResource.ROOT), settings);
    }
}
