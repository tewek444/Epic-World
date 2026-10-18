package me.nyxia.epicworld.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import javax.annotation.Nullable;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.registry.ModRegistries;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Exposes {@link TerrainEngine} values to Minecraft's noise router. The engine is seeded through a normal
 * noise holder, so every world seed gets its own deterministic engine without any global state.
 */
public record EpicDensity(Output output, Holder<EpicSettings> settings, DensityFunction.NoiseHolder seedNoise,
                          @Nullable TerrainEngine engine) implements DensityFunction {
    public enum Output implements StringRepresentable {
        CONTINENTALNESS, EROSION, TEMPERATURE, HUMIDITY, HEIGHT, TERRAIN, SURFACE_TERRAIN, DEPTH, BIOME, JAGGEDNESS, ROCKS, TUNNEL;

        public static final Codec<Output> CODEC = StringRepresentable.fromEnum(Output::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Density units per block of height difference; matches the classic overworld steepness. */
    public static final double SCALE = 0.04;

    public static final MapCodec<EpicDensity> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Output.CODEC.fieldOf("output").forGetter(EpicDensity::output),
            RegistryFileCodec.create(ModRegistries.WORLD_SETTINGS, EpicSettings.CODEC).fieldOf("settings").forGetter(EpicDensity::settings),
            DensityFunction.NoiseHolder.CODEC.fieldOf("seed_noise").forGetter(EpicDensity::seedNoise)
    ).apply(i, (output, settings, noise) -> new EpicDensity(output, settings, noise, null)));

    public static final KeyDispatchDataCodec<EpicDensity> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    @Override
    public double compute(FunctionContext context) {
        if (engine == null) return 0;
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();
        // Evaluated for every block (not interpolated): skip the column lookup outside the band tunnels can occupy.
        if (output == Output.TUNNEL && (y < engine.sea - 52 || y > engine.sea + 40)) return 1;
        var column = engine.column(x, z);
        return switch (output) {
            case CONTINENTALNESS -> Math.max(-1.5, Math.min(1.5, column.land));
            case EROSION -> 1 - 2 * column.mountain;
            case TEMPERATURE -> Math.max(-2, Math.min(2, column.temperature * 0.5));
            case HUMIDITY -> Math.max(-2, Math.min(2, column.humidity * 0.5));
            case HEIGHT -> column.height;
            // Preliminary surface for aquifers. Under water it is placed a cell lower: a sea floor less than 8 blocks deep
            // otherwise made the aquifer treat the column as dry land with its own lower water table, which left air
            // pockets under a stone barrier right next to the sea.
            // The same for river tunnels: their water must be the open river's, not an aquifer behind a barrier.
            case SURFACE_TERRAIN -> SCALE * ((column.height < engine.sea ? column.height - 8
                    : column.tunnelKind > 0 ? Math.min(column.height, column.tunnelFloor - 8) : column.height) - y);
            case TERRAIN -> SCALE * (column.height - y);
            case ROCKS -> {
                // Stone rings and pillars, added after caves so caves never cut them into floating pieces.
                if (column.rings <= 0.02 && column.pillars <= 0.02) yield -1;
                double distance = engine.formationDistance(x, y, z, column);
                yield distance < 40 ? Math.max(-1, Math.min(1, -distance * 0.12)) : -1;
            }
            // River tunnels: air inside, no effect elsewhere (combined with min).
            case TUNNEL -> column.tunnelKind > 0 && y > column.tunnelFloor && y < column.tunnelTop ? -1 : 1;
            case DEPTH -> (column.height - y) / 128.0;
            case BIOME -> engine.biomeAt(x, y, z);
            case JAGGEDNESS -> column.jag;
        };
    }

    @Override
    public void fillArray(double[] array, ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(array, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        var noise = visitor.visitNoise(seedNoise);
        TerrainEngine resolved = engine;
        if (resolved == null && noise.noise() != null && settings.isBound()) {
            resolved = TerrainEngine.get(seedOf(noise.noise()), settings.value());
        }
        return visitor.apply(new EpicDensity(output, settings, noise, resolved));
    }

    static long seedOf(NormalNoise noise) {
        return EngineSeed.of(noise);
    }

    @Override
    public double minValue() {
        return switch (output) {
            case CONTINENTALNESS -> -1.5;
            case EROSION -> -1;
            case TEMPERATURE, HUMIDITY -> -2;
            case HEIGHT -> -4096;
            case TERRAIN, SURFACE_TERRAIN -> -SCALE * 4096;
            case DEPTH -> -32;
            case BIOME, JAGGEDNESS -> 0;
            case ROCKS, TUNNEL -> -1;
        };
    }

    @Override
    public double maxValue() {
        return switch (output) {
            case CONTINENTALNESS -> 1.5;
            case EROSION, JAGGEDNESS, ROCKS, TUNNEL -> 1;
            case TEMPERATURE, HUMIDITY -> 2;
            case HEIGHT -> 4096;
            case TERRAIN, SURFACE_TERRAIN -> SCALE * 4096;
            case DEPTH -> 32;
            case BIOME -> BiomeCatalog.ENTRIES.size();
        };
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /** Finds the engine used by a wired noise router (null for non-Epic generators). */
    @Nullable
    public static TerrainEngine find(DensityFunction function) {
        TerrainEngine[] found = new TerrainEngine[1];
        function.mapAll(new Visitor() {
            @Override
            public DensityFunction apply(DensityFunction f) {
                if (f instanceof EpicDensity epic && epic.engine != null) found[0] = epic.engine;
                return f;
            }
        });
        return found[0];
    }
}
