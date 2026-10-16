package me.nyxia.epicworld.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.registry.ModRegistries;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.RegistryPatchGenerator;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.SurfaceRuleData;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;

/**
 * Builds every settings-dependent data-pack file of Epic World (dimension type, noise settings, biomes, preset)
 * from an {@link EpicSettings}. The result is served by {@code RuntimePack}; nothing here is written to the mod jar.
 */
public final class RuntimeWorldgen {
    private RuntimeWorldgen() {}

    public static final ResourceKey<NoiseGeneratorSettings> SETTINGS = ResourceKey.create(Registries.NOISE_SETTINGS, ModRegistries.id("epic_world"));
    public static final ResourceKey<DimensionType> DIMENSION = ResourceKey.create(Registries.DIMENSION_TYPE, ModRegistries.id("epic_world"));
    public static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(Registries.WORLD_PRESET, ModRegistries.id("epic_world"));

    private static volatile HolderLookup.Provider vanilla;

    private static HolderLookup.Provider vanilla() {
        if (vanilla == null) {
            synchronized (RuntimeWorldgen.class) {
                if (vanilla == null) vanilla = VanillaRegistries.createLookup();
            }
        }
        return vanilla;
    }

    public static ResourceKey<Biome> biomeKey(String path) {
        return ResourceKey.create(Registries.BIOME, ModRegistries.id(path));
    }

    /** All generated files, keyed by their path inside a data pack ("data/epicworld/..."). */
    public static Map<String, byte[]> generate(EpicSettings settings) {
        var builder = new RegistrySetBuilder()
                .add(ModRegistries.WORLD_SETTINGS, context -> context.register(ModRegistries.ACTIVE_SETTINGS, settings))
                .add(Registries.NOISE, TerrainRouter::noises)
                .add(Registries.CONFIGURED_FEATURE, EpicFeatures::configured)
                .add(Registries.PLACED_FEATURE, context -> EpicFeatures.placed(context, settings))
                .add(Registries.BIOME, context -> biomes(context, settings))
                .add(Registries.NOISE_SETTINGS, context -> noiseSettings(context, settings))
                .add(Registries.DIMENSION_TYPE, context -> dimension(context, settings))
                .add(Registries.WORLD_PRESET, RuntimeWorldgen::preset);
        var patched = RegistryPatchGenerator.createLookup(CompletableFuture.completedFuture(vanilla()), builder).join();
        HolderLookup.Provider patches = patched.patches();
        DynamicOps<JsonElement> ops = patches.createSerializationContext(JsonOps.INSTANCE);
        Map<String, byte[]> files = new LinkedHashMap<>();
        DataPackRegistriesHooks.getDataPackRegistriesWithDimensions().forEach(data -> encode(files, patches, ops, data));
        return files;
    }

    private static <T> void encode(Map<String, byte[]> files, HolderLookup.Provider patches, DynamicOps<JsonElement> ops,
                                   RegistryDataLoader.RegistryData<T> data) {
        var lookup = patches.lookup(data.key());
        if (lookup.isEmpty()) return;
        lookup.get().listElements().filter(h -> h.key().location().getNamespace().equals("epicworld")).forEach(holder -> {
            JsonElement json = data.elementCodec().encodeStart(ops, holder.value()).getOrThrow(
                    message -> new IllegalStateException("Cannot encode " + holder.key() + ": " + message));
            String path = "data/" + holder.key().location().getNamespace() + "/" + Registries.elementsDirPath(data.key())
                    + "/" + holder.key().location().getPath() + ".json";
            files.put(path, json.toString().getBytes(StandardCharsets.UTF_8));
        });
    }

    // ------------------------------------------------------------------------------------------------------
    // Biomes

    private static java.util.Set<ResourceKey<Biome>> overworldKeys() {
        var set = new HashSet<ResourceKey<Biome>>();
        net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList.knownPresets()
                .get(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD)
                .values().forEach(pair -> set.add(pair.getSecond()));
        return set;
    }

