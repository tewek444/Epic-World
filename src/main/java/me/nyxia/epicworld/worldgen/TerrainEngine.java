package me.nyxia.epicworld.worldgen;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.nyxia.epicworld.config.EpicSettings;
import me.nyxia.epicworld.worldgen.BiomeCatalog.Role;
import me.nyxia.epicworld.worldgen.BiomeCatalog.Shape;
import me.nyxia.epicworld.worldgen.noise.Fbm;
import me.nyxia.epicworld.worldgen.noise.Hash;

/**
 * Deterministic description of the whole Overworld surface. Every value depends only on the world seed,
 * the settings and the queried coordinates, never on the order in which chunks are generated.
 *
 * <p>Instances are shared between threads; per-thread caches only memoize pure results.
 */
public final class TerrainEngine {
    private static final Map<Key, TerrainEngine> ENGINES = new ConcurrentHashMap<>();

    private record Key(long seed, EpicSettings settings) {}

    public static TerrainEngine get(long seed, EpicSettings settings) {
        if (ENGINES.size() > 8) ENGINES.clear();
        return ENGINES.computeIfAbsent(new Key(seed, settings), key -> new TerrainEngine(key.seed, key.settings));
    }

    public final EpicSettings settings;
    public final BiomeCatalog.Resolved biomes;
    public final long seed;
    public final int minY;
    public final int top;
    public final int sea;
    private final double sizeMul;
    private final int oceanUsableDepth;
    private final int lakeUsableDepth;

    private final Fbm warpX;
    private final Fbm warpZ;
    private final Fbm continent;
    private final Fbm temperature;
    private final Fbm humidity;
    private final Fbm mountainBelt;
    private final Fbm peaks;
    private final Fbm hillMask;
    private final Fbm hills;
    private final Fbm lowland;
    private final Fbm oceanDepth;
    private final Fbm underwaterRidges;
    private final Fbm trenches;
    private final Fbm detail;
    private final Fbm archipelagoMask;
    private final Fbm shapeNoise;
    private final Fbm biomeWarp;
    private final Fbm specialNoise;
    private final Fbm riverWarpX;
    private final Fbm riverWarpZ;
    private final Fbm riverWidth;
    private final Fbm[] riverLayers;
    private final double[] riverWidths;
    private final double[] riverPeriods;
    private final Fbm delta;
    private final Fbm rough;
    // Relief (Alpha 3): regional elevation with escarpments, mountain kinds and giant ranges.
    private final Fbm provinces;
    private final Fbm tablelands;
    private final Fbm stepHeight;
    private final Fbm giantBelt;
    private final Fbm mountainClass;
    private final Fbm mountainSharpness;
    private final Fbm mountainWidth;
    private final Fbm mountainDetail;
    private final Fbm uplift;

    private final double oceanThreshold;
    private final double continentSigma;
    private final double continentMean;
    private final double temperatureSigma;
    private final double humiditySigma;

    private final int lake = BiomeCatalog.index("lake");
    private final int river = BiomeCatalog.index("river");
    private final int frozenRiver = BiomeCatalog.index("frozen_river");
    private final int beach = BiomeCatalog.index("beach");
    private final int snowyBeach = BiomeCatalog.index("snowy_beach");
    private final int stonyShore = BiomeCatalog.index("stony_shore");
    private final int stoneForest = BiomeCatalog.index("giant_stone_forest");
    private final int lushCaves = BiomeCatalog.index("lush_caves");
    private final int glowingCaves = BiomeCatalog.index("glowing_caves");
    private final int dryRiverbed = BiomeCatalog.index("dry_riverbed");
    private final int willowSwamp = BiomeCatalog.index("willow_swamp");
    private final int marbleCaves = BiomeCatalog.index("marble_caves");
    private final int atoll = BiomeCatalog.index("atoll");
    private final int mushroom = BiomeCatalog.index("mushroom_fields");
    private final int deepDark = BiomeCatalog.index("deep_dark");

    private final SharedCache<Site> siteCache = new SharedCache<>(16384);
    private final SharedCache<LakeShape> lakeCache = new SharedCache<>(1024);
    private final SharedCache<Formation> formationCache = new SharedCache<>(8192);
    private final SharedCache<IslandSpec> islandCache = new SharedCache<>(4096);
    private final SharedCache<ForestSpec> forestCache = new SharedCache<>(1024);
    private final SharedCache<SwampSpec> swampCache = new SharedCache<>(1024);

