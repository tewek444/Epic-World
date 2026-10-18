package me.nyxia.epicworld.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nyxia.epicworld.config.EpicSettings;

/**
 * All Epic World Overworld biomes, their default frequency and the climate/terrain cells they may occupy.
 *
 * <p><b>The order of {@link #ENTRIES} is part of the save format</b>: the biome index produced by the terrain
 * engine refers to it. Only append new entries.
 */
public final class BiomeCatalog {
    private BiomeCatalog() {}

    /** Terrain-driven special shaping applied inside a biome's regions. */
    public enum Shape { NONE, SWAMP, MANGROVE, WILLOW, DUNES, BADLANDS, WOODED_BADLANDS, ERODED, SAVANNA_PLATEAU,
        WINDSWEPT, STONE_FOREST, STONE_RINGS, JAGGED, MEADOW }

    public record Entry(String path, String vanilla, int weight, double size, Shape shape) {
        public boolean isNew() { return vanilla == null; }
        public String id() { return "epicworld:" + path; }
    }

    public static final List<Entry> ENTRIES = List.of(
            v("badlands", 100, Shape.BADLANDS), v("bamboo_jungle", 50, Shape.NONE), v("beach", 100, Shape.NONE),
            v("birch_forest", 100, Shape.NONE), v("cherry_grove", 40, Shape.MEADOW), v("cold_ocean", 100, Shape.NONE),
            v("dark_forest", 100, Shape.NONE), v("deep_cold_ocean", 100, Shape.NONE), v("deep_dark", 100, Shape.NONE),
            v("deep_frozen_ocean", 100, Shape.NONE), v("deep_lukewarm_ocean", 100, Shape.NONE), v("deep_ocean", 100, Shape.NONE),
            v("desert", 100, Shape.DUNES), v("dripstone_caves", 30, Shape.NONE), v("eroded_badlands", 35, Shape.ERODED),
            v("flower_forest", 30, Shape.NONE), v("forest", 100, Shape.NONE), v("frozen_ocean", 100, Shape.NONE),
            v("frozen_peaks", 100, Shape.JAGGED), v("frozen_river", 100, Shape.NONE), v("grove", 100, Shape.NONE),
            v("ice_spikes", 25, Shape.NONE), v("jagged_peaks", 100, Shape.JAGGED), v("jungle", 100, Shape.NONE),
            v("lukewarm_ocean", 100, Shape.NONE), v("lush_caves", 30, Shape.NONE), v("mangrove_swamp", 50, Shape.MANGROVE),
            v("meadow", 100, Shape.MEADOW), v("mushroom_fields", 100, Shape.NONE), v("ocean", 100, Shape.NONE),
            v("old_growth_birch_forest", 40, Shape.NONE), v("old_growth_pine_taiga", 60, Shape.NONE),
            v("old_growth_spruce_taiga", 60, Shape.NONE), v("plains", 100, Shape.NONE), v("river", 100, Shape.NONE),
            v("savanna", 100, Shape.NONE), v("savanna_plateau", 100, Shape.SAVANNA_PLATEAU), v("snowy_beach", 100, Shape.NONE),
            v("snowy_plains", 100, Shape.NONE), v("snowy_slopes", 100, Shape.NONE), v("snowy_taiga", 100, Shape.NONE),
            v("sparse_jungle", 70, Shape.NONE), v("stony_peaks", 100, Shape.JAGGED), v("stony_shore", 100, Shape.NONE),
            v("sunflower_plains", 30, Shape.NONE), v("swamp", 60, Shape.SWAMP), v("taiga", 100, Shape.NONE),
            v("warm_ocean", 100, Shape.NONE), v("windswept_forest", 70, Shape.WINDSWEPT),
            v("windswept_gravelly_hills", 50, Shape.WINDSWEPT), v("windswept_hills", 100, Shape.WINDSWEPT),
            v("windswept_savanna", 50, Shape.WINDSWEPT), v("wooded_badlands", 70, Shape.WOODED_BADLANDS),
            // Epic World biomes (append only).
            n("lake", 100, Shape.NONE), n("atoll", 100, Shape.NONE), n("stone_ring_sea", 3, Shape.STONE_RINGS),
            n("willow_swamp", 40, Shape.WILLOW), n("giant_stone_forest", 12, Shape.STONE_FOREST),
            n("marble_caves", 8, Shape.NONE), n("glowing_caves", 8, Shape.NONE), n("dry_riverbed", 100, Shape.NONE));

