package me.nyxia.epicworld.config;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Every value that influences generation. A world keeps its own copy (see {@code WorldSnapshot});
 * the global config only provides defaults for new worlds.
 *
 * <p>Vertical values are coordinates. {@code height} is the exclusive top of generated terrain and
 * {@code heightLimit} the exclusive top of the build range.
 */
public record EpicSettings(World world, Biomes biomes, Rivers rivers, Lakes lakes, Oceans oceans,
                           Archipelagos archipelagos, Atolls atolls, Compatibility compatibility) {
    /** Bumped whenever the same settings would produce different terrain. Stored in each snapshot. */
    public static final int GENERATOR_VERSION = 5;

    public record World(int minY, int height, int heightLimit, int seaLevel) {
        public static final Codec<World> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("min_y").forGetter(World::minY),
                Codec.INT.fieldOf("height").forGetter(World::height),
                Codec.INT.fieldOf("height_limit").forGetter(World::heightLimit),
                Codec.INT.fieldOf("sea_level").forGetter(World::seaLevel)).apply(i, World::new));
    }

    public record BiomeOverride(Optional<Boolean> enabled, Optional<Double> size, Optional<Integer> weight) {
        public static final Codec<BiomeOverride> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.optionalFieldOf("enabled").forGetter(BiomeOverride::enabled),
                Codec.DOUBLE.optionalFieldOf("size").forGetter(BiomeOverride::size),
                Codec.INT.optionalFieldOf("weight").forGetter(BiomeOverride::weight)).apply(i, BiomeOverride::new));
    }

    public record Biomes(double globalSizeMultiplier, Map<String, BiomeOverride> overrides) {
        public static final Codec<Biomes> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.fieldOf("global_size_multiplier").forGetter(Biomes::globalSizeMultiplier),
                Codec.unboundedMap(Codec.STRING, BiomeOverride.CODEC).optionalFieldOf("overrides", Map.of())
                        .forGetter(Biomes::overrides)).apply(i, Biomes::new));

        public Biomes {
            overrides = Map.copyOf(new TreeMap<>(overrides));
        }
    }

    public record Rivers(boolean enabled, int minWidth, int maxWidth, double widthVariation,
                         boolean depthScalesWithWidth, boolean connectedNetworks, boolean allowMountainGorges) {
        public static final Codec<Rivers> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.fieldOf("enabled").forGetter(Rivers::enabled),
                Codec.INT.fieldOf("min_width").forGetter(Rivers::minWidth),
                Codec.INT.fieldOf("max_width").forGetter(Rivers::maxWidth),
                Codec.DOUBLE.fieldOf("width_variation").forGetter(Rivers::widthVariation),
                Codec.BOOL.fieldOf("depth_scales_with_width").forGetter(Rivers::depthScalesWithWidth),
                Codec.BOOL.fieldOf("connected_river_networks").forGetter(Rivers::connectedNetworks),
                Codec.BOOL.fieldOf("allow_mountain_gorges").forGetter(Rivers::allowMountainGorges)).apply(i, Rivers::new));
    }

    public record Lakes(boolean enabled, int minRadius, int maxRadius, int maxDepth,
                        boolean depthScalesWithSize, boolean generateIslands, boolean connectToRivers) {
        public static final Codec<Lakes> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.fieldOf("enabled").forGetter(Lakes::enabled),
                Codec.INT.fieldOf("min_radius").forGetter(Lakes::minRadius),
                Codec.INT.fieldOf("max_radius").forGetter(Lakes::maxRadius),
                Codec.INT.fieldOf("max_depth").forGetter(Lakes::maxDepth),
                Codec.BOOL.fieldOf("depth_scales_with_size").forGetter(Lakes::depthScalesWithSize),
                Codec.BOOL.fieldOf("generate_islands").forGetter(Lakes::generateIslands),
                Codec.BOOL.fieldOf("connect_to_rivers").forGetter(Lakes::connectToRivers)).apply(i, Lakes::new));
    }

    public record Oceans(boolean enabled, double coverage, boolean underwaterMountains, boolean trenches,
                         int maxDepth, boolean generateIslands) {
        public static final Codec<Oceans> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.fieldOf("enabled").forGetter(Oceans::enabled),
                Codec.DOUBLE.fieldOf("coverage").forGetter(Oceans::coverage),
                Codec.BOOL.fieldOf("underwater_mountains").forGetter(Oceans::underwaterMountains),
                Codec.BOOL.fieldOf("ocean_trenches").forGetter(Oceans::trenches),
                Codec.INT.fieldOf("max_depth").forGetter(Oceans::maxDepth),
                Codec.BOOL.fieldOf("generate_islands").forGetter(Oceans::generateIslands)).apply(i, Oceans::new));
    }

    public record Archipelagos(boolean enabled, int minIslandRadius, int maxIslandRadius) {
        public static final Codec<Archipelagos> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.fieldOf("enabled").forGetter(Archipelagos::enabled),
                Codec.INT.fieldOf("min_island_radius").forGetter(Archipelagos::minIslandRadius),
                Codec.INT.fieldOf("max_island_radius").forGetter(Archipelagos::maxIslandRadius)).apply(i, Archipelagos::new));
    }

    public record Atolls(boolean enabled, int minRadius, int maxRadius, boolean generateLagoons, boolean generateCoralReefs) {
        public static final Codec<Atolls> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.fieldOf("enabled").forGetter(Atolls::enabled),
                Codec.INT.fieldOf("min_radius").forGetter(Atolls::minRadius),
                Codec.INT.fieldOf("max_radius").forGetter(Atolls::maxRadius),
                Codec.BOOL.fieldOf("generate_lagoons").forGetter(Atolls::generateLagoons),
                Codec.BOOL.fieldOf("generate_coral_reefs").forGetter(Atolls::generateCoralReefs)).apply(i, Atolls::new));
    }

    public record Compatibility(boolean terrainBlending, int blendingDistance) {
        public static final Codec<Compatibility> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.fieldOf("terrain_blending").forGetter(Compatibility::terrainBlending),
                Codec.INT.fieldOf("blending_distance").forGetter(Compatibility::blendingDistance)).apply(i, Compatibility::new));
    }

    public static final Codec<EpicSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            World.CODEC.fieldOf("world").forGetter(EpicSettings::world),
            Biomes.CODEC.fieldOf("biomes").forGetter(EpicSettings::biomes),
            Rivers.CODEC.fieldOf("rivers").forGetter(EpicSettings::rivers),
            Lakes.CODEC.fieldOf("lakes").forGetter(EpicSettings::lakes),
            Oceans.CODEC.fieldOf("oceans").forGetter(EpicSettings::oceans),
            Archipelagos.CODEC.fieldOf("archipelagos").forGetter(EpicSettings::archipelagos),
            Atolls.CODEC.fieldOf("atolls").forGetter(EpicSettings::atolls),
            Compatibility.CODEC.fieldOf("compatibility").forGetter(EpicSettings::compatibility)).apply(i, EpicSettings::new));

    public static final EpicSettings DEFAULT = new EpicSettings(
            new World(-64, 448, 1024, 208),
            new Biomes(1.0, Map.of()),
            new Rivers(true, 16, 256, 0.2, true, true, true),
            new Lakes(true, 32, 512, 256, true, true, true),
            new Oceans(true, 0.5, true, true, 256, true),
            new Archipelagos(true, 16, 2048),
            new Atolls(true, 64, 2048, true, true),
            new Compatibility(true, 256));

    /** Highest Y (exclusive) Minecraft 1.21.1 can store. */
    public static final int MAX_BUILD_TOP = 2032;
    public static final int MIN_MIN_Y = -2032;

    /** Throws {@link IllegalArgumentException} with a readable, translated-free message when a value is unusable. */
    public EpicSettings validate() {
        var w = world;
        check(w.minY % 16 == 0, "world.min_y must be a multiple of 16 (got " + w.minY + ")");
        check(w.height % 16 == 0, "world.height must be a multiple of 16 (got " + w.height + ")");
        check(w.heightLimit % 16 == 0, "world.height_limit must be a multiple of 16 (got " + w.heightLimit + ")");
        check(w.minY >= MIN_MIN_Y, "world.min_y must be >= " + MIN_MIN_Y);
        check(w.heightLimit <= MAX_BUILD_TOP, "world.height_limit must be <= " + MAX_BUILD_TOP + " (Minecraft limit)");
        check(w.height - w.minY >= 128, "world.height must be at least 128 blocks above world.min_y");
        check(w.heightLimit >= w.height, "world.height_limit must be >= world.height");
        check(w.heightLimit - w.minY <= 4064, "world.height_limit - world.min_y must be <= 4064");
        check(w.seaLevel >= w.minY + 32 && w.seaLevel <= w.height - 48,
                "world.sea_level must be between min_y + 32 and height - 48 (got " + w.seaLevel + ")");
        check(biomes.globalSizeMultiplier >= 0.1 && biomes.globalSizeMultiplier <= 100,
                "biomes.global_size_multiplier must be between 0.1 and 100");
        for (var entry : biomes.overrides.entrySet()) {
            entry.getValue().size().ifPresent(size -> check(size >= 0.1 && size <= 10,
                    "biomes.overrides." + entry.getKey() + ".size must be between 0.1 and 10"));
            entry.getValue().weight().ifPresent(weight -> check(weight >= 0 && weight <= 10000,
                    "biomes.overrides." + entry.getKey() + ".weight must be between 0 and 10000"));
        }
        check(rivers.minWidth >= 4 && rivers.maxWidth >= rivers.minWidth && rivers.maxWidth <= 1024,
                "rivers: need 4 <= min_width <= max_width <= 1024");
        check(rivers.widthVariation >= 0 && rivers.widthVariation <= 1, "rivers.width_variation must be 0.0 - 1.0");
        check(lakes.minRadius >= 8 && lakes.maxRadius >= lakes.minRadius && lakes.maxRadius <= 4096,
                "lakes: need 8 <= min_radius <= max_radius <= 4096");
        check(lakes.maxDepth >= 2, "lakes.max_depth must be >= 2");
        check(oceans.coverage >= 0 && oceans.coverage <= 0.95, "oceans.coverage must be 0.0 - 0.95");
        check(oceans.maxDepth >= 8, "oceans.max_depth must be >= 8");
        check(archipelagos.minIslandRadius >= 8 && archipelagos.maxIslandRadius >= archipelagos.minIslandRadius
                && archipelagos.maxIslandRadius <= 8192, "archipelagos: need 8 <= min_island_radius <= max_island_radius <= 8192");
        check(atolls.minRadius >= 32 && atolls.maxRadius >= atolls.minRadius && atolls.maxRadius <= 8192,
                "atolls: need 32 <= min_radius <= max_radius <= 8192");
        check(compatibility.blendingDistance >= 0 && compatibility.blendingDistance <= 1024,
                "compatibility.blending_distance must be 0 - 1024");
        return this;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException("Invalid Epic World setting: " + message);
    }

    /** Deepest usable water: never closer than 12 blocks to the bottom of the world. */
    public int usableDepth(int requested) {
        return Math.max(4, Math.min(requested, world.seaLevel - world.minY - 12));
    }
}
