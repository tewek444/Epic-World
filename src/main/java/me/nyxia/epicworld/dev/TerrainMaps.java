package me.nyxia.epicworld.dev;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.worldgen.BiomeCatalog;
import me.nyxia.epicworld.worldgen.TerrainEngine;

/**
 * Renders shaded biome maps and terrain statistics straight from {@link TerrainEngine}, without starting Minecraft.
 * Used by {@code gradlew renderTerrainMaps} and by the data-generation verification.
 */
public final class TerrainMaps {
    private TerrainMaps() {}

    public static void main(String[] args) throws IOException {
        if (System.getProperty("epicworld.ridges") != null) {
            var engine = TerrainEngine.get(me.nyxia.epicworld.worldgen.EngineSeed.ofWorldSeed(Long.parseLong(args[1].split(",")[0])), EpicSettings.DEFAULT);
            ridges(engine);
            return;
        }
        if (System.getProperty("epicworld.probe") != null) {
            var engine = TerrainEngine.get(me.nyxia.epicworld.worldgen.EngineSeed.ofWorldSeed(Long.parseLong(args[1].split(",")[0])), EpicSettings.DEFAULT);
            for (String point : System.getProperty("epicworld.probe").split(";")) {
                String[] xz = point.split(",");
                int x = Integer.parseInt(xz[0]);
                int z = Integer.parseInt(xz[1]);
                var c = engine.column(x, z);
                System.out.printf(java.util.Locale.ROOT, "%d,%d h=%.1f land=%.3f biome=%s river=%s %s%n", x, z, c.height, c.land,
                        BiomeCatalog.get(c.biome).path(), c.river, engine.describeRivers(x, z));
            }
            return;
        }
        Path out = Path.of(args.length > 0 ? args[0] : "build/reports/terrain");
        long[] seeds = args.length > 1 ? java.util.Arrays.stream(args[1].split(",")).mapToLong(Long::parseLong).toArray()
                : new long[]{1, 42, 8675309};
        Files.createDirectories(out);
        var report = new StringBuilder();
        for (long seed : seeds) report.append(render(out, seed, EpicSettings.DEFAULT));
        Files.writeString(out.resolve("maps.txt"), report);
        System.out.print(report);
    }

