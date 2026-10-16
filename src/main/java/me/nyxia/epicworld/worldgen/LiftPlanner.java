package me.nyxia.epicworld.worldgen;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;

/**
 * Plans how far each part of an old world is lifted when it is converted to Epic World, so that its surface meets the
 * new terrain around it instead of sitting in a basin far below.
 *
 * <p>Old chunks are grouped into islands (explored areas; chunks closer than {@link #MERGE} chunks belong together).
 * A whole island moves by one amount, a multiple of 16 blocks, so nothing built across chunk borders is torn apart.
 * The amount is chosen along the island's edge: the old surface (water level where there is water) is compared with
 * the surface Epic World will generate just outside, and the lift minimising the weighted mismatch wins. Water next
 * to water weighs most, so seas and rivers line up. What remains is left to the blending of new chunks.
 */
public final class LiftPlanner {
    private LiftPlanner() {}

    /** Old chunks closer than this many chunks form one island. */
    public static final int MERGE = 2;
    /** Distances (blocks) from an edge chunk where the new surface is sampled. */
    private static final int[] RING = {48, 96, 160};

    /** What the planner needs to know about one old chunk. */
    public record OldChunk(LegacyIndex.Surface surface, int topY) {}

    /** One edge sample: old and new surface level, and how much it counts. */
    public record EdgeSample(long chunk, int oldLevel, int newLevel, int weight) {
        public int diff() { return newLevel - oldLevel; }
    }

    public record Island(int id, LongList chunks, int minX, int minZ, int maxX, int maxZ, int topY, int maxLift,
                         List<EdgeSample> edge, int lift) {
        /** Weighted share of the edge still off by more than {@code blocks} after the lift. */
        public double shareOffBy(int blocks) {
            double total = 0;
            double off = 0;
            for (EdgeSample s : edge) {
                total += s.weight;
                if (Math.abs(s.diff() - lift) > blocks) off += s.weight;
            }
            return total == 0 ? 0 : off / total;
        }

        /** Percentile (0–1) of the absolute residual mismatch along the edge, in blocks. */
        public int residual(double percentile) {
            if (edge.isEmpty()) return 0;
            int[] r = edge.stream().mapToInt(s -> Math.abs(s.diff() - lift)).sorted().toArray();
            return r[Math.min(r.length - 1, (int) (percentile * r.length))];
        }
    }

    public record Plan(List<Island> islands, Long2IntOpenHashMap islandOf) {
        public int liftAt(int chunkX, int chunkZ) {
            int id = islandOf.getOrDefault(ChunkPos.asLong(chunkX, chunkZ), -1);
            return id < 0 ? 0 : islands.get(id).lift();
        }
    }

    public static Plan plan(Map<Long, OldChunk> old, TerrainEngine engine) {
        long[] keys = old.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        var index = new Long2IntOpenHashMap();
        index.defaultReturnValue(-1);
        for (int i = 0; i < keys.length; i++) index.put(keys[i], i);

        // Union-find over chunks within MERGE of each other.
        int[] parent = new int[keys.length];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < keys.length; i++) {
            int x = ChunkPos.getX(keys[i]);
            int z = ChunkPos.getZ(keys[i]);
            for (int dx = -MERGE; dx <= MERGE; dx++) {
                for (int dz = -MERGE; dz <= MERGE; dz++) {
                    int j = index.get(ChunkPos.asLong(x + dx, z + dz));
                    if (j >= 0) union(parent, i, j);
                }
            }
        }
        var groups = new java.util.LinkedHashMap<Integer, LongArrayList>();
        for (int i = 0; i < keys.length; i++) groups.computeIfAbsent(find(parent, i), k -> new LongArrayList()).add(keys[i]);

