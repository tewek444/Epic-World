package me.nyxia.epicworld.worldgen;

import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.registry.ModRegistries;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import static net.minecraft.world.level.levelgen.DensityFunctions.*;

/**
 * Noise router: the surface comes from {@link TerrainEngine}; caves, aquifers and ore veins follow the vanilla
 * overworld recipe, rescaled to the configured vertical range.
 */
final class TerrainRouter {
    private TerrainRouter() {}

    static final ResourceKey<NormalNoise.NoiseParameters> SEED = ResourceKey.create(Registries.NOISE, ModRegistries.id("seed"));

    static void noises(BootstrapContext<NormalNoise.NoiseParameters> context) {
        context.register(SEED, new NormalNoise.NoiseParameters(0, 1.0));
    }

    static NoiseRouter create(BootstrapContext<?> context, EpicSettings settings) {
        HolderGetter<NormalNoise.NoiseParameters> noise = context.lookup(Registries.NOISE);
        Holder<EpicSettings> settingsHolder = context.lookup(ModRegistries.WORLD_SETTINGS).getOrThrow(ModRegistries.ACTIVE_SETTINGS);
        var seed = new DensityFunction.NoiseHolder(noise.getOrThrow(SEED));
        int minY = settings.world().minY();
        int top = settings.world().height();

        var terrain = epic(EpicDensity.Output.TERRAIN, settingsHolder, seed);
        var jag = flatCache(epic(EpicDensity.Output.JAGGEDNESS, settingsHolder, seed));
        // Up to ~16 blocks of 3D overhangs and spires on mountains; nothing on plains and coasts.
        var relief = mul(constant(0.65), mul(jag.square(), noise(noise.getOrThrow(Noises.JAGGED), 0.8, 0.45)));
        var slopedCheese = add(terrain, relief);

        var entrances = entrances(noise);
        var spaghettiRoughness = cacheOnce(mul(mappedNoise(noise.getOrThrow(Noises.SPAGHETTI_ROUGHNESS_MODULATOR), 0.0, -0.1),
                add(noise(noise.getOrThrow(Noises.SPAGHETTI_ROUGHNESS)).abs(), constant(-0.4))));
        var surfaceCaves = min(slopedCheese, mul(constant(5), entrances));
        var underground = underground(noise, slopedCheese, entrances, spaghettiRoughness, minY, top);
        var shaped = rangeChoice(slopedCheese, -1000000, 1.5625, surfaceCaves, underground);
        var rocks = interpolated(epic(EpicDensity.Output.ROCKS, settingsHolder, seed));
        var finalDensity = max(min(postProcess(slide(shaped, minY, top)), noodle(noise, minY, top)), rocks);

        var surfaceOnly = epic(EpicDensity.Output.SURFACE_TERRAIN, settingsHolder, seed);
        var initial = slide(add(surfaceOnly, constant(0.390625)).clamp(-64, 64), minY, top);

        var y = yClampedGradient(-4064, 4062, -4064, 4062);
        // Vanilla copper (-16..50) and iron (-60..-8) vein ranges.
        int veinMin = -60;
        int veinMax = 50;
        var veinToggle = yLimited(y, noise(noise.getOrThrow(Noises.ORE_VEININESS), 1.5, 1.5), veinMin, veinMax, 0);
        var veinA = yLimited(y, noise(noise.getOrThrow(Noises.ORE_VEIN_A), 4, 4), veinMin, veinMax, 0).abs();
        var veinB = yLimited(y, noise(noise.getOrThrow(Noises.ORE_VEIN_B), 4, 4), veinMin, veinMax, 0).abs();
        var veinRidged = add(constant(-0.08), max(veinA, veinB));

        return new NoiseRouter(
                noise(noise.getOrThrow(Noises.AQUIFER_BARRIER), 0.5),
                noise(noise.getOrThrow(Noises.AQUIFER_FLUID_LEVEL_FLOODEDNESS), 0.67),
                noise(noise.getOrThrow(Noises.AQUIFER_FLUID_LEVEL_SPREAD), 0.7142857142857143),
                noise(noise.getOrThrow(Noises.AQUIFER_LAVA)),
                flatCache(epic(EpicDensity.Output.TEMPERATURE, settingsHolder, seed)),
                flatCache(epic(EpicDensity.Output.HUMIDITY, settingsHolder, seed)),
                flatCache(epic(EpicDensity.Output.CONTINENTALNESS, settingsHolder, seed)),
                flatCache(epic(EpicDensity.Output.EROSION, settingsHolder, seed)),
                epic(EpicDensity.Output.DEPTH, settingsHolder, seed),
                epic(EpicDensity.Output.BIOME, settingsHolder, seed),
                initial,
                finalDensity,
                veinToggle,
                veinRidged,
                noise(noise.getOrThrow(Noises.ORE_GAP)));
    }

    private static DensityFunction epic(EpicDensity.Output output, Holder<EpicSettings> settings, DensityFunction.NoiseHolder seed) {
        return new EpicDensity(output, settings, seed, null);
    }

