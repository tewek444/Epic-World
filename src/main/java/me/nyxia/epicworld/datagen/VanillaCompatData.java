package me.nyxia.epicworld.datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

/**
 * Vanilla data that names biomes directly: "Adventuring Time", jungle fishing loot and wolf variants.
 * Each copy accepts the vanilla biome or its Epic counterpart, so it keeps working in both world types.
 */
final class VanillaCompatData implements DataProvider {
    private static final List<String> FILES = List.of(
            "advancement/adventure/adventuring_time",
            "loot_table/gameplay/fishing/junk",
            "wolf_variant/ashen", "wolf_variant/black", "wolf_variant/chestnut", "wolf_variant/pale",
            "wolf_variant/rusty", "wolf_variant/snowy", "wolf_variant/spotted", "wolf_variant/striped", "wolf_variant/woods");

    private final PackOutput output;
    private final Set<String> overworld = new HashSet<>();

    VanillaCompatData(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        MultiNoiseBiomeSourceParameterList.knownPresets().get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD)
                .values().forEach(pair -> overworld.add(pair.getSecond().location().toString()));
        var futures = new ArrayList<CompletableFuture<?>>();
        for (String file : FILES) {
            String path = "data/minecraft/" + file + ".json";
            try (var stream = MultiNoiseBiomeSourceParameterList.class.getResourceAsStream("/" + path)) {
                if (stream == null) throw new IllegalStateException("Missing vanilla file " + path);
                JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                if (file.startsWith("advancement")) adventuringTime(json);
                else rewrite(json);
                futures.add(DataProvider.saveStable(cache, json, output.getOutputFolder().resolve(path)));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private JsonElement counterparts(JsonElement biomes) {
        if (biomes.isJsonPrimitive() && overworld.contains(biomes.getAsString())) {
            var list = new JsonArray();
            list.add(biomes.getAsString());
            list.add(biomes.getAsString().replace("minecraft:", "epicworld:"));
            return list;
        }
        if (biomes.isJsonArray()) {
            var list = new JsonArray();
            for (var element : biomes.getAsJsonArray()) {
                list.add(element);
                if (element.isJsonPrimitive() && overworld.contains(element.getAsString())) {
                    list.add(new JsonPrimitive(element.getAsString().replace("minecraft:", "epicworld:")));
                }
            }
            return list;
        }
        return biomes;
    }

    private void rewrite(JsonElement node) {
        if (node.isJsonObject()) {
            var object = node.getAsJsonObject();
            for (var entry : List.copyOf(object.entrySet())) {
                if (entry.getKey().equals("biomes")) object.add("biomes", counterparts(entry.getValue()));
                else rewrite(entry.getValue());
            }
        } else if (node.isJsonArray()) {
            for (var child : node.getAsJsonArray()) rewrite(child);
        }
    }

    /** Each biome requirement becomes "the vanilla biome OR its Epic World copy". */
    private void adventuringTime(JsonObject json) {
        var criteria = json.getAsJsonObject("criteria");
        var requirements = new JsonArray();
        for (var requirement : json.getAsJsonArray("requirements")) {
            var alternatives = new JsonArray();
            for (var name : requirement.getAsJsonArray()) {
                alternatives.add(name);
                String id = name.getAsString();
                if (!overworld.contains(id)) continue;
                String epic = id.replace("minecraft:", "epicworld:");
                var criterion = criteria.getAsJsonObject(id).deepCopy();
                criterion.getAsJsonObject("conditions").getAsJsonArray("player").get(0).getAsJsonObject()
                        .getAsJsonObject("predicate").getAsJsonObject("location").addProperty("biomes", epic);
                criteria.add(epic, criterion);
                alternatives.add(epic);
            }
            requirements.add(alternatives);
        }
        json.add("requirements", requirements);
    }

    @Override
    public String getName() {
        return "Epic World vanilla data compatibility";
    }
}
