package me.nyxia.epicworld.dev;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.config.SettingsToml;
import me.nyxia.epicworld.data.RegionFiles;
import me.nyxia.epicworld.worldgen.EngineSeed;
import me.nyxia.epicworld.worldgen.LegacyIndex;
import me.nyxia.epicworld.worldgen.LiftPlanner;
import me.nyxia.epicworld.worldgen.TerrainEngine;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

/**
 * {@code gradlew previewLift -Pworld=<world folder>}: plans the lift of a world's old chunks (see {@link LiftPlanner})
 * without touching the world, and writes a report and a map to {@code build/reports/lift/<world>/}.
 */
public final class LiftPreview {
    private LiftPreview() {}

    public static void main(String[] args) throws IOException {
        Path world = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);

        CompoundTag level = NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.unlimitedHeap()).getCompound("Data");
        long seed = level.getCompound("WorldGenSettings").getLong("seed");
        EpicSettings settings = EpicSettings.DEFAULT;
        Path snapshot = world.resolve("epicworld/settings.toml");
        if (Files.exists(snapshot)) settings = SettingsToml.parse(Files.readString(snapshot, StandardCharsets.UTF_8), w -> {});
        var engine = TerrainEngine.get(EngineSeed.ofWorldSeed(seed), settings);

        // Chunks recorded as old when the world was converted; without that record every chunk with terrain counts.
        LongOpenHashSet recorded = null;
        Path index = world.resolve("data/epicworld_generation.dat");
        if (Files.exists(index)) {
            recorded = new LongOpenHashSet(NbtIo.readCompressed(index, NbtAccounter.unlimitedHeap()).getCompound("data").getLongArray("legacy"));
        }
        final var legacy = recorded;
        Map<Long, LiftPlanner.OldChunk> old = new HashMap<>();
        long start = System.nanoTime();
        RegionFiles.forEachChunk(world.resolve("region"), (pos, tag) -> {
            if (legacy != null && !legacy.contains(pos.toLong())) return;
            LegacyIndex.parseSurface(tag, -64).ifPresent(s -> old.put(pos.toLong(), new LiftPlanner.OldChunk(s, topY(tag))));
        });
        double readSeconds = (System.nanoTime() - start) / 1e9;
        var plan = LiftPlanner.plan(old, engine);

        var report = new StringBuilder();
        report.append(String.format(Locale.ROOT, "world %s, seed %d, sea %d, height limit %d%n", world, seed, engine.sea,
                settings.world().heightLimit()));
        report.append(String.format(Locale.ROOT, "old chunks: %d (recorded list: %s), read in %.1f s, %d islands%n%n",
                old.size(), legacy != null ? "yes" : "no", readSeconds, plan.islands().size()));
        report.append("island  chunks  blocks x..x / z..z                lift  max   edge  resid50 resid90  off>24  off>48\n");
        for (var island : plan.islands()) {
            report.append(String.format(Locale.ROOT, "%6d  %6d  %7d..%-7d / %7d..%-7d  %4d  %4d  %5d  %7d %7d  %5.0f%%  %5.0f%%%n",
                    island.id(), island.chunks().size(), island.minX() * 16, island.maxX() * 16 + 15, island.minZ() * 16,
                    island.maxZ() * 16 + 15, island.lift(), island.maxLift(), island.edge().size(), island.residual(0.5),
                    island.residual(0.9), 100 * island.shareOffBy(24), 100 * island.shareOffBy(48)));
        }
        Files.writeString(out.resolve("lift-plan.txt"), report, StandardCharsets.UTF_8);
        System.out.print(report);
        map(plan, old, engine, out.resolve("lift-plan.png"));
    }

    /** Top Y of the highest section holding anything but air. */
    private static int topY(CompoundTag tag) {
        int top = Integer.MIN_VALUE;
        for (Tag t : tag.getList("sections", Tag.TAG_COMPOUND)) {
            var section = (CompoundTag) t;
            var palette = section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
            for (Tag p : palette) {
                String name = ((CompoundTag) p).getString("Name");
                if (!name.equals("minecraft:air") && !name.equals("minecraft:cave_air") && !name.equals("minecraft:void_air")) {
                    top = Math.max(top, section.getByte("Y") * 16 + 15);
                    break;
                }
            }
        }
        return top == Integer.MIN_VALUE ? -64 : top;
    }

    /** One pixel per chunk (or per several on huge worlds): new terrain shaded, old chunks tinted by island and lift. */
    private static void map(LiftPlanner.Plan plan, Map<Long, LiftPlanner.OldChunk> old, TerrainEngine engine, Path file) throws IOException {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long c : old.keySet()) {
            minX = Math.min(minX, ChunkPos.getX(c));
            maxX = Math.max(maxX, ChunkPos.getX(c));
            minZ = Math.min(minZ, ChunkPos.getZ(c));
            maxZ = Math.max(maxZ, ChunkPos.getZ(c));
        }
        int margin = 24;
        minX -= margin;
        minZ -= margin;
        maxX += margin;
        maxZ += margin;
        int step = Math.max(1, Math.max(maxX - minX, maxZ - minZ) / 3000 + 1);
        int w = (maxX - minX) / step + 1;
        int h = (maxZ - minZ) / step + 1;
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int cx = minX + i * step;
                int cz = minZ + j * step;
                int id = plan.islandOf().get(ChunkPos.asLong(cx, cz));
                if (id >= 0) {
                    var island = plan.islands().get(id);
                    float hue = (id * 0.618034f) % 1f;
                    float bright = 0.45f + 0.55f * Math.min(1f, island.lift() / 256f);
                    image.setRGB(i, j, Color.HSBtoRGB(hue, 0.55f, bright));
                    continue;
                }
                var col = engine.column(cx * 16 + 8, cz * 16 + 8);
                image.setRGB(i, j, terrain(col.height, engine.sea));
            }
        }
        var g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        for (var island : plan.islands()) {
            if (island.chunks().size() < 16) continue;
            int x = ((island.minX() + island.maxX()) / 2 - minX) / step;
            int z = ((island.minZ() + island.maxZ()) / 2 - minZ) / step;
            String label = "#" + island.id() + " +" + island.lift();
            g.setColor(Color.BLACK);
            g.drawString(label, x + 1, z + 1);
            g.setColor(Color.WHITE);
            g.drawString(label, x, z);
        }
        g.dispose();
        ImageIO.write(image, "png", file.toFile());
    }

    private static int terrain(double height, int sea) {
        if (height < sea) {
            double t = Math.max(0, Math.min(1, (sea - height) / 150));
            return new Color((int) (40 - 25 * t), (int) (110 - 70 * t), (int) (190 - 90 * t)).getRGB();
        }
        double t = Math.max(0, Math.min(1, (height - sea) / 220));
        if (t < 0.5) return new Color((int) (70 + 120 * t), (int) (150 - 20 * t), (int) (60 + 20 * t)).getRGB();
        return new Color((int) (130 + 120 * (t - 0.5)), (int) (120 + 130 * (t - 0.5)), (int) (90 + 160 * (t - 0.5))).getRGB();
    }
}