    private static DensityFunction entrances(HolderGetter<NormalNoise.NoiseParameters> noise) {
        var rarity = cacheOnce(noise(noise.getOrThrow(Noises.SPAGHETTI_3D_RARITY), 2.0, 1.0));
        var thickness = mappedNoise(noise.getOrThrow(Noises.SPAGHETTI_3D_THICKNESS), -0.065, -0.088);
        var a = weirdScaledSampler(rarity, noise.getOrThrow(Noises.SPAGHETTI_3D_1), DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE1);
        var b = weirdScaledSampler(rarity, noise.getOrThrow(Noises.SPAGHETTI_3D_2), DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE1);
        var spaghetti = add(max(a, b), thickness).clamp(-1, 1);
        var roughness = cacheOnce(mul(mappedNoise(noise.getOrThrow(Noises.SPAGHETTI_ROUGHNESS_MODULATOR), 0.0, -0.1),
                add(noise(noise.getOrThrow(Noises.SPAGHETTI_ROUGHNESS)).abs(), constant(-0.4))));
        var entrance = add(add(noise(noise.getOrThrow(Noises.CAVE_ENTRANCE), 0.75, 0.5), constant(0.37)),
                yClampedGradient(-10, 30, 0.3, 0.0));
        return cacheOnce(min(entrance, add(roughness, spaghetti)));
    }

    private static DensityFunction underground(HolderGetter<NormalNoise.NoiseParameters> noise, DensityFunction slopedCheese,
                                               DensityFunction entrances, DensityFunction roughness, int minY, int top) {
        var modulator = noise(noise.getOrThrow(Noises.SPAGHETTI_2D_MODULATOR), 2.0, 1.0);
        var sampler2d = weirdScaledSampler(modulator, noise.getOrThrow(Noises.SPAGHETTI_2D), DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE2);
        var elevation = mappedNoise(noise.getOrThrow(Noises.SPAGHETTI_2D_ELEVATION), 0.0, Math.floorDiv(minY, 8), 8.0);
        var thickness2d = cacheOnce(mappedNoise(noise.getOrThrow(Noises.SPAGHETTI_2D_THICKNESS), 2.0, 1.0, -0.6, -1.3));
        var layer = add(elevation, yClampedGradient(minY, top, 8.0, -40.0)).abs();
        var spaghetti2d = max(add(sampler2d, mul(constant(0.083), thickness2d)), add(layer, thickness2d).cube()).clamp(-1, 1);
        var caveLayer = mul(constant(4), noise(noise.getOrThrow(Noises.CAVE_LAYER), 8.0).square());
        var cheese = add(add(constant(0.27), noise(noise.getOrThrow(Noises.CAVE_CHEESE), 0.6666666666666666)).clamp(-1, 1),
                add(constant(1.5), mul(constant(-0.64), slopedCheese)).clamp(0, 0.5));
        var caves = min(min(add(caveLayer, cheese), entrances), add(spaghetti2d, roughness));
        var pillar = add(mul(noise(noise.getOrThrow(Noises.PILLAR), 25.0, 0.3), constant(2)),
                mappedNoise(noise.getOrThrow(Noises.PILLAR_RARENESS), 0.0, -2.0));
        var pillars = cacheOnce(mul(pillar, mappedNoise(noise.getOrThrow(Noises.PILLAR_THICKNESS), 0.0, 1.1).cube()));
        return max(caves, rangeChoice(pillars, -1000000, 0.03, constant(-1000000), pillars));
    }

    private static DensityFunction noodle(HolderGetter<NormalNoise.NoiseParameters> noise, int minY, int top) {
        var y = yClampedGradient(-4064, 4062, -4064, 4062);
        int lo = minY + 4;
        int hi = top - 64;
        var toggle = yLimited(y, noise(noise.getOrThrow(Noises.NOODLE), 1, 1), lo, hi, -1);
        var thickness = yLimited(y, mappedNoise(noise.getOrThrow(Noises.NOODLE_THICKNESS), 1, 1, -0.05, -0.1), lo, hi, 0);
        var ridgeA = yLimited(y, noise(noise.getOrThrow(Noises.NOODLE_RIDGE_A), 2.6666666666666665, 2.6666666666666665), lo, hi, 0);
        var ridgeB = yLimited(y, noise(noise.getOrThrow(Noises.NOODLE_RIDGE_B), 2.6666666666666665, 2.6666666666666665), lo, hi, 0);
        var ridges = mul(constant(1.5), max(ridgeA.abs(), ridgeB.abs()));
        return rangeChoice(toggle, -1000000, 0, constant(64), add(thickness, ridges));
    }

    private static DensityFunction postProcess(DensityFunction f) {
        return mul(interpolated(blendDensity(f)), constant(0.64)).squeeze();
    }

    /** Solid floor near the bottom of the world, open air near the top of the terrain range. */
    private static DensityFunction slide(DensityFunction f, int minY, int top) {
        var topFade = yClampedGradient(top - 28, top - 8, 1, 0);
        var topped = lerp(topFade, -0.078125, f);
        var bottomFade = yClampedGradient(minY, minY + 24, 0, 1);
        return lerp(bottomFade, 0.1171875, topped);
    }

    private static DensityFunction yLimited(DensityFunction y, DensityFunction inRange, int minY, int maxY, int outside) {
        return interpolated(rangeChoice(y, minY, maxY + 1, inRange, constant(outside)));
    }
}
