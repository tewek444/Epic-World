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

    public EpicWorld(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, me.nyxia.epicworld.config.EpicPerformance.SPEC,
                "epicworld-performance.toml");
        ModRegistries.register(modBus);
        ModBlocks.register(modBus);
        EpicFeatureTypes.register(modBus);
        modBus.addListener(RuntimePack::register);
        modBus.addListener(EpicDataGen::gather);
        modBus.addListener(EpicWorld::commonSetup);
        modBus.addListener(EpicWorld::spawnPlacements);
        modBus.addListener(EpicWorld::signBlockEntities);
        NeoForge.EVENT_BUS.addListener(EpicWorld::toolActions);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) me.nyxia.epicworld.client.EpicClient.register(modBus);
        NeoForge.EVENT_BUS.addListener(EpicWorld::levelLoad);
        NeoForge.EVENT_BUS.addListener(EpicWorld::serverStarted);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> LegacyIndex.clear());
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.dev.GenerationBenchmark::tick);
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.dev.GenerationBenchmark::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.voxy.LodGenerator::onServerStarted);
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.voxy.LodGenerator::onServerStopping);
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.voxy.LodGenerator::onTick);
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.pregen.Pregenerator::onServerStarted);
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.pregen.Pregenerator::onServerStopping);
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.pregen.Pregenerator::onTick);
        modBus.addListener(me.nyxia.epicworld.network.EpicNetwork::register);
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.worldgen.deferred.DeepFiller::onLoad);
        NeoForge.EVENT_BUS.addListener(me.nyxia.epicworld.worldgen.deferred.DeepFiller::onTick);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> me.nyxia.epicworld.worldgen.deferred.DeepFiller.clear());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> EpicCommands.register(event.getDispatcher()));
        WorldSettingsStore.ensureGlobalConfig();
    }

    /** Willow signs use the vanilla sign block entities (and so the vanilla sign renderers). */
    private static void signBlockEntities(net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent event) {
        event.modify(net.minecraft.world.level.block.entity.BlockEntityType.SIGN, ModBlocks.WILLOW_SIGN.get(), ModBlocks.WILLOW_WALL_SIGN.get());
        event.modify(net.minecraft.world.level.block.entity.BlockEntityType.HANGING_SIGN, ModBlocks.WILLOW_HANGING_SIGN.get(),
                ModBlocks.WILLOW_WALL_HANGING_SIGN.get());
    }

    /** Axe stripping of willow logs and wood. */
    private static void toolActions(net.neoforged.neoforge.event.level.BlockEvent.BlockToolModificationEvent event) {
        if (event.getItemAbility() != net.neoforged.neoforge.common.ItemAbilities.AXE_STRIP) return;
        var state = event.getState();
        net.minecraft.world.level.block.Block stripped = state.is(ModBlocks.WILLOW_LOG.get()) ? ModBlocks.STRIPPED_WILLOW_LOG.get()
                : state.is(ModBlocks.WILLOW_WOOD.get()) ? ModBlocks.STRIPPED_WILLOW_WOOD.get() : null;
        if (stripped != null) {
            event.setFinalState(stripped.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                    state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)));
        }
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
    }    /**
     * Glow squid normally spawn only 33 blocks or more below the sea, which river tunnels never reach. In the glowing
     * and glowworm tunnels (lush caves and glowing caves away from the sky) they may also spawn in the river water.
     */
    private static void spawnPlacements(net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent event) {
        var lush = me.nyxia.epicworld.worldgen.RuntimeWorldgen.biomeKey("lush_caves");
        var glowing = me.nyxia.epicworld.worldgen.RuntimeWorldgen.biomeKey("glowing_caves");
        event.register(net.minecraft.world.entity.EntityType.GLOW_SQUID,
                (type, level, reason, pos, random) -> level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)
                        && !level.canSeeSky(pos) && (level.getBiome(pos).is(lush) || level.getBiome(pos).is(glowing)),
                net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent.Operation.OR);
    }


}