    private static Entry v(String path, int weight, Shape shape) { return new Entry(path, path, weight, 1.0, shape); }

    private static Entry n(String path, int weight, Shape shape) { return new Entry(path, null, weight, 1.0, shape); }

    private static final Map<String, Integer> INDEX = new HashMap<>();

    static {
        for (int i = 0; i < ENTRIES.size(); i++) INDEX.put(ENTRIES.get(i).path(), i);
    }

    public static int index(String path) {
        Integer i = INDEX.get(path);
        if (i == null) throw new IllegalArgumentException("Unknown Epic World biome " + path);
        return i;
    }

    public static Entry get(int index) { return ENTRIES.get(index); }

    /** Roles a column can have after the terrain is known. */
    public enum Role { OCEAN, DEEP_OCEAN, LOWLAND, HIGHLAND, SLOPE, PEAK, CAVE }

    /** Temperature levels 0..4 (frozen..hot) × humidity levels 0..4 (arid..humid). Names are biome paths. */
    private static final Map<Role, String[][][]> TABLES = new HashMap<>();

    private static String[] c(String... names) { return names; }

    static {
        TABLES.put(Role.OCEAN, new String[][][]{
                {c("frozen_ocean"), c("frozen_ocean"), c("frozen_ocean"), c("frozen_ocean"), c("frozen_ocean")},
                {c("cold_ocean", "stone_ring_sea"), c("cold_ocean"), c("cold_ocean", "stone_ring_sea"), c("cold_ocean"), c("cold_ocean")},
                {c("ocean", "stone_ring_sea"), c("ocean"), c("ocean", "stone_ring_sea"), c("ocean"), c("ocean", "stone_ring_sea")},
                {c("lukewarm_ocean", "stone_ring_sea"), c("lukewarm_ocean"), c("lukewarm_ocean"), c("lukewarm_ocean", "stone_ring_sea"), c("lukewarm_ocean")},
                {c("warm_ocean"), c("warm_ocean", "stone_ring_sea"), c("warm_ocean"), c("warm_ocean"), c("warm_ocean")}});
        TABLES.put(Role.DEEP_OCEAN, new String[][][]{
                {c("deep_frozen_ocean"), c("deep_frozen_ocean"), c("deep_frozen_ocean"), c("deep_frozen_ocean"), c("deep_frozen_ocean")},
                {c("deep_cold_ocean"), c("deep_cold_ocean"), c("deep_cold_ocean"), c("deep_cold_ocean"), c("deep_cold_ocean")},
                {c("deep_ocean"), c("deep_ocean"), c("deep_ocean"), c("deep_ocean"), c("deep_ocean")},
                {c("deep_lukewarm_ocean"), c("deep_lukewarm_ocean"), c("deep_lukewarm_ocean"), c("deep_lukewarm_ocean"), c("deep_lukewarm_ocean")},
                {c("deep_lukewarm_ocean"), c("deep_lukewarm_ocean"), c("deep_lukewarm_ocean"), c("deep_lukewarm_ocean"), c("deep_lukewarm_ocean")}});
        TABLES.put(Role.LOWLAND, new String[][][]{
                {c("snowy_plains", "ice_spikes"), c("snowy_plains", "ice_spikes"), c("snowy_plains", "snowy_taiga"), c("snowy_taiga"), c("snowy_taiga", "snowy_plains")},
                {c("plains"), c("plains", "forest"), c("forest", "taiga"), c("taiga", "old_growth_pine_taiga", "swamp"), c("old_growth_spruce_taiga", "old_growth_pine_taiga", "taiga")},
                {c("plains", "sunflower_plains", "flower_forest"), c("plains", "forest", "sunflower_plains"), c("forest", "flower_forest", "birch_forest"),
                        c("birch_forest", "old_growth_birch_forest", "dark_forest", "swamp"), c("dark_forest", "swamp", "old_growth_birch_forest")},
                {c("savanna"), c("savanna", "plains"), c("forest", "savanna", "sparse_jungle"),
                        c("sparse_jungle", "jungle", "mangrove_swamp"), c("jungle", "bamboo_jungle", "mangrove_swamp")},
                {c("desert"), c("desert"), c("desert", "savanna"), c("jungle", "sparse_jungle", "mangrove_swamp"), c("bamboo_jungle", "jungle", "mangrove_swamp")}});
        TABLES.put(Role.HIGHLAND, new String[][][]{
                {c("snowy_plains"), c("snowy_plains", "ice_spikes"), c("snowy_taiga"), c("snowy_taiga", "grove"), c("grove", "snowy_taiga")},
                {c("windswept_gravelly_hills", "windswept_hills"), c("windswept_hills", "plains"), c("windswept_forest", "forest"),
                        c("taiga", "windswept_forest"), c("old_growth_spruce_taiga", "taiga")},
                {c("meadow", "windswept_hills"), c("meadow", "plains"), c("forest", "cherry_grove", "meadow"),
                        c("birch_forest", "cherry_grove"), c("dark_forest")},
                {c("savanna_plateau"), c("savanna_plateau", "windswept_savanna"), c("forest", "savanna_plateau"),
                        c("jungle"), c("bamboo_jungle", "jungle")},
                {c("badlands", "eroded_badlands"), c("wooded_badlands", "badlands"), c("wooded_badlands", "savanna_plateau"), c("jungle", "sparse_jungle"), c("jungle")}});
        TABLES.put(Role.SLOPE, new String[][][]{
                {c("snowy_slopes"), c("snowy_slopes"), c("snowy_slopes", "grove"), c("grove", "snowy_slopes"), c("grove")},
                {c("snowy_slopes"), c("snowy_slopes", "grove"), c("grove", "snowy_slopes"), c("grove"), c("grove")},
                {c("meadow"), c("meadow"), c("meadow", "cherry_grove"), c("cherry_grove", "meadow"), c("grove")},
                {c("windswept_savanna"), c("windswept_savanna", "savanna_plateau"), c("meadow", "windswept_savanna"),
                        c("meadow"), c("jungle")},
                {c("eroded_badlands"), c("wooded_badlands", "eroded_badlands"), c("wooded_badlands"), c("jungle"), c("jungle")}});
        TABLES.put(Role.PEAK, new String[][][]{
                {c("frozen_peaks", "jagged_peaks"), c("frozen_peaks", "jagged_peaks"), c("frozen_peaks", "jagged_peaks"), c("frozen_peaks", "jagged_peaks"), c("frozen_peaks", "jagged_peaks")},
                {c("jagged_peaks", "frozen_peaks"), c("jagged_peaks", "frozen_peaks"), c("jagged_peaks", "frozen_peaks"), c("jagged_peaks", "frozen_peaks"), c("jagged_peaks", "frozen_peaks")},
                {c("stony_peaks", "jagged_peaks"), c("stony_peaks", "jagged_peaks"), c("jagged_peaks", "stony_peaks"), c("jagged_peaks", "stony_peaks"), c("jagged_peaks", "stony_peaks")},
                {c("stony_peaks"), c("stony_peaks"), c("stony_peaks"), c("stony_peaks"), c("stony_peaks")},
                {c("stony_peaks"), c("stony_peaks"), c("stony_peaks"), c("stony_peaks"), c("stony_peaks")}});
        // "" = keep the surface biome underground.
        TABLES.put(Role.CAVE, new String[][][]{
                {c("", "dripstone_caves"), c("", "dripstone_caves"), c("", "lush_caves"), c("", "lush_caves"), c("", "lush_caves")},
                {c("", "dripstone_caves"), c("", "dripstone_caves"), c("", "lush_caves"),
                        c("", "lush_caves", "glowing_caves"), c("", "lush_caves", "glowing_caves")},
                {c("", "dripstone_caves"), c("", "dripstone_caves"), c("", "lush_caves", "dripstone_caves"),
                        c("", "lush_caves", "glowing_caves"), c("", "lush_caves", "glowing_caves")},
                {c("", "dripstone_caves"), c("", "dripstone_caves"), c("", "lush_caves", "dripstone_caves"),
                        c("", "lush_caves", "glowing_caves"), c("", "lush_caves", "glowing_caves")},
                {c("", "dripstone_caves"), c("", "dripstone_caves"), c("", "dripstone_caves", "lush_caves"), c("", "lush_caves", "glowing_caves"), c("", "lush_caves")}});
    }

