package me.nyxia.epicworld.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Biomes chosen by {@link TerrainEngine}. The engine's choice reaches this source through the climate sampler's
 * "weirdness" slot, which Epic World's router fills with the biome index (see {@link EpicDensity.Output#BIOME}).
 * The list order equals {@link BiomeCatalog#ENTRIES}.
 */
public final class EpicBiomeSource extends BiomeSource {
    /**
     * The list is rebuilt from the biome registry in {@link BiomeCatalog} order on every load and not stored: a world
     * created by an older version keeps its stored list (without biomes added since), and new indices would fall back
     * to the first biome. A stored {@code biomes} field of older saves is ignored.
     */
    public static final MapCodec<EpicBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            net.minecraft.resources.RegistryOps.retrieveGetter(net.minecraft.core.registries.Registries.BIOME)
    ).apply(i, EpicBiomeSource::fromRegistry));

    static EpicBiomeSource fromRegistry(net.minecraft.core.HolderGetter<Biome> registry) {
        var list = new java.util.ArrayList<Holder<Biome>>();
        for (var entry : BiomeCatalog.ENTRIES) list.add(registry.getOrThrow(RuntimeWorldgen.biomeKey(entry.path())));
        return new EpicBiomeSource(list);
    }

    private final List<Holder<Biome>> biomes;
    /** Biome path (e.g. "plains") to list index, for continuing biomes of old chunks (see {@link LegacyBiomes}). */
    private volatile java.util.Map<String, Integer> byPath;

    public EpicBiomeSource(List<Holder<Biome>> biomes) {
        this.biomes = List.copyOf(biomes);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return biomes.stream().distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        int old = LegacyBiomes.at(x << 2, z << 2, this::indexOf);
        if (old >= 0) return biomes.get(old);
        double value = sampler.weirdness().compute(new DensityFunction.SinglePointContext(x << 2, y << 2, z << 2));
        int index = (int) Math.round(value);
        if (index < 0 || index >= biomes.size()) index = 0;
        return biomes.get(index);
    }

    /** Index of an old chunk's biome in this source: vanilla ids map to their Epic copies; unknown ones give -1. */
    private int indexOf(String id) {
        var map = byPath;
        if (map == null) {
            var built = new java.util.HashMap<String, Integer>();
            for (int i = 0; i < biomes.size(); i++) {
                int index = i;
                biomes.get(i).unwrapKey().ifPresent(k -> built.putIfAbsent(k.location().getPath(), index));
            }
            byPath = map = built;
        }
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        if (!namespace.equals("minecraft") && !namespace.equals("epicworld")) return -1;
        return map.getOrDefault(id.substring(colon + 1), -1);
    }

    @Override
    public void addDebugInfo(List<String> info, net.minecraft.core.BlockPos pos, Climate.Sampler sampler) {
        info.add("Epic World biome index: " + Math.round(sampler.weirdness().compute(
                new DensityFunction.SinglePointContext(pos.getX(), pos.getY(), pos.getZ()))));
    }
}
