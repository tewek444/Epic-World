package me.nyxia.epicworld.worldgen;

import me.nyxia.epicworld.worldgen.noise.Hash;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * The world seed never reaches density functions directly; {@link TerrainEngine} is seeded from the
 * {@code epicworld:seed} noise that Minecraft instantiates per world. This class reproduces that derivation.
 * It has no registry dependencies, so dev tools can use it without bootstrapping Minecraft.
 */
public final class EngineSeed {
    private EngineSeed() {}

    /** Engine seed of a world seed, exactly as {@code RandomState} wires the {@code epicworld:seed} noise. */
    public static long ofWorldSeed(long worldSeed) {
        var random = new XoroshiroRandomSource(worldSeed).forkPositional();
        var noise = NormalNoise.create(random.fromHashOf(ResourceLocation.fromNamespaceAndPath("epicworld", "seed")),
                new NormalNoise.NoiseParameters(0, 1.0));
        return of(noise);
    }

    /** Stable 64-bit seed from a seeded noise instance. */
    public static long of(NormalNoise noise) {
        long seed = 0x5EEDL;
        for (int i = 0; i < 6; i++) {
            double v = noise.getValue(i * 101.37 + 0.31, i * -17.9 + 3.3, i * 53.11 - 7.7);
            seed = Hash.mix(seed, Double.doubleToLongBits(v));
        }
        return seed;
    }
}