        var sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> Integer.compare(b.size(), a.size()));
        var islands = new ArrayList<Island>();
        var islandOf = new Long2IntOpenHashMap();
        islandOf.defaultReturnValue(-1);
        int heightLimit = engine.settings.world().heightLimit();
        for (LongArrayList chunks : sorted) {
            int id = islands.size();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            int topY = Integer.MIN_VALUE;
            for (long c : chunks) {
                islandOf.put(c, id);
                minX = Math.min(minX, ChunkPos.getX(c));
                maxX = Math.max(maxX, ChunkPos.getX(c));
                minZ = Math.min(minZ, ChunkPos.getZ(c));
                maxZ = Math.max(maxZ, ChunkPos.getZ(c));
                topY = Math.max(topY, old.get(c).topY());
            }
            var edge = edge(chunks, old, engine);
            int maxLift = Math.max(0, (heightLimit - 1 - topY) / 16 * 16);
            islands.add(new Island(id, chunks, minX, minZ, maxX, maxZ, topY, maxLift, edge, bestLift(edge, maxLift)));
        }
        return new Plan(islands, islandOf);
    }

    /** Edge chunks of an island paired with the new surface just outside. */
    private static List<EdgeSample> edge(LongList chunks, Map<Long, OldChunk> old, TerrainEngine engine) {
        var samples = new ArrayList<EdgeSample>();
        for (long c : chunks) {
            int cx = ChunkPos.getX(c);
            int cz = ChunkPos.getZ(c);
            boolean border = false;
            for (int dx = -1; dx <= 1 && !border; dx++) {
                for (int dz = -1; dz <= 1 && !border; dz++) {
                    if (!old.containsKey(ChunkPos.asLong(cx + dx, cz + dz))) border = true;
                }
            }
            if (!border) continue;
            var surface = old.get(c).surface();
            int wetCells = 0;
            int[] levels = new int[16];
            for (int g = 0; g < 16; g++) {
                boolean wet = surface.water()[g] != LegacyIndex.DRY;
                if (wet) wetCells++;
                levels[g] = wet ? surface.water()[g] : surface.ground()[g];
            }
            Arrays.sort(levels);
            int oldLevel = (levels[7] + levels[8]) / 2;
            boolean oldWet = wetCells >= 8;

            var outside = new ArrayList<Integer>();
            int newWet = 0;
            int bx = cx * 16 + 8;
            int bz = cz * 16 + 8;
            for (int dir = 0; dir < 8; dir++) {
                double a = dir * Math.PI / 4;
                for (int r : RING) {
                    int x = bx + (int) Math.round(Math.cos(a) * r);
                    int z = bz + (int) Math.round(Math.sin(a) * r);
                    if (old.containsKey(ChunkPos.asLong(x >> 4, z >> 4))) continue;
                    var col = engine.column(x, z);
                    int level;
                    if (col.lakeLevel != Integer.MIN_VALUE) {
                        level = col.lakeLevel;
                        newWet++;
                    } else if (col.height < engine.sea) {
                        level = engine.sea - 1;
                        newWet++;
                    } else {
                        level = (int) Math.round(col.height);
                    }
                    outside.add(level);
                }
            }
            if (outside.isEmpty()) continue;
            outside.sort(null);
            int newLevel = outside.get(outside.size() / 2);
            boolean bothWet = oldWet && newWet * 2 >= outside.size();
            int weight = bothWet ? 4 : (oldWet || newWet * 2 >= outside.size()) ? 2 : 1;
            samples.add(new EdgeSample(c, oldLevel, newLevel, weight));
        }
        return samples;
    }

    /** Multiple of 16 in [0, maxLift] with the least weighted absolute mismatch; the smaller one on ties. */
    static int bestLift(List<EdgeSample> edge, int maxLift) {
        if (edge.isEmpty()) return 0;
        int best = 0;
        double bestCost = Double.MAX_VALUE;
        for (int lift = 0; lift <= maxLift; lift += 16) {
            double cost = 0;
            for (EdgeSample s : edge) cost += s.weight * Math.abs(s.diff() - lift);
            if (cost < bestCost - 1e-9) {
                bestCost = cost;
                best = lift;
            }
        }
        return best;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
    }
}
