package me.nyxia.epicworld.data;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

/**
 * Reads chunk NBT straight from {@code .mca} region files, without a running server. Used to plan a world conversion
 * before the world is loaded.
 */
public final class RegionFiles {
    private RegionFiles() {}

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /** Calls {@code consumer} for every chunk stored in the region folder. Unreadable chunks are skipped. */
    public static void forEachChunk(Path regionDir, BiConsumer<ChunkPos, CompoundTag> consumer) throws IOException {
        if (!Files.isDirectory(regionDir)) return;
        try (var files = Files.list(regionDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                var m = REGION.matcher(file.getFileName().toString());
                if (!m.matches()) continue;
                int rx = Integer.parseInt(m.group(1));
                int rz = Integer.parseInt(m.group(2));
                byte[] data = Files.readAllBytes(file);
                if (data.length < 8192) continue;
                for (int i = 0; i < 1024; i++) {
                    var pos = new ChunkPos(rx * 32 + (i & 31), rz * 32 + (i >> 5));
                    CompoundTag tag = read(data, i, regionDir, pos);
                    if (tag != null) consumer.accept(pos, tag);
                }
            }
        }
    }

    @Nullable
    private static CompoundTag read(byte[] data, int index, Path regionDir, ChunkPos pos) {
        int offset = readInt(data, index * 4);
        if (offset == 0) return null;
        int sector = (offset >>> 8) * 4096;
        if (sector + 5 > data.length) return null;
        int length = readInt(data, sector);
        int type = data[sector + 4] & 0xFF;
        try {
            InputStream raw;
            if ((type & 0x80) != 0) {
                // Oversized chunk stored next to the region file.
                Path external = regionDir.resolve("c." + pos.x + "." + pos.z + ".mcc");
                if (!Files.exists(external)) return null;
                raw = Files.newInputStream(external);
                type &= 0x7F;
            } else {
                if (length <= 1 || sector + 4 + length > data.length) return null;
                raw = new ByteArrayInputStream(data, sector + 5, length - 1);
            }
            InputStream in = switch (type) {
                case 1 -> new GZIPInputStream(raw);
                case 2 -> new InflaterInputStream(raw);
                case 3 -> raw;
                default -> null;
            };
            if (in == null) return null;
            try (var stream = new DataInputStream(in)) {
                return NbtIo.read(stream, NbtAccounter.unlimitedHeap());
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Rewrites one region file: every stored chunk goes through {@code transform} (returning the same tag unchanged is
     * fine; returning {@code null} keeps the original bytes). Timestamps are kept. The new file replaces the old one
     * only once it is completely written.
     */
    public static void rewrite(Path file, java.util.function.BiFunction<ChunkPos, CompoundTag, CompoundTag> transform) throws IOException {
        var m = REGION.matcher(file.getFileName().toString());
        if (!m.matches()) return;
        int rx = Integer.parseInt(m.group(1));
        int rz = Integer.parseInt(m.group(2));
        Path dir = file.getParent();
        byte[] data = Files.readAllBytes(file);
        if (data.length < 8192) return;
        var out = new java.io.ByteArrayOutputStream(data.length);
        byte[] header = new byte[8192];
        System.arraycopy(data, 4096, header, 4096, 4096); // timestamps
        out.write(header);
        int nextSector = 2;
        for (int i = 0; i < 1024; i++) {
            if (readInt(data, i * 4) == 0) continue;
            var pos = new ChunkPos(rx * 32 + (i & 31), rz * 32 + (i >> 5));
            CompoundTag tag = read(data, i, dir, pos);
            if (tag == null) continue;
            CompoundTag result = transform.apply(pos, tag);
            if (result == null) result = tag;
            var compressed = new java.io.ByteArrayOutputStream();
            try (var stream = new java.io.DataOutputStream(new java.util.zip.DeflaterOutputStream(compressed))) {
                NbtIo.write(result, stream);
            }
            byte[] payload = compressed.toByteArray();
            Path external = dir.resolve("c." + pos.x + "." + pos.z + ".mcc");
            byte[] entry;
            if (payload.length + 5 > 255 * 4096) {
                Files.write(external, payload);
                entry = new byte[]{0, 0, 0, 1, (byte) (2 | 0x80)};
            } else {
                Files.deleteIfExists(external);
                entry = new byte[5 + payload.length];
                writeInt(entry, 0, payload.length + 1);
                entry[4] = 2;
                System.arraycopy(payload, 0, entry, 5, payload.length);
            }
            int sectors = (entry.length + 4095) / 4096;
            writeInt(header, i * 4, (nextSector << 8) | sectors);
            out.write(entry);
            out.write(new byte[sectors * 4096 - entry.length]);
            nextSector += sectors;
        }
        byte[] result = out.toByteArray();
        System.arraycopy(header, 0, result, 0, 4096);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, result);
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeInt(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    private static int readInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }
}
