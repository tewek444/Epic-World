package me.nyxia.epicworld.voxy;

import javax.annotation.Nullable;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Set on a LOD worker thread while it places the features of one chunk: the world generation region then serves the
 * in-memory LOD chunks, entities are dropped and features that only reach underground are skipped.
 */
public final class LodContext {
    private LodContext() {}

    private static final ThreadLocal<LodGenerator> CURRENT = new ThreadLocal<>();

    static void enter(LodGenerator generator) {
        CURRENT.set(generator);
    }

    static void exit() {
        CURRENT.remove();
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** The chunk at x, z for the current LOD pass, or null outside one. */
    @Nullable
    public static ChunkAccess chunk(int x, int z) {
        LodGenerator g = CURRENT.get();
        return g == null ? null : g.chunk(x, z);
    }

    /** Whether a feature at this height of this chunk can be skipped in the current LOD pass. */
    public static boolean skipFeature(ChunkAccess chunk, int y) {
        return active() && LodGenerator.deepFeature(chunk, y);
    }
}
