package me.nyxia.epicworld.worldgen;

import java.util.List;
import java.util.OptionalInt;
import me.nyxia.epicworld.block.ModBlocks;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.registry.ModRegistries;
import me.nyxia.epicworld.worldgen.feature.CaveDecorFeature;
import me.nyxia.epicworld.worldgen.feature.EpicFeatureTypes;
import me.nyxia.epicworld.worldgen.feature.HangingDecorator;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BiomeDefaultFeatures;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.features.MiscOverworldFeatures;
import net.minecraft.data.worldgen.placement.PlacementUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.FancyFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedBlockStateProvider;
import net.minecraft.world.level.levelgen.feature.trunkplacers.FancyTrunkPlacer;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceWaterDepthFilter;
import net.minecraft.world.level.material.Fluids;

/** Configured/placed features of the new biomes and the biome definitions that use them. */
final class EpicFeatures {
    private EpicFeatures() {}

    private static ResourceKey<ConfiguredFeature<?, ?>> configuredKey(String name) {
        return ResourceKey.create(Registries.CONFIGURED_FEATURE, ModRegistries.id(name));
    }

    private static ResourceKey<PlacedFeature> placedKey(String name) {
        return ResourceKey.create(Registries.PLACED_FEATURE, ModRegistries.id(name));
    }

    static final ResourceKey<ConfiguredFeature<?, ?>> CATTAIL_PATCH = configuredKey("cattail_patch");
    static final ResourceKey<ConfiguredFeature<?, ?>> WATER_GRASS_PATCH = configuredKey("water_grass_patch");
    static final ResourceKey<ConfiguredFeature<?, ?>> SILT_DISK = configuredKey("silt_disk");
    static final ResourceKey<ConfiguredFeature<?, ?>> MARBLE_DECOR = configuredKey("marble_cave_decor");
    static final ResourceKey<ConfiguredFeature<?, ?>> GLOWING_DECOR = configuredKey("glowing_cave_decor");
    static final ResourceKey<ConfiguredFeature<?, ?>> DRY_REEDS_PATCH = configuredKey("dry_reeds_patch");
    static final ResourceKey<ConfiguredFeature<?, ?>> RING_VINES_DECOR = configuredKey("stone_ring_vines");

    static final ResourceKey<PlacedFeature> WILLOWS = placedKey("trees_willow");
    static final ResourceKey<PlacedFeature> HUGE_WILLOWS = placedKey("trees_huge_willow");
    static final ResourceKey<PlacedFeature> CATTAILS = placedKey("patch_cattail");
    static final ResourceKey<PlacedFeature> WATER_GRASS = placedKey("patch_water_grass");
    static final ResourceKey<PlacedFeature> SILT_DISKS = placedKey("disk_silt");
    static final ResourceKey<PlacedFeature> MARBLE_CAVES = placedKey("marble_cave_decor");
    static final ResourceKey<PlacedFeature> GLOWING_CAVES = placedKey("glowing_cave_decor");
    static final ResourceKey<PlacedFeature> PILLAR_SPRINGS = placedKey("stone_forest_springs");
    static final ResourceKey<PlacedFeature> WILLOW_FERNS = placedKey("willow_swamp_ferns");
    static final ResourceKey<PlacedFeature> WILLOW_GRASS = placedKey("willow_swamp_grass");
    static final ResourceKey<PlacedFeature> DRY_REEDS = placedKey("patch_dry_reeds");
    static final ResourceKey<PlacedFeature> RING_VINES = placedKey("stone_ring_vines");