    /** Weight of keeping the surface biome underground, compared with cave biome weights. */
    public static final int SURFACE_CAVE_WEIGHT = 140;

    /** Resolved per-world view: enabled flags, weights and sizes after applying the config overrides. */
    public static final class Resolved {
        public final boolean[] enabled = new boolean[ENTRIES.size()];
        public final double[] weight = new double[ENTRIES.size()];
        public final double[] size = new double[ENTRIES.size()];
        /** Candidate indices and cumulative weights per role/temperature/humidity; index -1 = surface biome. */
        private final int[][][][] candidates = new int[Role.values().length][5][5][];
        private final double[][][][] cumulative = new double[Role.values().length][5][5][];

        public Resolved(EpicSettings settings) {
            for (int i = 0; i < ENTRIES.size(); i++) {
                Entry entry = ENTRIES.get(i);
                var override = settings.biomes().overrides().get(entry.id());
                enabled[i] = override == null || override.enabled().orElse(true);
                weight[i] = override == null ? entry.weight() : override.weight().orElse(entry.weight());
                size[i] = Math.max(0.1, Math.min(10, override == null ? entry.size() : override.size().orElse(entry.size())));
                if (weight[i] <= 0) enabled[i] = false;
            }
            for (Role role : Role.values()) {
                String[][][] table = TABLES.get(role);
                for (int t = 0; t < 5; t++) {
                    for (int h = 0; h < 5; h++) {
                        var ids = new ArrayList<Integer>();
                        var weights = new ArrayList<Double>();
                        for (String name : table[t][h]) {
                            if (name.isEmpty()) {
                                ids.add(-1);
                                weights.add((double) SURFACE_CAVE_WEIGHT);
                            } else if (enabled[index(name)]) {
                                ids.add(index(name));
                                weights.add(weight[index(name)]);
                            }
                        }
                        if (ids.isEmpty()) fallback(role, t, h, ids, weights);
                        int[] array = ids.stream().mapToInt(Integer::intValue).toArray();
                        double[] cum = new double[array.length];
                        double total = 0;
                        for (int k = 0; k < array.length; k++) {
                            total += weights.get(k);
                            cum[k] = total;
                        }
                        candidates[role.ordinal()][t][h] = array;
                        cumulative[role.ordinal()][t][h] = cum;
                    }
                }
            }
        }

