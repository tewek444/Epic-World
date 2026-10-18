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
        if (System.getProperty("epicworld.tunnels") != null) {
            int size = Integer.parseInt(System.getProperty("epicworld.tunnels"));
            for (String seed : args[1].split(",")) {
                var engine = TerrainEngine.get(me.nyxia.epicworld.worldgen.EngineSeed.ofWorldSeed(Long.parseLong(seed)), EpicSettings.DEFAULT);
                int step = 8;
                int n = size / step;
                int[] kinds = new int[4];
                var examples = new java.util.TreeMap<Integer, String>();
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        int x = (i - n / 2) * step;
                        int z = (j - n / 2) * step;
                        var c = engine.column(x, z);
                        if (c.tunnelKind == 0) continue;
                        kinds[c.tunnelKind]++;
                        examples.putIfAbsent(c.tunnelKind * 1000 + examples.size(), String.format(Locale.ROOT,
                                "kind %d at %d %d floor %.0f top %.0f surface %.0f", c.tunnelKind, x, z, c.tunnelFloor, c.tunnelTop, c.height));
                    }
                }
                System.out.printf(Locale.ROOT, "seed %s, %d x %d: tunnel columns (8-block grid) plain %d, glowing %d, glowworms %d%n",
                        seed, size, size, kinds[1], kinds[2], kinds[3]);
                examples.values().stream().filter(e -> e.contains("floor 20")).limit(400).forEach(e -> System.out.println("  " + e));
            }
            return;
        }
        if (System.getProperty("epicworld.crossings") != null) {
            for (String seed : args[1].split(",")) {
                var engine = TerrainEngine.get(me.nyxia.epicworld.worldgen.EngineSeed.ofWorldSeed(Long.parseLong(seed)), EpicSettings.DEFAULT);
                crossings(engine, Long.parseLong(seed), Integer.parseInt(System.getProperty("epicworld.crossings")));
            }
            return;
        }
        if (System.getProperty("epicworld.regions") != null) {
            String[] spec = System.getProperty("epicworld.regions").split(":");
            for (String seed : args[1].split(",")) {
                var engine = TerrainEngine.get(me.nyxia.epicworld.worldgen.EngineSeed.ofWorldSeed(Long.parseLong(seed)), EpicSettings.DEFAULT);
                regions(engine, Long.parseLong(seed), spec[0], Integer.parseInt(spec[1]));
            }
            return;
        }
        if (System.getProperty("epicworld.slivers") != null) {
            for (String seed : args[1].split(",")) {
                var engine = TerrainEngine.get(me.nyxia.epicworld.worldgen.EngineSeed.ofWorldSeed(Long.parseLong(seed)), EpicSettings.DEFAULT);
                slivers(engine, Long.parseLong(seed), Integer.parseInt(System.getProperty("epicworld.slivers")));
            }
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
    /**
     * Meetings of rivers of different layers within {@code size} blocks around the origin (4-block grid). A meeting is
     * a connected patch covered by two layers; it is a crossing when the narrower river leaves the patch in two or more
     * separate places (it goes through), a junction when it touches it from one side only (a tributary).
     */
    static void crossings(TerrainEngine engine, long seed, int size) {
        int step = 4;
        int n = size / step;
        int[][] mask = new int[n][n];
        IntStream.range(0, n).parallel().forEach(i -> {
            for (int j = 0; j < n; j++) mask[i][j] = engine.column((i - n / 2) * step, (j - n / 2) * step).riverChannels;
        });
        boolean[][] seen = new boolean[n][n];
        int crossings = 0;
        int junctions = 0;
        var examples = new java.util.ArrayList<String>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (Integer.bitCount(mask[i][j]) < 2 || seen[i][j]) continue;
                // The patch where two (or more) layers overlap.
                var patch = new java.util.ArrayList<int[]>();
                var stack = new java.util.ArrayDeque<int[]>();
                stack.push(new int[]{i, j});
                seen[i][j] = true;
                int both = mask[i][j];
                while (!stack.isEmpty()) {
                    int[] q = stack.pop();
                    patch.add(q);
                    for (int[] d : dirs) {
                        int a = q[0] + d[0], b = q[1] + d[1];
                        if (a < 0 || b < 0 || a >= n || b >= n || seen[a][b] || Integer.bitCount(mask[a][b]) < 2) continue;
                        seen[a][b] = true;
                        both |= mask[a][b];
                        stack.push(new int[]{a, b});
                    }
                }
                int narrow = 31 - Integer.numberOfLeadingZeros(both);
                // Cells of the narrow river alone next to the patch, grouped into separate arms.
                var arm = new java.util.HashSet<Long>();
                for (int[] q : patch) {
                    for (int[] d : dirs) {
                        int a = q[0] + d[0], b = q[1] + d[1];
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        if (mask[a][b] == 1 << narrow) arm.add((long) a * n + b);
                    }
                }
                int arms = 0;
                var armSeen = new java.util.HashSet<Long>();
                for (long start : arm) {
                    if (armSeen.contains(start)) continue;
                    arms++;
                    var s2 = new java.util.ArrayDeque<Long>();
                    s2.push(start);
                    armSeen.add(start);
                    while (!s2.isEmpty()) {
                        long c = s2.pop();
                        int a = (int) (c / n), b = (int) (c % n);
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                long o = (long) (a + dx) * n + (b + dz);
                                if (arm.contains(o) && armSeen.add(o)) s2.push(o);
                            }
                        }
                    }
                }
                int[] c = patch.get(patch.size() / 2);
                if (arms >= 2) {
                    crossings++;
                    if (examples.size() < 8) examples.add(((c[0] - n / 2) * step) + "," + ((c[1] - n / 2) * step));
                } else {
                    junctions++;
                }
            }
        }
        System.out.printf(Locale.ROOT, "seed %d, %d x %d blocks: %d crossings, %d junctions; crossings e.g. %s%n",
                seed, size, size, crossings, junctions, String.join("  ", examples));
    }

    /**
     * Connected regions of one surface biome within {@code size} blocks around the origin, sampled every 16 blocks:
     * count, area, mean height inside and mean height of the ring of other land just outside each region.
     */
    static void regions(TerrainEngine engine, long seed, String biome, int size) {
        int step = 16;
        int n = size / step;
        int target = BiomeCatalog.index(biome);
        int sea = EpicSettings.DEFAULT.world().seaLevel();
        boolean[][] in = new boolean[n][n];
        double[][] height = new double[n][n];
        boolean[][] land = new boolean[n][n];
        IntStream.range(0, n).parallel().forEach(i -> {
            for (int j = 0; j < n; j++) {
                int ox = Integer.getInteger("epicworld.regionsX", 0);
                int oz = Integer.getInteger("epicworld.regionsZ", 0);
                var c = engine.column(ox + (i - n / 2) * step, oz + (j - n / 2) * step);
                in[i][j] = c.biome == target;
                height[i][j] = c.height;
                land[i][j] = c.height >= sea;
            }
        });
        boolean[][] seen = new boolean[n][n];
        var sizes = new java.util.ArrayList<String>();
        int count = 0;
        long cells = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!in[i][j] || seen[i][j]) continue;
                var stack = new java.util.ArrayDeque<int[]>();
                stack.push(new int[]{i, j});
                seen[i][j] = true;
                int area = 0;
                double sum = 0;
                long sumX = 0;
                long sumZ = 0;
                double ringSum = 0;
                int ring = 0;
                var ringSeen = new java.util.HashSet<Long>();
                while (!stack.isEmpty()) {
                    int[] q = stack.pop();
                    area++;
                    sum += height[q[0]][q[1]];
                    sumX += q[0];
                    sumZ += q[1];
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int a = q[0] + d[0];
                        int b = q[1] + d[1];
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        if (in[a][b]) {
                            if (!seen[a][b]) {
                                seen[a][b] = true;
                                stack.push(new int[]{a, b});
                            }
                        } else if (land[a][b] && ringSeen.add((long) a * n + b)) {
                            ringSum += height[a][b];
                            ring++;
                        }
                    }
                }
                count++;
                cells += area;
                if (sizes.size() < 400) sizes.add(String.format(Locale.ROOT, "%d:%.0f/%.0f@%d,%d", area,
                        sum / area - sea, ring == 0 ? Double.NaN : ringSum / ring - sea, (sumX / area - n / 2) * step, (sumZ / area - n / 2) * step));
            }
        }
        sizes.sort(java.util.Comparator.comparingInt((String t) -> Integer.parseInt(t.substring(0, t.indexOf(':')))).reversed());
        System.out.printf(Locale.ROOT, "seed %d, %s in %d x %d blocks: %d regions, %.2f %% of the area, %.1f km2%n", seed, biome,
                size, size, count, 100.0 * cells / ((double) n * n), cells * step * step / 1e6);
        System.out.println("  largest (cells of 16x16 : height inside / height around, above sea @ centre x,z): "
                + String.join("  ", sizes.subList(0, Math.min(12, sizes.size()))));
    }

    /**
     * Counts thin strips of land in the water: dry columns at most 3 blocks wide between water on both sides along x or
     * z, within {@code size} blocks around the origin. Prints the count per cause and a few examples.
     */
    static void slivers(TerrainEngine engine, long seed, int size) {
        int sea = EpicSettings.DEFAULT.world().seaLevel();
        int n = size / 2;
        var counts = new TreeMap<String, Integer>();
        var examples = new TreeMap<String, String>();
        int[] total = {0};
        String out = System.getProperty("epicworld.sliversOut");
        java.util.List<String> all = out == null ? null : new java.util.ArrayList<>();
        IntStream.range(-n, n).parallel().forEach(z -> {
            boolean[] wet = new boolean[size + 2];
            String[] biome = new String[size + 2];
            for (int i = 0; i < size + 2; i++) {
                var c = engine.column(i - n - 1, z);
                wet[i] = c.height < sea;
                biome[i] = BiomeCatalog.get(c.biome).path();
            }
            for (int i = 1; i < size + 1; i++) {
                if (wet[i] || !wet[i - 1]) continue;
                int j = i;
                while (j < size + 1 && !wet[j]) j++;
                if (j < size + 1 && j - i <= 3) {
                    // Cause: the water biomes on either side.
                    String key = biome[i - 1] + " | " + biome[j];
                    synchronized (counts) {
                        counts.merge(key, 1, Integer::sum);
                        examples.putIfAbsent(key, (i - n - 1) + " " + z);
                        if (all != null) all.add(key + ";" + (i - n - 1) + ";" + z);
                        total[0]++;
                    }
                }
                i = j;
            }
        });
        System.out.printf(Locale.ROOT, "seed %d, %d x %d blocks: %d thin strips%n", seed, size, size, total[0]);
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15)
                .forEach(e -> System.out.printf(Locale.ROOT, "  %6d  %-40s e.g. %s%n", e.getValue(), e.getKey(), examples.get(e.getKey())));
        if (all != null) {
            try {
                Files.write(Path.of(out + "-" + seed + ".txt"), all.stream().sorted().toList());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

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
            case "dry_riverbed" -> 0xA0805A;
            case "lush_caves", "dripstone_caves", "deep_dark", "marble_caves", "glowing_caves" -> 0x303030;
            default -> 0xFF00FF;
        };
    }
}