    static void configured(BootstrapContext<ConfiguredFeature<?, ?>> context) {
        var hangingLeaves = BlockStateProvider.simple(ModBlocks.WILLOW_LEAVES.get().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        var moss = BlockStateProvider.simple(ModBlocks.SPANISH_MOSS.get());
        context.register(ModBlocks.WILLOW_TREE, new ConfiguredFeature<>(Feature.TREE, new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(ModBlocks.WILLOW_LOG.get()), new FancyTrunkPlacer(5, 3, 0),
                BlockStateProvider.simple(ModBlocks.WILLOW_LEAVES.get()), new FancyFoliagePlacer(ConstantInt.of(2), ConstantInt.of(3), 4),
                new TwoLayersFeatureSize(0, 0, 0, OptionalInt.of(4)))
                .decorators(List.of(new HangingDecorator(hangingLeaves, 0.3f, 3), new HangingDecorator(moss, 0.25f, 4)))
                .ignoreVines().build()));
        // Bald-cypress-like: a thick 2x2 trunk and a broad, flat crown hung with leaves and Spanish moss.
        context.register(ModBlocks.HUGE_WILLOW_TREE, new ConfiguredFeature<>(Feature.TREE, new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(ModBlocks.WILLOW_LOG.get()),
                new net.minecraft.world.level.levelgen.feature.trunkplacers.DarkOakTrunkPlacer(10, 4, 3),
                BlockStateProvider.simple(ModBlocks.WILLOW_LEAVES.get()),
                new net.minecraft.world.level.levelgen.feature.foliageplacers.DarkOakFoliagePlacer(ConstantInt.of(0), ConstantInt.of(0)),
                new net.minecraft.world.level.levelgen.feature.featuresize.ThreeLayersFeatureSize(1, 1, 0, 1, 2, OptionalInt.empty()))
                .decorators(List.of(new HangingDecorator(hangingLeaves, 0.45f, 5), new HangingDecorator(moss, 0.4f, 7)))
                .ignoreVines().build()));

