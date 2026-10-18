package me.nyxia.epicworld.worldgen.deferred;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Vanilla's noise fill ({@code NoiseBasedChunkGenerator#doFill}) for a vertical slice of a chunk only. Every density
 * function is a function of the block position, and cell corners are aligned to the cell grid, so filling
 * {@code [a, b)} and {@code [b, c)} separately gives exactly the blocks of one fill of {@code [a, c)}.
 */
public final class DeepNoise {
    private DeepNoise() {}

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** The fluid picker of {@code NoiseBasedChunkGenerator#createFluidPicker}. */
    public static Aquifer.FluidPicker fluids(NoiseGeneratorSettings settings) {
        var lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int sea = settings.seaLevel();
        var water = new Aquifer.FluidStatus(sea, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, sea) ? lava : water;
    }

    /** Biomes of the sections in {@code [fromY, toY)} only (section-aligned), like {@code ChunkAccess#fillBiomesFromNoise}. */
    public static void fillBiomes(ChunkAccess chunk, net.minecraft.world.level.biome.BiomeResolver resolver,
                                  net.minecraft.world.level.biome.Climate.Sampler sampler, int fromY, int toY) {
        int qx = net.minecraft.core.QuartPos.fromBlock(chunk.getPos().getMinBlockX());
        int qz = net.minecraft.core.QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
        for (int sy = Math.max(chunk.getMinSection(), fromY >> 4); sy < Math.min(chunk.getMaxSection(), toY >> 4); sy++) {
            chunk.getSection(chunk.getSectionIndexFromSectionY(sy)).fillBiomesFromNoise(resolver, sampler, qx,
                    net.minecraft.core.QuartPos.fromSection(sy), qz);
        }
    }

    /** A noise chunk covering only {@code [fromY, toY)}; both must be multiples of the cell height. */
    public static NoiseChunk noiseChunk(ChunkAccess chunk, RandomState random, NoiseGeneratorSettings settings,
                                        DensityFunctions.BeardifierOrMarker beardifier, Aquifer.FluidPicker fluids,
                                        Blender blender, int fromY, int toY) {
        var base = settings.noiseSettings();
        var range = NoiseSettings.create(fromY, toY - fromY, base.noiseSizeHorizontal(), base.noiseSizeVertical());
        return new RangeNoiseChunk(16 / range.getCellWidth(), random, chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(),
                range, beardifier, settings, fluids, blender);
    }

    /**
     * Aquifers decide water levels from the preliminary surface, which vanilla searches for only inside the noise
     * chunk's own range. A partial range would never find the real surface, so search the whole world range here.
     */
    private static final class RangeNoiseChunk extends NoiseChunk {
        private final it.unimi.dsi.fastutil.longs.Long2IntMap surface = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        private final net.minecraft.world.level.levelgen.DensityFunction initial;
        private final int minY;
        private final int height;
        private final int cellHeight;

        RangeNoiseChunk(int cells, RandomState random, int x, int z, NoiseSettings range,
                        DensityFunctions.BeardifierOrMarker beardifier, NoiseGeneratorSettings settings,
                        Aquifer.FluidPicker fluids, Blender blender) {
            super(cells, random, x, z, range, beardifier, settings, fluids, blender);
            this.initial = random.router().initialDensityWithoutJaggedness();
            this.minY = settings.noiseSettings().minY();
            this.height = settings.noiseSettings().height();
            this.cellHeight = settings.noiseSettings().getCellHeight();
        }

        @Override
        public int preliminarySurfaceLevel(int x, int z) {
            int qx = net.minecraft.core.QuartPos.toBlock(net.minecraft.core.QuartPos.fromBlock(x));
            int qz = net.minecraft.core.QuartPos.toBlock(net.minecraft.core.QuartPos.fromBlock(z));
            long key = net.minecraft.server.level.ColumnPos.asLong(qx, qz);
            if (surface.containsKey(key)) return surface.get(key);
            int found = Integer.MAX_VALUE;
            for (int y = minY + height; y >= minY; y -= cellHeight) {
                if (initial.compute(new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(qx, y, qz)) > 0.390625) {
                    found = y;
                    break;
                }
            }
            surface.put(key, found);
            return found;
        }
    }

    /**
     * Fills {@code [fromY, toY)} of {@code chunk} like vanilla does (blocks, worldgen heightmaps, fluid post-processing),
     * using a noise chunk made by {@link #noiseChunk} for the same range.
     */
    public static void fill(ChunkAccess chunk, NoiseChunk noise, NoiseGeneratorSettings settings, int fromY, int toY) {
        var base = settings.noiseSettings();
        int cellWidth = base.getCellWidth();
        int cellHeight = base.getCellHeight();
        int minCellY = Mth.floorDiv(fromY, cellHeight);
        int cellCountY = (toY - fromY) / cellHeight;
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        Aquifer aquifer = noise.aquifer();
        BlockState fallback = settings.defaultBlock();
        var pos = new BlockPos.MutableBlockPos();
        int cells = 16 / cellWidth;
        noise.initializeForFirstCellX();
        for (int cx = 0; cx < cells; cx++) {
            noise.advanceCellX(cx);
            for (int cz = 0; cz < cells; cz++) {
                int sectionIndex = chunk.getSectionsCount() - 1;
                LevelChunkSection section = chunk.getSection(sectionIndex);
                for (int cy = cellCountY - 1; cy >= 0; cy--) {
                    noise.selectCellYZ(cy, cz);
                    for (int dy = cellHeight - 1; dy >= 0; dy--) {
                        int y = (minCellY + cy) * cellHeight + dy;
                        int index = chunk.getSectionIndex(y);
                        if (index != sectionIndex) {
                            sectionIndex = index;
                            section = chunk.getSection(index);
                        }
                        noise.updateForY(y, (double) dy / cellHeight);
                        for (int dx = 0; dx < cellWidth; dx++) {
                            int x = baseX + cx * cellWidth + dx;
                            noise.updateForX(x, (double) dx / cellWidth);
                            for (int dz = 0; dz < cellWidth; dz++) {
                                int z = baseZ + cz * cellWidth + dz;
                                noise.updateForZ(z, (double) dz / cellWidth);
                                BlockState state = noise.getInterpolatedState();
                                if (state == null) state = fallback;
                                if (state == AIR) continue;
                                section.setBlockState(x & 15, y & 15, z & 15, state, false);
                                oceanFloor.update(x & 15, y, z & 15, state);
                                surface.update(x & 15, y, z & 15, state);
                                if (aquifer.shouldScheduleFluidUpdate() && !state.getFluidState().isEmpty()) {
                                    chunk.markPosForPostprocessing(pos.set(x, y, z));
                                }
                            }
                        }
                    }
                }
            }
            noise.swapSlices();
        }
        noise.stopInterpolation();
    }
}
