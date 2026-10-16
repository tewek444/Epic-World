package me.nyxia.epicworld.datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.neoforged.neoforge.common.Tags;

/**
 * Appends Epic biomes to vanilla and conventional ({@code c:}) biome tags, so structures, mob variants, raids and
 * other mods treat them like their vanilla counterparts. New biomes get hand-picked tags.
 */
final class BiomeCompatibilityData implements DataProvider {
    private final PackOutput output;

    BiomeCompatibilityData(PackOutput output) {
        this.output = output;
    }

    /** Tags for the biomes that have no vanilla counterpart. */
    private static final Map<String, List<String>> NEW = new LinkedHashMap<>();

    private static void add(String tag, String... biomes) {
        NEW.computeIfAbsent(tag, k -> new ArrayList<>()).addAll(List.of(biomes));
    }

    static {
        String[] all = {"lake", "atoll", "stone_ring_sea", "willow_swamp", "giant_stone_forest", "marble_caves", "glowing_caves"};
        add("minecraft:is_overworld", all);
        add("c:is_overworld", all);
        add("minecraft:has_structure/mineshaft", all);
        add("minecraft:stronghold_biased_to", "lake", "willow_swamp", "giant_stone_forest", "marble_caves", "glowing_caves");
        add("minecraft:has_structure/swamp_hut", "willow_swamp");
        add("minecraft:has_structure/ruined_portal_swamp", "willow_swamp");
        add("minecraft:has_structure/ruined_portal_standard", "lake", "giant_stone_forest", "marble_caves", "glowing_caves");
        add("minecraft:has_structure/ruined_portal_ocean", "atoll", "stone_ring_sea");
        add("minecraft:has_structure/shipwreck", "atoll", "stone_ring_sea");
        add("minecraft:has_structure/ocean_ruin_warm", "atoll");
        add("minecraft:has_structure/ocean_ruin_cold", "stone_ring_sea");
        add("minecraft:has_structure/buried_treasure", "atoll");
        add("minecraft:has_structure/trail_ruins", "giant_stone_forest");
        add("minecraft:is_ocean", "atoll", "stone_ring_sea");
        add("minecraft:is_forest", "giant_stone_forest");
        add("minecraft:spawns_warm_variant_frogs", "willow_swamp");
        add("minecraft:allows_surface_slime_spawns", "willow_swamp");
        add("minecraft:has_closer_water_fog", "willow_swamp");
        add("minecraft:produces_corals_from_bonemeal", "atoll");
        add("minecraft:water_on_map_outlines", "lake", "atoll", "stone_ring_sea", "willow_swamp");
        add("minecraft:increased_fire_burnout", "willow_swamp");
        add("c:is_swamp", "willow_swamp");
        add("c:is_wet/overworld", "willow_swamp", "giant_stone_forest", "glowing_caves");
        add("c:is_hot/overworld", "atoll");
        add("c:is_temperate/overworld", "lake", "giant_stone_forest", "marble_caves");
        add("c:is_forest", "giant_stone_forest");
        add("c:is_dense_vegetation/overworld", "willow_swamp", "giant_stone_forest");
        add("c:is_cave", "marble_caves", "glowing_caves");
        add("c:is_underground", "marble_caves", "glowing_caves");
        add("c:is_ocean", "atoll", "stone_ring_sea");
        add("c:is_shallow_ocean", "atoll");
        add("c:is_beach", "atoll");
        add("c:is_mountain", "giant_stone_forest");
        add("c:is_rare", "stone_ring_sea", "giant_stone_forest", "marble_caves", "glowing_caves", "atoll");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        var overworld = new HashSet<String>();
        MultiNoiseBiomeSourceParameterList.knownPresets().get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD)
                .values().forEach(pair -> overworld.add(pair.getSecond().location().toString()));
        Map<String, Set<String>> tags = new LinkedHashMap<>();
        try {
            for (Class<?> holder : List.of(BiomeTags.class, Tags.Biomes.class)) {
                for (var field : holder.getFields()) {
                    if (field.getType() != TagKey.class) continue;
                    var id = ((TagKey<?>) field.get(null)).location();
                    String path = "data/" + id.getNamespace() + "/tags/worldgen/biome/" + id.getPath() + ".json";
                    try (var stream = holder.getResourceAsStream("/" + path)) {
                        if (stream == null) continue;
                        var source = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                        for (var entry : source.getAsJsonArray("values")) {
                            String value = entry.isJsonPrimitive() ? entry.getAsString() : entry.getAsJsonObject().get("id").getAsString();
                            if (overworld.contains(value)) {
                                tags.computeIfAbsent(id.toString(), k -> new LinkedHashSet<>()).add(value.replace("minecraft:", "epicworld:"));
                            }
                        }
                    }
                }
            }
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
        NEW.forEach((tag, biomes) -> biomes.forEach(b -> tags.computeIfAbsent(tag, k -> new LinkedHashSet<>()).add("epicworld:" + b)));
        var futures = new ArrayList<CompletableFuture<?>>();
        tags.forEach((tag, values) -> {
            String[] parts = tag.split(":", 2);
            var json = new JsonObject();
            json.addProperty("replace", false);
            var array = new JsonArray();
            values.forEach(array::add);
            json.add("values", array);
            futures.add(DataProvider.saveStable(cache, json, output.getOutputFolder()
                    .resolve("data/" + parts[0] + "/tags/worldgen/biome/" + parts[1] + ".json")));
        });
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return "Epic World biome tags";
    }
}