    /** Vanilla biome temperatures fall above Y=80; shifting them keeps the same climate relative to the raised sea. */
    static float temperatureOffset(EpicSettings settings) {
        return (settings.world().seaLevel() - 63) * 0.00125f;
    }

    private static void biomes(BootstrapContext<Biome> context, EpicSettings settings) {
        var overworld = overworldKeys();
        float offset = temperatureOffset(settings);
        Map<ResourceKey<Biome>, Biome> vanillaValues = new LinkedHashMap<>();
        net.minecraft.data.worldgen.biome.BiomeData.bootstrap(new BootstrapContext<>() {
            @Override
            public Holder.Reference<Biome> register(ResourceKey<Biome> key, Biome value, Lifecycle lifecycle) {
                vanillaValues.put(key, value);
                if (overworld.contains(key)) {
                    return context.register(biomeKey(key.location().getPath()), copy(value, offset, null), lifecycle);
                }
                return context.lookup(Registries.BIOME).getOrThrow(key);
            }

            @Override
            public <S> HolderGetter<S> lookup(ResourceKey<? extends Registry<? extends S>> key) {
                return context.lookup(key);
            }
        });
        NewBiomes.register(context, settings, key -> vanillaValues.get(key), offset);
    }

    /** Copy of a biome with shifted temperature and optional effect changes; features and spawns are shared. */
    static Biome copy(Biome value, float temperatureOffset, Consumer<BiomeSpecialEffects.Builder> effects) {
        var climate = value.getModifiedClimateSettings();
        return new Biome.BiomeBuilder().hasPrecipitation(climate.hasPrecipitation())
                .temperature(climate.temperature() + temperatureOffset)
                .temperatureAdjustment(climate.temperatureModifier()).downfall(climate.downfall())
                .specialEffects(effects == null ? value.getSpecialEffects() : effects(value.getSpecialEffects(), effects))
                .mobSpawnSettings(fixCategories(value.getMobSettings()))
                .generationSettings(value.getGenerationSettings()).build();
    }