        var water = BlockPredicate.matchesFluids(Fluids.WATER);
        var nearWater = BlockPredicate.anyOf(water, BlockPredicate.matchesFluids(new Vec3i(1, -1, 0), Fluids.WATER),
                BlockPredicate.matchesFluids(new Vec3i(-1, -1, 0), Fluids.WATER), BlockPredicate.matchesFluids(new Vec3i(0, -1, 1), Fluids.WATER),
                BlockPredicate.matchesFluids(new Vec3i(0, -1, -1), Fluids.WATER));
        var cattail = ModBlocks.CATTAIL.get().defaultBlockState();
        context.register(CATTAIL_PATCH, new ConfiguredFeature<>(Feature.RANDOM_PATCH, new RandomPatchConfiguration(48, 7, 2,
                PlacementUtils.filtered(Feature.SIMPLE_BLOCK, new SimpleBlockConfiguration(new net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider(
                        net.minecraft.util.random.SimpleWeightedRandomList.<net.minecraft.world.level.block.state.BlockState>builder()
                                .add(cattail, 3).add(ModBlocks.REED.get().defaultBlockState(), 2).build())),
                        BlockPredicate.allOf(BlockPredicate.replaceable(), nearWater, BlockPredicate.wouldSurvive(cattail, Vec3i.ZERO))))));
        var grass = ModBlocks.WATER_GRASS.get().defaultBlockState();
        context.register(WATER_GRASS_PATCH, new ConfiguredFeature<>(Feature.RANDOM_PATCH, new RandomPatchConfiguration(64, 8, 3,
                PlacementUtils.filtered(Feature.SIMPLE_BLOCK, new SimpleBlockConfiguration(BlockStateProvider.simple(grass)),
                        BlockPredicate.allOf(BlockPredicate.matchesBlocks(Blocks.WATER), BlockPredicate.wouldSurvive(grass, Vec3i.ZERO))))));
        context.register(SILT_DISK, new ConfiguredFeature<>(Feature.DISK, new DiskConfiguration(
                RuleBasedBlockStateProvider.simple(ModBlocks.SILT.get()),
                BlockPredicate.matchesBlocks(List.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.MUD, Blocks.CLAY, Blocks.SAND)),
                UniformInt.of(2, 5), 2)));
        context.register(MARBLE_DECOR, new ConfiguredFeature<>(EpicFeatureTypes.CAVE_DECOR.get(), new CaveDecorFeature.Config(CaveDecorFeature.Kind.MARBLE)));
        context.register(GLOWING_DECOR, new ConfiguredFeature<>(EpicFeatureTypes.CAVE_DECOR.get(), new CaveDecorFeature.Config(CaveDecorFeature.Kind.GLOWING)));
        context.register(RING_VINES_DECOR, new ConfiguredFeature<>(EpicFeatureTypes.CAVE_DECOR.get(), new CaveDecorFeature.Config(CaveDecorFeature.Kind.RING_VINES)));
        var reeds = ModBlocks.DRY_REEDS.get().defaultBlockState();
        context.register(DRY_REEDS_PATCH, new ConfiguredFeature<>(Feature.RANDOM_PATCH, new RandomPatchConfiguration(40, 7, 2,
                PlacementUtils.filtered(Feature.SIMPLE_BLOCK, new SimpleBlockConfiguration(
                        new net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider(
                                net.minecraft.util.random.SimpleWeightedRandomList.<net.minecraft.world.level.block.state.BlockState>builder()
                                        .add(reeds, 3).add(ModBlocks.DRY_CATTAIL.get().defaultBlockState(), 2).build())),
                        BlockPredicate.allOf(BlockPredicate.replaceable(), BlockPredicate.wouldSurvive(reeds, Vec3i.ZERO))))));
    }

    static void placed(BootstrapContext<PlacedFeature> context, EpicSettings settings) {
        var features = context.lookup(Registries.CONFIGURED_FEATURE);
        var sapling = ModBlocks.WILLOW_SAPLING.get().defaultBlockState();
        context.register(WILLOWS, new PlacedFeature(features.getOrThrow(ModBlocks.WILLOW_TREE), List.of(
                PlacementUtils.countExtra(1, 0.5f, 2), InSquarePlacement.spread(), SurfaceWaterDepthFilter.forMaxDepth(2),
                PlacementUtils.HEIGHTMAP_OCEAN_FLOOR, BiomeFilter.biome(),
                BlockPredicateFilter.forPredicate(BlockPredicate.wouldSurvive(sapling, Vec3i.ZERO)))));
        context.register(HUGE_WILLOWS, new PlacedFeature(features.getOrThrow(ModBlocks.HUGE_WILLOW_TREE), List.of(
                RarityFilter.onAverageOnceEvery(2), InSquarePlacement.spread(), SurfaceWaterDepthFilter.forMaxDepth(2),
                PlacementUtils.HEIGHTMAP_OCEAN_FLOOR, BiomeFilter.biome(),
                // The 2x2 trunk needs ground under all four corners at the same level, or part of it stands on water.
                BlockPredicateFilter.forPredicate(BlockPredicate.allOf(BlockPredicate.solid(new Vec3i(0, -1, 0)),
                        BlockPredicate.solid(new Vec3i(1, -1, 0)), BlockPredicate.solid(new Vec3i(0, -1, 1)),
                        BlockPredicate.solid(new Vec3i(1, -1, 1)))),
                BlockPredicateFilter.forPredicate(BlockPredicate.wouldSurvive(sapling, Vec3i.ZERO)))));
        context.register(CATTAILS, new PlacedFeature(features.getOrThrow(CATTAIL_PATCH), List.of(
                CountPlacement.of(5), InSquarePlacement.spread(), PlacementUtils.HEIGHTMAP_WORLD_SURFACE, BiomeFilter.biome())));
        context.register(WATER_GRASS, new PlacedFeature(features.getOrThrow(WATER_GRASS_PATCH), List.of(
                CountPlacement.of(4), InSquarePlacement.spread(), PlacementUtils.HEIGHTMAP_TOP_SOLID, BiomeFilter.biome())));
        context.register(SILT_DISKS, new PlacedFeature(features.getOrThrow(SILT_DISK), List.of(
                CountPlacement.of(3), InSquarePlacement.spread(), PlacementUtils.HEIGHTMAP_TOP_SOLID, BiomeFilter.biome())));
        var w = settings.world();
        int caveTop = Math.max(w.minY() + 24, w.seaLevel() - 40);
        // Once per chunk that contains a marble lake; the feature finds the biome itself.
        context.register(MARBLE_CAVES, new PlacedFeature(features.getOrThrow(MARBLE_DECOR), List.of(InSquarePlacement.spread())));
        // Once per chunk that contains the biome (deep caves or a river tunnel); the feature finds the biome itself.
        context.register(GLOWING_CAVES, new PlacedFeature(features.getOrThrow(GLOWING_DECOR), List.of(InSquarePlacement.spread())));
        context.register(RING_VINES, new PlacedFeature(features.getOrThrow(RING_VINES_DECOR), List.of(InSquarePlacement.spread())));
        context.register(DRY_REEDS, new PlacedFeature(features.getOrThrow(DRY_REEDS_PATCH), List.of(
                CountPlacement.of(3), InSquarePlacement.spread(), PlacementUtils.HEIGHTMAP_WORLD_SURFACE, BiomeFilter.biome())));
        context.register(WILLOW_FERNS, new PlacedFeature(features.getOrThrow(net.minecraft.data.worldgen.features.VegetationFeatures.PATCH_LARGE_FERN),
                List.of(CountPlacement.of(3), InSquarePlacement.spread(), PlacementUtils.HEIGHTMAP, BiomeFilter.biome())));
        context.register(WILLOW_GRASS, new PlacedFeature(features.getOrThrow(net.minecraft.data.worldgen.features.VegetationFeatures.PATCH_TAIGA_GRASS),
                List.of(CountPlacement.of(6), InSquarePlacement.spread(), PlacementUtils.HEIGHTMAP_WORLD_SURFACE, BiomeFilter.biome())));
        // Waterfalls pouring from the stone pillars.
        context.register(PILLAR_SPRINGS, new PlacedFeature(features.getOrThrow(MiscOverworldFeatures.SPRING_WATER), List.of(
                CountPlacement.of(40), InSquarePlacement.spread(),
                HeightRangePlacement.uniform(VerticalAnchor.absolute(w.seaLevel() + 10), VerticalAnchor.absolute(w.height() - 16)),
                BiomeFilter.biome())));
    }

    /**
     * Generation settings of a vanilla prototype in its original order (so no feature-order cycle can arise), minus
     * rejected features, with Epic features appended at the end of their steps.
     */
    private static BiomeGenerationSettings derive(BootstrapContext<Biome> context, Biome base,
                                                  java.util.function.Predicate<String> reject,
                                                  java.util.Map<GenerationStep.Decoration, List<ResourceKey<PlacedFeature>>> extra) {
        return derive(context, base, reject, java.util.Map.of(), extra);
    }

    /**
     * As above; {@code first} features run before the prototype's features of their step (trees before grass, flowers
     * and mushrooms, which would otherwise take the place of trunk blocks).
     */
    private static BiomeGenerationSettings derive(BootstrapContext<Biome> context, Biome base,
                                                  java.util.function.Predicate<String> reject,
                                                  java.util.Map<GenerationStep.Decoration, List<ResourceKey<PlacedFeature>>> first,
                                                  java.util.Map<GenerationStep.Decoration, List<ResourceKey<PlacedFeature>>> extra) {
        var placed = context.lookup(Registries.PLACED_FEATURE);
        var gen = new BiomeGenerationSettings.Builder(placed, context.lookup(Registries.CONFIGURED_CARVER));
        var settings = base.getGenerationSettings();
        for (var step : GenerationStep.Carving.values()) {
            settings.getCarvers(step).forEach(carver -> gen.addCarver(step, carver));
        }
        var steps = GenerationStep.Decoration.values();
        var features = settings.features();
        for (int i = 0; i < steps.length; i++) {
            for (var key : first.getOrDefault(steps[i], List.of())) gen.addFeature(steps[i], placed.getOrThrow(key));
            if (i < features.size()) {
                for (var feature : features.get(i)) {
                    String id = feature.unwrapKey().map(k -> k.location().toString()).orElse("");
                    if (!reject.test(id)) gen.addFeature(steps[i], feature);
                }
            }
            for (var key : extra.getOrDefault(steps[i], List.of())) gen.addFeature(steps[i], placed.getOrThrow(key));
        }
        return gen.build();
    }

    static Biome willowSwamp(BootstrapContext<Biome> context, Biome swamp, float offset) {
        // Fresh water: no seagrass (water grass instead).
        var gen = derive(context, swamp, id -> id.equals("minecraft:trees_swamp") || id.equals("minecraft:flower_swamp")
                        || id.equals("minecraft:patch_dead_bush") || id.equals("minecraft:lake_lava_surface")
                        || id.equals("minecraft:seagrass_swamp"),
                java.util.Map.of(GenerationStep.Decoration.VEGETAL_DECORATION, List.of(HUGE_WILLOWS, WILLOWS)),
                java.util.Map.of(GenerationStep.Decoration.UNDERGROUND_ORES, List.of(SILT_DISKS),
                        GenerationStep.Decoration.VEGETAL_DECORATION, List.of(CATTAILS, WATER_GRASS, WILLOW_FERNS, WILLOW_GRASS)));
        var climate = swamp.getModifiedClimateSettings();
        return new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.85f + offset).downfall(0.95f)
                .temperatureAdjustment(climate.temperatureModifier())
                .specialEffects(RuntimeWorldgen.effects(swamp.getSpecialEffects(), e -> e.waterColor(0x6B8F4E).waterFogColor(0x1F3A1A)
                        .fogColor(0xB3C2A8).grassColorOverride(0x5E8C35).foliageColorOverride(0x4F7F2A)))
                .mobSpawnSettings(swamp.getMobSettings()).generationSettings(gen).build();
    }

    /** Stone ring sea: turquoise water; glow berry vines hang from the arches and overhangs. */
    static Biome stoneRingSea(BootstrapContext<Biome> context, Biome ocean, float offset) {
        var gen = derive(context, ocean, id -> false,
                java.util.Map.of(GenerationStep.Decoration.VEGETAL_DECORATION, List.of(RING_VINES)));
        return new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.7f + offset).downfall(0.6f)
                .specialEffects(RuntimeWorldgen.effects(ocean.getSpecialEffects(), e -> e.waterColor(0x3AA6B0).waterFogColor(0x0E4650)
                        .fogColor(0xC8D2D4)))
                .mobSpawnSettings(ocean.getMobSettings()).generationSettings(gen).build();
    }

    /** Dry river beds: desert climate and life, dry reeds along the bed instead of cacti. */
    static Biome dryRiverbed(BootstrapContext<Biome> context, Biome desert, float offset) {
        var gen = derive(context, desert, id -> id.equals("minecraft:patch_cactus_desert") || id.equals("minecraft:patch_sugar_cane_desert"),
                java.util.Map.of(GenerationStep.Decoration.VEGETAL_DECORATION, List.of(DRY_REEDS)));
        return new Biome.BiomeBuilder().hasPrecipitation(false).temperature(1.6f + offset).downfall(0.05f)
                .specialEffects(RuntimeWorldgen.effects(desert.getSpecialEffects(), e -> e.grassColorOverride(0xBFB76B)
                        .foliageColorOverride(0xAEA42A)))
                .mobSpawnSettings(desert.getMobSettings()).generationSettings(gen).build();
    }

    static Biome giantStoneForest(BootstrapContext<Biome> context, Biome taiga, float offset) {
        var gen = derive(context, taiga, id -> id.equals("minecraft:patch_berry_common"),
                java.util.Map.of(GenerationStep.Decoration.FLUID_SPRINGS, List.of(PILLAR_SPRINGS)));
        return new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8f + offset).downfall(0.9f)
                .specialEffects(RuntimeWorldgen.effects(taiga.getSpecialEffects(), e -> e.fogColor(0xD4DEDC).skyColor(0x9CB7C9)
                        .grassColorOverride(0x6FAF4A).foliageColorOverride(0x4FA03A)))
                .mobSpawnSettings(taiga.getMobSettings()).generationSettings(gen).build();
    }

    static Biome marbleCaves(BootstrapContext<Biome> context, Biome dripstone, float offset) {
        var gen = derive(context, dripstone, id -> id.contains("dripstone"),
                java.util.Map.of(GenerationStep.Decoration.UNDERGROUND_DECORATION, List.of(MARBLE_CAVES)));
        return new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8f + offset).downfall(0.4f)
                .specialEffects(RuntimeWorldgen.effects(dripstone.getSpecialEffects(), e -> e.waterColor(0x4FD6CF).waterFogColor(0x1C6E70)))
                .mobSpawnSettings(dripstone.getMobSettings()).generationSettings(gen).build();
    }

    static Biome glowingCaves(BootstrapContext<Biome> context, Biome lush, float offset) {
        var gen = derive(context, lush, id -> id.contains("lush_caves") || id.contains("cave_vines") || id.contains("azalea")
                        || id.contains("spore_blossom") || id.contains("classic_vines"),
                java.util.Map.of(GenerationStep.Decoration.UNDERGROUND_DECORATION, List.of(GLOWING_CAVES)));
        var spawns = new MobSpawnSettings.Builder();
        BiomeDefaultFeatures.commonSpawns(spawns);
        spawns.addSpawn(MobCategory.UNDERGROUND_WATER_CREATURE, new MobSpawnSettings.SpawnerData(EntityType.GLOW_SQUID, 10, 4, 6));
        spawns.addSpawn(MobCategory.AXOLOTLS, new MobSpawnSettings.SpawnerData(EntityType.AXOLOTL, 10, 4, 6));
        return new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.5f + offset).downfall(0.8f)
                .specialEffects(RuntimeWorldgen.effects(lush.getSpecialEffects(), e -> e.waterColor(0x2A5A8A).waterFogColor(0x061A33)
                        .fogColor(0x0B1A2A)))
                .mobSpawnSettings(spawns.build()).generationSettings(gen).build();
    }

    static SurfaceRules.RuleSource siltRule() {
        return SurfaceRules.state(ModBlocks.SILT.get().defaultBlockState());
    }
}
