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
    private final int atoll = BiomeCatalog.index("atoll");
    private final int mushroom = BiomeCatalog.index("mushroom_fields");
    private final int deepDark = BiomeCatalog.index("deep_dark");

    private final ThreadLocal<Caches> caches = ThreadLocal.withInitial(Caches::new);

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
        var r = settings.rivers();
        int layers = r.maxWidth() == r.minWidth() ? 1 : r.maxWidth() <= 2 * r.minWidth() ? 2 : 4;
        riverLayers = new Fbm[layers];
        riverWidths = new double[layers];
        riverPeriods = new double[layers];
        for (int i = 0; i < layers; i++) {
            riverLayers[i] = new Fbm(Hash.mix(seed, 1000 + i), 2, 0.35);
            double f = layers == 1 ? 1 : (double) (layers - 1 - i) / (layers - 1);
            riverWidths[i] = r.minWidth() * Math.pow((double) r.maxWidth() / r.minWidth(), f);
            // Wider rivers are rarer: each layer covers roughly 0.6-0.8 % of the land.
            riverPeriods[i] = 2.5 * riverWidths[i] / (0.006 + 0.002 * riverWidths[i] / r.maxWidth());
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
        public boolean river;
    }

    public Column column(int x, int z) {
        Caches c = caches.get();
        int slot = (int) (Hash.mix(x, z) & (Caches.COLUMNS - 1));
        long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        if (c.columnKeys[slot] == key && c.columns[slot] != null) return c.columns[slot];
        Column column = compute(x, z, true);
        c.columnKeys[slot] = key;
        c.columns[slot] = column;
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

    private record Base(double height, double land, double mountain, double hilliness, double depthFactor) {}

    /** Continents, mountains and ocean floor before islands, biome shaping, lakes and rivers. */
    private Base base(double x, double z) {
        double l = land(x, z);
        if (l >= 0) {
            double inland = smooth(0, 0.7, l);
            double coastFade = smooth(0, 0.18, l);
            double belt = mountainBelt.ridged(x + 900, z - 1300, 17000);
            double mountain = smooth(0.4, 0.68, belt) * smooth(0.03, 0.45, l);
            double massif = smooth(0.35, 0.7, hillMask.sample(x - 4000, z + 2500, 9000)) * smooth(0.1, 0.8, l) * 0.6;
            mountain = Math.max(mountain, massif * smooth(0.3, 0.55, belt));
            // Main ridges every ~1.4 km, secondary crests and gullies on their flanks.
            double ridge = peaks.ridged(x, z, 1400);
            double crest = hills.ridged(x + 3100, z - 700, 420);
            double mountainHeight = mountain * ((top - sea - 70) * (0.25 + 0.75 * Math.pow(ridge, 1.35))
                    + 55 * (crest - 0.35) * smooth(0.1, 0.6, ridge));
            double hilliness = smooth(-0.25, 0.55, hillMask.sample(x, z, 7000));
            double hillHeight = hilliness * 40 * (0.5 + 0.5 * hills.sample(x, z, 950)) * coastFade;
            double rise = Math.pow(smooth(0, 0.55, l), 0.55);
            // Land starts 1.5 blocks above the water so the coastline is a clean edge, not a flooded flat.
            double low = 1.5 + 6 * smooth(0, 0.04, l) + 44 * rise * (0.55 + 0.45 * lowland.sample(x, z, 5200))
                    + 3 * Math.max(0, detail.sample(x, z, 180)) * coastFade;
            return new Base(sea + low + hillHeight + mountainHeight, l, mountain, hilliness, 0);
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
        return new Base(sea - d, l, 0, 0, smooth(0.3, 1.0, u));
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
        double tEff = t0 - Math.max(0, elevation - 30) / 110.0;
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
        col.jag = Math.min(1, col.jag + 0.55 * b.mountain + 0.15 * b.hilliness);

        // ---- lakes
        LakeHit lakeHit = null;
        if (withLakes && settings.lakes().enabled() && biomes.on("lake") && b.land > 0.08) {
            lakeHit = lake(x, z, h);
            if (lakeHit != null) {
                h = lakeHit.height;
                if (lakeHit.inside && lakeHit.level > sea) col.lakeLevel = lakeHit.level;
            }
        }

        // ---- rivers
        boolean riverHere = false;
        if (settings.rivers().enabled() && b.land > -0.06 && (lakeHit == null || lakeHit.level <= sea || !lakeHit.near)) {
            double carved = rivers(x, z, h, b.land);
            if (carved < h) {
                riverHere = carved < sea - 0.5 && b.land > -0.02;
                h = carved;
            }
        }

        h = Math.max(minY + 14, Math.min(top - 10, h));
        col.height = h;
        elevation = h - sea;
        tEff = t0 - Math.max(0, elevation - 30) / 110.0;
        col.temperature = tEff;
        int tl = level(tEff, T_LEVELS);
        int hl = level(h0, H_LEVELS);

        // ---- final surface biome
        if (lakeHit != null && lakeHit.inside && h < lakeHit.level) {
            col.biome = tl == 0 ? landBiome(Role.LOWLAND, x, z) : lake;
        } else if (riverHere) {
            col.river = true;
            col.biome = tl == 0 ? frozenRiver : river;
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
        if (e > 0.62 * span && b.mountain > 0.3) return Role.PEAK;
        if (e > 0.36 * span && b.mountain > 0.12) return Role.SLOPE;
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
        Caches c = caches.get();
        long key = Hash.cell(scale + 11, gx, gz, 13);
        int slot = (int) (key & (Caches.ISLANDS - 1));
        if (c.islandKeys[slot] == key && c.islandFilled[slot]) return c.islands[slot];
        IslandSpec spec = buildIsland(scale, gx, gz);
        c.islandKeys[slot] = key;
        c.islands[slot] = spec;
        c.islandFilled[slot] = true;
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
            case STONE_FOREST -> {
                col.pillars = w;
                double floor = sea + 10 + 16 * (0.5 + 0.5 * specialNoise.sample(x, z, 300));
                return lerp(w * 0.85, h, Math.min(h, floor + 0.3 * Math.max(0, h - floor - 40)));
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
        Caches c = caches.get();
        long key = Hash.cell(role.ordinal() * 7 + fixedT, cx, cz, 3);
        int slot = (int) (key & (Caches.SITES - 1));
        if (c.siteKeys[slot] == key && c.sites[slot] != null) return c.sites[slot];
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
        c.siteKeys[slot] = key;
        c.sites[slot] = s;
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
                             double angle, boolean valid) {}

    private record LakeHit(double height, int level, boolean inside, boolean shore, boolean near, double radius) {}

    private double lakeCell() {
        return Math.max(2400, settings.lakes().maxRadius() * biomes.sizeOf("lake") * 3.4);
    }

    private LakeShape lakeShape(int gx, int gz) {
        Caches c = caches.get();
        long key = Hash.cell(77, gx, gz, 5);
        int slot = (int) (key & (Caches.LAKES - 1));
        if (c.lakeKeys[slot] == key && c.lakes[slot] != null) return c.lakes[slot];
        LakeShape shape = buildLake(gx, gz);
        c.lakeKeys[slot] = key;
        c.lakes[slot] = shape;
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
        return new LakeShape(cx, cz, radius, level, Math.max(2, depth), h, aspect, angle, true);
    }

    /**
     * Lakes standing above the sea are left out entirely when any part of them would reach the transition around old
     * chunks of a converted world: the reshaped ground there would break their banks.
     */
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
                    double carved = Math.min(h, bed);
                    // Shallow margin: shore of the lake slopes into the water instead of a cliff.
                    carved = Math.min(carved, s.level + 0.5 + (q - 0.96) * 60);
                    return new LakeHit(carved, s.level, carved < s.level, false, true, s.radius);
                }
                if (s.level > sea) {
                    double outer = q - 1;
                    double rimHeight = outer < band ? s.level + 1.3 : s.level + 1.3 - (outer - band) * radius * 0.55;
                    double raised = Math.max(h, rimHeight);
                    if (outer < band * 4 + 0.3) {
                        result = new LakeHit(raised, s.level, false, outer < band * 2, true, s.radius);
                        h = raised;
                    }
                } else if (q < 1 + band * 3) {
                    double shoreHeight = Math.min(h, s.level + 1 + (q - 1) * radius * 0.25);
                    result = new LakeHit(shoreHeight, s.level, false, true, false, s.radius);
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

    private double rivers(double x, double z, double h, double land) {
        var r = settings.rivers();
        double e = Math.max(0, h - sea);
        double estuary = 1 + 0.9 * smooth(0.08, 0.0, land);
        double carved = h;
        double largerReach = Double.MAX_VALUE;
        for (int k = riverLayers.length - 1; k >= 0; k--) {
            double width = riverWidths[k];
            // Fixed per river: the slow width field changes by at most width_variation along its course.
            double variation = r.widthVariation() * riverWidth.sample(x + k * 7919, z, riverPeriods[k] * 3);
            double half = width * 0.5 * (1 + variation) * estuary;
            double bank = r.allowMountainGorges() ? 10 + 0.38 * e + width * 0.5 : 24 + 1.25 * e + width;
            // Quick reject on the raw field before paying for the gradient.
            double reachBlocks = half + bank;
            double field = Math.abs(riverField(k, x, z));
            if (field > 0.35 && reachBlocks < riverPeriods[k] * 0.06) continue;
            double dist = riverDistance(k, x, z);
            if (!r.connectedNetworks() && largerReach < 1.4 && k < riverLayers.length - 1) continue;
            if (dist >= reachBlocks) {
                largerReach = Math.min(largerReach, dist / reachBlocks);
                continue;
            }
            largerReach = Math.min(largerReach, dist / reachBlocks);
            double depth = r.depthScalesWithWidth() ? Math.max(3, Math.min(48, 2 + width * 0.14)) : 6;
            depth = Math.min(depth, sea - minY - 16);
            double value;
            if (dist < half) {
                double t = dist / half;
                value = sea - 0.6 - depth * (1 - t * t) * (0.85 + 0.15 * detail.sample(x, z, 60));
            } else {
                double t = (dist - half) / bank;
                value = lerp(smooth(0, 1, t), sea + 1.1, h);
            }
            carved = Math.min(carved, value);
        }
        // Deltas: braided channels on very low coastal plains.
        if (land > 0 && land < 0.09 && e < 7) {
            double d = Math.abs(delta.sample(x, z, 650));
            double strength = smooth(0.09, 0.02, land) * smooth(7, 2, e);
            if (d < 0.045 * strength) carved = Math.min(carved, sea - 1.5);
        }
        return carved;
    }

    // ------------------------------------------------------------------------------------------------------
    // 3D formations: stone ring sea and the giant stone forest

    private record Torus(double x, double y, double z, double nx, double ny, double nz, double major, double minor) {}

    private record Pillar(double x, double z, double radius, double bottom, double top, long hash) {}

    private record Bridge(double x1, double y1, double z1, double x2, double y2, double z2, double radius) {}

    private record Formation(Torus[] rings, Pillar[] pillars, Bridge[] bridges) {}

    private static final Formation EMPTY = new Formation(new Torus[0], new Pillar[0], new Bridge[0]);

    private Formation ringCell(int gx, int gz) {
        Caches c = caches.get();
        long key = Hash.cell(91, gx, gz, 9);
        int slot = (int) (key & (Caches.FORMATIONS - 1));
        if (c.formationKeys[slot] == key && c.formations[slot] != null) return c.formations[slot];
        double cell = 230;
        long h = Hash.cell(seed, gx, gz, 71);
        Formation f = EMPTY;
        if (Hash.unit(h, 0) < 0.62) {
            int count = 1 + (int) (Hash.unit(h, 1) * 2.2);
            var rings = new Torus[count];
            var pillars = new Pillar[count];
            for (int i = 0; i < count; i++) {
                double major = 16 + 80 * Math.pow(Hash.unit(h, 10 + i), 1.3);
                double minor = 5 + Math.min(16, major * 0.18) * (0.6 + 0.4 * Hash.unit(h, 20 + i));
                double cx = (gx + 0.2 + 0.6 * Hash.unit(h, 30 + i)) * cell;
                double cz = (gz + 0.2 + 0.6 * Hash.unit(h, 40 + i)) * cell;
                double floorY = column((int) cx, (int) cz).height;
                double cy = sea + (Hash.unit(h, 50 + i) - 0.25) * major * 0.9;
                cy = Math.min(cy, top - 14 - major - minor);
                double yaw = Hash.unit(h, 60 + i) * Math.PI;
                double tilt = (Hash.unit(h, 70 + i) - 0.5) * 0.7;
                double nx = Math.cos(yaw) * Math.cos(tilt);
                double nz = Math.sin(yaw) * Math.cos(tilt);
                double ny = Math.sin(tilt);
                rings[i] = new Torus(cx, cy, cz, nx, ny, nz, major, minor);
                // Ring feet stand on stacks reaching the sea floor.
                double footY = cy - major * 0.92;
                pillars[i] = new Pillar(cx, cz, minor * 1.25, floorY - 6, Math.max(footY, floorY + 2), Hash.mix(h, i));
            }
            f = new Formation(rings, pillars, new Bridge[0]);
        }
        c.formationKeys[slot] = key;
        c.formations[slot] = f;
        return f;
    }

    private Formation pillarCell(int gx, int gz) {
        Caches c = caches.get();
        long key = Hash.cell(92, gx, gz, 9);
        int slot = (int) (key & (Caches.FORMATIONS - 1));
        if (c.formationKeys[slot] == key && c.formations[slot] != null) return c.formations[slot];
        double cell = 72;
        long h = Hash.cell(seed, gx, gz, 72);
        Formation f = EMPTY;
        if (Hash.unit(h, 0) < 0.72) {
            double px = (gx + 0.25 + 0.5 * Hash.unit(h, 1)) * cell;
            double pz = (gz + 0.25 + 0.5 * Hash.unit(h, 2)) * cell;
            Column ground = column((int) px, (int) pz);
            if (ground.pillars > 0.05 && ground.height > sea) {
                double radius = 8 + 18 * Math.pow(Hash.unit(h, 3), 1.6);
                double height = (45 + 230 * Math.pow(Hash.unit(h, 4), 1.4)) * (0.35 + 0.65 * ground.pillars);
                double topY = Math.min(top - 12, ground.height + height);
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
        c.formationKeys[slot] = key;
        c.formations[slot] = f;
        return f;
    }

    /** Signed distance (negative inside) to rock formations, or +infinity when none are near. */
    public double formationDistance(int x, int y, int z, Column col) {
        double best = Double.POSITIVE_INFINITY;
        if (col.rings > 0.02) {
            double cell = 230;
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
            best = best - roughness + (1 - col.rings) * 30;
        }
        if (col.pillars > 0.02) {
            double cell = 72;
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
        boolean cave = BiomeCatalog.get(target).path().endsWith("_caves") || BiomeCatalog.get(target).path().equals("deep_dark");
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

    // ------------------------------------------------------------------------------------------------------
    // Biome at a 3D position

    public int biomeAt(int x, int y, int z) {
        Column col = column(x, z);
        int surface = (int) Math.floor(col.height);
        if (col.deepDark > 0.5 && y < minY + 0.33 * (sea - minY) && y < surface - 40) return deepDark;
        if (col.caveBiome >= 0 && y < surface - 26 && y > minY + 8) return col.caveBiome;
        return col.biome;
    }

    // ------------------------------------------------------------------------------------------------------

    private static final class Caches {
        static final int COLUMNS = 2048;
        static final int SITES = 4096;
        static final int LAKES = 256;
        static final int FORMATIONS = 1024;
        static final int ISLANDS = 1024;
        final long[] columnKeys = new long[COLUMNS];
        final Column[] columns = new Column[COLUMNS];
        final long[] siteKeys = new long[SITES];
        final Site[] sites = new Site[SITES];
        final long[] lakeKeys = new long[LAKES];
        final LakeShape[] lakes = new LakeShape[LAKES];
        final long[] formationKeys = new long[FORMATIONS];
        final Formation[] formations = new Formation[FORMATIONS];
        final long[] islandKeys = new long[ISLANDS];
        final IslandSpec[] islands = new IslandSpec[ISLANDS];
        final boolean[] islandFilled = new boolean[ISLANDS];
    }
}
