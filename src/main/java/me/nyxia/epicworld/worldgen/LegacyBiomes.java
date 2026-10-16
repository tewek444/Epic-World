package me.nyxia.epicworld.worldgen;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import me.nyxia.epicworld.worldgen.noise.Fbm;
import net.minecraft.world.level.ChunkPos;

/**
 * Near old chunks, new chunks continue the biomes of the old surface for part of the transition, so vegetation and
 * ground cover do not change at the old chunk border. The edge between continued and new biomes follows a noise
 * line instead of the old chunk outline. Results are cached per 4×4 column; old surfaces come from
 * {@link LegacyIndex}.
 */
final class LegacyBiomes {
    private LegacyBiomes() {}

    /** Part of the blending distance where old biomes continue (before the noise wobble). */
    private static final double REACH = 0.4;
    private static final double WOBBLE = 0.35;
    private static final Fbm EDGE = new Fbm(0x5EED_B10EL, 3, 0.5);
    private static final int NONE = -1;

    private static final ConcurrentHashMap<Long, Integer> CACHE = new ConcurrentHashMap<>();
    private static volatile LegacyIndex cachedFor;

    /**
     * Index of the old biome to continue at this block column, or {@link #NONE}. {@code indexOf} maps a biome id to
     * the generator's biome list ({@code NONE} for biomes it does not have, e.g. of removed mods).
     */
    static int at(int x, int z, ToIntFunction<String> indexOf) {
        LegacyIndex index = LegacyIndex.currentAny();
        if (index == null || index.isEmpty() || index.blendingDistance() <= 0) return NONE;
        if (cachedFor != index) {
            CACHE.clear();
            cachedFor = index;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        double reach = index.blendingDistance() * REACH * (1 + WOBBLE);
        int rc = (int) Math.ceil(reach / 16) + 1;
        if (index.isLegacy(cx, cz) || !index.anyNear(cx, cz, rc)) return NONE;
        long key = ChunkPos.asLong(x >> 2, z >> 2);
        Integer cached = CACHE.get(key);
        if (cached != null) return cached;
        if (CACHE.size() > 200_000) CACHE.clear();

        double best = Double.MAX_VALUE;
        String biome = null;
        for (int ox = -rc; ox <= rc; ox++) {
            for (int oz = -rc; oz <= rc; oz++) {
                if (!index.isLegacy(cx + ox, cz + oz)) continue;
                var surface = index.surface(cx + ox, cz + oz);
                if (surface.isEmpty()) continue;
                String[] biomes = surface.get().biomes();
                for (int g = 0; g < 16; g++) {
                    if (biomes[g] == null) continue;
                    double px = (cx + ox) * 16 + (g & 3) * 4 + 2;
                    double pz = (cz + oz) * 16 + (g >> 2) * 4 + 2;
                    double d = Math.hypot(px - x, pz - z);
                    if (d < best) {
                        best = d;
                        biome = biomes[g];
                    }
                }
            }
        }
        int result = NONE;
        double limit = index.blendingDistance() * REACH * (1 + WOBBLE * EDGE.sample(x, z, 96));
        if (biome != null && best < limit) result = indexOf.applyAsInt(biome);
        CACHE.put(key, result);
        return result;
    }
}
