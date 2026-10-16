package me.nyxia.epicworld.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import javax.annotation.Nullable;
import me.nyxia.epicworld.worldgen.noise.Fbm;
import me.nyxia.epicworld.worldgen.noise.Hash;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Bends the surface of a freshly generated chunk towards neighbouring chunks that were made before the current
 * settings or by another generator (see {@link LegacyIndex}). Only the new chunk is modified. The result depends on
 * the saved state of the old chunks, not on generation order.
 *
 * <p>When the old terrain lies at the current sea level, the new surface simply slides to the old heights. When it lies
 * well below (a vanilla world has its sea at 63, Epic World at 208 by default) the transition becomes a step:
 * <ul>
 *   <li>the lower part, next to the old chunks, keeps the old water level and continues the old heights, then rises
 *   as a steep cliff;</li>
 *   <li>at about {@link #STEP} of the blending distance (the line meanders) a rim on top of the cliff holds the new
 *   sea, rivers and lakes; new land meets the rim from above;</li>
 *   <li>rivers and a few points along the rim cut notches into it, so water pours down the escarpment as waterfalls.</li>
 * </ul>
 */
final class LegacyBlending {
    private LegacyBlending() {}

    /** Water override for {@link EpicChunkGenerator}: generator default (the sea level). */
    static final int DEFAULT_WATER = Integer.MAX_VALUE;
    /** Water override: no water above the terrain in this column. */
    static final int NO_WATER = Integer.MIN_VALUE;

    /** Position of the escarpment as a fraction of the blending distance. */
    private static final double STEP = 0.55;
    /** Width of the rim that holds the new water level, in blocks. */
    private static final int RIM = 16;
    /** Length of the shallow shelf in front of the rim on the new side. */
    private static final int SHELF = 32;
    /** Waterfall grid: one candidate notch per cell. */
    private static final int FALL_CELL = 64;
    private static final double FALL_CHANCE = 0.45;

    /** Exponent of the cliff profile: higher keeps the old heights longer and makes the cliff steeper. */
    private static final double CLIFF = 5;
    /** How far the escarpment line meanders, as a fraction of its distance from the old terrain. */
    private static final double MEANDER = 0.12;

    private record Sample(double x, double z, double height, int water) {}

    private record Noise(long seed, Fbm fbm) {}

    private static volatile Noise noise;

    private static Fbm meander(long seed) {
        Noise n = noise;
        if (n == null || n.seed != seed) noise = n = new Noise(seed, new Fbm(Hash.mix(seed, 0x5EA1), 3, 0.5));
        return n.fbm;
    }

    /**
     * Applies the transition and returns per-column water levels (index {@code dx + 16*dz}) to use instead of the sea
     * level when filling water, or {@code null} when the chunk is not near old terrain.
     */
    @Nullable
    static int[] apply(NoiseBasedChunkGenerator generator, TerrainEngine engine, RandomState state, ChunkAccess chunk) {
        var compat = engine.settings.compatibility();
        if (!compat.terrainBlending() || compat.blendingDistance() <= 0) return null;
        LegacyIndex index = LegacyIndex.currentAny();
        if (index == null || index.isEmpty()) return null;
        var cp = chunk.getPos();
        if (index.isLegacy(cp.x, cp.z)) return null;
        int distance = compat.blendingDistance();
        int rc = distance / 16 + 1;
        if (!index.anyNear(cp.x, cp.z, rc)) return null;

        var samples = new ArrayList<Sample>();
        for (int cx = cp.x - rc; cx <= cp.x + rc; cx++) {
            for (int cz = cp.z - rc; cz <= cp.z + rc; cz++) {
                if (!index.isLegacy(cx, cz)) continue;
                var surface = index.surface(cx, cz);
                if (surface.isEmpty()) continue;
                short[] ground = surface.get().ground();
                short[] water = surface.get().water();
                for (int g = 0; g < 16; g++) {
                    samples.add(new Sample(cx * 16 + (g & 3) * 4 + 2, cz * 16 + (g >> 2) * 4 + 2, ground[g],
                            water[g] == LegacyIndex.DRY ? NO_WATER : water[g]));
                }
            }
        }
        if (samples.isEmpty()) return null;

        // Exact old surface columns next to this chunk, so the new surface meets the old one without a step.
        var fine = new ArrayList<int[]>();
        for (int cx = cp.x - 1; cx <= cp.x + 1; cx++) {
            for (int cz = cp.z - 1; cz <= cp.z + 1; cz++) {
                if (!index.isLegacy(cx, cz)) continue;
                var surface = index.surface(cx, cz);
                if (surface.isEmpty()) continue;
                short[] g = surface.get().fineGround();
                for (int i = 0; i < 256; i++) fine.add(new int[]{cx * 16 + (i & 15), cz * 16 + (i >> 4), g[i]});
            }
        }
        int shore = Math.max(24, Math.min(96, distance / 4));

        // Distance to the old terrain, its height and its water level, on a 5×5 lattice covering the chunk. Lattice
        // points on chunk borders are shared, so neighbouring chunks agree there.
        double[] dist = new double[25];
        double[] old = new double[25];
        int[] oldWater = new int[25];
        int[] highWater = new int[25];
        double[] rough = new double[25];
        for (int gz = 0; gz < 5; gz++) {
            for (int gx = 0; gx < 5; gx++) {
                double px = cp.getMinBlockX() + gx * 4;
                double pz = cp.getMinBlockZ() + gz * 4;
                double best = Double.MAX_VALUE;
                for (Sample s : samples) best = Math.min(best, Math.hypot(s.x - px, s.z - pz));
                double sum = 0;
                double weights = 0;
                double wetWeights = 0;
                double nearestWet = Double.MAX_VALUE;
                int nearestWetLevel = NO_WATER;
                int edgeWet = 0;
                var levels = new java.util.HashMap<Integer, Double>();
                for (Sample s : samples) {
                    double d = Math.hypot(s.x - px, s.z - pz);
                    if (s.water != NO_WATER && d < nearestWet) {
                        nearestWet = d;
                        nearestWetLevel = s.water;
                    }
                    if (s.water != NO_WATER && d <= best + 12) edgeWet++;
                    if (d > best + 24) continue;
                    double w = 1 / ((d + 2) * (d + 2));
                    sum += w * s.height;
                    weights += w;
                    if (s.water != NO_WATER) {
                        wetWeights += w;
                        levels.merge(s.water, w, Double::sum);
                    }
                }
                int i = gx + 5 * gz;
                dist[i] = Math.max(0, best - 2);
                old[i] = sum / weights;
                // Relief of the old surface near the edge (spread of heights), continued into the transition.
                double spread = 0;
                int count = 0;
                for (Sample s : samples) {
                    if (Math.hypot(s.x - px, s.z - pz) > best + 40) continue;
                    spread += (s.height - old[i]) * (s.height - old[i]);
                    count++;
                }
                rough[i] = count > 1 ? Math.sqrt(spread / count) : 0;
                oldWater[i] = wetWeights >= 0.25 * weights
                        ? levels.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).orElseThrow().getKey()
                        : NO_WATER;
                // Old water standing above the new sea level close to the edge (lifted worlds) needs a bank.
                // Only a sizeable old water body that reaches the edge of the old chunks (not a pond or stream inside
                // them) and stands clearly above the new sea gets the continued shore.
                highWater[i] = nearestWet <= best + 6 && edgeWet >= 3 && nearestWetLevel > engine.sea + 3 ? nearestWetLevel : NO_WATER;
            }
        }

        BlockState stone = generator.generatorSettings().value().defaultBlock();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        int sea = engine.sea;
        Fbm wobble = meander(engine.seed);
        int[] result = new int[256];
        Arrays.fill(result, DEFAULT_WATER);
        var pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                double fx = dx / 4.0;
                double fz = dz / 4.0;
                int ix = Math.min(3, (int) fx);
                int iz = Math.min(3, (int) fz);
                double tx = fx - ix;
                double tz = fz - iz;
                double d = bilinear(dist, ix, iz, tx, tz);
                if (d >= distance) continue;
                double hOld = bilinear(old, ix, iz, tx, tz);
                int x = cp.getMinBlockX() + dx;
                int z = cp.getMinBlockZ() + dz;
                int topNew = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz);
                if (d < FINE && !fine.isEmpty()) {
                    // Right at the border: the actual old columns, fading into the smoothed old surface.
                    double sum = 0;
                    double weights = 0;
                    for (int[] c : fine) {
                        double dd = (c[0] - x) * (c[0] - x) + (c[1] - z) * (c[1] - z);
                        if (dd > FINE * FINE) continue;
                        // dd is a squared distance: 1/d^4 lets the nearest old column decide at the border itself.
                        double w = 1 / (dd * dd + 0.1);
                        sum += w * c[2];
                        weights += w;
                    }
                    if (weights > 0) hOld = sum / weights + (hOld - sum / weights) * smoothstep(d / FINE);
                }

                // Plain slide towards the old surface (old terrain at the current sea level). The weight eases out
                // with zero slope at both ends, so neither side shows a crease. The zone is not a band of constant
                // width: its outer edge wanders between half and all of the blending distance (low-frequency noise),
                // so the contours are not parallel to the seam; large height gaps keep the full width (gentler).
                var column = engine.column(x, z);
                double gap = Math.min(1, Math.abs(hOld - column.height) / 80);
                double narrow = (0.5 + 0.5 * wobble.sample(x, z, 220) + 0.35 * wobble.sample(x - 911, z + 577, 70)) * (1 - gap);
                double t = d * (1 + Math.max(0, narrow)) / distance;
                double w = 1 - smootherstep(t);
                double slide = topNew + (hOld - topNew) * w;
                // The old relief continues as hills and hollows of a similar size: zero at the seam (exact match)
                // and fading out before the new terrain takes over.
                double relief = bilinear(rough, ix, iz, tx, tz);
                double bump = Math.max(0, 4 * Math.min(1, t * 2.5) * (1 - Math.min(1, t * 2.5)));
                slide += Math.min(24, relief) * 0.9 * wobble.sample(x + 3301, z - 1709, 52) * bump * w;
                // How much the step model applies: fully once the old terrain is clearly below the new sea level.
                // Compared by the old surface level: the water level where the old side is under water, so an old sea
                // that already sits at the new sea level (lifted worlds) is not treated as low ground.
                int oldWet = oldWater[Math.round((float) fx) + 5 * Math.round((float) fz)];
                double oldLevel = oldWet != NO_WATER ? Math.max(hOld, oldWet) : hOld;
                double stepped = smoothstep((sea - 4 - oldLevel) / 24);
                double target = slide;
                int waterTop = DEFAULT_WATER;
                boolean fall = false;
                if (stepped > 0) {
                    double step = distance * STEP * (1 + MEANDER * wobble.sample(x, z, 320));
                    int lowWater = oldWater[Math.round((float) fx) + 5 * Math.round((float) fz)];
                    boolean wet = topNew < sea - 1 && column.lakeLevel == Integer.MIN_VALUE;
                    double stepTarget;
                    int rim = sea + 1;
                    // Below the escarpment: the old heights continue, then rise steeply to the rim.
                    double cliff = hOld + (rim - hOld) * Math.pow(Math.min(1, d / step), CLIFF);
                    if (!wet) {
                        // Land: the new surface comes down to the rim height from above.
                        stepTarget = d >= step ? rim + (topNew - rim) * smoothstep((d - step) / (distance - step)) : cliff;
                        if (d < step) waterTop = lowWater;
                    } else if (d >= step + RIM) {
                        // New sea or river: rises to a shallow shelf in front of the rim.
                        double k = smoothstep(1 - (d - step - RIM) / SHELF);
                        stepTarget = Math.max(topNew, topNew + (sea - 5 - topNew) * k);
                    } else if (d >= step) {
                        // The rim, notched where a river or a waterfall crosses it.
                        fall = lowWater != NO_WATER && (column.river || waterfall(engine.seed, x, z, dist, ix, iz, tx, tz));
                        double crest = rim + 1.5 * (1 + wobble.sample(x, z, 24)) + (Hash.mix(engine.seed, Hash.mix(x, z)) & 1);
                        // The inner half holds the water; the outer half is a beach sloping into the new sea.
                        double beach = smoothstep(((d - step) / RIM - 0.5) * 2);
                        stepTarget = fall ? sea - 3 : crest + (sea - 5 - crest) * beach;
                    } else {
                        stepTarget = cliff;
                        waterTop = lowWater;
                    }
                    if (stepped >= 0.5 || fall) {
                        target = wet || fall ? stepTarget : slide + (stepTarget - slide) * stepped;
                    } else {
                        target = slide + (stepTarget - slide) * stepped;
                        waterTop = DEFAULT_WATER;
                    }
                }

                int high = highWater[Math.round((float) fx) + 5 * Math.round((float) fz)];
                if (high != NO_WATER && stepped < 0.5) {
                    // Old water above the new sea (a lake cut by the edge of the old chunks): closed right there by a
                    // narrow natural bank a little above the water, whose width and height wander, then the usual
                    // slope towards the new terrain. Extending such water outward needed a dam-like apron.
                    double width = 10 + 4 * wobble.sample(x, z, 48);
                    double crest = high + 2 + wobble.sample(x - 517, z + 233, 20);
                    double bank = d < width ? crest : crest - (d - width) * 0.8;
                    target = Math.max(target, bank);
                }
                if (stepped < 0.5 && column.river && column.lakeLevel == Integer.MIN_VALUE && topNew < sea - 1) {
                    // Rivers keep their bed through the transition. Where the old side has water at the new sea
                    // level they run into it; next to old land the bed rises over the last blocks into a small bay.
                    int nearWater = oldWater[Math.round((float) fx) + 5 * Math.round((float) fz)];
                    boolean mouth = nearWater != NO_WATER && Math.abs(nearWater - (sea - 1)) <= 3;
                    double keep = mouth ? 1 : smoothstep((d - 8) / 24);
                    target += (Math.min(target, topNew) - target) * keep;
                }
                int top = (int) Math.round(target);
                top = Math.max(chunk.getMinBuildHeight() + 2, Math.min(engine.top - 2, top));
                if (stepped > 0 && d < distance * STEP * (1 + MEANDER) + RIM) {
                    // The escarpment cuts through the ground: seal aquifer water and lava it would expose as springs.
                    int bottom = Math.max(chunk.getMinBuildHeight() + 1, Math.min(top, topNew) - (sea - (int) hOld) - 8);
                    for (int y = Math.min(top, topNew); y >= bottom; y--) {
                        if (!chunk.getBlockState(pos.set(x, y, z)).getFluidState().isEmpty()) chunk.setBlockState(pos, stone, false);
                    }
                }
                int fill = waterTop == DEFAULT_WATER ? sea - 1 : waterTop;
                for (int y = topNew + 1; y <= top; y++) chunk.setBlockState(pos.set(x, y, z), stone, false);
                for (int y = Math.max(Math.max(topNew, sea - 1), fill); y > top; y--) {
                    chunk.setBlockState(pos.set(x, y, z), y <= fill ? water : air, false);
                }
                if (fall) {
                    // Water on the lip spreads over the edge once the chunk is complete.
                    chunk.markPosForPostprocessing(pos.set(x, sea - 1, z));
                }
                result[dx + 16 * dz] = fall ? sea - 1 : waterTop;
            }
        }
        return result;
    }

    /**
     * After surface rules: steep faces in the transition (cliffs, gorge walls) show rock instead of the soil the
     * surface rules put on every column top. Only chunks near old terrain are touched.
     */
    static void rockFaces(TerrainEngine engine, ChunkAccess chunk) {
        int[] low = steepColumns(engine, chunk);
        if (low == null) return;
        BlockState stone = Blocks.STONE.defaultBlockState();
        var pos = new BlockPos.MutableBlockPos();
        var cp = chunk.getPos();
        for (int i = 0; i < 256; i++) {
            if (low[i] == Integer.MIN_VALUE) continue;
            int x = cp.getMinBlockX() + (i & 15);
            int z = cp.getMinBlockZ() + (i >> 4);
            int top = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, i & 15, i >> 4);
            for (int y = top; y > low[i] - 4; y--) {
                BlockState state = chunk.getBlockState(pos.set(x, y, z));
                if (state.is(net.minecraft.tags.BlockTags.DIRT) || state.is(net.minecraft.tags.BlockTags.SAND)
                        || state.is(Blocks.GRAVEL) || state.is(Blocks.SANDSTONE) || state.is(Blocks.CLAY)) {
                    chunk.setBlockState(pos, stone, false);
                }
            }
        }
    }

    /**
     * After features: springs on the escarpment face (a fluid source walled in by rock, with rock above) would cover
     * the long cliff with streams of water and lava. They are filled in; waterfalls of the rim stay, they are open
     * to the sky.
     */
    static void sealSprings(TerrainEngine engine, ChunkAccess chunk) {
        int[] low = steepColumns(engine, chunk);
        if (low == null) return;
        BlockState stone = Blocks.STONE.defaultBlockState();
        var pos = new BlockPos.MutableBlockPos();
        var above = new BlockPos.MutableBlockPos();
        var cp = chunk.getPos();
        for (int i = 0; i < 256; i++) {
            if (low[i] == Integer.MIN_VALUE) continue;
            int x = cp.getMinBlockX() + (i & 15);
            int z = cp.getMinBlockZ() + (i >> 4);
            int top = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, i & 15, i >> 4);
            for (int y = top; y > low[i] - 8; y--) {
                var fluid = chunk.getBlockState(pos.set(x, y, z)).getFluidState();
                if (!fluid.isSource()) continue;
                if (!chunk.getBlockState(above.set(x, y + 1, z)).isSolid()) continue;
                chunk.setBlockState(pos, stone, false);
            }
        }
    }

    /**
     * For chunks near old terrain: per column the lowest neighbouring surface where the drop to a neighbour is at
     * least 4 blocks, {@link Integer#MIN_VALUE} elsewhere; {@code null} for chunks away from old terrain.
     */
    @Nullable
    private static int[] steepColumns(TerrainEngine engine, ChunkAccess chunk) {
        var compat = engine.settings.compatibility();
        if (!compat.terrainBlending() || compat.blendingDistance() <= 0) return null;
        LegacyIndex index = LegacyIndex.currentAny();
        if (index == null || index.isEmpty()) return null;
        var cp = chunk.getPos();
        if (index.isLegacy(cp.x, cp.z) || !index.anyNear(cp.x, cp.z, compat.blendingDistance() / 16 + 1)) return null;
        int[] h = new int[256];
        for (int i = 0; i < 256; i++) h[i] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, i & 15, i >> 4);
        int[] low = new int[256];
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int top = h[dx + 16 * dz];
                int min = top;
                if (dx > 0) min = Math.min(min, h[dx - 1 + 16 * dz]);
                if (dx < 15) min = Math.min(min, h[dx + 1 + 16 * dz]);
                if (dz > 0) min = Math.min(min, h[dx + 16 * (dz - 1)]);
                if (dz < 15) min = Math.min(min, h[dx + 16 * (dz + 1)]);
                low[dx + 16 * dz] = top - min >= 4 ? min : Integer.MIN_VALUE;
            }
        }
        return low;
    }

    /**
     * True when the column lies in a waterfall notch: a strip 2–5 blocks wide across the rim, through a point jittered
     * inside a {@link #FALL_CELL} grid cell. The strip follows the direction towards the old terrain.
     */
    private static boolean waterfall(long seed, int x, int z, double[] dist, int ix, int iz, double tx, double tz) {
        double gx = ((dist[ix + 1 + 5 * iz] - dist[ix + 5 * iz]) * (1 - tz) + (dist[ix + 1 + 5 * (iz + 1)] - dist[ix + 5 * (iz + 1)]) * tz) / 4;
        double gz = ((dist[ix + 5 * (iz + 1)] - dist[ix + 5 * iz]) * (1 - tx) + (dist[ix + 1 + 5 * (iz + 1)] - dist[ix + 1 + 5 * iz]) * tx) / 4;
        double length = Math.hypot(gx, gz);
        if (length < 1e-6) return false;
        gx /= length;
        gz /= length;
        int cx = Math.floorDiv(x, FALL_CELL);
        int cz = Math.floorDiv(z, FALL_CELL);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                long h = Hash.cell(seed, cx + ox, cz + oz, 0x5EA1);
                if (Hash.unit(h, 0) >= FALL_CHANCE) continue;
                double px = (cx + ox + Hash.unit(h, 1)) * FALL_CELL;
                double pz = (cz + oz + Hash.unit(h, 2)) * FALL_CELL;
                double half = 1 + 1.5 * Hash.unit(h, 3);
                double rx = x + 0.5 - px;
                double rz = z + 0.5 - pz;
                if (Math.abs(rx * gz - rz * gx) <= half && Math.abs(rx * gx + rz * gz) <= RIM + SHELF) return true;
            }
        }
        return false;
    }

    private static double bilinear(double[] grid, int ix, int iz, double tx, double tz) {
        double a = grid[ix + 5 * iz];
        double b = grid[ix + 1 + 5 * iz];
        double c = grid[ix + 5 * (iz + 1)];
        double d = grid[ix + 1 + 5 * (iz + 1)];
        return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
    }

    /** Distance from old chunks within which the exact old columns are matched. */
    private static final int FINE = 12;

    private static double smootherstep(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * t * (t * (6 * t - 15) + 10);
    }

    private static double smoothstep(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }
}
