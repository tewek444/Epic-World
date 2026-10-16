package me.nyxia.epicworld.registry;

import com.mojang.serialization.MapCodec;
import java.util.function.Supplier;
import me.nyxia.epicworld.EpicWorld;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.worldgen.EpicBiomeSource;
import me.nyxia.epicworld.worldgen.EpicChunkGenerator;
import me.nyxia.epicworld.worldgen.EpicDensity;
import me.nyxia.epicworld.worldgen.GenerationStamp;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Worldgen codecs, the per-world settings registry and chunk attachments. */
public final class ModRegistries {
    private ModRegistries() {}

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(EpicWorld.MODID, path);
    }

    /** Data-pack registry holding the settings a world was generated with (entry {@code epicworld:active}). */
    public static final ResourceKey<Registry<EpicSettings>> WORLD_SETTINGS = ResourceKey.createRegistryKey(id("world_settings"));
    public static final ResourceKey<EpicSettings> ACTIVE_SETTINGS = ResourceKey.create(WORLD_SETTINGS, id("active"));

    private static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTIONS =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, EpicWorld.MODID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, EpicWorld.MODID);
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, EpicWorld.MODID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, EpicWorld.MODID);

    static {
        DENSITY_FUNCTIONS.register("terrain", () -> EpicDensity.MAP_CODEC);
        BIOME_SOURCES.register("epic", () -> EpicBiomeSource.CODEC);
        CHUNK_GENERATORS.register("epic", () -> EpicChunkGenerator.CODEC);
    }

    public static final Supplier<AttachmentType<GenerationStamp>> GENERATION_STAMP = ATTACHMENTS.register("generation",
            () -> AttachmentType.builder(() -> GenerationStamp.NONE).serialize(GenerationStamp.CODEC, s -> s != GenerationStamp.NONE).build());

    public static void register(IEventBus modBus) {
        DENSITY_FUNCTIONS.register(modBus);
        BIOME_SOURCES.register(modBus);
        CHUNK_GENERATORS.register(modBus);
        ATTACHMENTS.register(modBus);
        modBus.addListener(DataPackRegistryEvent.NewRegistry.class,
                event -> event.dataPackRegistry(WORLD_SETTINGS, EpicSettings.CODEC));
    }
}
