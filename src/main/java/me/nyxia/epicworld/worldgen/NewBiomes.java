package me.nyxia.epicworld.worldgen;

import java.util.function.Function;
import me.nyxia.epicworld.config.EpicSettings;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

/** The Epic World biomes that have no vanilla counterpart. */
final class NewBiomes {
    private NewBiomes() {}

    static void register(BootstrapContext<Biome> context, EpicSettings settings,
                         Function<ResourceKey<Biome>, Biome> vanilla, float offset) {
        context.register(RuntimeWorldgen.biomeKey("lake"), RuntimeWorldgen.copy(vanilla.apply(Biomes.RIVER), offset + 0.3f,
                e -> e.waterColor(0x3D8FD1).waterFogColor(0x0A3A66)));
        Biome atollBase = vanilla.apply(settings.atolls().generateCoralReefs() ? Biomes.WARM_OCEAN : Biomes.LUKEWARM_OCEAN);
        context.register(RuntimeWorldgen.biomeKey("atoll"), RuntimeWorldgen.copy(atollBase, offset,
                e -> e.waterColor(0x43D5EE).waterFogColor(0x0E7C8A)));
        context.register(RuntimeWorldgen.biomeKey("stone_ring_sea"), EpicFeatures.stoneRingSea(context, vanilla.apply(Biomes.OCEAN), offset));
        context.register(RuntimeWorldgen.biomeKey("willow_swamp"), EpicFeatures.willowSwamp(context, vanilla.apply(Biomes.SWAMP), offset));
        context.register(RuntimeWorldgen.biomeKey("giant_stone_forest"),
                EpicFeatures.giantStoneForest(context, vanilla.apply(Biomes.TAIGA), offset));
        context.register(RuntimeWorldgen.biomeKey("marble_caves"), EpicFeatures.marbleCaves(context, vanilla.apply(Biomes.DRIPSTONE_CAVES), offset));
        context.register(RuntimeWorldgen.biomeKey("glowing_caves"), EpicFeatures.glowingCaves(context, vanilla.apply(Biomes.LUSH_CAVES), offset));
        context.register(RuntimeWorldgen.biomeKey("dry_riverbed"), EpicFeatures.dryRiverbed(context, vanilla.apply(Biomes.DESERT), offset));
    }

    private static SurfaceRules.ConditionSource biome(String path) {
        return SurfaceRules.isBiome(RuntimeWorldgen.biomeKey(path));
    }

    /** Rules evaluated before the (remapped) vanilla overworld rules. */
    static SurfaceRules.RuleSource surfaceRules(EpicSettings settings) {
        var sand = SurfaceRules.state(Blocks.SAND.defaultBlockState());
        var sandstone = SurfaceRules.state(Blocks.SANDSTONE.defaultBlockState());
        var gravel = SurfaceRules.state(Blocks.GRAVEL.defaultBlockState());
        var clay = SurfaceRules.state(Blocks.CLAY.defaultBlockState());
        var stone = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        var grass = SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState());
        var dirt = SurfaceRules.state(Blocks.DIRT.defaultBlockState());
        var podzol = SurfaceRules.state(Blocks.PODZOL.defaultBlockState());
        var coarse = SurfaceRules.state(Blocks.COARSE_DIRT.defaultBlockState());
        var mossy = SurfaceRules.state(Blocks.MOSS_BLOCK.defaultBlockState());
        var underwater = SurfaceRules.not(SurfaceRules.waterBlockCheck(-1, 0));
        var deep = SurfaceRules.stoneDepthCheck(0, true, 2, CaveSurface.FLOOR);

        var atoll = SurfaceRules.ifTrue(biome("atoll"), SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, sand),
                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, sand),
                SurfaceRules.ifTrue(deep, sandstone)));

        var lake = SurfaceRules.ifTrue(biome("lake"), SurfaceRules.ifTrue(underwater, SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.25), gravel),
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.05, 0.08), clay),
                        sand)),
                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, sand))));

        var rings = SurfaceRules.ifTrue(biome("stone_ring_sea"), SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.steep(), stone),
                SurfaceRules.ifTrue(underwater, SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.1), gravel), sand))),
                // Moss on the rings and stacks, as on the real ones; no soil under it.
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.3), mossy), stone)),
                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, stone)));

        var stoneForest = SurfaceRules.ifTrue(biome("giant_stone_forest"), SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.steep(), stone),
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.ifTrue(SurfaceRules.waterBlockCheck(-1, 0), SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.3), mossy),
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.4, -0.15), podzol),
                        grass))),
                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, dirt)));

        var willow = SurfaceRules.ifTrue(biome("willow_swamp"), SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(underwater, SurfaceRules.sequence(
                                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.0), EpicFeatures.siltRule()),
                                SurfaceRules.state(Blocks.MUD.defaultBlockState()))),
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.45), EpicFeatures.siltRule()),
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.3, -0.1), coarse),
                        grass)),
                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, dirt)));

        var siltstone = SurfaceRules.state(me.nyxia.epicworld.block.ModBlocks.SILTSTONE.get().defaultBlockState());
        var dryRiver = SurfaceRules.ifTrue(biome("dry_riverbed"), SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.4), sand),
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.5, -0.3), coarse),
                        SurfaceRules.state(me.nyxia.epicworld.block.ModBlocks.CRACKED_MUD.get().defaultBlockState()))),
                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, siltstone),
                SurfaceRules.ifTrue(deep, siltstone)));

        // Marble lakes: bare rock (lined with marble by the decoration), a little grass, gravel under the water.
        var marble = SurfaceRules.ifTrue(biome("marble_caves"), SurfaceRules.sequence(
                SurfaceRules.ifTrue(underwater, SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.2), gravel), stone))),
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.steep(), stone),
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.15), grass), stone)),
                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, stone)));

        return SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(), SurfaceRules.sequence(atoll, lake, rings, stoneForest, willow, dryRiver, marble));
    }
}