    /** Vanilla lists ocelots under MONSTER in jungles; spawn every mob under its own category. */
    static MobSpawnSettings fixCategories(MobSpawnSettings mobs) {
        var builder = new MobSpawnSettings.Builder().creatureGenerationProbability(mobs.getCreatureProbability());
        for (var category : MobCategory.values()) {
            for (var spawn : mobs.getMobs(category).unwrap()) {
                var own = spawn.type.getCategory();
                builder.addSpawn(own == MobCategory.MISC ? category : own, spawn);
            }
        }
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            var cost = mobs.getMobSpawnCost(type);
            if (cost != null) builder.addMobCharge(type, cost.charge(), cost.energyBudget());
        }
        return builder.build();
    }

    static BiomeSpecialEffects effects(BiomeSpecialEffects base, Consumer<BiomeSpecialEffects.Builder> changes) {
        var b = new BiomeSpecialEffects.Builder().fogColor(base.getFogColor()).waterColor(base.getWaterColor())
                .waterFogColor(base.getWaterFogColor()).skyColor(base.getSkyColor())
                .grassColorModifier(base.getGrassColorModifier());
        base.getFoliageColorOverride().ifPresent(b::foliageColorOverride);
        base.getGrassColorOverride().ifPresent(b::grassColorOverride);
        base.getAmbientParticleSettings().ifPresent(b::ambientParticle);
        base.getAmbientLoopSoundEvent().ifPresent(b::ambientLoopSound);
        base.getAmbientMoodSettings().ifPresent(b::ambientMoodSound);
        base.getAmbientAdditionsSettings().ifPresent(b::ambientAdditionsSound);
        base.getBackgroundMusic().ifPresent(b::backgroundMusic);
        changes.accept(b);
        return b.build();
    }

    // ------------------------------------------------------------------------------------------------------
    // Dimension, noise settings, preset

    private static void dimension(BootstrapContext<DimensionType> context, EpicSettings settings) {
        var base = vanilla().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
        var w = settings.world();
        int height = w.heightLimit() - w.minY();
        context.register(DIMENSION, new DimensionType(base.fixedTime(), base.hasSkyLight(), base.hasCeiling(),
                base.ultraWarm(), base.natural(), base.coordinateScale(), base.bedWorks(), base.respawnAnchorWorks(),
                w.minY(), height, height, base.infiniburn(), base.effectsLocation(), base.ambientLight(), base.monsterSettings()));
    }

    private static void noiseSettings(BootstrapContext<NoiseGeneratorSettings> context, EpicSettings settings) {
        var base = NoiseGeneratorSettings.overworld(context, false, false);
        var w = settings.world();
        // Spawn search is replaced by TerrainEngine#findSpawn (see ClimateSamplerMixin); this point is only a fallback.
        var spawn = List.of(new Climate.ParameterPoint(
                Climate.Parameter.span(-1, 1), Climate.Parameter.span(-1, 1), Climate.Parameter.span(0.15f, 1.5f),
                Climate.Parameter.span(-1, 1), Climate.Parameter.point(0), Climate.Parameter.span(-1, 1), 0));
        context.register(SETTINGS, new NoiseGeneratorSettings(
                NoiseSettings.create(w.minY(), w.height() - w.minY(), 1, 2),
                base.defaultBlock(), base.defaultFluid(), TerrainRouter.create(context, settings),
                surface(settings), spawn, w.seaLevel(), false, true, true, false));
    }

    private static SurfaceRules.RuleSource surface(EpicSettings settings) {
        JsonElement json = SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, SurfaceRuleData.overworld()).getOrThrow();
        // Surface rules contain biome IDs and absolute coastal height anchors. Keep bedrock/ore anchors intact.
        remapSurface(json, settings.world().seaLevel() - 63);
        var vanillaRules = SurfaceRules.RuleSource.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        return SurfaceRules.sequence(NewBiomes.surfaceRules(settings), vanillaRules);
    }

    private static void remapSurface(JsonElement node, int seaOffset) {
        if (node.isJsonArray()) {
            for (var child : node.getAsJsonArray()) remapSurface(child, seaOffset);
        } else if (node.isJsonObject()) {
            var object = node.getAsJsonObject();
            if (object.has("biome_is")) {
                var ids = object.getAsJsonArray("biome_is");
                for (int i = 0; i < ids.size(); i++) {
                    ids.set(i, new JsonPrimitive(ids.get(i).getAsString().replace("minecraft:", "epicworld:")));
                }
            }
            if (object.has("absolute") && object.get("absolute").getAsInt() >= 60) {
                object.addProperty("absolute", object.get("absolute").getAsInt() + seaOffset);
            }
            object.entrySet().forEach(entry -> remapSurface(entry.getValue(), seaOffset));
        }
    }

    private static void preset(BootstrapContext<WorldPreset> context) {
        var biomes = context.lookup(Registries.BIOME);
        var list = new ArrayList<Holder<Biome>>();
        for (var entry : BiomeCatalog.ENTRIES) list.add(biomes.getOrThrow(biomeKey(entry.path())));
        var overworld = new LevelStem(context.lookup(Registries.DIMENSION_TYPE).getOrThrow(DIMENSION),
                new EpicChunkGenerator(new EpicBiomeSource(list), context.lookup(Registries.NOISE_SETTINGS).getOrThrow(SETTINGS)));
        Map<ResourceKey<LevelStem>, LevelStem> dimensions = new LinkedHashMap<>();
        dimensions.put(LevelStem.OVERWORLD, overworld);
        dimensions.put(LevelStem.NETHER, new LevelStem(
                context.lookup(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.NETHER),
                new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(context.lookup(
                        Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)),
                        context.lookup(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER))));
        dimensions.put(LevelStem.END, new LevelStem(
                context.lookup(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END),
                new NoiseBasedChunkGenerator(TheEndBiomeSource.create(biomes),
                        context.lookup(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.END))));
        context.register(PRESET, new WorldPreset(dimensions));
    }
}