        /** Every candidate in this climate cell is disabled: borrow the nearest enabled cell of the same role. */
        private void fallback(Role role, int t, int h, List<Integer> ids, List<Double> weights) {
            String[][][] table = TABLES.get(role);
            for (int radius = 1; radius <= 8 && ids.isEmpty(); radius++) {
                for (int dt = -radius; dt <= radius; dt++) {
                    for (int dh = -radius; dh <= radius; dh++) {
                        int tt = t + dt;
                        int hh = h + dh;
                        if (tt < 0 || tt > 4 || hh < 0 || hh > 4 || Math.abs(dt) + Math.abs(dh) != radius) continue;
                        for (String name : table[tt][hh]) {
                            if (!name.isEmpty() && enabled[index(name)] && !ids.contains(index(name))) {
                                ids.add(index(name));
                                weights.add(weight[index(name)]);
                            }
                        }
                    }
                }
            }
            if (ids.isEmpty()) {
                // The whole role is disabled; keep generation valid with the vanilla default of that role.
                String name = switch (role) {
                    case OCEAN -> "ocean";
                    case DEEP_OCEAN -> "deep_ocean";
                    case LOWLAND, HIGHLAND -> "plains";
                    case SLOPE -> "meadow";
                    case PEAK -> "stony_peaks";
                    case CAVE -> "";
                };
                ids.add(name.isEmpty() ? -1 : index(name));
                weights.add(1.0);
            }
        }

        /** Weighted choice; {@code roll} in [0, 1). */
        public int pick(Role role, int t, int h, double roll) {
            int[] ids = candidates[role.ordinal()][t][h];
            double[] cum = cumulative[role.ordinal()][t][h];
            double target = roll * cum[cum.length - 1];
            for (int k = 0; k < ids.length; k++) {
                if (target < cum[k]) return ids[k];
            }
            return ids[ids.length - 1];
        }

        public boolean on(String path) { return enabled[index(path)]; }

        public double weightRatio(String path) {
            int i = index(path);
            return enabled[i] ? weight[i] / Math.max(1, ENTRIES.get(i).weight()) : 0;
        }

        public double sizeOf(String path) { return size[index(path)]; }
    }

    public static String[] paths() {
        return ENTRIES.stream().map(Entry::path).toArray(String[]::new);
    }

    @Override
    public String toString() { return Arrays.toString(paths()); }
}
