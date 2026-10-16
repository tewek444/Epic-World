package me.nyxia.epicworld.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import me.nyxia.epicworld.EpicWorld;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

/**
 * World lift support for the saved data of two mods that store world positions outside chunks: Waystones (the
 * waystone list used for teleports) and Create (the train track graph and trains). Their block entities and entities
 * move with the chunks; this moves the positions kept in {@code data/}. Files of mods that are not installed are
 * simply absent and skipped. Other mods are not adjusted.
 */
final class ModData {
    private ModData() {}

    static final String WAYSTONES = "data/waystones.dat";
    static final String CREATE_TRACKS = "data/create_tracks.dat";
    private static final String OVERWORLD = "minecraft:overworld";

    /** Waystones, sharestones and warp plates: {@code data.Waystones[]} with {@code World} and {@code BlockPos}. */
    static void shiftWaystones(Path dir, IntBinaryOperator liftAt) throws IOException {
        Path file = dir.resolve(WAYSTONES);
        if (!Files.exists(file)) return;
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        int moved = 0;
        for (Tag t : root.getCompound("data").getList("Waystones", Tag.TAG_COMPOUND)) {
            var waystone = (CompoundTag) t;
            if (!waystone.getString("World").equals(OVERWORLD)) continue;
            if (waystone.get("BlockPos") instanceof IntArrayTag array && array.size() == 3) {
                int[] p = array.getAsIntArray();
                int lift = liftAt.applyAsInt(p[0], p[2]);
                if (lift == 0) continue;
                p[1] += lift;
                waystone.putIntArray("BlockPos", p);
                moved++;
            } else if (waystone.contains("BlockPos", Tag.TAG_COMPOUND)) {
                var pos = waystone.getCompound("BlockPos");
                int lift = liftAt.applyAsInt(pos.getInt("X"), pos.getInt("Z"));
                if (lift == 0) continue;
                pos.putInt("Y", pos.getInt("Y") + lift);
                moved++;
            }
        }
        write(root, file);
        EpicWorld.LOGGER.info("Epic World: moved {} waystones with the lifted chunks", moved);
    }

    /**
     * Create trains: track graph nodes and bogey positions are {@code {D, Pos}} locations in half blocks (D indexes
     * {@code DimensionPalette}); carriages keep their entity position and positioning anchor per dimension.
     */
    static void shiftCreateTracks(Path dir, IntBinaryOperator liftAt) throws IOException {
        Path file = dir.resolve(CREATE_TRACKS);
        if (!Files.exists(file)) return;
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        CompoundTag data = root.getCompound("data");
        List<String> palette = new ArrayList<>();
        for (Tag t : data.getList("DimensionPalette", Tag.TAG_COMPOUND)) palette.add(((CompoundTag) t).getString("Id"));
        int overworld = palette.indexOf(OVERWORLD);
        if (overworld < 0) return;
        int[] moved = {0};
        shiftLocations(data, overworld, liftAt, moved);
        for (Tag t : data.getList("Trains", Tag.TAG_COMPOUND)) {
            for (Tag c : ((CompoundTag) t).getList("Carriages", Tag.TAG_COMPOUND)) {
                var carriage = (CompoundTag) c;
                boolean inOverworld = false;
                for (Tag p : carriage.getList("EntityPositioning", Tag.TAG_COMPOUND)) {
                    var positioning = (CompoundTag) p;
                    if (positioning.getInt("Dim") != overworld) continue;
                    inOverworld = true;
                    shiftVec(positioning.getList("PositionAnchor", Tag.TAG_DOUBLE), liftAt);
                }
                if (!inOverworld) continue;
                shiftVec(carriage.getCompound("Entity").getList("Pos", Tag.TAG_DOUBLE), liftAt);
                var passengers = carriage.getCompound("Passengers");
                for (String seat : passengers.getAllKeys()) shiftVec(passengers.getCompound(seat).getList("Pos", Tag.TAG_DOUBLE), liftAt);
            }
        }
        write(root, file);
        EpicWorld.LOGGER.info("Epic World: moved {} Create track locations with the lifted chunks", moved[0]);
    }

    /** Every {@code {D, Pos}} location (track nodes, bogey points, signals, stations) in the Overworld. */
    private static void shiftLocations(Tag tag, int overworld, IntBinaryOperator liftAt, int[] moved) {
        if (tag instanceof CompoundTag c) {
            if (c.getTagType("D") == Tag.TAG_INT && c.get("Pos") instanceof IntArrayTag pos && pos.size() == 3) {
                if (c.getInt("D") == overworld) {
                    int[] p = pos.getAsIntArray();
                    int lift = liftAt.applyAsInt(Math.floorDiv(p[0], 2), Math.floorDiv(p[2], 2));
                    if (lift != 0) {
                        p[1] += 2 * lift;
                        c.putIntArray("Pos", p);
                        moved[0]++;
                    }
                }
                return;
            }
            for (String key : c.getAllKeys()) shiftLocations(c.get(key), overworld, liftAt, moved);
        } else if (tag instanceof ListTag list) {
            for (Tag t : list) shiftLocations(t, overworld, liftAt, moved);
        }
    }

    private static void shiftVec(ListTag pos, IntBinaryOperator liftAt) {
        if (pos.size() != 3) return;
        int lift = liftAt.applyAsInt(Mth.floor(pos.getDouble(0)), Mth.floor(pos.getDouble(2)));
        pos.set(1, DoubleTag.valueOf(pos.getDouble(1) + lift));
    }

    private static void write(CompoundTag root, Path file) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, temp);
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
