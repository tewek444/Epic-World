package me.nyxia.epicworld.data;

import com.mojang.serialization.Dynamic;
import java.nio.file.Files;
import java.nio.file.Path;
import me.nyxia.epicworld.EpicWorld;
import me.nyxia.epicworld.worldgen.RuntimeWorldgen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * Switches the Overworld of an existing save to Epic World generation. Minecraft never changes the generator of a save
 * on its own, so this happens only when the save contains the file {@value #MARKER}. Existing chunks are kept as they
 * are; new chunks blend into them (see {@code LegacyBlending}).
 *
 * <p>The replacement is done on the raw {@code WorldGenSettings} data before Minecraft decodes it, so an old generator
 * that can no longer be decoded (for example one listing biomes of a removed mod) does not prevent the conversion.
 */
public final class WorldConversion {
    private WorldConversion() {}

    public static final String MARKER = "epicworld/convert";
    private static final String OVERWORLD = LevelStem.OVERWORLD.location().toString();
    private static final String EPIC_GENERATOR = "epicworld:epic_world";

    /**
     * When a world is opened for conversion and has no settings snapshot yet, adapts the height range of Epic World
     * to the old world: a deeper old bottom becomes {@code min_y}, a higher old top raises {@code height_limit}.
     * Otherwise the old chunks would not fit. Runs before the world's data packs (and so the generator) are built.
     */
    public static void prepareSettings(Path dir) {
        if (!Files.exists(dir.resolve(MARKER)) || Files.exists(dir.resolve(WorldSettingsStore.SNAPSHOT))) return;
        try {
            var level = net.minecraft.nbt.NbtIo.readCompressed(dir.resolve("level.dat"), net.minecraft.nbt.NbtAccounter.unlimitedHeap())
                    .getCompound("Data");
            String generator = level.getCompound("WorldGenSettings").getCompound("dimensions").getCompound(OVERWORLD)
                    .getCompound("generator").getString("type");
            if (generator.equals(EPIC_GENERATOR)) return;
            int[] range = {Integer.MAX_VALUE, Integer.MIN_VALUE};
            var waterLevels = new java.util.HashMap<Integer, Integer>();
            RegionFiles.forEachChunk(dir.resolve("region"), (pos, tag) -> {
                if (tag.contains("yPos", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)) range[0] = Math.min(range[0], tag.getInt("yPos") * 16);
                for (var t : tag.getList("sections", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    var section = (net.minecraft.nbt.CompoundTag) t;
                    if (section.contains("block_states")) range[1] = Math.max(range[1], section.getByte("Y") * 16 + 15);
                }
                me.nyxia.epicworld.worldgen.LegacyIndex.parseSurface(tag, -64).ifPresent(s -> {
                    for (short top : s.water()) {
                        if (top != me.nyxia.epicworld.worldgen.LegacyIndex.DRY) waterLevels.merge((int) top, 1, Integer::sum);
                    }
                });
            });
            if (range[0] == Integer.MAX_VALUE) return;
            var global = WorldSettingsStore.global();
            var w = global.world();
            int minY = Math.min(w.minY(), range[0]);
            int limit = Math.max(w.heightLimit(), Math.floorDiv(range[1] + 1 + 15, 16) * 16);
            // Old chunks are lifted by whole sections: pick the sea level nearest to the configured one at which the
            // old sea, lifted, lies exactly at the new sea (no one-block step between old and new water).
            int sea = w.seaLevel();
            boolean lift = !Files.readString(dir.resolve(MARKER)).toLowerCase(java.util.Locale.ROOT).contains("no-lift");
            var oldWater = waterLevels.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey);
            if (lift && oldWater.isPresent()) {
                int top = oldWater.get();
                sea = top + 1 + 16 * Math.round((w.seaLevel() - 1 - top) / 16f);
            }
            if (minY == w.minY() && limit == w.heightLimit() && sea == w.seaLevel()) return;
            var adjusted = new me.nyxia.epicworld.config.EpicSettings(new me.nyxia.epicworld.config.EpicSettings.World(
                    minY, w.height(), limit, sea), global.biomes(), global.rivers(), global.lakes(), global.oceans(),
                    global.archipelagos(), global.atolls(), global.compatibility());
            WorldSettingsStore.writeSnapshot(dir, adjusted);
            EpicWorld.LOGGER.warn("Epic World: the old Overworld of {} spans Y {}..{} with its sea surface at {}; this world gets "
                    + "min_y = {}, height_limit = {}, sea_level = {} (saved in {})", dir, range[0], range[1],
                    oldWater.map(String::valueOf).orElse("-"), minY, limit, sea, dir.resolve(WorldSettingsStore.SNAPSHOT));
        } catch (Exception e) {
            EpicWorld.LOGGER.warn("Epic World: cannot inspect the old Overworld of {} before conversion: {}", dir, e.toString());
        }
    }

    /** Called with the raw {@code WorldGenSettings} of {@code level.dat}. */
    public static <T> Dynamic<T> apply(Dynamic<T> worldGen, RegistryAccess registries) {
        Path dir = WorldSettingsStore.worldDirectory();
        if (dir == null || !Files.exists(dir.resolve(MARKER))) return worldGen;
        var current = worldGen.get("dimensions").get(OVERWORLD);
        String generatorType = current.get("generator").get("type").asString("");
        if (generatorType.equals(EPIC_GENERATOR)) return worldGen;

        LevelStem epic = registries.registryOrThrow(Registries.WORLD_PRESET).getHolder(RuntimeWorldgen.PRESET)
                .flatMap(preset -> preset.value().overworld())
                .orElseThrow(() -> new IllegalStateException("Epic World: the epic_world world preset is missing"));
        checkHeights(dir, current.get("type").asString(""), epic.type().value(), registries);
        T encoded = LevelStem.CODEC.encodeStart(worldGen.getOps(), epic)
                .getOrThrow(error -> new IllegalStateException("Epic World: cannot encode the Overworld: " + error));
        EpicWorld.LOGGER.warn("Epic World: converting the Overworld of {} from {} to Epic World generation (requested by {}). "
                + "Existing chunks keep their blocks; new chunks blend into them.", dir, generatorType, MARKER);
        return worldGen.update("dimensions", dims -> dims.set(OVERWORLD, new Dynamic<>(worldGen.getOps(), encoded)));
    }

    /** Existing chunks must keep their vertical position and all their blocks. */
    private static void checkHeights(Path dir, String typeId, DimensionType to, RegistryAccess registries) {
        var location = ResourceLocation.tryParse(typeId);
        if (location == null) return;
        var from = registries.registryOrThrow(Registries.DIMENSION_TYPE).get(ResourceKey.create(Registries.DIMENSION_TYPE, location));
        if (from == null) {
            EpicWorld.LOGGER.warn("Epic World: unknown dimension type {} of the old Overworld; cannot check its height range", typeId);
            return;
        }
        int fromTop = from.minY() + from.height();
        if (to.minY() > from.minY() || to.minY() + to.height() < fromTop) {
            throw new IllegalStateException("Epic World: cannot convert " + dir + ": the existing Overworld spans Y "
                    + from.minY() + ".." + (fromTop - 1) + ", Epic World settings give " + to.minY() + ".."
                    + (to.minY() + to.height() - 1) + ". Set world.min_y = " + from.minY()
                    + " and world.height_limit >= " + fromTop + " in config/epicworld.toml, or remove " + dir.resolve(MARKER));
        }
    }
}
