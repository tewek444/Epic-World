package me.nyxia.epicworld.worldgen.deferred;

import me.nyxia.epicworld.worldgen.TerrainEngine;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * Experiment: generate only the top of a chunk (the surface and {@link #margin()} blocks of ground under it) when it is
 * created, and the deep part later, once a player comes near it ({@link DeepFiller}). Until then the deep part is a
 * single placeholder block: one palette entry per section, so it costs next to nothing to fill, light, save and send.
 */
public final class DeferredDepth {
    private DeferredDepth() {}

    /**
     * The placeholder. Not the default block (stone), so surface rules skip it, and natural enough to look like a plug
     * where a cave runs into the not yet generated depth.
     */
    public static final BlockState PLACEHOLDER = Blocks.DEEPSLATE.defaultBlockState();

    /** {@code performance.deferred_caves}, or {@code -Depicworld.deferDepth}. Only decides about new chunks. */
    public static boolean enabled() {
        return !BYPASS.get() && me.nyxia.epicworld.config.EpicPerformance.deferredCaves();
    }

    /** Set by tests on the thread that starts a fill, to get the plain (non-deferred) generation. */
    public static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    /** Ground generated right away under the lowest surface of a chunk (and its border), in blocks. */
    public static int margin() {
        return Integer.getInteger("epicworld.deferMargin", 48);
    }

    /** A deferred part smaller than this is not worth the bookkeeping. */
    private static final int MIN_DEFERRED = 32;

    /**
     * The section-aligned height below which this chunk's generation is deferred, or {@link Integer#MIN_VALUE} for
     * none. Taken from the lowest engine surface (or river tunnel floor) within 8 blocks of the chunk.
     */
    public static int deferTop(TerrainEngine engine, ChunkPos pos) {
        int low = Integer.MAX_VALUE;
        // Every 4 blocks, on the grid of biome samples: columns the biome step of this chunk and its neighbours computed.
        for (int dx = -8; dx <= 24; dx += 4) {
            for (int dz = -8; dz <= 24; dz += 4) {
                var column = engine.column(pos.getMinBlockX() + dx, pos.getMinBlockZ() + dz);
                double h = column.height;
                if (column.tunnelKind > 0) h = Math.min(h, column.tunnelFloor);
                low = Math.min(low, (int) Math.floor(h));
            }
        }
        int top = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(low - margin()));
        return top - engine.minY < MIN_DEFERRED ? Integer.MIN_VALUE : top;
    }

    /** Replaces every section below {@code top} with a fresh placeholder section (keeping its biomes). */
    public static void placeholder(ChunkAccess chunk, int top) {
        LevelChunkSection[] sections = chunk.getSections();
        int end = chunk.getSectionIndex(top);
        for (int i = 0; i < end; i++) {
            sections[i] = new LevelChunkSection(solid(PLACEHOLDER), sections[i].getBiomes());
        }
    }

    static PalettedContainer<BlockState> solid(BlockState state) {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, state, PalettedContainer.Strategy.SECTION_STATES);
    }

    @SuppressWarnings("unchecked")
    static PalettedContainer<Holder<Biome>> copyBiomes(LevelChunkSection section) {
        PalettedContainerRO<Holder<Biome>> biomes = section.getBiomes();
        return ((PalettedContainer<Holder<Biome>>) biomes).copy();
    }

    /**
     * Set on the server thread while {@link DeepFiller} decorates the depth of a chunk: world generation writes then
     * go only below the deferred top of chunks whose deep noise is in, and chunks are the live level chunks.
     */
    public static final ThreadLocal<Boolean> DEEP_PASS = ThreadLocal.withInitial(() -> false);

    /** Carving masks of the deep carvers, per chunk, until its depth is decorated (server thread only). */
    public static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<net.minecraft.world.level.chunk.CarvingMask> DEEP_MASKS =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    public static net.minecraft.world.level.chunk.CarvingMask deepMask(ChunkPos pos) {
        var mask = DEEP_MASKS.get(pos.toLong());
        return mask != null ? mask : new net.minecraft.world.level.chunk.CarvingMask(0, 0);
    }

    /** Post-processing marks made during the deep pass (server thread only). */
    public static final java.util.List<net.minecraft.core.BlockPos> DEEP_POST_PROCESS = new java.util.ArrayList<>();

    /**
     * Whether world generation (features, structures) may write at height {@code y} of {@code target}. The normal pass
     * writes everything except a deferred depth; the deep pass writes exactly that depth. Together they write each
     * position once.
     */
    public static boolean canWrite(ChunkAccess target, int y) {
        DeferState state = state(target);
        if (DEEP_PASS.get()) return state.deferred() && state.stage() != DeferState.Stage.PLACEHOLDER && y < state.top();
        return !state.deferred() || y >= state.top();
    }

    /** Features reach at most this far from their origin (ore blobs, geodes, dripstone clusters). */
    private static final int FEATURE_REACH = 32;

    /** Whether a feature at height {@code y} of {@code chunk} could only write where the current pass may not. */
    public static boolean skipFeature(ChunkAccess chunk, int y) {
        // Features without a height placement keep the decoration origin at the bottom of the world and find their own
        // heights (top layer freezing, chunk-wide decorators): never skip those.
        if (y == chunk.getMinBuildHeight()) return false;
        DeferState state = state(chunk);
        if (!state.deferred()) return false;
        if (DEEP_PASS.get()) return y >= state.top() + FEATURE_REACH;
        // Skipping deep features when a chunk is created saves time but shifts the random sequence of the later
        // placements of the same feature (ores land elsewhere above the top too). Off by default.
        return SKIP_DEEP_FEATURES && y < state.top() - FEATURE_REACH;
    }

    private static final boolean SKIP_DEEP_FEATURES = Boolean.getBoolean("epicworld.skipDeepFeatures");

    /** The deferred top of a chunk whose depth is still the placeholder, or {@link Integer#MIN_VALUE}. */
    public static int placeholderTop(ChunkAccess chunk) {
        DeferState state = state(chunk);
        return state.deferred() && state.stage() == DeferState.Stage.PLACEHOLDER ? state.top() : Integer.MIN_VALUE;
    }

    public static DeferState state(ChunkAccess chunk) {
        return chunk.hasData(me.nyxia.epicworld.registry.ModRegistries.DEFER_STATE)
                ? chunk.getData(me.nyxia.epicworld.registry.ModRegistries.DEFER_STATE) : DeferState.NONE;
    }

    public static void setState(ChunkAccess chunk, DeferState state) {
        chunk.setData(me.nyxia.epicworld.registry.ModRegistries.DEFER_STATE, state);
    }
}
