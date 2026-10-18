package me.nyxia.epicworld.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import me.nyxia.epicworld.worldgen.deferred.DeepNoise;
import me.nyxia.epicworld.worldgen.deferred.DeferState;
import me.nyxia.epicworld.worldgen.deferred.DeferredDepth;

/**
 * The vanilla noise generator plus the parts Minecraft's density functions cannot express:
 * water in lakes above sea level, and smoothing towards chunks made by an earlier generator.
 */
public class EpicChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<EpicChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(NoiseBasedChunkGenerator::generatorSettings)
    ).apply(i, EpicChunkGenerator::new));

    private static final Map<RandomState, TerrainEngine> ENGINES = new WeakHashMap<>();

    public EpicChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource, settings);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Nullable
    public static TerrainEngine engine(RandomState state) {
        synchronized (ENGINES) {
            return ENGINES.computeIfAbsent(state, s -> EpicDensity.find(s.router().continents()));
        }
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        CompletableFuture<ChunkAccess> noise = fillTopOnly(blender, randomState, structureManager, chunk);
        if (noise == null) noise = super.fillFromNoise(blender, randomState, structureManager, chunk);
        return noise.thenApply(filled -> {
            TerrainEngine engine = engine(randomState);
            if (engine != null) {
                int[] water = LegacyBlending.apply(this, engine, randomState, filled);
                fillLakes(engine, filled, water);
                GenerationStamp.mark(filled, engine);
            }
            return filled;
        });
    }

    /**
     * Epic World's own noise fill (vanilla's {@code doFill} on a range), or null to use vanilla's (blending near old
     * chunks, upgrades). Fills from the deferred top ({@link DeferredDepth}, experiment) or the bottom of the world, and
     * only up to {@link #solidTop}: everything above it is air in a vanilla fill too.
     */
    @Nullable
    private CompletableFuture<ChunkAccess> fillTopOnly(Blender blender, RandomState randomState,
                                                       StructureManager structureManager, ChunkAccess chunk) {
        if (blender != Blender.empty()) return null;
        if (!(chunk instanceof net.minecraft.world.level.chunk.ProtoChunk proto) || proto.isUpgrading()) return null;
        TerrainEngine engine = engine(randomState);
        if (engine == null || LegacyBlending.nearLegacy(engine, chunk.getPos())) return null;
        var settings = generatorSettings().value();
        int noiseMin = settings.noiseSettings().minY();
        int noiseTop = noiseMin + settings.noiseSettings().height();
        if (noiseMin != chunk.getMinBuildHeight() || noiseTop > chunk.getMaxBuildHeight()) return null;
        int deferred = DeferredDepth.enabled() ? DeferredDepth.deferTop(engine, chunk.getPos()) : Integer.MIN_VALUE;
        if (deferred >= noiseTop) deferred = Integer.MIN_VALUE;
        int top = deferred;
        int from = deferred == Integer.MIN_VALUE ? noiseMin : deferred;
        return CompletableFuture.supplyAsync(net.minecraft.Util.wrapThreadWithTaskName("wgen_fill_noise", () -> {
            var beardifier = net.minecraft.world.level.levelgen.Beardifier.forStructuresInChunk(structureManager, chunk.getPos());
            int to = Math.min(noiseTop, solidTop(engine, chunk.getPos(), beardifier, randomState, settings));
            if (to > from) {
                var sections = new java.util.ArrayList<net.minecraft.world.level.chunk.LevelChunkSection>();
                for (int i = chunk.getSectionIndex(from); i <= chunk.getSectionIndex(to - 1); i++) {
                    var section = chunk.getSection(i);
                    section.acquire();
                    sections.add(section);
                }
                try {
                    var noise = DeepNoise.noiseChunk(chunk, randomState, settings, simplify(beardifier), DeepNoise.fluids(settings),
                            blender, from, to);
                    DeepNoise.fill(chunk, noise, settings, from, to);
                } finally {
                    for (var section : sections) section.release();
                }
            }
            if (top != Integer.MIN_VALUE) {
                DeferredDepth.placeholder(chunk, top);
                DeferredDepth.setState(chunk, new DeferState(top, DeferState.Stage.PLACEHOLDER));
            }
            return chunk;
        }), net.minecraft.Util.backgroundExecutor());
    }

    /** {@code performance.fast_generation}, or {@code -Depicworld.solidTop} (false fills the whole noise range, like vanilla). */
    private static boolean solidTopEnabled() {
        String override = System.getProperty("epicworld.solidTop");
        return override != null ? Boolean.parseBoolean(override) : me.nyxia.epicworld.config.EpicPerformance.fastGeneration();
    }

    /**
     * A cell-aligned height above which a vanilla fill of this chunk places only air. Above it:
     * <ul>
     *   <li>the density is negative: terrain plus the jagged relief (at most {@code 0.65 * jag^2 * max(jagged noise)}),
     *   and caves, noodles and tunnels only lower it. Stone rings and pillars add their own density, so chunks with them
     *   are filled to the top; structure beards add density up to 12 blocks around their pieces.</li>
     *   <li>the aquifer gives air: at least 6 blocks above the sea and above every local fluid level, which never exceed
     *   the preliminary surface (at most the terrain height) of aquifer cells within 21 blocks, no fluid status is water
     *   at that height and the barrier pressure stays negative (see {@code Aquifer.NoiseBasedAquifer}).</li>
     * </ul>
     */
    public static int solidTop(TerrainEngine engine, net.minecraft.world.level.ChunkPos pos,
                                net.minecraft.world.level.levelgen.Beardifier beardifier, RandomState random,
                                NoiseGeneratorSettings settings) {
        int noiseTop = settings.noiseSettings().minY() + settings.noiseSettings().height();
        if (!solidTopEnabled()) return noiseTop;
        double reliefPerJag = 0.65 * random.getOrCreateNoise(net.minecraft.world.level.levelgen.Noises.JAGGED).maxValue() / EpicDensity.SCALE;
        double top = engine.sea + 6;
        int x0 = pos.getMinBlockX();
        int z0 = pos.getMinBlockZ();
        // Density: the interpolation corners of this chunk (and a cell around).
        for (int dx = -4; dx <= 20; dx += 4) {
            for (int dz = -4; dz <= 20; dz += 4) {
                var column = engine.column(x0 + dx, z0 + dz);
                if (column.rings > 0.02 || column.pillars > 0.02) return noiseTop;
                top = Math.max(top, column.height + reliefPerJag * column.jag * column.jag + 2);
            }
        }
        // Aquifer: preliminary surfaces of the aquifer cells that can be nearest to a block of this chunk.
        for (int dx = -24; dx <= 40; dx += 8) {
            for (int dz = -24; dz <= 40; dz += 8) {
                top = Math.max(top, engine.column(x0 + dx, z0 + dz).height + 6);
            }
        }
        if (beardifier == null) return (int) Math.ceil(top / 16) * 16 + 16;
        beardifier.pieceIterator.back(Integer.MAX_VALUE);
        while (beardifier.pieceIterator.hasNext()) top = Math.max(top, beardifier.pieceIterator.next().box().maxY() + 13);
        beardifier.pieceIterator.back(Integer.MAX_VALUE);
        beardifier.junctionIterator.back(Integer.MAX_VALUE);
        while (beardifier.junctionIterator.hasNext()) top = Math.max(top, beardifier.junctionIterator.next().getSourceGroundY() + 13);
        beardifier.junctionIterator.back(Integer.MAX_VALUE);
        // Noise ranges must be whole sections (NoiseSettings), which also keeps a cell of margin.
        return (int) Math.ceil(top / 16) * 16 + 16;
    }

    /** A beardifier without pieces adds nothing: the marker computes 0 without iterating. */
    private static net.minecraft.world.level.levelgen.DensityFunctions.BeardifierOrMarker simplify(
            net.minecraft.world.level.levelgen.Beardifier beardifier) {
        beardifier.pieceIterator.back(Integer.MAX_VALUE);
        beardifier.junctionIterator.back(Integer.MAX_VALUE);
        if (beardifier.pieceIterator.hasNext() || beardifier.junctionIterator.hasNext()) return beardifier;
        return net.minecraft.world.level.levelgen.DensityFunctions.BeardifierMarker.INSTANCE;
    }

    /** Carvers run over the whole height; under a deferred top they only cut the placeholder, which is restored. */
    @Override
    public void applyCarvers(net.minecraft.server.level.WorldGenRegion level, long seed, RandomState random,
                             net.minecraft.world.level.biome.BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, net.minecraft.world.level.levelgen.GenerationStep.Carving step) {
        super.applyCarvers(level, seed, random, biomeManager, structureManager, chunk, step);
        DeferState state = DeferredDepth.state(chunk);
        if (state.deferred() && state.stage() == DeferState.Stage.PLACEHOLDER) DeferredDepth.placeholder(chunk, state.top());
    }

    @Override
    public void buildSurface(net.minecraft.server.level.WorldGenRegion region, StructureManager structureManager,
                             RandomState randomState, ChunkAccess chunk) {
        super.buildSurface(region, structureManager, randomState, chunk);
        TerrainEngine engine = engine(randomState);
        if (engine != null) LegacyBlending.rockFaces(engine, chunk);
    }

    @Override
    public void applyBiomeDecoration(net.minecraft.world.level.WorldGenLevel level, ChunkAccess chunk,
                                     StructureManager structureManager) {
        super.applyBiomeDecoration(level, chunk, structureManager);
        if (level instanceof net.minecraft.server.level.WorldGenRegion region) {
            TerrainEngine engine = engine(region.getLevel().getChunkSource().randomState());
            if (engine != null) LegacyBlending.sealSprings(engine, chunk);
        }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Makes every water surface self-contained, judged on the real blocks (which are interpolated from 4-block cells
     * and may sit a block or two below the engine's height near banks):
     * <ul>
     *   <li>Sea level: all sky-open air below sea level becomes water, like vanilla oceans. Any land lower than the
     *   water is then water too, so nothing can spill sideways.</li>
     *   <li>Lakes above sea level: a column next to a lake that is lower than the lake surface is raised to it (a natural
     *   dam). Each chunk decides only about its own columns from engine data, so neighbouring chunks agree.</li>
     * </ul>
     * Filling stops at the first solid block, so caves under a lake or river bed stay dry. Near old chunks
     * {@code waterOverride} (from {@link LegacyBlending}, may be null) replaces the sea level per column.
     */
    public static void fillLakes(TerrainEngine engine, ChunkAccess chunk, @Nullable int[] waterOverride) {
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState dam = Blocks.STONE.defaultBlockState();
        var pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                var column = engine.column(x, z);
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, dx, dz);
                if (column.lakeLevel == Integer.MIN_VALUE) {
                    int damTo = Integer.MIN_VALUE;
                    for (int[] side : SIDES) {
                        int level = engine.column(x + side[0], z + side[1]).lakeLevel;
                        if (level != Integer.MIN_VALUE && level > top) damTo = Math.max(damTo, level);
                    }
                    for (int y = top + 1; y <= damTo; y++) chunk.setBlockState(pos.set(x, y, z), dam, false);
                }
                int sea = engine.sea - 1;
                if (waterOverride != null && waterOverride[dx + 16 * dz] != LegacyBlending.DEFAULT_WATER) {
                    sea = waterOverride[dx + 16 * dz];
                }
                int surface = Math.max(sea, column.lakeLevel);
                if (surface == LegacyBlending.NO_WATER) continue;
                for (int y = surface; y > chunk.getMinBuildHeight(); y--) {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.is(Blocks.WATER)) continue;
                    if (!state.isAir()) break;
                    chunk.setBlockState(pos, water, false);
                }
            }
        }
    }

    /** Highest water block the generator guarantees in a column, or {@link Integer#MIN_VALUE}. */
    private static int waterSurface(TerrainEngine engine, TerrainEngine.Column column) {
        if (column.lakeLevel != Integer.MIN_VALUE) return column.lakeLevel;
        return column.height < engine.sea ? engine.sea - 1 : Integer.MIN_VALUE;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        int height = super.getBaseHeight(x, z, type, level, random);
        TerrainEngine engine = engine(random);
        if (engine != null && type.isOpaque().test(Blocks.WATER.defaultBlockState())) {
            int surface = waterSurface(engine, engine.column(x, z));
            if (surface != Integer.MIN_VALUE) height = Math.max(height, surface + 1);
        }
        return height;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        NoiseColumn column = super.getBaseColumn(x, z, height, random);
        TerrainEngine engine = engine(random);
        if (engine == null) return column;
        int surface = waterSurface(engine, engine.column(x, z));
        if (surface == Integer.MIN_VALUE) return column;
        BlockState[] states = new BlockState[height.getHeight()];
        for (int i = 0; i < states.length; i++) states[i] = column.getBlock(height.getMinBuildHeight() + i);
        for (int y = surface; y > height.getMinBuildHeight(); y--) {
            int i = y - height.getMinBuildHeight();
            if (i >= states.length) continue;
            if (states[i].is(Blocks.WATER)) continue;
            if (!states[i].isAir()) break;
            states[i] = Blocks.WATER.defaultBlockState();
        }
        return new NoiseColumn(height.getMinBuildHeight(), states);
    }
}