    public static String render(Path out, long seed, EpicSettings settings) throws IOException {
        var engine = TerrainEngine.get(me.nyxia.epicworld.worldgen.EngineSeed.ofWorldSeed(seed), settings);
        var sb = new StringBuilder("world seed=" + seed + "\n");
        long start = System.nanoTime();
        map(engine, out.resolve("seed-" + seed + "-overview.png"), 1024, 64, 0, 0);
        double overviewSeconds = (System.nanoTime() - start) / 1e9;
        String center = System.getProperty("epicworld.center");
        int[] land = center == null ? findLand(engine)
                : java.util.Arrays.stream(center.split(",")).mapToInt(Integer::parseInt).toArray();
        map(engine, out.resolve("seed-" + seed + "-region.png"), 1024, 8, land[0], land[1]);
        map(engine, out.resolve("seed-" + seed + "-detail.png"), 768, 2, land[0], land[1]);
        sb.append(String.format(Locale.ROOT, "  overview render %.2fs (%.1f us/column)%n", overviewSeconds,
                overviewSeconds * 1e6 / (1024.0 * 1024)));
        sb.append(stats(engine));
        sb.append("  nearest to 0,0:");
        for (int i = 0; i < BiomeCatalog.ENTRIES.size(); i++) {
            var pos = engine.locate(i, 0, 0, 60000);
            sb.append(' ').append(BiomeCatalog.get(i).path()).append('=')
                    .append(pos == null ? "-" : pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        sb.append('\n');
        return sb.toString();
    }

    private static boolean oceanBiome(int biome) {
        String path = BiomeCatalog.get(biome).path();
        return path.contains("ocean") || path.equals("atoll") || path.equals("stone_ring_sea");
    }

    /** Finds thin land strips (1-3 blocks) separating the ocean from flooded non-ocean land. */
    static void ridges(TerrainEngine engine) {
        int found = 0;
        var kinds = new TreeMap<String, Integer>();
        for (int row = -60; row <= 60 && found < 4000; row++) {
            int z = row * 160;
            int prevKind = -1;
            int runStart = 0;
            int beforeRun = -1;
            for (int x = -20000; x <= 20000; x++) {
                var c = engine.column(x, z);
                int kind = c.height >= engine.sea ? 1 : oceanBiome(c.biome) ? 0 : 2;
                if (kind != prevKind) {
                    if (prevKind == 1 && x - runStart <= 3 && beforeRun >= 0 && beforeRun != 1 && kind != 1) {
                        int flooded = kind == 2 ? x : runStart - 1;
                        var f = engine.column(flooded, z);
                        var l = engine.column(runStart, z);
                        String key = BiomeCatalog.get(f.biome).path() + " | ridge " + BiomeCatalog.get(l.biome).path();
                        kinds.merge(key, 1, Integer::sum);
                        if (found < 6) {
                            System.out.printf(java.util.Locale.ROOT, "ridge at %d,%d width %d: flooded %s h=%.1f land=%.3f | ridge h=%.1f land=%.3f%n",
                                    runStart, z, x - runStart, BiomeCatalog.get(f.biome).path(), f.height, f.land, l.height, l.land);
                        }
                        found++;
                    }
                    beforeRun = prevKind;
                    prevKind = kind;
                    runStart = x;
                }
            }
        }
        System.out.println("thin ridges found: " + found);
        kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(12)
                .forEach(e -> System.out.println("  " + e.getValue() + "  " + e.getKey()));
    }

    /** A land point near the origin, so region maps show more than open sea. */
    static int[] findLand(TerrainEngine engine) {
        for (int r = 0; r < 200; r++) {
            for (int a = 0; a < 16; a++) {
                int x = (int) (Math.cos(a * Math.PI / 8) * r * 400);
                int z = (int) (Math.sin(a * Math.PI / 8) * r * 400);
                var c = engine.column(x, z);
                if (c.land > 0.3 && c.height > engine.sea + 20) return new int[]{x, z};
            }
        }
        return new int[]{0, 0};
    }

    /** Ocean share and biome distribution over a 1,000,000-block square. */
    public static String stats(TerrainEngine engine) {
        int n = 400;
        double spacing = 2500;
        int[] counts = new int[BiomeCatalog.ENTRIES.size()];
        int ocean = 0;
        int water = 0;
        double minH = Double.MAX_VALUE;
        double maxH = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int x = (int) ((i - n / 2) * spacing + 37);
                int z = (int) ((j - n / 2) * spacing - 91);
                var c = engine.column(x, z);
                counts[c.biome]++;
                String path = BiomeCatalog.get(c.biome).path();
                if (path.contains("ocean") || path.equals("atoll") || path.equals("stone_ring_sea")) ocean++;
                if (c.height < engine.sea) water++;
                minH = Math.min(minH, c.height);
                maxH = Math.max(maxH, c.height);
            }
        }
        double total = (double) n * n;
        var sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "  ocean biome share %.3f (target %.2f), water %.3f, height %.0f..%.0f%n",
                ocean / total, engine.settings.oceans().coverage(), water / total, minH, maxH));
        Map<String, Integer> sorted = new TreeMap<>();
        for (int i = 0; i < counts.length; i++) if (counts[i] > 0) sorted.put(BiomeCatalog.get(i).path(), counts[i]);
        sb.append("  biomes:");
        sorted.forEach((k, v) -> sb.append(String.format(Locale.ROOT, " %s=%.3f", k, v / total)));
        sb.append('\n');
        return sb.toString();
    }

    public static void map(TerrainEngine engine, Path file, int size, int scale, int cx, int cz) throws IOException {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        double[] heights = new double[size * size];
        int[] biomes = new int[size * size];
        IntStream.range(0, size).parallel().forEach(j -> {
            for (int i = 0; i < size; i++) {
                int x = cx + (i - size / 2) * scale;
                int z = cz + (j - size / 2) * scale;
                var c = engine.column(x, z);
                heights[j * size + i] = c.height;
                biomes[j * size + i] = c.biome;
            }
        });
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double h = heights[j * size + i];
                double west = heights[j * size + Math.max(0, i - 1)];
                double north = heights[Math.max(0, j - 1) * size + i];
                double shade = Math.max(-1, Math.min(1, ((h - west) + (h - north)) / scale * 0.9));
                int rgb = color(BiomeCatalog.get(biomes[j * size + i]).path());
                double light = h < engine.sea ? 0.55 + 0.45 * Math.max(0, 1 - (engine.sea - h) / 160.0)
                        : 0.85 + 0.25 * shade + 0.25 * Math.min(1, (h - engine.sea) / 260.0);
                image.setRGB(i, j, scaleColor(rgb, light));
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }

    private static int scaleColor(int rgb, double f) {
        int r = (int) Math.max(0, Math.min(255, ((rgb >> 16) & 255) * f));
        int g = (int) Math.max(0, Math.min(255, ((rgb >> 8) & 255) * f));
        int b = (int) Math.max(0, Math.min(255, (rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    public static int color(String path) {
        return switch (path) {
            case "ocean" -> 0x1E50B4;
            case "deep_ocean" -> 0x0F2E78;
            case "warm_ocean" -> 0x22A7C9;
            case "lukewarm_ocean" -> 0x2678B8;
            case "deep_lukewarm_ocean" -> 0x175584;
            case "cold_ocean" -> 0x274A8F;
            case "deep_cold_ocean" -> 0x16305E;
            case "frozen_ocean" -> 0x7E9BD2;
            case "deep_frozen_ocean" -> 0x4D6AA6;
            case "river" -> 0x3A7BFF;
            case "frozen_river" -> 0xA6C6FF;
            case "lake" -> 0x2F8CE8;
            case "atoll" -> 0x41E0D0;
            case "stone_ring_sea" -> 0x5B6D7A;
            case "beach" -> 0xE8DB9A;
            case "snowy_beach" -> 0xF0F0E4;
            case "stony_shore" -> 0x8A8A8A;
            case "plains" -> 0x8DB360;
            case "sunflower_plains" -> 0xB5C95A;
            case "forest" -> 0x2E7D32;
            case "flower_forest" -> 0x6CA548;
            case "birch_forest" -> 0x58A35C;
            case "old_growth_birch_forest" -> 0x4A8C4F;
            case "dark_forest" -> 0x1B4D1F;
            case "taiga" -> 0x3B6E58;
            case "old_growth_pine_taiga" -> 0x4F6B44;
            case "old_growth_spruce_taiga" -> 0x415E3A;
            case "snowy_taiga" -> 0x9DB8A8;
            case "snowy_plains" -> 0xEDF2F5;
            case "ice_spikes" -> 0xB8E3F5;
            case "grove" -> 0xC9D9D0;
            case "snowy_slopes" -> 0xDDE6EE;
            case "frozen_peaks" -> 0xC4D7F0;
            case "jagged_peaks" -> 0xD8D8E0;
            case "stony_peaks" -> 0x9C9285;
            case "meadow" -> 0x7FC27A;
            case "cherry_grove" -> 0xF2A5C8;
            case "windswept_hills" -> 0x7C8F6E;
            case "windswept_gravelly_hills" -> 0x8E8E86;
            case "windswept_forest" -> 0x58705A;
            case "windswept_savanna" -> 0xA59E54;
            case "savanna" -> 0xBDB25F;
            case "savanna_plateau" -> 0xA79D55;
            case "desert" -> 0xF0D78C;
            case "badlands" -> 0xD9733B;
            case "wooded_badlands" -> 0xB0703E;
            case "eroded_badlands" -> 0xFF6D2E;
            case "jungle" -> 0x3A9B1E;
            case "sparse_jungle" -> 0x66B03A;
            case "bamboo_jungle" -> 0x7CB518;
            case "swamp" -> 0x4C6355;
            case "mangrove_swamp" -> 0x3F6B4E;
            case "willow_swamp" -> 0x5E7F3C;
            case "mushroom_fields" -> 0xB07CC6;
            case "giant_stone_forest" -> 0x7E8C6A;
            case "lush_caves", "dripstone_caves", "deep_dark", "marble_caves", "glowing_caves" -> 0x303030;
            default -> 0xFF00FF;
        };
    }
}