    private TerrainEngine(long seed, EpicSettings settings) {
        this.seed = seed;
        this.settings = settings;
        this.biomes = new BiomeCatalog.Resolved(settings);
        var w = settings.world();
        this.minY = w.minY();
        this.top = w.height();
        this.sea = w.seaLevel();
        this.sizeMul = settings.biomes().globalSizeMultiplier();
        this.oceanUsableDepth = settings.usableDepth(settings.oceans().maxDepth());
        this.lakeUsableDepth = settings.usableDepth(settings.lakes().maxDepth());
        int salt = 0;
        warpX = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        warpZ = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        continent = new Fbm(Hash.mix(seed, ++salt), 8, 0.55);
        temperature = new Fbm(Hash.mix(seed, ++salt), 4, 0.35);
        humidity = new Fbm(Hash.mix(seed, ++salt), 4, 0.35);
        mountainBelt = new Fbm(Hash.mix(seed, ++salt), 4, 0.5);
        peaks = new Fbm(Hash.mix(seed, ++salt), 6, 0.5);
        hillMask = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        hills = new Fbm(Hash.mix(seed, ++salt), 4, 0.5);
        lowland = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        oceanDepth = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        underwaterRidges = new Fbm(Hash.mix(seed, ++salt), 4, 0.5);
        trenches = new Fbm(Hash.mix(seed, ++salt), 3, 0.45);
        detail = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        archipelagoMask = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        shapeNoise = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        biomeWarp = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        specialNoise = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        riverWarpX = new Fbm(Hash.mix(seed, ++salt), 2, 0.5);
        riverWarpZ = new Fbm(Hash.mix(seed, ++salt), 2, 0.5);
        riverWidth = new Fbm(Hash.mix(seed, ++salt), 2, 0.5);
        delta = new Fbm(Hash.mix(seed, ++salt), 2, 0.4);
        rough = new Fbm(Hash.mix(seed, ++salt), 2, 0.5);
        provinces = new Fbm(Hash.mix(seed, ++salt), 4, 0.5);
        tablelands = new Fbm(Hash.mix(seed, ++salt), 2, 0.5);
        stepHeight = new Fbm(Hash.mix(seed, ++salt), 2, 0.5);
        giantBelt = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        mountainClass = new Fbm(Hash.mix(seed, ++salt), 2, 0.5);
        mountainSharpness = new Fbm(Hash.mix(seed, ++salt), 2, 0.5);
        mountainWidth = new Fbm(Hash.mix(seed, ++salt), 2, 0.5);
        mountainDetail = new Fbm(Hash.mix(seed, ++salt), 7, 0.42);
        uplift = new Fbm(Hash.mix(seed, ++salt), 3, 0.5);
        var r = settings.rivers();
        int layers = r.maxWidth() == r.minWidth() ? 1 : r.maxWidth() <= 2 * r.minWidth() ? 2 : 4;
        riverLayers = new Fbm[layers];
        riverWidths = new double[layers];
        riverPeriods = new double[layers];
        for (int i = 0; i < layers; i++) {
            riverLayers[i] = new Fbm(Hash.mix(seed, 1000 + i), 2, 0.35);
            double f = layers == 1 ? 1 : (double) (layers - 1 - i) / (layers - 1);
            riverWidths[i] = r.minWidth() * Math.pow((double) r.maxWidth() / r.minWidth(), f);
            // Wider rivers are rarer: each layer covers roughly 0.5-0.7 % of the land.
            riverPeriods[i] = 2.9 * riverWidths[i] / (0.006 + 0.002 * riverWidths[i] / r.maxWidth());
        }

        // Calibrate the ocean threshold so the requested share of a very large area is ocean.
        int n = 96;
        double[] samples = new double[n * n];
        double[] temps = new double[n * n];
        double[] hums = new double[n * n];
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double x = (i - n / 2.0) * 9001.0 + 517;
                double z = (j - n / 2.0) * 9001.0 - 311;
                double c = rawContinent(x, z);
                samples[i * n + j] = c;
                sum += c;
                sumSq += c * c;
                temps[i * n + j] = temperature.sample(x, z, 40000 * sizeMul);
                hums[i * n + j] = humidity.sample(x, z, 26000 * sizeMul);
            }
        }
        continentMean = sum / samples.length;
        continentSigma = Math.sqrt(Math.max(1e-9, sumSq / samples.length - continentMean * continentMean));
        Arrays.sort(samples);
        // Islands take a little of the ocean area; aim slightly higher to compensate.
        double target = Math.min(0.97, settings.oceans().coverage() * 1.02 + 0.004);
        oceanThreshold = samples[(int) Math.min(samples.length - 1, Math.max(0, target * samples.length))];
        temperatureSigma = sigma(temps);
        humiditySigma = sigma(hums);
    }

    private static double sigma(double[] values) {
        double s = 0;
        double sq = 0;
        for (double v : values) {
            s += v;
            sq += v * v;
        }
        double mean = s / values.length;
        return Math.sqrt(Math.max(1e-9, sq / values.length - mean * mean));
    }

    private double rawContinent(double x, double z) {
        double wx = x + 3400 * warpX.sample(x, z, 13000);
        double wz = z + 3400 * warpZ.sample(x, z, 13000);
        return continent.sample(wx, wz, 52000);
    }

    // ------------------------------------------------------------------------------------------------------
    // Column model

    /** Everything known about one block column. Values are immutable once returned. */
    public static final class Column {
        public double height;
        public double land;
        public double temperature;
        public double humidity;
        public double mountain;
        public double jag;
        public int biome;
        public int caveBiome = -1;
        public double deepDark;
        /** Water surface Y of an elevated lake covering this column, or {@link Integer#MIN_VALUE}. */
        public int lakeLevel = Integer.MIN_VALUE;
        public double rings;
        public double pillars;
        /** Typical top of the stone forest pillars around this column (the old plateau), or NaN. */
        public double forestTop = Double.NaN;
        public boolean river;
        /** Bit k set where the channel of river layer k (0 = widest) covers this column; for diagnostics. */
        public int riverChannels;
        /** For diagnostics: lowest share of a river kept here (1 = full river, 0 = faded out), or NaN off any channel. */
        public double riverKeep = Double.NaN;
        /** River tunnel through a mountain: air between floor and top (absolute Y); kind 0 = none. */
        public double tunnelFloor;
        public double tunnelTop = Double.NEGATIVE_INFINITY;
        public int tunnelKind;
        /** Set on the water, islets and shore of a marble lake. */
        LakeShape marbleLake;
        /** Willow swamp (bayou) strength, 0 outside. */
        public double swamp;
        /** Bed of a dried-up river (hot, dry country). */
        public boolean dryRiver;
    }

    /**
     * Columns shared by all threads, one tile of 16x16 per chunk: generation steps of one chunk run on different worker
     * threads and neighbours ask for border columns, so a per-thread cache recomputed most columns several times.
     */
    private static final int TILES = 2048;
    private final java.util.concurrent.atomic.AtomicReferenceArray<Tile> tiles = new java.util.concurrent.atomic.AtomicReferenceArray<>(TILES);

    private static final class Tile {
        final long key;
        final java.util.concurrent.atomic.AtomicReferenceArray<Column> columns = new java.util.concurrent.atomic.AtomicReferenceArray<>(256);

        Tile(long key) {
            this.key = key;
        }
    }

    public Column column(int x, int z) {
        int tx = x >> 4;
        int tz = z >> 4;
        long key = ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
        int slot = (int) (Hash.mix(tx, tz) & (TILES - 1));
        Tile tile = tiles.get(slot);
        if (tile == null || tile.key != key) {
            tile = new Tile(key);
            tiles.set(slot, tile);
        }
        int index = (x & 15) | (z & 15) << 4;
        Column column = tile.columns.get(index);
        if (column == null) {
            column = compute(x, z, true);
            tile.columns.set(index, column);
        }
        return column;
    }

    private static double smooth(double edge0, double edge1, double v) {
        double t = (v - edge0) / (edge1 - edge0);
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) { return a + (b - a) * t; }

    private int level(double z, double[] thresholds) {
        int i = 0;
        while (i < thresholds.length && z > thresholds[i]) i++;
        return i;
    }

    private static final double[] T_LEVELS = {-1.175, -0.385, 0.44, 0.915};
    private static final double[] H_LEVELS = {-0.84, -0.25, 0.25, 0.84};

    public double baseTemperature(double x, double z) {
        return temperature.sample(x, z, 40000 * sizeMul) / temperatureSigma;
    }

    public double baseHumidity(double x, double z) {
        return humidity.sample(x, z, 26000 * sizeMul) / humiditySigma;
    }

    /** Land/sea value: negative in the ocean, about 1 deep inland. */
    public double land(double x, double z) {
        if (!settings.oceans().enabled()) return 0.6 + 0.4 * (rawContinent(x, z) - continentMean) / continentSigma;
        return (rawContinent(x, z) - oceanThreshold) / continentSigma;
    }

    /** {@code valley}: the land without hills and mountains, where rivers flow (sea level for ocean columns). */
    private record Base(double height, double land, double mountain, double hilliness, double depthFactor, double valley) {}

    /** Continents, mountains and ocean floor before islands, biome shaping, lakes and rivers. */
    private Base base(double x, double z) {
        double l = land(x, z);
        if (l >= 0) {
            double coastFade = smooth(0, 0.18, l);
            double span = top - sea;
            double pw = 1100;
            double px = x + pw * warpX.sample(x + 7100, z - 2300, 4200);
            double pz = z + pw * warpZ.sample(x - 5300, z + 6100, 4200);
            double valley = valley(x, z, l);
            double up = valley - sea;

            // ---- hills everywhere, stronger in hill country and at the foot of mountains
            double hilliness = smooth(-0.35, 0.5, hillMask.sample(x, z, 6500));

            // ---- mountains: ranges along ridged belts, each with its own size, sharpness and width
            double width = 0.5 + 0.5 * mountainWidth.sample(x, z, 15000);
            double belt = mountainBelt.ridged(x + 900, z - 1300, 17000);
            double mountain = smooth(lerp(width, 0.56, 0.36), lerp(width, 0.74, 0.62), belt) * smooth(0.03, 0.4, l);
            double massif = smooth(0.35, 0.7, hillMask.sample(x - 4000, z + 2500, 9000)) * smooth(0.1, 0.8, l) * 0.6;
            mountain = Math.max(mountain, massif * smooth(0.3, 0.55, belt));
            double size = smooth(-0.35, 0.45, mountainClass.sample(x, z, 21000));
            double sharp = smooth(-0.3, 0.4, mountainSharpness.sample(x, z, 16000));
            double maxH = Math.min(span - 50, span * lerp(size, 0.11, 0.36));
            double mountainHeight = 0;
            if (mountain > 0) {
                double relief = mountainRelief(x, z, lerp(size, 1100, 1900), sharp, 0);
                mountainHeight = maxH * Math.pow(mountain, 0.7) * (0.12 + 0.88 * relief);
                // Wide ranges with soft relief become table mountains: steep sides, a broad top.
                double flatTop = smooth(0.62, 0.9, width) * (1 - 0.7 * sharp);
                if (flatTop > 0) {
                    double cap = maxH * (0.45 + 0.1 * relief);
                    double capped = cap + 8 * Math.tanh((mountainHeight - cap) / 24);
                    mountainHeight = lerp(flatTop, mountainHeight, Math.min(mountainHeight, capped));
                }
            }

            // ---- giant ranges: rare, long cordilleras up to the top of the world
            double giant = giantMask(x, z, l, px, pz);
            if (giant > 0) {
                double relief = mountainRelief(x + 5000, z - 3000, 4200, 0.45, 1);
                double giantHeight = (span - 40) * Math.pow(giant, 0.6) * (0.1 + 0.9 * relief);
                if (giantHeight > mountainHeight) mountainHeight = giantHeight;
                mountain = Math.max(mountain, giant);
            }

            double foothills = smooth(0, 0.45, mountain);
            // Highlands are more rugged than coastal plains.
            double hillAmp = (14 + 44 * hilliness + 30 * foothills) * coastFade * (1 + Math.max(0, up - 40) / 450);
            double hillHeight = hillAmp * (0.5 + 0.5 * hills.eroded(x, z, 760));
            return new Base(valley + hillHeight + mountainHeight, l, mountain, Math.max(hilliness, foothills), 0, valley);
        }
        double u = -l;
        double normal = 40 + 110 * (0.5 + 0.5 * oceanDepth.sample(x, z, 9000));
        // Coastal drop-off to 3-12 blocks within the first few hundred blocks, then a gradual shelf to the open ocean.
        // A long 1-block-deep shelf made noise poke out of the water as thin strips of land along every coast.
        double d = 2.5 + 10 * smooth(0, 0.06, u) + (normal - 12.5) * Math.pow(smooth(0.02, 1.1, u), 0.85);
        d -= 5 * detail.sample(x, z, 420) * smooth(0, 0.3, u);
        if (settings.oceans().underwaterMountains()) {
            double ridge = underwaterRidges.ridged(x, z, 4200);
            d -= smooth(0.55, 0.95, ridge) * smooth(0.35, 0.9, u) * normal * 0.85;
        }
        if (settings.oceans().trenches()) {
            double tx = x + 2500 * warpZ.sample(x, z, 6000);
            double t = trenches.ridged(tx, z * 0.45, 15000);
            d += smooth(0.88, 0.985, t) * smooth(0.75, 1.5, u) * Math.max(0, oceanUsableDepth - d);
        }
        d = Math.max(2.5, Math.min(oceanUsableDepth, d));
        return new Base(sea - d, l, 0, 0, smooth(0.3, 1.0, u), sea);
    }

    /**
     * Mountain relief in [0, 1] at a period (spacing of the main ridges): sharp = ridged multifractal (knife-edge crests,
     * V-shaped valleys), soft = eroded fbm (rounded summits, broad valleys). Domain-warped so ridges curve and branch.
     * {@code extra} raises the contrast for giant ranges.
     */
    private double mountainRelief(double x, double z, double period, double sharp, int extra) {
        double wx = x + 0.3 * period * warpX.sample(x + 300, z, period * 0.9);
        double wz = z + 0.3 * period * warpZ.sample(x, z - 300, period * 0.9);
        double soft = 0.5 + 0.5 * mountainDetail.eroded(wx, wz, period * 1.3);
        double ridged = Math.min(1, mountainDetail.ridgedSoft(wx, wz, period, 0.02) * 1.35);
        double r = lerp(sharp, soft, 0.75 * ridged + 0.25 * soft);
        return Math.pow(Math.max(0, r), 1.25 + 0.35 * extra);
    }

    /**
     * The smooth part of the land at a column: the rise from the coast, the continental uplift (low coasts, highlands
     * hundreds of blocks above the sea inland, basins between them) and the plateaus. Hills and mountains stand on it,
     * rivers flow at its height.
     */
    private double valley(double x, double z, double l) {
        double span = top - sea;
        double coastFade = smooth(0, 0.18, l);
        double rise = Math.pow(smooth(0, 0.55, l), 0.55);
        // Land starts 1.5 blocks above the water so the coastline is a clean edge, not a flooded flat.
        double low = 1.5 + 6 * smooth(0, 0.04, l) + 26 * rise * (0.55 + 0.45 * lowland.sample(x, z, 5200))
                + 3 * Math.max(0, detail.sample(x, z, 180)) * coastFade;
        double interior = smooth(0.12, 0.85, l);
        double highland = smooth(-0.45, 0.55, uplift.sample(x + 2100, z - 8800, 34000));
        double up = 0.34 * span * interior * (0.25 + 0.75 * highland);
        double open = 1 - riverCorridor(x, z);
        up *= open;
        // Plateaus: in tableland country the levels are flat and separated by escarpments (a drop of a few dozen
        // blocks to the next plateau); elsewhere the same field rises and falls gently.
        double inland = smooth(0.04, 0.5, l);
        double pw = 1100;
        double px = x + pw * warpX.sample(x + 7100, z - 2300, 4200);
        double pz = z + pw * warpZ.sample(x - 5300, z + 6100, 4200);
        double level = Math.max(0, (provinces.sample(px, pz, 12000) + 0.12) / 0.15);
        double table = smooth(-0.05, 0.35, tablelands.sample(x, z, 18000));
        int k = (int) level;
        double f = level - k;
        double cliff = 0.1 + 0.08 * (0.5 + 0.5 * stepHeight.sample(x + 900, z, 700));
        double stepped = k + smooth(1 - cliff, 1, f);
        double levels = Math.min(4.5, lerp(table, level * 0.8, stepped));
        double stepH = (30 + 22 * (0.5 + 0.5 * stepHeight.sample(x, z, 6000))) * Math.min(1, span / 400.0);
        return sea + low + up + levels * stepH * inland * open;
    }

    /** Grid step of the river corridor field (it is smooth over hundreds of blocks). */
    private static final int CORRIDOR_GRID = 16;
    private final SharedCache<double[]> corridorCache = new SharedCache<>(1 << 16);

    /**
     * How much of the uplift a river valley removes at a column: 1 near a river's course, 0 away from it. The valley is
     * broader for wider rivers. Interpolated from a 16-block grid of exact values.
     */
    private double riverCorridor(double x, double z) {
        if (!settings.rivers().enabled() || Boolean.getBoolean("epicworld.noCorridors")) return 0;
        double gx = x / CORRIDOR_GRID;
        double gz = z / CORRIDOR_GRID;
        int ix = (int) Math.floor(gx);
        int iz = (int) Math.floor(gz);
        double tx = gx - ix;
        double tz = gz - iz;
        double a = corridorAt(ix, iz);
        double b = corridorAt(ix + 1, iz);
        double c = corridorAt(ix, iz + 1);
        double d = corridorAt(ix + 1, iz + 1);
        return lerp(tz, lerp(tx, a, b), lerp(tx, c, d));
    }

    private static final ThreadLocal<StringBuilder> DEBUG_RIVERS = new ThreadLocal<>();

    /** Diagnostics: base height, valley floor, mountain mask, giant mask and the column height without lakes. */
    public String debugColumn(int x, int z) {
        Base b = base(x, z);
        DEBUG_RIVERS.set(new StringBuilder());
        Column noLakes = compute(x, z, false);
        String riverLog = DEBUG_RIVERS.get().toString();
        DEBUG_RIVERS.remove();
        Role role = landRole(b.height, b);
        Pick pick = role == null ? null : choose(role, x, z);
        String shape = pick == null ? "-" : BiomeCatalog.get(pick.biome).path() + "/" + BiomeCatalog.get(pick.biome).shape() + " w=" + String.format(java.util.Locale.ROOT, "%.2f", pick.weight)
                + " shaped=" + String.format(java.util.Locale.ROOT, "%.1f", shapeTerrain(x, z, b.height, BiomeCatalog.get(pick.biome).shape(), pick.weight, new Column()));
        return String.format(java.util.Locale.ROOT, "base %.1f valley %.1f mountain %.2f giant %.2f corridor %.2f role %s pick %s noLakes %.1f final %.1f",
                b.height, b.valley, b.mountain, giantMask(x, z), riverCorridor(x, z), role, shape, noLakes.height, column(x, z).height) + riverLog;
    }

    /** River corridor strength at a column, for tools. */
    public double corridor(double x, double z) {
        return riverCorridor(x, z);
    }

    private double corridorAt(int ix, int iz) {
        long key = ((long) ix << 32) ^ (iz & 0xFFFFFFFFL);
        var hit = corridorCache.get(key);
        if (hit != null) return hit.value()[0];
        double x = (double) ix * CORRIDOR_GRID;
        double z = (double) iz * CORRIDOR_GRID;
        double best = 0;
        for (int k = 0; k < riverLayers.length; k++) {
            double width = riverWidths[k];
            double reach = 700 + 5 * width;
            // The course without its meanders: a smooth field, so the valley is smooth (the meanders, a couple of river
            // widths to each side, stay inside it). The meandering field made the corridor jump from cell to cell.
            double n = riverCourse(k, x, z);
            // No cheap reject on the field value: any bound cut the valley off mid-slope somewhere (a cliff). The grid
            // keeps this affordable.
            double e = 16;
            double fx = (riverCourse(k, x + e, z) - n) / e;
            double fz = (riverCourse(k, x, z + e) - n) / e;
            double dist = Math.abs(n) / Math.max(Math.sqrt(fx * fx + fz * fz), 1e-7);
            best = Math.max(best, smooth(reach, reach * 0.22, dist));
        }
        corridorCache.put(key, new double[]{best});
        return best;
    }

    private double valley(double x, double z) {
        double l = land(x, z);
        return l < 0 ? sea : valley(x, z, l);
    }

    /** Strength of a giant range at a column (0 outside); px, pz are the warped coordinates of {@link #base}. */
    private double giantMask(double x, double z, double l, double px, double pz) {
        // Stretched along a direction that turns slowly: long cordilleras, not rings.
        double turn = 0.9 * warpX.sample(x - 9000, z + 4000, 90000);
        double ga = Math.cos(turn) * px + Math.sin(turn) * pz;
        double gb = -Math.sin(turn) * px + Math.cos(turn) * pz;
        return smooth(0.6, 0.8, giantBelt.ridged(ga * 0.35, gb, 42000)) * smooth(0.12, 0.5, l);
    }

    /** Strength of a giant range at a column, for tools (0 outside, 1 in the core). */
    public double giantMask(double x, double z) {
        double l = land(x, z);
        if (l < 0) return 0;
        double pw = 1100;
        double px = x + pw * warpX.sample(x + 7100, z - 2300, 4200);
        double pz = z + pw * warpZ.sample(x - 5300, z + 6100, 4200);
        return giantMask(x, z, l, px, pz);
    }

    /** Deterministic single-island profile. Returns NaN outside the island's influence. */
    private double island(double x, double z, double cx, double cz, double radius, long h, double peakScale) {
        double dx = x - cx;
        double dz = z - cz;
        double reach = radius * 2.2 + 64;
        if (Math.abs(dx) > reach || Math.abs(dz) > reach) return Double.NaN;
        double angle = Hash.unit(h, 7) * Math.PI;
        double aspect = 1 + 0.6 * Hash.unit(h, 8);
        double rx = (dx * Math.cos(angle) + dz * Math.sin(angle)) / aspect;
        double rz = -dx * Math.sin(angle) + dz * Math.cos(angle);
        double dist = Math.sqrt(rx * rx + rz * rz);
        double theta = Math.atan2(rz, rx);
        double wobble = 0.24 * shapeNoise.first(Math.cos(theta) * 1.3 + Hash.unit(h, 9) * 50, Math.sin(theta) * 1.3, 1)
                + 0.1 * shapeNoise.sample(x + Hash.unit(h, 10) * 1e4, z, Math.max(40, radius * 0.7));
        double q = dist / (radius * (1 + wobble));
        if (q > 2.2) return Double.NaN;
        double peak = Math.max(3, Math.min(radius * 0.13, 170)) * peakScale;
        if (q < 1) {
            double profile = Math.pow(1 - q, 0.8);
            double relief = 0.65 + 0.35 * hills.sample(x, z, Math.max(60, radius * 0.5));
            double mountain = radius > 400 ? Math.pow(peaks.ridged(x, z, 1400), 2) * smooth(0.2, 0.7, 1 - q) * peak * 0.8 : 0;
            return sea + 1 + peak * profile * relief + mountain;
        }
        return sea - 1 - (q - 1) * radius * 0.45;
    }

    private record Atoll(double height, double zone) {}

    private Atoll atoll(double x, double z, double cx, double cz, double radius, long h) {
        double dx = x - cx;
        double dz = z - cz;
        double reach = radius * 1.6 + 96;
        if (Math.abs(dx) > reach || Math.abs(dz) > reach) return null;
        double angle = Hash.unit(h, 7) * Math.PI;
        double aspect = 1 + 0.45 * Hash.unit(h, 8);
        double rx = (dx * Math.cos(angle) + dz * Math.sin(angle)) / aspect;
        double rz = -dx * Math.sin(angle) + dz * Math.cos(angle);
        double theta = Math.atan2(rz, rx);
        double wobble = 0.1 * shapeNoise.first(Math.cos(theta) * 1.4 + Hash.unit(h, 9) * 50, Math.sin(theta) * 1.4, 1);
        double q = Math.sqrt(rx * rx + rz * rz) / (radius * (1 + wobble));
        double band = Math.max(0.035, Math.min(0.22, 26 / radius));
        if (q > 1 + band * 4 + 0.3) return null;
        var a = settings.atolls();
        double gaps = shapeNoise.first(Math.cos(theta) * 3.2 + Hash.unit(h, 11) * 90, Math.sin(theta) * 3.2, 1);
        double ring = 1 - Math.abs(q - 1) / band;
        double height;
        if (ring > 0) {
            boolean land = gaps > -0.35;
            height = land ? sea + 1 + 2.5 * ring : sea - 2 - 2 * (1 - ring);
        } else if (q < 1) {
            double inner = (1 - band - q) / Math.max(0.05, 1 - band);
            height = a.generateLagoons()
                    ? sea - 3 - 9 * smooth(0, 0.6, inner) + 2.5 * detail.sample(x, z, 40) * smooth(0, 0.3, inner)
                    : sea + 1.5;
        } else {
            double out = q - 1 - band;
            height = out < band * 1.4 ? sea - 2 - 3 * out / (band * 1.4) : sea - 5 - (out - band * 1.4) * radius * 1.1;
        }
        double zone = q < 1 + band * 2.6 ? 1 : 0;
        return new Atoll(height, zone);
    }

    private static final int SHORE = -2;

    private Column compute(int x, int z, boolean withLakes) {
        var col = new Column();
        Base b = base(x, z);
        double h = b.height;
        col.land = b.land;
        col.mountain = b.mountain;
        double t0 = baseTemperature(x, z);
        double h0 = baseHumidity(x, z);
        col.humidity = h0;
        int special = -1;

        // ---- islands, archipelagos, atolls, mushroom islands (all gated at their own centre, never per column)
        if (b.land < 0.05) {
            for (int scale = 0; scale < ISLAND_SCALES; scale++) {
                double cell = islandCell(scale);
                if (cell <= 0) continue;
                int gx = (int) Math.floor(x / cell);
                int gz = (int) Math.floor(z / cell);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        IslandSpec spec = islandSpec(scale, gx + dx, gz + dz);
                        if (spec == null) continue;
                        if (spec.kind == IslandKind.ATOLL) {
                            Atoll a = atoll(x, z, spec.x, spec.z, spec.radius, spec.hash);
                            if (a == null) continue;
                            if (a.zone > 0) {
                                // The lagoon may be lower than the surrounding sea floor.
                                h = a.height;
                                special = atoll;
                            } else if (a.height > h) {
                                h = a.height;
                            }
                        } else {
                            double ih = island(x, z, spec.x, spec.z, spec.radius, spec.hash, spec.kind == IslandKind.MUSHROOM ? 0.5 : 1);
                            if (!Double.isNaN(ih) && ih > h) {
                                h = ih;
                                if (spec.kind == IslandKind.MUSHROOM && ih > sea - 12) special = mushroom;
                            }
                        }
                    }
                }
            }
        }

        // ---- biome region selection and biome-driven shaping
        double elevation = h - sea;
        double tEff = cooled(t0, elevation);
        Role role = landRole(h, b);
        Pick pick = role == null ? null : choose(role, x, z);
        double shaped = h;
        if (pick != null && h >= sea) {
            Shape shape = BiomeCatalog.get(pick.biome).shape();
            if ((shape == Shape.SWAMP || shape == Shape.MANGROVE || shape == Shape.WILLOW) && elevation > 16) {
                pick = new Pick(alternative(pick, role), pick.weight);
                shape = BiomeCatalog.get(pick.biome).shape();
            }
            shaped = shapeTerrain(x, z, h, shape, pick.weight, col);
        } else if (pick != null && BiomeCatalog.get(pick.biome).shape() == Shape.STONE_RINGS) {
            col.rings = pick.weight;
        }
        h = shaped;
        col.jag = Math.min(1, col.jag + 0.4 * b.mountain * b.mountain + 0.1 * b.hilliness);

        // ---- willow swamp (bayou): a rare, large flat wetland of shallow pools and low islands
        if (biomes.on("willow_swamp") && b.land > 0.02 && h > sea - 4 && h < sea + 30) {
            double mask = willowSwampMask(x, z);
            if (mask > 0) {
                // About half the ground 1-2 blocks under water, the rest low islands and mud banks.
                double target = sea - 1.0 + 2.6 * specialNoise.sample(x, z, 60) + 0.8 * detail.sample(x, z, 16);
                h = lerp(smooth(0, 0.6, mask), h, target);
                col.swamp = smooth(0.2, 0.5, mask);
            }
        }

        // ---- giant stone forest: a rare, large dissected plateau with a valley floor between the pillars
        if (h > sea + 2 && biomes.on("giant_stone_forest") && b.land > 0.1) {
            ForestHit forest = stoneForest(x, z);
            if (forest != null) {
                double floorWeight = smooth(0, 0.55, forest.mask);
                // A broad, gentle valley: never a pit with walls, and the floor stays above the sea.
                double floor = Math.max(sea + 4, h - 6 - 6 * (0.5 + 0.5 * specialNoise.sample(x, z, 260)));
                h = lerp(floorWeight, h, floor);
                col.pillars = smooth(0.12, 0.6, forest.mask);
                col.forestTop = forest.top;
            }
        }

        // ---- lakes
        LakeHit lakeHit = null;
        if (withLakes && settings.lakes().enabled() && biomes.on("lake") && b.land > 0.08) {
            lakeHit = lake(x, z, h);
            if (lakeHit != null) {
                h = lakeHit.height;
                if (lakeHit.inside && lakeHit.level > sea) col.lakeLevel = lakeHit.level;
                if (lakeHit.shape != null && lakeHit.shape.marble) marbleGrotto(x, z, h, lakeHit, col);
            }
        }

        // ---- rivers
        boolean riverHere = false;
        if (settings.rivers().enabled() && b.land > -0.06 && (lakeHit == null || lakeHit.level <= sea || !lakeHit.inside)) {
            // The river fades out only where the land itself rises into highlands; mountains and plateau shapes on it
            // are crossed by a gorge or a tunnel as before.
            double carved = rivers(x, z, h, b.land, t0, h0, b.valley, col);
            boolean cut = carved < h;
            if (cut) h = carved;
            // No overhangs or spires of the 3D relief noise over the water or its banks.
            if (cut || col.riverChannels != 0) col.jag = 0;
            riverHere = (cut || col.riverChannels != 0) && h < sea - 0.5 && b.land > -0.02;
        }

        // Soft ceiling: the last couple of hundred blocks under the top of the generator rise ever slower, so peaks stay
        // peaks instead of being cut flat at the limit.
        double knee = top - 220;
        if (h > knee) h = knee + 180 * Math.tanh((h - knee) / 180);
        h = Math.max(minY + 14, Math.min(top - 10, h));
        col.height = h;
        elevation = h - sea;
        tEff = cooled(t0, elevation);
        col.temperature = tEff;
        int tl = level(tEff, T_LEVELS);
        int hl = level(h0, H_LEVELS);

        // ---- final surface biome
        if (col.marbleLake != null && !riverHere) {
            col.biome = marbleCaves;
        } else if (lakeHit != null && lakeHit.inside && h < lakeHit.level) {
            col.biome = tl == 0 ? landBiome(Role.LOWLAND, x, z) : lake;
        } else if (riverHere) {
            col.river = true;
            col.biome = tl == 0 ? frozenRiver : river;
        } else if (col.swamp > 0.5 && h > sea - 4) {
            // The whole wetland is swamp, its pools included.
            col.biome = willowSwamp;
        } else if (col.dryRiver && h >= sea) {
            col.biome = dryRiverbed;
        } else if (col.pillars > 0.25 && h >= sea) {
            col.biome = stoneForest;
        } else if (special >= 0 && (special == atoll || h < sea + 40)) {
            col.biome = special;
        } else if (h < sea) {
            Role water = (sea - h > 55 && b.land < -0.25) ? Role.DEEP_OCEAN : Role.OCEAN;
            int oceanLevel = level(t0, T_LEVELS);
            Pick p = choose(water, x, z, oceanLevel);
            col.biome = p.biome;
            if (BiomeCatalog.get(p.biome).shape() == Shape.STONE_RINGS) col.rings = p.weight;
        } else if (isShore(h, b, lakeHit)) {
            if (tl == 0) col.biome = snowyBeach;
            else if (b.mountain > 0.15 || rough.sample(x, z, 900) > 0.45 || (lakeHit != null && lakeHit.radius < 160)) col.biome = stonyShore;
            else col.biome = beach;
            if (lakeHit != null && tl > 0 && lakeHit.radius < 160) col.biome = landBiome(Role.LOWLAND, x, z);
        } else {
            Role finalRole = landRole(h, b);
            if (pick != null && finalRole == role) col.biome = pick.biome;
            else col.biome = landBiome(finalRole, x, z);
        }
        if (col.biome == SHORE) col.biome = beach;
        if (!biomes.enabled[col.biome]) col.biome = substitute(col.biome, tl, hl);

        // ---- caves
        Pick cave = choose(Role.CAVE, x, z);
        col.caveBiome = cave.biome;
        if (biomes.on("deep_dark")) {
            col.deepDark = smooth(0.35, 0.65, b.mountain) * Math.min(1, biomes.weightRatio("deep_dark"));
        }
        return col;
    }

    /** Temperature at an elevation: cools from 150 blocks above the sea; snow from about 550 in a temperate climate. */
    private static double cooled(double t0, double elevation) {
        return t0 - Math.max(0, elevation - 150) / 330.0;
    }

    private boolean isShore(double h, Base b, LakeHit lakeHit) {
        if (lakeHit != null && lakeHit.shore && h < lakeHit.level + 2.5 && lakeHit.radius >= 100) return true;
        return h < sea + 3 + 2.5 * detail.first(b.land * 9000, h, 1) && b.land < 0.12;
    }

    /** Replacement for a disabled fixed-purpose biome (river, beach, ...). */
    private int substitute(int biome, int tl, int hl) {
        String path = BiomeCatalog.get(biome).path();
        String fallback = switch (path) {
            case "frozen_river" -> "river";
            case "snowy_beach", "stony_shore" -> "beach";
            case "atoll", "mushroom_fields", "stone_ring_sea" -> "ocean";
            default -> "plains";
        };
        int index = BiomeCatalog.index(fallback);
        if (biomes.enabled[index]) return index;
        return biomes.pick(Role.LOWLAND, tl, hl, 0.5);
    }

    private Role landRole(double h, Base b) {
        if (h < sea) return null;
        double e = h - sea;
        double span = top - sea;
        // Absolute heights in tall worlds: peaks start where the trees end, not at a share of the world.
        if (e > Math.min(0.62 * span, 520) && b.mountain > 0.3) return Role.PEAK;
        if (e > Math.min(0.36 * span, 260) && b.mountain > 0.12) return Role.SLOPE;
        if (e > 58 || (b.hilliness > 0.6 && e > 32)) return Role.HIGHLAND;
        return Role.LOWLAND;
    }

    private int landBiome(Role role, double x, double z) {
        Pick p = choose(role, x, z);
        Shape s = BiomeCatalog.get(p.biome).shape();
        if (s == Shape.SWAMP || s == Shape.MANGROVE || s == Shape.WILLOW) return alternative(p, role);
        return p.biome;
    }

    // ------------------------------------------------------------------------------------------------------
    // Islands

    private enum IslandKind { PLAIN, MUSHROOM, ATOLL }

    private record IslandSpec(IslandKind kind, double x, double z, double radius, long hash) {}

    private static final int ISLAND_SCALES = 6;

    /** Grid size of each island scale: 0 isolated, 1-3 archipelago large/medium/small, 4 mushroom, 5 atoll. */
    private double islandCell(int scale) {
        var ar = settings.archipelagos();
        double lo = ar.minIslandRadius();
        double hi = ar.maxIslandRadius();
        double split1 = Math.max(lo, Math.min(hi, 64));
        double split2 = Math.max(lo, Math.min(hi, 512));
        return switch (scale) {
            case 0 -> settings.oceans().generateIslands() ? 3600 : 0;
            case 1 -> ar.enabled() && hi > split2 ? Math.max(5200, hi * 3.2) : 0;
            case 2 -> ar.enabled() && split2 > split1 ? Math.max(1300, split2 * 3) : 0;
            case 3 -> ar.enabled() && (split1 > lo || lo == hi) ? Math.max(260, split1 * 4.5) : 0;
            case 4 -> biomes.weightRatio("mushroom_fields") > 0 ? 15000 * Math.max(1, biomes.sizeOf("mushroom_fields")) : 0;
            case 5 -> settings.atolls().enabled() && biomes.on("atoll")
                    ? Math.max(2400, settings.atolls().maxRadius() * biomes.sizeOf("atoll") * 3.4) : 0;
            default -> 0;
        };
    }

    private IslandSpec islandSpec(int scale, int gx, int gz) {
        long key = Hash.cell(scale + 11, gx, gz, 13);
        var hit = islandCache.get(key);
        if (hit != null) return hit.value();
        IslandSpec spec = buildIsland(scale, gx, gz);
        islandCache.put(key, spec);
        return spec;
    }

    private IslandSpec buildIsland(int scale, int gx, int gz) {
        double cell = islandCell(scale);
        long h = Hash.cell(seed, gx, gz, 21 + scale);
        var ar = settings.archipelagos();
        double lo = ar.minIslandRadius();
        double hi = ar.maxIslandRadius();
        double split1 = Math.max(lo, Math.min(hi, 64));
        double split2 = Math.max(lo, Math.min(hi, 512));
        double minR;
        double maxR;
        IslandKind kind = IslandKind.PLAIN;
        switch (scale) {
            case 0 -> { minR = 40; maxR = 600; }
            case 1 -> { minR = split2; maxR = hi; }
            case 2 -> { minR = split1; maxR = split2; }
            case 3 -> { minR = lo; maxR = Math.max(lo, split1); }
            case 4 -> {
                kind = IslandKind.MUSHROOM;
                minR = 260 * biomes.sizeOf("mushroom_fields");
                maxR = 820 * biomes.sizeOf("mushroom_fields");
            }
            default -> {
                kind = IslandKind.ATOLL;
                minR = settings.atolls().minRadius() * biomes.sizeOf("atoll");
                maxR = settings.atolls().maxRadius() * biomes.sizeOf("atoll");
            }
        }
        double radius = minR * Math.pow(maxR / minR, Math.pow(Hash.unit(h, 3), kind == IslandKind.ATOLL ? 1.6 : 1.4));
        double margin = Math.min(0.45, radius * (kind == IslandKind.ATOLL ? 1.3 : 1.2) / cell);
        double cx = (gx + 0.5 + (Hash.unit(h, 1) - 0.5) * (1 - 2 * margin)) * cell;
        double cz = (gz + 0.5 + (Hash.unit(h, 2) - 0.5) * (1 - 2 * margin)) * cell;
        double u = -land(cx, cz);
        double roll = Hash.unit(h, 0);
        double probability = switch (scale) {
            case 0 -> 0.16 * smooth(0.25, 0.5, u);
            case 1, 2, 3 -> {
                double mask = smooth(0.25, 0.6, archipelagoMask.sample(cx, cz, 26000)) * smooth(0.12, 0.35, u);
                // Islands cluster inside an archipelago instead of being spread evenly.
                double cluster = smooth(-0.35, 0.45, shapeNoise.sample(cx * 1.7, cz * 1.7, scale == 3 ? 2600 : 9000));
                yield mask * cluster * (scale == 1 ? 0.55 : scale == 2 ? 0.8 : 0.95);
            }
            case 4 -> u > 0.45 ? 0.14 * biomes.weightRatio("mushroom_fields") : 0;
            default -> u > 0.3 && baseTemperature(cx, cz) > T_LEVELS[2] ? 0.3 * biomes.weightRatio("atoll") : 0;
        };
        if (roll >= probability) return null;
        return new IslandSpec(kind, cx, cz, radius, h);
    }

    // ------------------------------------------------------------------------------------------------------
    // Biome-specific terrain

    private double shapeTerrain(double x, double z, double h, Shape shape, double w, Column col) {
        if (w <= 0) return h;
        double e = h - sea;
        switch (shape) {
            case SWAMP, MANGROVE, WILLOW -> {
                double base = switch (shape) {
                    case SWAMP -> 0.4;
                    case MANGROVE -> -0.1;
                    default -> -0.3;
                };
                double target = sea + base + 1.9 * specialNoise.sample(x, z, 70);
                if (shape == Shape.WILLOW && Math.abs(specialNoise.first(x + 500, z, 160)) < 0.07) target -= 2.5;
                return lerp(w * (1 - smooth(10, 18, e)), h, target);
            }
            case DUNES -> {
                double angle = 0.6;
                double along = x * Math.cos(angle) + z * Math.sin(angle);
                double across = -x * Math.sin(angle) + z * Math.cos(angle);
                double crest = 1 - Math.abs(specialNoise.first(along / 3.2, across, 220));
                double amp = 5 + 16 * (0.5 + 0.5 * specialNoise.sample(x, z, 3000));
                return h + w * amp * crest * crest * smooth(1, 6, e);
            }
            case BADLANDS, WOODED_BADLANDS, ERODED, SAVANNA_PLATEAU -> {
                double lift = switch (shape) {
                    case BADLANDS -> 34;
                    case WOODED_BADLANDS -> 70;
                    case ERODED -> 24;
                    default -> 52;
                };
                double step = shape == Shape.SAVANNA_PLATEAU ? 26 : shape == Shape.ERODED ? 9 : 15;
                double raised = h + lift * smooth(2, 14, e) * (0.75 + 0.25 * specialNoise.sample(x, z, 900));
                if (shape == Shape.ERODED) {
                    raised += 22 * (Math.pow(specialNoise.ridged(x, z, 260), 3) - 0.3);
                }
                double v = raised - sea;
                double terraced = Math.floor(v / step) * step + smooth(0.7, 1.0, (v / step) - Math.floor(v / step)) * step;
                return lerp(w, h, sea + lerp(0.8, v, terraced));
            }
            case WINDSWEPT -> {
                return h + w * 34 * (specialNoise.ridged(x, z, 420) - 0.35) * smooth(2, 20, e);
            }
            case JAGGED -> {
                col.jag = Math.min(1, col.jag + 0.5 * w);
                return h;
            }
            case MEADOW -> {
                return lerp(0.35 * w, h, sea + e * 0.92 + 4 * hills.sample(x, z, 400));
            }
            default -> {
                return h;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Giant stone forest (modelled on Zhangjiajie): rare sites on a coarse grid, each a large irregular area

    private record SwampSpec(double x, double z, double radius, long hash) {}

    private double swampCell() { return 4800 * Math.max(1, biomes.sizeOf("willow_swamp")); }

    private SwampSpec swampSpec(int gx, int gz) {
        long key = Hash.cell(98, gx, gz, 13);
        var hit = swampCache.get(key);
        if (hit != null) return hit.value();
        SwampSpec spec = buildSwamp(gx, gz);
        swampCache.put(key, spec);
        return spec;
    }

    private SwampSpec buildSwamp(int gx, int gz) {
        double cell = swampCell();
        long h = Hash.cell(seed, gx, gz, 63);
        if (Hash.unit(h, 0) >= 0.6 * biomes.weightRatio("willow_swamp")) return null;
        double radius = (350 + 450 * Hash.unit(h, 3)) * biomes.sizeOf("willow_swamp");
        double margin = Math.min(0.45, radius * 1.3 / cell);
        for (int attempt = 0; attempt < 8; attempt++) {
            double cx = (gx + 0.5 + (Hash.unit(h, 20 + attempt) - 0.5) * (1 - 2 * margin)) * cell;
            double cz = (gz + 0.5 + (Hash.unit(h, 30 + attempt) - 0.5) * (1 - 2 * margin)) * cell;
            Base b = base(cx, cz);
            double e = b.height - sea;
            int tl = level(baseTemperature(cx, cz), T_LEVELS);
            int hl = level(baseHumidity(cx, cz), H_LEVELS);
            // Warm, humid lowland close to the sea, like the bayous of Louisiana.
            boolean climate = (tl >= 3 && hl >= 2) || (tl == 2 && hl >= 3);
            if (b.land < 0.03 || b.land > 0.6 || e < 1 || e > 35 || b.mountain > 0.1 || !climate) continue;
            return new SwampSpec(cx, cz, radius, h);
        }
        return null;
    }

    /** Willow swamp strength at a column (0 outside, 1 inside). */
    private double willowSwampMask(double x, double z) {
        double cell = swampCell();
        int gx = (int) Math.floor(x / cell);
        int gz = (int) Math.floor(z / cell);
        double best = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                SwampSpec s = swampSpec(gx + dx, gz + dz);
                if (s == null) continue;
                double ddx = x - s.x;
                double ddz = z - s.z;
                double reach = s.radius * 1.4;
                if (Math.abs(ddx) > reach || Math.abs(ddz) > reach) continue;
                double theta = Math.atan2(ddz, ddx);
                double wobble = 0.25 * shapeNoise.first(Math.cos(theta) * 1.8 + Hash.unit(s.hash, 9) * 50, Math.sin(theta) * 1.8, 1)
                        + 0.1 * shapeNoise.sample(x + Hash.unit(s.hash, 10) * 1e4, z, 160);
                double q = Math.sqrt(ddx * ddx + ddz * ddz) / (s.radius * (1 + wobble));
                best = Math.max(best, smooth(1.0, 0.72, q));
            }
        }
        return best;
    }

    private record ForestSpec(double x, double z, double radius, double top, long hash) {}

    private record ForestHit(double mask, double top) {}

    private double forestCell() { return 6000 * Math.max(1, biomes.sizeOf("giant_stone_forest")); }

    private ForestSpec forestSpec(int gx, int gz) {
        long key = Hash.cell(97, gx, gz, 13);
        var hit = forestCache.get(key);
        if (hit != null) return hit.value();
        ForestSpec spec = buildForest(gx, gz);
        forestCache.put(key, spec);
        return spec;
    }

    private ForestSpec buildForest(int gx, int gz) {
        double cell = forestCell();
        long h = Hash.cell(seed, gx, gz, 61);
        if (Hash.unit(h, 0) >= 0.55 * biomes.weightRatio("giant_stone_forest")) return null;
        double radius = (600 + 700 * Hash.unit(h, 3)) * biomes.sizeOf("giant_stone_forest");
        double margin = Math.min(0.45, radius * 1.3 / cell);
        // A few candidate centres per cell; the first in suitable country wins.
        for (int attempt = 0; attempt < 6; attempt++) {
            double cx = (gx + 0.5 + (Hash.unit(h, 20 + attempt) - 0.5) * (1 - 2 * margin)) * cell;
            double cz = (gz + 0.5 + (Hash.unit(h, 30 + attempt) - 0.5) * (1 - 2 * margin)) * cell;
            Base b = base(cx, cz);
            double e = b.height - sea;
            int tl = level(baseTemperature(cx, cz), T_LEVELS);
            int hl = level(baseHumidity(cx, cz), H_LEVELS);
            // Warm or temperate, humid hill country away from the coast and from the main mountain belts.
            if (b.land < 0.18 || e < 6 || e > 140 || b.mountain > 0.4 || tl < 2 || tl > 3 || hl < 1) continue;
            // The pillars are what is left of a plateau: their tops sit well above the surrounding land.
            double topY = Math.min(top - 24, b.height + 60 + 30 * Hash.unit(h, 4));
            return new ForestSpec(cx, cz, radius, topY, h);
        }
        return null;
    }

    /** Stone forest strength (0 at the edge, 1 inside) and pillar level at a column, or null outside every forest. */
    private ForestHit stoneForest(double x, double z) {
        double cell = forestCell();
        int gx = (int) Math.floor(x / cell);
        int gz = (int) Math.floor(z / cell);
        ForestHit best = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ForestSpec f = forestSpec(gx + dx, gz + dz);
                if (f == null) continue;
                double ddx = x - f.x;
                double ddz = z - f.z;
                double reach = f.radius * 1.35;
                if (Math.abs(ddx) > reach || Math.abs(ddz) > reach) continue;
                double theta = Math.atan2(ddz, ddx);
                double wobble = 0.22 * shapeNoise.first(Math.cos(theta) * 1.6 + Hash.unit(f.hash, 9) * 50, Math.sin(theta) * 1.6, 1)
                        + 0.08 * shapeNoise.sample(x + Hash.unit(f.hash, 10) * 1e4, z, 220);
                double q = Math.sqrt(ddx * ddx + ddz * ddz) / (f.radius * (1 + wobble));
                double mask = smooth(1.0, 0.7, q);
                if (mask > 0 && (best == null || mask > best.mask)) best = new ForestHit(mask, f.top);
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------------
    // Weighted Voronoi biome regions

    private record Site(double x, double z, int biome, double size, double roll2) {}

    private record Pick(int biome, double weight) {}

    private double cellSize(Role role) {
        double base = switch (role) {
            case OCEAN, DEEP_OCEAN -> 2600;
            case LOWLAND, HIGHLAND -> 1150;
            case SLOPE, PEAK -> 900;
            case CAVE -> 640;
        };
        return base * sizeMul;
    }

    private Site site(Role role, int cx, int cz, int fixedT) {
        long key = Hash.cell(role.ordinal() * 7 + fixedT, cx, cz, 3);
        var hit = siteCache.get(key);
        if (hit != null) return hit.value();
        long h = Hash.cell(seed, cx, cz, 100 + role.ordinal());
        double cell = cellSize(role);
        double px = (cx + 0.1 + 0.8 * Hash.unit(h, 0)) * cell;
        double pz = (cz + 0.1 + 0.8 * Hash.unit(h, 1)) * cell;
        double t = baseTemperature(px, pz);
        if (role == Role.SLOPE) t -= 1.0;
        if (role == Role.PEAK) t -= 1.7;
        int tl = fixedT >= 0 ? fixedT : level(t, T_LEVELS);
        int hl = level(baseHumidity(px, pz), H_LEVELS);
        int biome = biomes.pick(role, tl, hl, Hash.unit(h, 2));
        double size = biome < 0 ? 1 : biomes.size[biome];
        Site s = new Site(px, pz, biome, size, Hash.unit(h, 4));
        siteCache.put(key, s);
        return s;
    }

    private Pick choose(Role role, double x, double z) { return choose(role, x, z, -1); }

    /** Nearest site under a multiplicatively weighted metric: larger {@code size} claims larger regions. */
    private Pick choose(Role role, double x, double z, int fixedT) {
        double cell = cellSize(role);
        double wx = x + 0.38 * cell * biomeWarp.sample(x, z, cell * 1.3);
        double wz = z + 0.38 * cell * biomeWarp.sample(z + 777, x - 333, cell * 1.3);
        int gx = (int) Math.floor(wx / cell);
        int gz = (int) Math.floor(wz / cell);
        double best = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;
        int bestBiome = -1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Site s = site(role, gx + dx, gz + dz, fixedT);
                double ddx = wx - s.x;
                double ddz = wz - s.z;
                double score = Math.sqrt(ddx * ddx + ddz * ddz) / s.size;
                if (score < best) {
                    if (s.biome != bestBiome) second = best;
                    best = score;
                    bestBiome = s.biome;
                } else if (score < second && s.biome != bestBiome) {
                    second = score;
                }
            }
        }
        double margin = (second - best) / Math.max(cell * 0.05, best + 1);
        return new Pick(bestBiome, smooth(0, 0.35, margin));
    }

    private int alternative(Pick pick, Role role) {
        // Re-roll deterministically among non-wetland candidates of the same climate cell.
        long h = Hash.mix(seed, pick.biome * 31L + role.ordinal());
        for (int i = 0; i < 6; i++) {
            int b = biomes.pick(role, 2, 2, Hash.unit(h, i));
            if (b >= 0) {
                Shape s = BiomeCatalog.get(b).shape();
                if (s != Shape.SWAMP && s != Shape.MANGROVE && s != Shape.WILLOW) return b;
            }
        }
        return BiomeCatalog.index("plains");
    }

    // ------------------------------------------------------------------------------------------------------
    // Lakes

    private record LakeShape(double cx, double cz, double radius, int level, double depth, long hash, double aspect,
                             double angle, boolean valid, boolean marble) {
        LakeShape(double cx, double cz, double radius, int level, double depth, long hash, double aspect, double angle, boolean valid) {
            this(cx, cz, radius, level, depth, hash, aspect, angle, valid, false);
        }
    }

    private record LakeHit(double height, int level, boolean inside, boolean shore, boolean near, double radius,
                           LakeShape shape, double q) {
        LakeHit(double height, int level, boolean inside, boolean shore, boolean near, double radius) {
            this(height, level, inside, shore, near, radius, null, Double.NaN);
        }
    }

    private double lakeCell() {
        return Math.max(2400, settings.lakes().maxRadius() * biomes.sizeOf("lake") * 3.4);
    }

    private LakeShape lakeShape(int gx, int gz) {
        long key = Hash.cell(77, gx, gz, 5);
        var hit = lakeCache.get(key);
        if (hit != null) return hit.value();
        // Cells without a lake (null) are cached too: they cost as much to rule out as a lake costs to build.
        LakeShape shape = buildLake(gx, gz);
        lakeCache.put(key, shape);
        return shape;
    }

    private LakeShape buildLake(int gx, int gz) {
        var cfg = settings.lakes();
        double cell = lakeCell();
        long h = Hash.cell(seed, gx, gz, 61);
        double ratio = biomes.weightRatio("lake");
        if (Hash.unit(h, 0) > 0.42 * Math.min(2, ratio)) return new LakeShape(0, 0, 0, 0, 0, h, 1, 0, false);
        double size = biomes.sizeOf("lake");
        double lo = cfg.minRadius() * size;
        double hi = cfg.maxRadius() * size;
        double radius = lo * Math.pow(hi / lo, Math.pow(Hash.unit(h, 3), 1.8));
        double margin = Math.min(0.45, radius * 1.7 / cell);
        double cx = (gx + 0.5 + (Hash.unit(h, 1) - 0.5) * (1 - 2 * margin)) * cell;
        double cz = (gz + 0.5 + (Hash.unit(h, 2) - 0.5) * (1 - 2 * margin)) * cell;
        double aspect = 1 + 0.8 * Hash.unit(h, 4);
        double angle = Hash.unit(h, 5) * Math.PI;
        Column center = compute((int) cx, (int) cz, false);
        if (center.land < 0.1 || center.height < sea + 1 || center.height > top - 70) {
            return new LakeShape(cx, cz, radius, 0, 0, h, aspect, angle, false);
        }
        double rim = center.height;
        boolean riverNear = center.river;
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI * 2 / 10;
            double rx = Math.cos(a) * radius * 1.25 * aspect;
            double rz = Math.sin(a) * radius * 1.25;
            double px = cx + rx * Math.cos(angle) - rz * Math.sin(angle);
            double pz = cz + rx * Math.sin(angle) + rz * Math.cos(angle);
            Column ring = compute((int) px, (int) pz, false);
            if (ring.land < 0.02) return new LakeShape(cx, cz, radius, 0, 0, h, aspect, angle, false);
            rim = Math.min(rim, ring.height);
            riverNear |= ring.river;
        }
        int level = (int) Math.floor(rim - 1.5);
        if (level - sea < 4 || riverNear) level = sea;
        if (riverNear && !cfg.connectToRivers()) return new LakeShape(cx, cz, radius, 0, 0, h, aspect, angle, false);
        if (level > sea && center.height - level > radius * 1.2 + 40) {
            // A basin on a steep mountainside would need a huge cut; skip it.
            return new LakeShape(cx, cz, radius, 0, 0, h, aspect, angle, false);
        }
        double depth = cfg.depthScalesWithSize() ? 5 + radius * 0.5 : cfg.maxDepth();
        depth = Math.min(depth, Math.min(lakeUsableDepth, level - minY - 12));
        // Marble lakes (General Carrera, Patagonia): rare, large, cool to temperate; marble cliffs and islets with caves
        // that the waves carved at the waterline.
        int tl = level(baseTemperature(cx, cz), T_LEVELS);
        boolean marble = biomes.on("marble_caves") && radius >= 110 && radius <= 260 && tl >= 1 && tl <= 2
                && Hash.unit(h, 8) < 0.3 * biomes.weightRatio("marble_caves");
        return new LakeShape(cx, cz, radius, level, Math.max(2, depth), h, aspect, angle, true, marble);
    }

    /**
     * Lakes standing above the sea are left out entirely when any part of them would reach the transition around old
     * chunks of a converted world: the reshaped ground there would break their banks.
     */
    /** Islet {@code i} of a marble lake: centre, radius and top, or null when it does not exist. */
    private double[] islet(LakeShape s, int i) {
        long h = Hash.mix(s.hash, 500 + i);
        if (i >= 6 + (int) (Hash.unit(s.hash, 12) * 9)) return null;
        // More islets near the shore, where the real ones stand.
        double q = 0.3 + 0.62 * Math.pow(Hash.unit(h, 1), 0.6);
        double a = Hash.unit(h, 2) * Math.PI * 2;
        double rx = Math.cos(a) * q * s.radius * s.aspect;
        double rz = Math.sin(a) * q * s.radius;
        double x = s.cx + rx * Math.cos(s.angle) - rz * Math.sin(s.angle);
        double z = s.cz + rx * Math.sin(s.angle) + rz * Math.cos(s.angle);
        double r = 6 + 14 * Math.pow(Hash.unit(h, 3), 1.5);
        double topY = s.level + 6 + 16 * Hash.unit(h, 4);
        return new double[]{x, z, r, topY, Hash.unit(h, 5)};
    }

    /** Height of the marble islets at a column (steep walls, rounded tops), or -infinity. */
    private double marbleIslet(double x, double z, LakeShape s) {
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 16; i++) {
            double[] p = islet(s, i);
            if (p == null) break;
            double dx = x - p[0];
            double dz = z - p[1];
            double r = p[2] * (1 + 0.15 * rough.sample(x + 31 * i, z, 7));
            double d = Math.sqrt(dx * dx + dz * dz) / r;
            if (d >= 1) continue;
            best = Math.max(best, p[3] - (p[3] - s.level) * 0.35 * Math.pow(d, 4));
        }
        return best;
    }

    /**
     * Grottoes at the waterline of a marble lake: the outer shell of each islet and alcoves along the shore cliff are
     * hollowed out from just under the water to a few blocks above it; the lake water fills them.
     */
    private void marbleGrotto(double x, double z, double h, LakeHit hit, Column col) {
        LakeShape s = hit.shape;
        col.marbleLake = s;
        double top = Double.NaN;
        if (hit.q < 1) {
            for (int i = 0; i < 16; i++) {
                double[] p = islet(s, i);
                if (p == null) break;
                double dx = x - p[0];
                double dz = z - p[1];
                double d = Math.sqrt(dx * dx + dz * dz) / p[2];
                // The core (inner ~55 %) stays and holds the islet up; arches go through where the noise opens it.
                double core = rough.sample(x + 77 * i, z - 13 * i, 6) > 0.15 ? 0 : 0.42;
                if (d < 0.95 && d > core && h > s.level + 5) {
                    top = s.level + 3 + 4 * p[4];
                    break;
                }
            }
        } else if (hit.q < 1 + 14 / hit.radius && h > s.level + 6 && rough.sample(x, z, 11) > -0.35) {
            top = s.level + 3 + 3.5 * (0.5 + 0.5 * rough.sample(x + 91, z, 7));
        }
        if (Double.isNaN(top)) return;
        col.tunnelKind = TUNNEL_MARBLE;
        col.tunnelFloor = s.level - 2;
        col.tunnelTop = top;
        if (s.level > sea) col.lakeLevel = s.level;
    }

    private boolean nearOldChunks(LakeShape s) {
        if (s.level <= sea) return false;
        LegacyIndex index = LegacyIndex.currentAny();
        if (index == null || index.isEmpty() || index.blendingDistance() <= 0) return false;
        double reach = index.blendingDistance() + s.radius * 2.6 * s.aspect + 64;
        return index.anyNear((int) Math.floor(s.cx / 16), (int) Math.floor(s.cz / 16), (int) Math.ceil(reach / 16));
    }

    private LakeHit lake(double x, double z, double h) {
        double cell = lakeCell();
        int gx = (int) Math.floor(x / cell);
        int gz = (int) Math.floor(z / cell);
        LakeHit result = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LakeShape s = lakeShape(gx + dx, gz + dz);
                if (!s.valid || nearOldChunks(s)) continue;
                double ddx = x - s.cx;
                double ddz = z - s.cz;
                double reach = s.radius * 2.6 * s.aspect + 64;
                if (Math.abs(ddx) > reach || Math.abs(ddz) > reach) continue;
                double rx = (ddx * Math.cos(s.angle) + ddz * Math.sin(s.angle)) / s.aspect;
                double rz = -ddx * Math.sin(s.angle) + ddz * Math.cos(s.angle);
                double dist = Math.sqrt(rx * rx + rz * rz);
                double theta = Math.atan2(rz, rx);
                double wobble = 0.17 * shapeNoise.first(Math.cos(theta) * 1.5 + Hash.unit(s.hash, 9) * 50, Math.sin(theta) * 1.5, 1)
                        + 0.07 * shapeNoise.sample(x, z, Math.max(20, s.radius * 0.3));
                double radius = s.radius * (1 + wobble);
                double q = dist / radius;
                double band = Math.max(0.06, 10 / radius);
                if (q < 1) {
                    double profile = Math.pow(1 - q * q, 0.6);
                    double floorNoise = 0.22 * detail.sample(x, z, Math.max(30, s.radius * 0.25));
                    double bed = s.level - 1 - (s.depth - 1) * profile * (1 + floorNoise);
                    if (settings.lakes().generateIslands() && s.radius > 90) {
                        double bump = smooth(0.5, 0.75, specialNoise.sample(x + s.cx, z, Math.max(40, s.radius * 0.33)));
                        bed = Math.max(bed, lerp(bump * smooth(0.15, 0.4, q) * smooth(1, 0.75, q), bed, s.level + 3));
                    }
                    // Ground far above the water is not cut away: the lake ends at the foot of a slope instead of cutting
                    // a sheer wall into a mountain that its basin reaches.
                    double cut = smooth(s.level + 40, s.level + 12, h);
                    if (cut <= 0) continue;
                    double carved = Math.min(h, bed);
                    // Shallow margin: shore of the lake slopes into the water instead of a cliff. Marble lakes keep
                    // their cliffs and get rock islets.
                    if (s.marble) {
                        carved = Math.max(carved, marbleIslet(x, z, s));
                    } else {
                        carved = Math.min(carved, s.level + 0.5 + (q - 0.96) * 60);
                    }
                    carved = lerp(cut, h, carved);
                    return new LakeHit(carved, s.level, carved < s.level, false, true, s.radius, s, q);
                }
                if (s.level > sea) {
                    double outer = q - 1;
                    double rimHeight = outer < band ? s.level + 1.3 : s.level + 1.3 - (outer - band) * radius * 0.55;
                    double raised = Math.max(h, rimHeight);
                    if (outer < band * 4 + 0.3) {
                        result = new LakeHit(raised, s.level, false, outer < band * 2, true, s.radius, s, q);
                        h = raised;
                    }
                } else if (q < 1 + band * 3) {
                    double shoreHeight = Math.min(h, s.level + 1 + (q - 1) * radius * 0.25);
                    result = new LakeHit(s.marble ? h : shoreHeight, s.level, false, true, false, s.radius, s, q);
                    h = shoreHeight;
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------------------
    // Rivers

    private double riverField(int layer, double x, double z) {
        double p = riverPeriods[layer];
        double w = riverWidths[layer];
        // Large-scale course, then meanders: wavelength ~11 channel widths, amplitude ~2 widths (natural proportions).
        double rx = x + 0.2 * p * riverWarpX.sample(x, z, p * 0.7);
        double rz = z + 0.2 * p * riverWarpZ.sample(x, z, p * 0.7);
        double meander = 11 * w + 100;
        double amplitude = 2.2 * (w + 10);
        double mx = rx + amplitude * riverWarpX.first(rx + 9137 * layer, rz, meander);
        double mz = rz + amplitude * riverWarpZ.first(rx, rz - 7411 * layer, meander);
        return riverLayers[layer].sample(mx, mz, p);
    }

    /** The large-scale course of a river layer: {@link #riverField} without the meanders. */
    private double riverCourse(int layer, double x, double z) {
        double p = riverPeriods[layer];
        double rx = x + 0.2 * p * riverWarpX.sample(x, z, p * 0.7);
        double rz = z + 0.2 * p * riverWarpZ.sample(x, z, p * 0.7);
        return riverLayers[layer].sample(rx, rz, p);
    }

    /** Distance in blocks to the large-scale course of a river layer (no meanders), from the field and its gradient. */
    private double courseDistance(int layer, double x, double z) {
        double n = riverCourse(layer, x, z);
        double e = 8;
        double gx = (riverCourse(layer, x + e, z) - n) / e;
        double gz = (riverCourse(layer, x, z + e) - n) / e;
        return Math.abs(n) / Math.max(Math.sqrt(gx * gx + gz * gz), 1e-7);
    }

    /** Distance in blocks to the centre line of a river layer, estimated from the field and its gradient. */
    private double riverDistance(int layer, double x, double z) {
        double n = riverField(layer, x, z);
        double e = 3;
        double gx = (riverField(layer, x + e, z) - n) / e;
        double gz = (riverField(layer, x, z + e) - n) / e;
        double g = Math.sqrt(gx * gx + gz * gz);
        return Math.abs(n) / Math.max(g, 1e-7);
    }

    /** Per-layer river diagnostics: width, field value and estimated distance to the channel centre. */
    public String describeRivers(double x, double z) {
        var sb = new StringBuilder();
        for (int k = 0; k < riverLayers.length; k++) {
            sb.append(String.format(java.util.Locale.ROOT, "[w=%.0f p=%.0f n=%.4f d=%.1f] ", riverWidths[k], riverPeriods[k],
                    riverField(k, x, z), riverDistance(k, x, z)));
        }
        return sb.toString();
    }

    private double rivers(double x, double z, double h, double land, double temperature, double humidity, double valleyHere, Column col) {
        var r = settings.rivers();
        double e = Math.max(0, h - sea);
        double estuary = 1 + 0.9 * smooth(0.08, 0.0, land);
        double carved = h;
        double largerReach = Double.MAX_VALUE;
        // Hot, dry country (deserts, dry savanna) has far fewer rivers; the widest ones mostly survive.
        double arid = smooth(0.44, 1.0, temperature) * smooth(-0.1, -0.75, humidity);
        double[][] probes = new double[riverLayers.length][];
        for (int k = riverLayers.length - 1; k >= 0; k--) {
            double width = riverWidths[k];
            // Where it meets a wider river, a narrower one is bent along it, the two halves in opposite directions, so
            // they flow in as two tributaries at different places instead of crossing.
            double rx = x;
            double rz = z;
            // Cheap reject first: the bend moves the river by at most a few hundred blocks, far less than its period. Not
            // where the land stands high above the valley: the banks of a deep gorge reach so far that the bound would cut
            // them off mid-slope (a sheer wall).
            if (h - valleyHere < 120 && Math.abs(riverField(k, x, z)) > 0.55) {
                if (DEBUG_RIVERS.get() != null) DEBUG_RIVERS.get().append(String.format(java.util.Locale.ROOT, "%n    layer %d skipped: field", k));
                continue;
            }
            if (r.connectedNetworks()) {
                for (int j = 0; j < k; j++) {
                    if (probes[j] == null) probes[j] = riverProbe(j, x, z);
                    double[] pj = probes[j];
                    double reach = riverWidths[j] * 0.5 + 80 + 3 * width;
                    if (pj[1] >= reach) continue;
                    double shift = (riverWidths[j] * 0.6 + 30 + 2 * width) * smooth(reach, reach * 0.3, pj[1]) * Math.signum(pj[0]);
                    rx += pj[2] * shift;
                    rz += pj[3] * shift;
                }
            }
            // Fixed per river: the slow width field changes by at most width_variation along its course.
            double variation = r.widthVariation() * riverWidth.sample(x + k * 7919, z, riverPeriods[k] * 3);
            double half = width * 0.5 * (1 + variation) * estuary;
            double above = Math.max(0, h - sea);
            // Deep gorges widen so their walls stay steep but not sheer; at most ~320 blocks, as far as the distance to
            // the river is reliable (further out the estimate jumps and the slope would break into walls). Under higher
            // mountains the river takes a tunnel (see tunnelWeight).
            double bank = r.allowMountainGorges() ? Math.min(320, 10 + 0.38 * above + 0.5 * Math.max(0, above - 150)) + width * 0.5
                    : Math.min(320, 24 + 1.25 * above) + width;
            // Quick reject on the raw field before paying for the gradient.
            double reachBlocks = half + bank;
            double field = Math.abs(riverField(k, rx, rz));
            if (field > 0.35 && reachBlocks < riverPeriods[k] * 0.06) {
                if (DEBUG_RIVERS.get() != null) DEBUG_RIVERS.get().append(String.format(java.util.Locale.ROOT, "%n    layer %d skipped: quick %.2f reach %.0f", k, field, reachBlocks));
                continue;
            }
            double dist = riverDistance(k, rx, rz);
            // Far out on the banks of a deep gorge the estimate from the meandering field jumps (its gradient swings with
            // the meanders): there the smooth course gives the distance, close to the water the meanders do.
            double near = 4 * (width + 10);
            if (reachBlocks > near) {
                double course = courseDistance(k, rx, rz);
                dist = lerp(smooth(near, 2.5 * near, course), dist, course);
            }
            if (!r.connectedNetworks() && largerReach < 1.4 && k < riverLayers.length - 1) continue;
            if (dist >= reachBlocks) {
                largerReach = Math.min(largerReach, dist / reachBlocks);
                if (DEBUG_RIVERS.get() != null) DEBUG_RIVERS.get().append(String.format(java.util.Locale.ROOT, "%n    layer %d skipped: dist %.0f >= reach %.0f", k, dist, reachBlocks));
                continue;
            }
            largerReach = Math.min(largerReach, dist / reachBlocks);
            double wet = 1;
            // Only the narrower rivers dry up; the wide ones flow through deserts too.
            if (arid > 0 && k >= 2) {
                double share = 1 - arid * 0.85;
                double gate = 0.5 + 1.2 * riverWidth.sample(x - 4111 * k, z + 2237 * k, riverPeriods[k] * 0.8);
                wet = smooth(share + 0.06, share - 0.06, gate);
            }
            double keep = wet * smooth(130, 35, valleyHere - sea);
            // Where the climate dried the river up, its bed is left: a shallow dry channel above the water table.
            double dry = 1 - wet;
            // The river flows at the height of its valley, the same across its width: taken at the centre line and
            // in steps of a few blocks (rapids, and waterfalls where the valley drops off an escarpment).
            // Rivers flow at sea level (Minecraft water cannot run downhill). Where the valley climbs into the
            // highlands the river fades out: a dry, ever shallower valley up to its source.
            double surface0 = sea;
            if (dry > 0.02 && biomes.on("dry_riverbed")) {
                carved = Math.min(carved, dryBed(h, Math.max(0, h - surface0), dist, half, bank, dry, col, surface0));
            }
            if (keep <= 0) {
                if (DEBUG_RIVERS.get() != null) DEBUG_RIVERS.get().append(String.format(java.util.Locale.ROOT, "%n    layer %d skipped: keep %.2f", k, keep));
                continue;
            }
            double depth = r.depthScalesWithWidth() ? Math.max(3, Math.min(48, 2 + width * 0.14)) : 6;
            depth = Math.min(depth, sea - minY - 16);
            // Whether the river tunnels does not depend on how much of it is kept (faded or dried up): a threshold there
            // switched between an untouched slope and a half-deep gorge from one column to the next.
            double tunnel = r.mountainTunnels() && r.allowMountainGorges() && k > 0 && h - surface0 > 12
                    ? tunnelWeight(k, rx, rz, dist, width, surface0) : 0;
            double value;
            if (dist < half) {
                col.riverKeep = Double.isNaN(col.riverKeep) ? keep : Math.min(col.riverKeep, keep);
                if (keep > 0.95) col.riverChannels |= 1 << k;
                double t = dist / half;
                value = surface0 - 0.6 - depth * (1 - t * t) * (0.85 + 0.15 * detail.sample(x, z, 60));
            } else {
                double t = (dist - half) / bank;
                value = lerp(smooth(0, 1, t), surface0 + 1.1, h);
            }
            double open = lerp(keep, h, value);
            if (tunnel > 0) {
                // Towards the mountain the gorge gets shallower and the river goes underground.
                double surface = lerp(tunnel, open, h);
                double arch = Math.max(8, Math.min(36, 7 + width * 0.3));
                double tunnelHalf = half * 1.15 + 3;
                if (dist < tunnelHalf) {
                    double t = dist / tunnelHalf;
                    double ceiling = surface0 + 1 + arch * Math.sqrt(1 - t * t) * (0.85 + 0.15 * rough.sample(x, z, 18));
                    // The arch is cut also where the river is still open (the tunnel mouth): the steep ground next to
                    // the gorge would otherwise leave spikes and a raised bed in the way.
                    // Where tunnels of two rivers overlap: the highest arch and the lowest bed, so no ledge of one
                    // stands in the channel of the other.
                    double floor = dist < half ? value : surface0 + 1;
                    if (col.tunnelKind == 0 || col.tunnelKind == TUNNEL_MARBLE) {
                        col.tunnelTop = ceiling;
                        col.tunnelFloor = floor;
                        double kind = riverWidth.sample(x - 3331 * k, z + 4441 * k, 3200);
                        col.tunnelKind = kind < -0.12 ? TUNNEL_PLAIN : kind < 0.18 ? TUNNEL_GLOWING : TUNNEL_GLOWWORMS;
                    } else {
                        col.tunnelTop = Math.max(col.tunnelTop, ceiling);
                        col.tunnelFloor = Math.min(col.tunnelFloor, floor);
                    }
                    // At least 5 blocks of rock above the arch; otherwise the river stays open.
                    carved = Math.min(carved, surface - ceiling >= 5 ? surface : open);
                    continue;
                }
                carved = Math.min(carved, surface);
                if (DEBUG_RIVERS.get() != null) DEBUG_RIVERS.get().append(String.format(java.util.Locale.ROOT,
                        "%n    layer %d (tunnel approach) dist %.0f half %.0f bank %.0f tunnel %.2f value %.1f surface %.1f", k, dist, half, bank, tunnel, value, surface));
                continue;
            }
            carved = Math.min(carved, open);
            if (DEBUG_RIVERS.get() != null) DEBUG_RIVERS.get().append(String.format(java.util.Locale.ROOT,
                    "%n    layer %d dist %.0f half %.0f bank %.0f keep %.2f tunnel %.2f value %.1f open %.1f", k, dist, half, bank, keep, tunnel, value, open));
        }
        // Deltas: braided channels on very low coastal plains, only at a river mouth (alone on a coast they were short
        // dead-end ditches).
        if (land > 0 && land < 0.09 && e < 7 && largerReach < 4) {
            double d = Math.abs(delta.sample(x, z, 650));
            double strength = smooth(0.09, 0.02, land) * smooth(7, 2, e);
            double channel = 0.045 * strength;
            if (d < channel || (land < 0.03 && channelInland(x, z, land, channel))) carved = Math.min(carved, sea - 1.5);
        }
        return carved;
    }

    /**
     * Dry river bed: the valley of the river it replaces (same banks), with a flat floor a little above the water so it
     * never fills, and a shallow channel in the middle.
     */
    private double dryBed(double h, double e, double dist, double half, double bank, double dry, Column col, double water) {
        double floor = water + 2 + 0.12 * e;
        double channel = half * 0.8;
        double v;
        if (dist < channel) {
            double t = dist / channel;
            v = Math.max(water + 1.5, floor - 1.5 * (1 - t * t));
            if (dry > 0.5) col.dryRiver = true;
        } else if (dist < channel + bank) {
            v = lerp(smooth(0, 1, (dist - channel) / bank), floor, h);
        } else {
            return h;
        }
        return lerp(dry, h, Math.min(h, v));
    }

    /** Tunnel kinds: plain stone, glowing (lush caves), blue glowworms (glowing caves). */
    public static final int TUNNEL_PLAIN = 1;
    public static final int TUNNEL_GLOWING = 2;
    public static final int TUNNEL_GLOWWORMS = 3;
    /** Wave-cut grotto at the waterline of a marble lake (shore cliffs and islets). */
    public static final int TUNNEL_MARBLE = 4;

    /**
     * Where a river crosses a mountain, part of the crossings run through a tunnel instead of a gorge. The weight (0..1)
     * is decided at the river's centre line, so the whole width agrees, and rises smoothly with the height of the land
     * above the river: the gorge gets shallower towards the mountain and the river enters a tunnel mouth in the slope.
     * Columns over the tunnel keep the mountain surface; the tunnel itself is cut by {@link EpicDensity}.
     */
    private double tunnelWeight(int k, double x, double z, double dist, double width, double water) {
        // Which crossings tunnel changes slowly along the river, and smoothly, so no gorge ends in a sheer wall.
        double gate = smooth(-0.05, 0.2, riverWidth.sample(x + 1777 * k, z - 913 * k, 2400));
        double n = riverField(k, x, z);
        double gx = (riverField(k, x + 3, z) - n) / 3;
        double gz = (riverField(k, x, z + 3) - n) / 3;
        double g = Math.sqrt(gx * gx + gz * gz);
        if (g < 1e-9) return 0;
        double sign = Math.signum(n);
        Base above = base(x - sign * gx / g * dist, z - sign * gz / g * dist);
        double rise = above.height - water;
        // Under the highest ranges the river mostly goes underground instead of cutting a gorge a kilometre deep.
        gate = Math.max(gate, smooth(150, 350, rise));
        if (gate <= 0) return 0;
        double arch = Math.max(8, Math.min(36, 7 + width * 0.3));
        // Only under real mountains: in hills the river keeps its valley.
        double start = Math.max(arch + 14, above.mountain > 0.2 ? 60 : 90);
        return gate * smooth(start, start + 30, rise);
    }

    /**
     * Field value, distance to the centre line, and unit direction along river layer {@code j} at a point; the
     * distance is infinite far from the river (cheap reject on the field before the gradient).
     */
    private double[] riverProbe(int j, double x, double z) {
        double n = riverField(j, x, z);
        if (Math.abs(n) > 0.35) return new double[]{n, Double.POSITIVE_INFINITY, 0, 0};
        double e = 3;
        double gx = (riverField(j, x + e, z) - n) / e;
        double gz = (riverField(j, x, z + e) - n) / e;
        double g = Math.max(Math.sqrt(gx * gx + gz * gz), 1e-7);
        double dist = Math.abs(n) / g;
        // Away from the water the meandering field gives a jumpy distance; the smooth course takes over there, so the
        // bend of a tributary (and its gorge) does not jerk from one column to the next.
        double near = 4 * (riverWidths[j] + 10);
        if (dist > near) dist = lerp(smooth(near, 2.5 * near, dist), dist, courseDistance(j, x, z));
        return new double[]{n, dist, -gz / g, gx / g};
    }

    private static final double DELTA_SLIVER = 16;

    /**
     * A delta channel running along the coast leaves only a thin strip of beach between itself and the sea, a line of
     * land in the water. True when a channel lies within {@link #DELTA_SLIVER} blocks of the coast on the inland side
     * of this column, so that the strip is flooded and the channel opens into the sea.
     */
    private boolean channelInland(double x, double z, double land, double channel) {
        double gx = (land(x + 8, z) - land(x - 8, z)) / 16;
        double gz = (land(x, z + 8) - land(x, z - 8)) / 16;
        double g = Math.sqrt(gx * gx + gz * gz);
        if (g < 1e-9) return false;
        double coast = land / g;
        if (coast >= DELTA_SLIVER) return false;
        double nx = gx / g;
        double nz = gz / g;
        // Decided once per strip, at the point of the coastline this column faces, so that the whole strip is either
        // flooded or kept and no single line of land is left at its edge.
        double qx = x - nx * coast;
        double qz = z - nz * coast;
        double width = channelEdge(qx, qz, nx, nz, channel);
        if (width > DELTA_SLIVER || coast > width + 1) return false;
        // The channel must be real there: on high ground the delta field carves nothing.
        double px = qx + nx * (width + 2);
        double pz = qz + nz * (width + 2);
        Base b = base(px, pz);
        double strength = smooth(0.09, 0.02, b.land) * smooth(7, 2, Math.max(0, b.height - sea));
        return Math.abs(delta.sample(px, pz, 650)) < 0.045 * strength;
    }

    /**
     * Distance from a point to the edge of the nearest delta channel inland (along {@code nx, nz}), estimated from the
     * delta field and its gradient; infinite when the channel is not inland of the point.
     */
    private double channelEdge(double x, double z, double nx, double nz, double channel) {
        double d = Math.abs(delta.sample(x, z, 650));
        double dx = (Math.abs(delta.sample(x + 2, z, 650)) - Math.abs(delta.sample(x - 2, z, 650))) / 4;
        double dz = (Math.abs(delta.sample(x, z + 2, 650)) - Math.abs(delta.sample(x, z - 2, 650))) / 4;
        double gd = Math.sqrt(dx * dx + dz * dz);
        if (gd < 1e-9 || -(dx * nx + dz * nz) / gd < 0.5) return Double.POSITIVE_INFINITY;
        return Math.max(0, d - channel) / gd;
    }

    // ------------------------------------------------------------------------------------------------------
    // 3D formations: stone ring sea and the giant stone forest

    private record Torus(double x, double y, double z, double nx, double ny, double nz, double major, double minor) {}

    private record Pillar(double x, double z, double radius, double bottom, double top, long hash) {}

    private record Bridge(double x1, double y1, double z1, double x2, double y2, double z2, double radius) {}

    private record Formation(Torus[] rings, Pillar[] pillars, Bridge[] bridges) {}

    private static final Formation EMPTY = new Formation(new Torus[0], new Pillar[0], new Bridge[0]);

    private static final double RING_CELL = 150;

    /** Open water of the stone ring sea: inside the biome (weight), away from coasts and at least {@code depth} deep. */
    private boolean ringWater(double x, double z, double weight, double depth) {
        Column c = column((int) x, (int) z);
        return c.rings > weight && c.land < -0.1 && c.height < sea - depth;
    }

    private Formation ringCell(int gx, int gz) {
        long key = Hash.cell(91, gx, gz, 9);
        var hit = formationCache.get(key);
        if (hit != null) return hit.value();
        double cell = RING_CELL;
        long h = Hash.cell(seed, gx, gz, 71);
        Formation f = EMPTY;
        if (Hash.unit(h, 0) < 0.9) {
            int count = 2 + (int) (Hash.unit(h, 1) * 2.99);
            // Sea stacks: slender spires around the rings.
            int stacks = 6 + (int) (Hash.unit(h, 2) * 9);
            var rings = new java.util.ArrayList<Torus>();
            var pillars = new java.util.ArrayList<Pillar>();
            for (int i = 0; i < count; i++) {
                double major = 20 + 70 * Math.pow(Hash.unit(h, 10 + i), 1.3);
                double minor = 5 + Math.min(16, major * 0.18) * (0.6 + 0.4 * Hash.unit(h, 20 + i));
                double cx = (gx + 0.15 + 0.7 * Hash.unit(h, 30 + i)) * cell;
                double cz = (gz + 0.15 + 0.7 * Hash.unit(h, 40 + i)) * cell;
                // Only out at sea, never near a coast, and only where the whole ring fits inside the biome (so it is
                // never cut off at the biome's edge).
                double reach = major + 16 + 12;
                if (!ringWater(cx, cz, 0.3, 10) || !ringWater(cx + reach, cz, 0.05, 6) || !ringWater(cx - reach, cz, 0.05, 6)
                        || !ringWater(cx, cz + reach, 0.05, 6) || !ringWater(cx, cz - reach, 0.05, 6)) continue;
                double floorY = column((int) cx, (int) cz).height;
                // Centres mostly above the water, so the rings stand as arches over the sea.
                double cy = sea + (Hash.unit(h, 50 + i) * 0.6 - 0.1) * major;
                cy = Math.min(cy, top - 14 - major - minor);
                double yaw = Hash.unit(h, 60 + i) * Math.PI;
                double tilt = (Hash.unit(h, 70 + i) - 0.5) * 0.7;
                double nx = Math.cos(yaw) * Math.cos(tilt);
                double nz = Math.sin(yaw) * Math.cos(tilt);
                double ny = Math.sin(tilt);
                rings.add(new Torus(cx, cy, cz, nx, ny, nz, major, minor));
                // Ring feet stand on stacks reaching the sea floor.
                double footY = cy - major * 0.92;
                pillars.add(new Pillar(cx, cz, minor * 1.25, floorY - 6, Math.max(footY, floorY + 2), Hash.mix(h, i)));
            }
            for (int i = 0; i < stacks; i++) {
                long hs = Hash.mix(h, 100 + i);
                double sx = (gx + Hash.unit(hs, 1)) * cell;
                double sz = (gz + Hash.unit(hs, 2)) * cell;
                if (!ringWater(sx, sz, 0.15, 8)) continue;
                double floorY = column((int) sx, (int) sz).height;
                double radius = 3 + 6 * Math.pow(Hash.unit(hs, 3), 1.6);
                double stackTop = sea + 8 + 55 * Math.pow(Hash.unit(hs, 4), 1.3);
                pillars.add(new Pillar(sx, sz, radius, floorY - 6, Math.min(top - 14, stackTop), hs));
            }
            f = new Formation(rings.toArray(Torus[]::new), pillars.toArray(Pillar[]::new), new Bridge[0]);
        }
        formationCache.put(key, f);
        return f;
    }

    private static final double PILLAR_CELL = 40;

    private Formation pillarCell(int gx, int gz) {
        long key = Hash.cell(92, gx, gz, 9);
        var hit = formationCache.get(key);
        if (hit != null) return hit.value();
        double cell = PILLAR_CELL;
        long h = Hash.cell(seed, gx, gz, 72);
        Formation f = EMPTY;
        if (Hash.unit(h, 0) < 0.85) {
            double px = (gx + 0.2 + 0.6 * Hash.unit(h, 1)) * cell;
            double pz = (gz + 0.2 + 0.6 * Hash.unit(h, 2)) * cell;
            Column ground = column((int) px, (int) pz);
            // Fewer pillars towards the edge of the forest.
            if (ground.pillars > 0.05 && ground.height > sea && !ground.river && Hash.unit(h, 14) < 0.35 + 0.65 * ground.pillars) {
                double radius = 5 + 11 * Math.pow(Hash.unit(h, 3), 1.5);
                // Tops near the old plateau level, lower at the edge; a few stand out above it.
                double plateau = Double.isNaN(ground.forestTop) ? ground.height + 60 : ground.forestTop;
                double level = lerp(0.4 + 0.6 * ground.pillars, ground.height + 25, plateau);
                double topY = level + 22 * (Hash.unit(h, 4) - 0.5) + (Hash.unit(h, 15) < 0.1 ? 35 * Hash.unit(h, 16) : 0);
                topY = Math.min(top - 12, Math.max(ground.height + 20, topY));
                var pillar = new Pillar(px, pz, radius, ground.height - 8, topY, h);
                Bridge[] bridges = new Bridge[0];
                if (Hash.unit(h, 5) < 0.12) {
                    int nx = gx + (Hash.unit(h, 6) < 0.5 ? 1 : 0);
                    int nz = gz + (nx == gx ? 1 : 0);
                    long h2 = Hash.cell(seed, nx, nz, 72);
                    double qx = (nx + 0.25 + 0.5 * Hash.unit(h2, 1)) * cell;
                    double qz = (nz + 0.25 + 0.5 * Hash.unit(h2, 2)) * cell;
                    double y = Math.min(topY, column((int) qx, (int) qz).height + 40) - 8 - 25 * Hash.unit(h, 7);
                    if (y > ground.height + 25) {
                        bridges = new Bridge[]{new Bridge(px, y, pz, qx, y - 4 * Hash.unit(h, 8), qz, 3 + 3 * Hash.unit(h, 9))};
                    }
                }
                f = new Formation(new Torus[0], new Pillar[]{pillar}, bridges);
            }
        }
        formationCache.put(key, f);
        return f;
    }

    /** Signed distance (negative inside) to rock formations, or +infinity when none are near. */
    public double formationDistance(int x, int y, int z, Column col) {
        double best = Double.POSITIVE_INFINITY;
        if (col.rings > 0.02) {
            double cell = RING_CELL;
            int gx = Math.floorDiv(x, (int) cell);
            int gz = Math.floorDiv(z, (int) cell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Formation f = ringCell(gx + dx, gz + dz);
                    for (Torus t : f.rings) best = Math.min(best, torus(x, y, z, t));
                    for (Pillar p : f.pillars) best = Math.min(best, pillar(x, y, z, p));
                }
            }
            double roughness = 3.2 * rough.sample(x + y * 0.7, z - y * 0.5, 22) + 1.4 * rough.sample(z - y, x + y * 0.3, 9);
            best = best - roughness;
        }
        if (col.pillars > 0.02) {
            double cell = PILLAR_CELL;
            int gx = Math.floorDiv(x, (int) cell);
            int gz = Math.floorDiv(z, (int) cell);
            double local = Double.POSITIVE_INFINITY;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Formation f = pillarCell(gx + dx, gz + dz);
                    for (Pillar p : f.pillars) local = Math.min(local, pillar(x, y, z, p));
                    for (Bridge b : f.bridges) local = Math.min(local, capsule(x, y, z, b));
                }
            }
            // Rough walls, but never deep enough to cut a pillar apart.
            double roughness = Math.min(2.4, 0.18 * Math.max(0, -local + 8)) * rough.sample(x + y * 0.8, z - y * 0.4, 14);
            best = Math.min(best, local - roughness);
        }
        return best;
    }

    private static double torus(double x, double y, double z, Torus t) {
        double px = x - t.x;
        double py = y - t.y;
        double pz = z - t.z;
        if (Math.abs(px) > t.major + t.minor + 8 || Math.abs(pz) > t.major + t.minor + 8
                || Math.abs(py) > t.major + t.minor + 8) return Double.POSITIVE_INFINITY;
        double along = px * t.nx + py * t.ny + pz * t.nz;
        double qx = px - along * t.nx;
        double qy = py - along * t.ny;
        double qz = pz - along * t.nz;
        double radial = Math.sqrt(qx * qx + qy * qy + qz * qz) - t.major;
        return Math.sqrt(radial * radial + along * along) - t.minor;
    }

    private double pillar(double x, double y, double z, Pillar p) {
        if (y < p.bottom - 4 || y > p.top + 6) return Double.POSITIVE_INFINITY;
        double dx = x - p.x;
        double dz = z - p.z;
        if (Math.abs(dx) > p.radius * 2 + 8 || Math.abs(dz) > p.radius * 2 + 8) return Double.POSITIVE_INFINITY;
        double rel = (y - p.bottom) / Math.max(1, p.top - p.bottom);
        double wobble = 1 + 0.2 * rough.first(y / 19.0 + Hash.unit(p.hash, 11) * 100, Hash.unit(p.hash, 12) * 100, 1)
                + 0.28 * smooth(0.92, 1, Math.sin(y / 23.0 + Hash.unit(p.hash, 13) * 6.28));
        double radius = p.radius * (1 - 0.22 * rel) * wobble;
        double horizontal = Math.sqrt(dx * dx + dz * dz) - radius;
        double vertical = y - p.top + 0.5 * Math.max(0, Math.sqrt(dx * dx + dz * dz) - radius * 0.6);
        return Math.max(horizontal, vertical);
    }

    private static double capsule(double x, double y, double z, Bridge b) {
        double vx = b.x2 - b.x1;
        double vy = b.y2 - b.y1;
        double vz = b.z2 - b.z1;
        double wx = x - b.x1;
        double wy = y - b.y1;
        double wz = z - b.z1;
        double len2 = vx * vx + vy * vy + vz * vz;
        double t = Math.max(0, Math.min(1, (wx * vx + wy * vy + wz * vz) / Math.max(1e-6, len2)));
        double sag = Math.sin(t * Math.PI) * -3;
        double dx = wx - vx * t;
        double dy = wy - vy * t - sag;
        double dz = wz - vz * t;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) - b.radius * (1 + 0.6 * Math.abs(t - 0.5));
    }

    // ------------------------------------------------------------------------------------------------------
    // Spawn

    /** Nearest comfortable land to the origin: dry lowland, no river, lake, mountain or ocean. */
    public net.minecraft.core.BlockPos findSpawn() {
        for (int ring = 0; ring < 400; ring++) {
            int steps = Math.max(1, ring * 8);
            for (int i = 0; i < steps; i++) {
                double angle = i * Math.PI * 2 / steps;
                int x = (int) Math.round(Math.cos(angle) * ring * 160);
                int z = (int) Math.round(Math.sin(angle) * ring * 160);
                Column c = column(x, z);
                String path = BiomeCatalog.get(c.biome).path();
                if (c.land > 0.12 && c.height > sea + 3 && c.height < sea + 70 && !c.river && c.lakeLevel == Integer.MIN_VALUE
                        && c.mountain < 0.2 && !path.contains("ocean") && !path.equals("lake") && !path.contains("swamp")) {
                    return new net.minecraft.core.BlockPos(x, (int) c.height, z);
                }
            }
        }
        return net.minecraft.core.BlockPos.ZERO;
    }

    /**
     * Nearest column whose surface (or, for cave biomes, underground) biome is {@code target}, searched on rings of
     * growing radius. Returns the surface position, or null.
     */
    public net.minecraft.core.BlockPos locate(int target, int originX, int originZ, int maxRadius) {
        String path = BiomeCatalog.get(target).path();
        // Marble caves are on the surface now (marble lakes).
        boolean cave = (path.endsWith("_caves") && !path.equals("marble_caves")) || path.equals("deep_dark");
        int step = 64;
        for (int r = 0; r <= maxRadius; r += step) {
            int count = Math.max(1, (int) (2 * Math.PI * r / step));
            for (int i = 0; i < count; i++) {
                double angle = i * Math.PI * 2 / count;
                int x = originX + (int) Math.round(Math.cos(angle) * r);
                int z = originZ + (int) Math.round(Math.sin(angle) * r);
                Column c = column(x, z);
                if (cave) {
                    boolean deep = target == deepDark && c.deepDark > 0.5;
                    if (c.caveBiome == target || deep) {
                        int y = deep ? minY + 20 : (int) Math.max(minY + 16, Math.min(c.height - 40, sea - 60));
                        return new net.minecraft.core.BlockPos(x, y, z);
                    }
                } else if (c.biome == target) {
                    return new net.minecraft.core.BlockPos(x, (int) Math.max(c.height, sea) + 2, z);
                }
            }
        }
        return null;
    }

    /** Nearest river tunnel of a kind ({@link #TUNNEL_PLAIN} ...), at the water inside it, or null. */
    public net.minecraft.core.BlockPos locateTunnel(int kind, int originX, int originZ, int maxRadius) {
        int step = 24;
        for (int r = 0; r <= maxRadius; r += step) {
            int count = Math.max(1, (int) (2 * Math.PI * r / step));
            for (int i = 0; i < count; i++) {
                double angle = i * Math.PI * 2 / count;
                int x = originX + (int) Math.round(Math.cos(angle) * r);
                int z = originZ + (int) Math.round(Math.sin(angle) * r);
                Column c = column(x, z);
                // Under rock, not at the open mouth.
                if (c.tunnelKind == kind && c.height > c.tunnelTop + 5) return new net.minecraft.core.BlockPos(x, sea + 1, z);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------
    // Biome at a 3D position

    public int biomeAt(int x, int y, int z) {
        Column col = column(x, z);
        int surface = (int) Math.floor(col.height);
        if (col.tunnelKind > 0 && y >= col.tunnelFloor - 4 && y <= col.tunnelTop + 4) {
            return switch (col.tunnelKind) {
                case TUNNEL_GLOWING -> lushCaves;
                case TUNNEL_GLOWWORMS -> glowingCaves;
                case TUNNEL_MARBLE -> marbleCaves;
                default -> river;
            };
        }
        if (col.deepDark > 0.5 && y < minY + 0.33 * (sea - minY) && y < surface - 40) return deepDark;
        if (col.caveBiome >= 0 && y < surface - 26 && y > minY + 8) return col.caveBiome;
        return col.biome;
    }

    // ------------------------------------------------------------------------------------------------------

    /**
     * A fixed-size cache shared by all threads: generation of one chunk runs on several worker threads, and the pool
     * replaces its threads, so per-thread caches rebuilt the same lakes, swamps and sites over and over. Values are
     * deterministic, so a lost race only repeats work. Null values are cached as well.
     */
    static final class SharedCache<V> {
        record Entry<V>(long key, V value) {}

        private final java.util.concurrent.atomic.AtomicReferenceArray<Entry<V>> slots;
        private final int mask;

        SharedCache(int size) {
            slots = new java.util.concurrent.atomic.AtomicReferenceArray<>(size);
            mask = size - 1;
        }

        Entry<V> get(long key) {
            Entry<V> e = slots.get((int) (Hash.mix(key, 0x51ED) & mask));
            return e != null && e.key == key ? e : null;
        }

        void put(long key, V value) {
            slots.set((int) (Hash.mix(key, 0x51ED) & mask), new Entry<>(key, value));
        }
    }
}
