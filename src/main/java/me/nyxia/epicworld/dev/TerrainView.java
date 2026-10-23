package me.nyxia.epicworld.dev;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.worldgen.BiomeCatalog;
import me.nyxia.epicworld.worldgen.EngineSeed;
import me.nyxia.epicworld.worldgen.TerrainEngine;

/**
 * Perspective landscape views straight from {@link TerrainEngine}, without starting Minecraft: a ray-marched height
 * field with biome colours, snow, slope light and haze. For judging relief (hills, escarpments, mountains) by eye.
 * <p>
 * {@code gradlew renderTerrainView -Pviews="seed:x,z,yaw,pitch,altitude;..."}: camera at block (x, z), altitude blocks
 * above the ground there, yaw in degrees (0 = looking north, 90 = east), pitch in degrees (negative = down).
 * {@code -Pviews="seed:auto"} looks at the highest ground within 12 km of the origin from 6 km away.
 */
public final class TerrainView {
    private TerrainView() {}

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final double FOV = Math.toRadians(70);
    private static final double FAR = 16000;
    private static final int GRID = 4;
    private static final int SPAN = (int) (2 * FAR / GRID) + 8;

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        String views = System.getProperty("epicworld.views", "1:auto");
        int n = 0;
        for (String view : views.split(";")) {
            String[] parts = view.split(":");
            long seed = Long.parseLong(parts[0]);
            var engine = TerrainEngine.get(EngineSeed.ofWorldSeed(seed), EpicSettings.DEFAULT);
            if (parts[1].startsWith("find")) {
                find(engine, Integer.parseInt(parts[1].substring(4)));
                continue;
            }
            if (parts[1].startsWith("extent")) {
                String[] xz = parts[1].substring(6).split(",");
                extent(engine, Integer.parseInt(xz[0]), Integer.parseInt(xz[1]));
                continue;
            }
            if (parts[1].startsWith("debug")) {
                for (String pt : parts[1].substring(5).split("/")) {
                    String[] xz = pt.split(",");
                    System.out.println(pt + ": " + engine.debugColumn(Integer.parseInt(xz[0]), Integer.parseInt(xz[1])));
                }
                continue;
            }
            if (parts[1].startsWith("cliffs")) {
                String[] xz = parts[1].substring(6).split(",");
                int cx = Integer.parseInt(xz[0]);
                int cz = Integer.parseInt(xz[1]);
                int cliffs = 0;
                for (int x = cx - 3000; x < cx + 3000; x += 4) {
                    for (int z = cz - 3000; z < cz + 3000; z += 4) {
                        var a = engine.column(x, z);
                        var b = engine.column(x + 4, z);
                        double dh = b.height - a.height;
                        if (Math.abs(dh) > 40) {
                            cliffs++;
                            if (cliffs % 50 == 1) {
                                var hi = dh > 0 ? b : a;
                                var lo = dh > 0 ? a : b;
                                System.out.printf(Locale.ROOT, "  cliff at %d %d dh=%.0f high %s (h=%.0f m=%.2f) low %s (h=%.0f river %b tunnel %d)%n", x, z, dh,
                                        BiomeCatalog.get(hi.biome).path(), hi.height, hi.mountain, BiomeCatalog.get(lo.biome).path(), lo.height,
                                        lo.river || lo.riverChannels != 0, lo.tunnelKind);
                            }
                        }
                    }
                }
                System.out.printf(Locale.ROOT, "seed %d cliffs (>40 over 4 blocks) around %d %d: %d%n", seed, cx, cz, cliffs);
                continue;
            }
            if (parts[1].equals("fade")) {
                int all = 0, faded = 0, partial = 0;
                for (int x = -12000; x <= 12000; x += 8) {
                    for (int z = -12000; z <= 12000; z += 8) {
                        var c = engine.column(x, z);
                        if (Double.isNaN(c.riverKeep)) continue;
                        all++;
                        if (c.riverKeep < 0.05) faded++;
                        else if (c.riverKeep < 0.95) partial++;
                    }
                }
                System.out.printf(Locale.ROOT, "seed %d river channel samples %d: faded %.1f%%, partial %.1f%%%n", seed, all,
                        100.0 * faded / Math.max(1, all), 100.0 * partial / Math.max(1, all));
                continue;
            }
            if (parts[1].startsWith("riverspots")) {
                for (int x = -12000; x <= 12000; x += 400) {
                    for (int z = -12000; z <= 12000; z += 400) {
                        var c = engine.column(x, z);
                        if (c.river && c.height < engine.sea - 2) System.out.printf(Locale.ROOT, "river at %d %d (h=%.0f, biome %s)%n", x, z, c.height,
                                BiomeCatalog.get(c.biome).path());
                    }
                }
                continue;
            }
            if (parts[1].startsWith("quality")) {
                String[] xz = parts[1].substring(7).split(",");
                quality(engine, Integer.parseInt(xz[0]), Integer.parseInt(xz[1]));
                continue;
            }
            if (parts[1].equals("rivers")) {
                rivers(engine);
                continue;
            }
            if (parts[1].equals("stats")) {
                stats(engine, seed);
                continue;
            }
            double[] cam = switch (parts[1]) {
                case "auto" -> auto(engine, 6000, 0);
                case "near" -> auto(engine, 2200, 1);
                case "low" -> lowland(engine);
                default -> parse(parts[1]);
            };
            String name = String.format(Locale.ROOT, "view-%d-%d.png", seed, n++);
            long start = System.nanoTime();
            render(engine, cam, out.resolve(name));
            System.out.printf(Locale.ROOT, "%s: camera %.0f,%.0f yaw %.0f pitch %.0f altitude %.0f (%.1fs)%n", name,
                    cam[0], cam[1], cam[2], cam[3], cam[4], (System.nanoTime() - start) / 1e9);
        }
    }

    private static double[] parse(String s) {
        String[] v = s.split(",");
        double[] d = new double[5];
        for (int i = 0; i < 5; i++) d[i] = Double.parseDouble(v[i]);
        return d;
    }

    /** Peaks over 400 blocks above the sea within {@code radius} blocks of the origin, nearest first. */
    private static void find(TerrainEngine engine, int radius) {
        var found = new java.util.ArrayList<int[]>();
        for (int x = -radius; x <= radius; x += 256) {
            for (int z = -radius; z <= radius; z += 256) {
                double h = engine.column(x, z).height;
                if (h - engine.sea > Integer.getInteger("epicworld.findAbove", 400)) found.add(new int[]{x, z, (int) h});
            }
        }
        found.sort(java.util.Comparator.comparingDouble(p -> Math.hypot(p[0], p[1])));
        System.out.println("peaks over sea+400 within " + radius + ": " + found.size());
        found.stream().limit(15).forEach(p -> System.out.printf(Locale.ROOT, "  %d %d y=%d (%.1f km)%n", p[0], p[1], p[2], Math.hypot(p[0], p[1]) / 1000));
    }

    /** Bounding box of the giant range containing a point (flood fill on a 64-block grid). */
    private static void extent(TerrainEngine engine, int x0, int z0) {
        int step = 64;
        var seen = new java.util.HashSet<Long>();
        var queue = new java.util.ArrayDeque<int[]>();
        int sx = Math.floorDiv(x0, step);
        int sz = Math.floorDiv(z0, step);
        queue.add(new int[]{sx, sz});
        seen.add(((long) sx << 32) ^ (sz & 0xFFFFFFFFL));
        int minX = sx, maxX = sx, minZ = sz, maxZ = sz;
        double top = 0;
        while (!queue.isEmpty() && seen.size() < 2_000_000) {
            int[] c = queue.poll();
            if (engine.giantMask(c[0] * step, c[1] * step) <= 0.02) continue;
            top = Math.max(top, engine.column(c[0] * step, c[1] * step).height);
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minZ = Math.min(minZ, c[1]);
            maxZ = Math.max(maxZ, c[1]);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = c[0] + d[0];
                int nz = c[1] + d[1];
                if (seen.add(((long) nx << 32) ^ (nz & 0xFFFFFFFFL))) queue.add(new int[]{nx, nz});
            }
        }
        System.out.printf(Locale.ROOT, "giant range around %d %d: x %d..%d, z %d..%d (%.1f x %.1f km), highest sampled y=%.0f%n", x0, z0,
                minX * step, maxX * step, minZ * step, maxZ * step, (maxX - minX) * step / 1000.0, (maxZ - minZ) * step / 1000.0, top);
    }

    /**
     * Relief quality over 2 x 2 km around a point: river channel columns that are faded (no water) or dry for another
     * reason, and one-block spikes and knife-edge walls in the height field.
     */
    private static void quality(TerrainEngine engine, int cx, int cz) {
        int size = 2000;
        int n = size + 2;
        double[] h = new double[n * n];
        var cols = new TerrainEngine.Column[n * n];
        java.util.stream.IntStream.range(0, n).parallel().forEach(j -> {
            for (int i = 0; i < n; i++) {
                var c = engine.column(cx - size / 2 - 1 + i, cz - size / 2 - 1 + j);
                cols[j * n + i] = c;
                h[j * n + i] = c.height;
            }
        });
        int channel = 0, faded = 0, partial = 0, dryFull = 0, spikes = 0, knives = 0, land = 0, blades = 0;
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                var c = cols[j * n + i];
                double y = h[j * n + i];
                if (y >= engine.sea) land++;
                if (!Double.isNaN(c.riverKeep)) {
                    channel++;
                    boolean wet = c.river || c.lakeLevel != Integer.MIN_VALUE || y < engine.sea - 1;
                    if (c.riverKeep < 0.05) faded++;
                    else if (c.riverKeep < 0.95) partial++;
                    else if (!wet && !c.dryRiver && c.tunnelKind == 0) dryFull++;
                }
                double max = -1e9;
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) if (di != 0 || dj != 0) max = Math.max(max, h[(j + dj) * n + i + di]);
                if (y - max > 3) spikes++;
                double wx = Math.min(y - h[j * n + i - 1], y - h[j * n + i + 1]);
                double wz = Math.min(y - h[(j - 1) * n + i], y - h[(j + 1) * n + i]);
                if (Math.max(wx, wz) > 5) knives++;
                if (i >= 6 && j >= 6 && i < n - 6 && j < n - 6) {
                    double bx = Math.min(y - h[j * n + i - 6], y - h[j * n + i + 6]);
                    double bz = Math.min(y - h[(j - 6) * n + i], y - h[(j + 6) * n + i]);
                    if (Math.max(bx, bz) > 18) {
                        blades++;
                        if (blades % 700 == 1) System.out.printf(Locale.ROOT, "  blade at %d %d h=%.0f drop %.0f mountain %.2f biome %s river %b%n",
                                cx - size / 2 - 1 + i, cz - size / 2 - 1 + j, y, Math.max(bx, bz), c.mountain, BiomeCatalog.get(c.biome).path(),
                                c.riverChannels != 0);
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "quality around %d %d: river channel %d columns: faded %.1f%%, partial %.1f%%, full but dry %.1f%%;"
                        + " spikes %d, knife-edge columns %d, blade ridges (3:1 both sides over 6 blocks) %d (of %d land)%n", cx, cz, channel,
                100.0 * faded / Math.max(1, channel), 100.0 * partial / Math.max(1, channel), 100.0 * dryFull / Math.max(1, channel), spikes,
                knives, blades, land);
    }

    /** River channel columns without water (gaps in a river) over 3 x 3 km, with examples. */
    private static void rivers(TerrainEngine engine) {
        int channel = 0;
        int dry = 0;
        var examples = new java.util.ArrayList<String>();
        for (int x = -1500; x < 1500; x++) {
            for (int z = -1500; z < 1500; z++) {
                var c = engine.column(x, z);
                if (c.riverChannels == 0) continue;
                channel++;
                boolean wet = c.river || c.lakeLevel != Integer.MIN_VALUE || c.height < engine.sea - 1;
                if (!wet && !c.dryRiver && c.tunnelKind == 0) {
                    dry++;
                    if (examples.size() < 12 && (x + z) % 64 == 0) {
                        examples.add(String.format(Locale.ROOT, "%d %d h=%.1f biome=%s", x, z, c.height, BiomeCatalog.get(c.biome).path()));
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "river channel columns %d, without water %d (%.1f%%)%n", channel, dry, 100.0 * dry / Math.max(1, channel));
        examples.forEach(e -> System.out.println("  " + e));
    }

    /** Land height percentiles and steepness over 24 x 24 km around the origin. */
    private static void stats(TerrainEngine engine, long seed) {
        int step = 32;
        var heights = new java.util.ArrayList<Double>();
        int steep = 0;
        for (int x = -12000; x <= 12000; x += step) {
            for (int z = -12000; z <= 12000; z += step) {
                double h = engine.column(x, z).height;
                if (h < engine.sea) continue;
                heights.add(h - engine.sea);
                double hx = engine.column(x + 8, z).height;
                if (Math.abs(hx - h) > 8) {
                    steep++;
                    if (steep % 4000 == 1) {
                        var c = engine.column(x, z);
                        System.out.printf(Locale.ROOT, "  steep at %d %d h=%.0f dh=%.0f corridor %.2f/%.2f mountain %.2f biome %s%n", x, z, h, hx - h,
                                engine.corridor(x, z), engine.corridor(x + 8, z), c.mountain, BiomeCatalog.get(c.biome).path());
                    }
                }
            }
        }
        java.util.Collections.sort(heights);
        int n = heights.size();
        java.util.function.DoubleFunction<Double> q = p -> heights.get((int) Math.min(n - 1, p * n));
        long over100 = heights.stream().filter(h -> h > 100).count();
        long over250 = heights.stream().filter(h -> h > 250).count();
        long over450 = heights.stream().filter(h -> h > 450).count();
        System.out.printf(Locale.ROOT, "seed %d land above sea: p10 %.0f p50 %.0f p90 %.0f p99 %.0f max %.0f; >100 %.1f%% >250 %.1f%% >450 %.2f%%;"
                        + " steeper than 45 deg %.1f%%%n", seed, q.apply(0.1), q.apply(0.5), q.apply(0.9), q.apply(0.99), heights.get(n - 1),
                100.0 * over100 / n, 100.0 * over250 / n, 100.0 * over450 / n, 100.0 * steep / n);
    }

    /** A camera 150 blocks over hill country (little mountain), looking slightly down across it. */
    private static double[] lowland(TerrainEngine engine) {
        for (int i = 0; i < 4000; i++) {
            int x = (int) ((i * 7919L) % 20000) - 10000;
            int z = (int) ((i * 104729L) % 20000) - 10000;
            var c = engine.column(x, z);
            if (c.height > engine.sea + 20 && c.mountain < 0.05 && engine.column(x, z - 3000).height > engine.sea) {
                return new double[]{x, z, 0, -7, 150};
            }
        }
        return new double[]{0, 0, 0, -7, 150};
    }

    /** Looks at the highest ground near the origin from {@code distance} blocks away; near = from low down. */
    private static double[] auto(TerrainEngine engine, double distance, int mode) {
        double best = -1e9;
        int bx = 0;
        int bz = 0;
        for (int x = -12000; x <= 12000; x += 64) {
            for (int z = -12000; z <= 12000; z += 64) {
                double h = engine.column(x, z).height;
                if (h > best) {
                    best = h;
                    bx = x;
                    bz = z;
                }
            }
        }
        double camX = bx;
        double camZ = bz + distance;
        double yaw = 0;
        double ground = engine.column((int) camX, (int) camZ).height;
        double altitude = mode == 1 ? 80 : Math.max(60, best - ground - 40);
        double pitch = Math.toDegrees(Math.atan2(best - (ground + altitude), distance)) - (mode == 1 ? 8 : 2);
        return new double[]{camX, camZ, yaw, pitch, altitude};
    }

    private static final class Field {
        final TerrainEngine engine;
        final int ox;
        final int oz;
        final float[] heights = new float[SPAN * SPAN];
        final int[] biomes = new int[SPAN * SPAN];

        Field(TerrainEngine engine, int ox, int oz) {
            this.engine = engine;
            this.ox = ox;
            this.oz = oz;
            java.util.Arrays.fill(heights, Float.NaN);
        }

        private int index(int i, int j) {
            if (i < 0 || j < 0 || i >= SPAN || j >= SPAN) return -1;
            int k = j * SPAN + i;
            if (Float.isNaN(heights[k])) {
                var c = engine.column(ox + i * GRID, oz + j * GRID);
                biomes[k] = c.biome;
                heights[k] = (float) c.height;
            }
            return k;
        }

        double height(double x, double z) {
            double fx = (x - ox) / GRID;
            double fz = (z - oz) / GRID;
            int i = (int) Math.floor(fx);
            int j = (int) Math.floor(fz);
            int a = index(i, j);
            int b = index(i + 1, j);
            int c = index(i, j + 1);
            int d = index(i + 1, j + 1);
            if (a < 0 || b < 0 || c < 0 || d < 0) return Double.NaN;
            double tx = fx - i;
            double tz = fz - j;
            double top = heights[a] + (heights[b] - heights[a]) * tx;
            double bottom = heights[c] + (heights[d] - heights[c]) * tx;
            return top + (bottom - top) * tz;
        }

        int biome(double x, double z) {
            int k = index((int) Math.round((x - ox) / GRID), (int) Math.round((z - oz) / GRID));
            return k < 0 ? 0 : biomes[k];
        }
    }

    static void render(TerrainEngine engine, double[] cam, Path file) throws IOException {
        var field = new Field(engine, (int) (cam[0] - FAR) - 8, (int) (cam[1] - FAR) - 8);
        double camY = engine.column((int) cam[0], (int) cam[1]).height + cam[4];
        double yaw = Math.toRadians(cam[2]);
        double pitch = Math.toRadians(cam[3]);
        // Forward, right and up vectors (x east, y up, z south; yaw 0 looks north = -z).
        double fx = Math.sin(yaw) * Math.cos(pitch);
        double fy = Math.sin(pitch);
        double fz = -Math.cos(yaw) * Math.cos(pitch);
        double rx = Math.cos(yaw);
        double rz = Math.sin(yaw);
        double ux = -Math.sin(yaw) * Math.sin(pitch);
        double uy = Math.cos(pitch);
        double uz = Math.cos(yaw) * Math.sin(pitch);
        double tan = Math.tan(FOV / 2);
        double aspect = (double) WIDTH / HEIGHT;
        // Sun from the south-west, fairly low: long shadows on north-east faces bring out the relief.
        double sx = -0.55;
        double sy = 0.55;
        double sz = 0.62;
        int[] pixels = new int[WIDTH * HEIGHT];
        IntStream.range(0, HEIGHT).parallel().forEach(py -> {
            for (int px = 0; px < WIDTH; px++) {
                double u = (2.0 * (px + 0.5) / WIDTH - 1) * tan * aspect;
                double v = (1 - 2.0 * (py + 0.5) / HEIGHT) * tan;
                double dx = fx + rx * u + ux * v;
                double dy = fy + uy * v;
                double dz = fz + rz * u + uz * v;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= len;
                dy /= len;
                dz /= len;
                pixels[py * WIDTH + px] = trace(field, engine, cam[0], camY, cam[1], dx, dy, dz, sx, sy, sz);
            }
        });
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        ImageIO.write(image, "png", file.toFile());
    }

    private static int trace(Field field, TerrainEngine engine, double ox, double oy, double oz, double dx, double dy, double dz,
                             double sx, double sy, double sz) {
        double sea = engine.sea;
        double t = 1;
        double prevT = 0;
        while (t < FAR) {
            double x = ox + dx * t;
            double y = oy + dy * t;
            double z = oz + dz * t;
            double h = field.height(x, z);
            if (Double.isNaN(h)) break;
            double surface = Math.max(h, sea);
            if (y <= surface) {
                // Refine between the last two steps.
                double lo = prevT;
                double hi = t;
                for (int i = 0; i < 8; i++) {
                    double mid = (lo + hi) / 2;
                    double hm = Math.max(field.height(ox + dx * mid, oz + dz * mid), sea);
                    if (oy + dy * mid <= hm) hi = mid;
                    else lo = mid;
                }
                return shade(field, engine, ox + dx * hi, oz + dz * hi, hi, sx, sy, sz);
            }
            prevT = t;
            t += Math.max(1.5, t * 0.004);
            if (y > 2100 && dy > 0) break;
        }
        return sky(dy);
    }

    private static int sky(double dy) {
        double k = Math.max(0, Math.min(1, dy * 2.5));
        return rgb(lerp(k, 0.78, 0.38), lerp(k, 0.86, 0.6), lerp(k, 0.98, 0.95));
    }

    private static int shade(Field field, TerrainEngine engine, double x, double z, double dist, double sx, double sy, double sz) {
        double h = field.height(x, z);
        double r;
        double g;
        double b;
        if (h < engine.sea) {
            double depth = Math.min(1, (engine.sea - h) / 40);
            r = lerp(depth, 0.18, 0.08);
            g = lerp(depth, 0.45, 0.22);
            b = lerp(depth, 0.62, 0.45);
        } else {
            double e = Math.max(2, Math.min(16, dist / 300));
            double hx = field.height(x + e, z) - field.height(x - e, z);
            double hz = field.height(x, z + e) - field.height(x, z - e);
            double nx = -hx;
            double ny = 2 * e;
            double nz = -hz;
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= nl;
            ny /= nl;
            nz /= nl;
            double steep = 1 - ny;
            String path = BiomeCatalog.get(field.biome(x, z)).path();
            int base = TerrainMaps.color(path);
            r = ((base >> 16) & 255) / 255.0;
            g = ((base >> 8) & 255) / 255.0;
            b = (base & 255) / 255.0;
            // Steep faces are bare rock, flatter high ground holds snow.
            double rock = smoothstep(0.35, 0.6, steep);
            r = lerp(rock, r, 0.47);
            g = lerp(rock, g, 0.45);
            b = lerp(rock, b, 0.43);
            double snowLine = engine.sea + 520;
            double snow = smoothstep(snowLine, snowLine + 60, h) * (1 - smoothstep(0.45, 0.75, steep));
            if (path.contains("snow") || path.contains("frozen") || path.contains("ice")) snow = Math.max(snow, 0.8 * (1 - rock));
            r = lerp(snow, r, 0.95);
            g = lerp(snow, g, 0.96);
            b = lerp(snow, b, 0.98);
            double light = 0.35 + 0.75 * Math.max(0, nx * sx + ny * sy + nz * sz);
            r *= light;
            g *= light;
            b *= light;
        }
        // Aerial perspective: distant ground fades into the haze.
        double haze = 1 - Math.exp(-dist / 7000);
        r = lerp(haze, r, 0.74);
        g = lerp(haze, g, 0.82);
        b = lerp(haze, b, 0.95);
        return rgb(r, g, b);
    }

    private static double smoothstep(double e0, double e1, double v) {
        double t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static int rgb(double r, double g, double b) {
        int ri = (int) Math.max(0, Math.min(255, r * 255));
        int gi = (int) Math.max(0, Math.min(255, g * 255));
        int bi = (int) Math.max(0, Math.min(255, b * 255));
        return ri << 16 | gi << 8 | bi;
    }
}
