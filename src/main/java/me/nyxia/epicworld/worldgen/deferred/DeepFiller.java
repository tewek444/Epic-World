package me.nyxia.epicworld.worldgen.deferred;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import me.nyxia.epicworld.EpicWorld;
import me.nyxia.epicworld.worldgen.EpicChunkGenerator;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Completes deferred depths ({@link DeferredDepth}) around players who go down towards them, in two stages per chunk,
 * like vanilla generation:
 * <ol>
 *   <li><b>Noise</b> (worker thread): terrain, caves and aquifers of the depth, then surface rules (deepslate, bedrock)
 *   and carvers, computed on a copy of the chunk. The server thread swaps the new sections in, keeping every block a
 *   player changed in the placeholder, and relights.</li>
 *   <li><b>Features</b> (server thread), once the 8 neighbours have their noise too: the chunk's own features and
 *   structure pieces, written only into deferred depths ({@link DeferredDepth#DEEP_PASS}).</li>
 * </ol>
 * Then the chunk is sent again to the players who see it.
 */
public final class DeepFiller {
    /** Chunks (Chebyshev radius) around a player whose depth is completed. */
    private static final int RADIUS = 2;
    /** A player this many blocks above a chunk's deferred top (or lower) starts its completion. */
    private static final int TRIGGER = 32;
    private static final int MAX_JOBS = 4;
    private static final int DECORATE_PER_TICK = 1;
    private static final int RESEND_DELAY = 10;

    private static final Map<ResourceKey<Level>, DeepFiller> LEVELS = new HashMap<>();

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<CompletableFuture<Result>> jobs = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap resend = new Long2IntOpenHashMap();
    /** Chunks a command asked to complete (kept until done or unloaded). */
    private final LongLinkedOpenHashSet forced = new LongLinkedOpenHashSet();
    /** Fluids and shapes to update once the depth of a chunk is decorated (level chunks keep no such marks). */
    private final Long2ObjectOpenHashMap<List<BlockPos>> postProcess = new Long2ObjectOpenHashMap<>();
    private int ticks;

    static final AtomicLong NOISE_NANOS = new AtomicLong();
    static long noiseCount;
    static long decorateNanos;
    static long decorateCount;

    private DeepFiller(ServerLevel level) {
        this.level = level;
    }

    @Nullable
    private static DeepFiller of(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof EpicChunkGenerator)) return null;
        return LEVELS.computeIfAbsent(level.dimension(), k -> new DeepFiller(level));
    }

    public static void clear() {
        LEVELS.clear();
    }

    /**
     * Features of a new chunk's neighbours (and its own ore veins, which bypass {@code setBlock}) may have put blocks
     * into the placeholder. All of them are done when a chunk becomes full: reset the depth to the bare placeholder.
     */
    public static void onLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk() || !(event.getChunk() instanceof LevelChunk chunk)) return;
        DeferState state = DeferredDepth.state(chunk);
        if (state.deferred() && state.stage() == DeferState.Stage.PLACEHOLDER) DeferredDepth.placeholder(chunk, state.top());
    }

    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        DeepFiller filler = LEVELS.get(level.dimension());
        if (filler == null) {
            if (level.players().isEmpty()) return;
            filler = of(level);
            if (filler == null) return;
        }
        filler.tick();
    }

    /** Asks for the depth of every loaded chunk within {@code radius} of {@code center} to be completed. */
    public static int force(ServerLevel level, ChunkPos center, int radius) {
        DeepFiller filler = of(level);
        if (filler == null) return 0;
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                if (chunk == null || !pending(DeferredDepth.state(chunk))) continue;
                filler.forced.add(ChunkPos.asLong(center.x + dx, center.z + dz));
                n++;
            }
        }
        return n;
    }

    /** Counts of loaded chunks by stage around {@code center}, and totals of this session. */
    public static String status(ServerLevel level, ChunkPos center, int radius) {
        int[] stages = new int[4];
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                if (chunk == null) continue;
                DeferState state = DeferredDepth.state(chunk);
                stages[state.deferred() ? state.stage().ordinal() : 3]++;
            }
        }
        DeepFiller filler = LEVELS.get(level.dimension());
        return String.format(java.util.Locale.ROOT,
                "radius %d: %d placeholder, %d noise only, %d completed, %d never deferred; %d jobs running;"
                        + " this session: depth noise %d chunks (%.1f ms each, worker), depth features %d chunks (%.1f ms each, server thread)",
                radius, stages[0], stages[1], stages[2], stages[3], filler == null ? 0 : filler.jobs.size(),
                noiseCount, noiseCount == 0 ? 0 : NOISE_NANOS.get() / 1e6 / noiseCount,
                decorateCount, decorateCount == 0 ? 0 : decorateNanos / 1e6 / decorateCount);
    }

    private static boolean pending(DeferState state) {
        return state.deferred() && state.stage() != DeferState.Stage.DONE;
    }

    private void tick() {
        ticks++;
        collect();
        if (ticks % 5 == 0) schedule();
        flushResend();
    }

    // ------------------------------------------------------------------------------------------------ scheduling

    private void schedule() {
        var targets = new LongOpenHashSet();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            ChunkPos center = player.chunkPosition();
            int y = player.getBlockY();
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                    if (chunk == null) continue;
                    DeferState state = DeferredDepth.state(chunk);
                    if (pending(state) && y < state.top() + TRIGGER) targets.add(chunk.getPos().toLong());
                }
            }
        }
        var forcedIt = forced.iterator();
        while (forcedIt.hasNext()) {
            long key = forcedIt.nextLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null || !pending(DeferredDepth.state(chunk))) forcedIt.remove();
            else targets.add(key);
        }
        int decorated = 0;
        for (long key : targets) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            DeferState state = DeferredDepth.state(chunk);
            boolean neighboursReady = true;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    LevelChunk n = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                    if (n == null) {
                        neighboursReady = false;
                        continue;
                    }
                    DeferState ns = DeferredDepth.state(n);
                    if (ns.deferred() && ns.stage() == DeferState.Stage.PLACEHOLDER) {
                        neighboursReady = false;
                        submit(n, ns);
                    }
                }
            }
            if (neighboursReady && state.stage() == DeferState.Stage.NOISE && decorated < DECORATE_PER_TICK) {
                decorate(chunk, state);
                decorated++;
            }
        }
    }

    private void flushResend() {
        if (resend.isEmpty()) return;
        var it = resend.long2IntEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getIntValue() > ticks) continue;
            long key = entry.getLongKey();
            it.remove();
            ChunkPos pos = new ChunkPos(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) continue;
            List<ServerPlayer> players = level.getChunkSource().chunkMap.getPlayers(pos, false);
            if (players.isEmpty()) continue;
            var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            for (ServerPlayer player : players) player.connection.send(packet);
        }
    }

    private void markResend(ChunkPos pos) {
        resend.put(pos.toLong(), ticks + RESEND_DELAY);
    }

    // ------------------------------------------------------------------------------------------------ noise stage

    private record Result(int top, LevelChunkSection[] sections, List<BlockPos> postProcess, net.minecraft.world.level.chunk.CarvingMask mask) {}

    private void submit(LevelChunk chunk, DeferState state) {
        long key = chunk.getPos().toLong();
        if (jobs.containsKey(key) || jobs.size() >= MAX_JOBS) return;
        var generator = (EpicChunkGenerator) level.getChunkSource().getGenerator();
        LevelChunkSection[] live = chunk.getSections();
        int topIndex = chunk.getSectionIndex(state.top());
        var copy = new LevelChunkSection[live.length];
        for (int i = 0; i < live.length; i++) {
            var states = i < topIndex ? DeferredDepth.solid(Blocks.AIR.defaultBlockState()) : live[i].getStates().copy();
            copy[i] = new LevelChunkSection(states, DeferredDepth.copyBiomes(live[i]));
        }
        var pos = chunk.getPos();
        var beardifier = Beardifier.forStructuresInChunk(level.structureManager(), pos);
        var context = new WorldGenerationContext(generator, level);
        var random = level.getChunkSource().randomState();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var registries = level.registryAccess();
        long seed = level.getSeed();
        int top = state.top();
        jobs.put(key, CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            Result result = noise(generator, pos, top, copy, beardifier, context, random, biomes, registries, seed);
            NOISE_NANOS.addAndGet(System.nanoTime() - start);
            return result;
        }, Util.backgroundExecutor()));
    }

    /** Worker thread: the deep part of one chunk, in the order of vanilla generation (noise, water, surface, carvers). */
    private Result noise(EpicChunkGenerator generator, ChunkPos pos, int top, LevelChunkSection[] sections, Beardifier beardifier,
                         WorldGenerationContext context, net.minecraft.world.level.levelgen.RandomState random,
                         net.minecraft.core.Registry<Biome> biomes, net.minecraft.core.RegistryAccess registries, long seed) {
        var settings = generator.generatorSettings().value();
        var fluids = DeepNoise.fluids(settings);
        var proto = new ProtoChunk(pos, UpgradeData.EMPTY, sections, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), level, biomes, null);
        Heightmap.primeHeightmaps(proto, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        int noiseMin = settings.noiseSettings().minY();
        var noise = DeepNoise.noiseChunk(proto, random, settings, beardifier, fluids, Blender.empty(), noiseMin, top);
        DeepNoise.fill(proto, noise, settings, noiseMin, top);
        floodFromAbove(proto, top);

        // Surface rules and carvers read the preliminary surface and aquifers of the whole column.
        proto.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, random, beardifier, settings, fluids, Blender.empty()));
        var source = generator.getBiomeSource();
        var biomeManager = new BiomeManager((x, y, z) -> source.getNoiseBiome(x, y, z, random.sampler()), BiomeManager.obfuscateSeed(seed));
        generator.buildSurface(proto, context, random, level.structureManager(), biomeManager, biomes, Blender.empty());
        carve(generator, proto, random, registries, biomeManager, seed);

        var postProcess = new ArrayList<BlockPos>();
        ShortList[] lists = proto.getPostProcessing();
        for (int i = 0; i < lists.length; i++) {
            if (lists[i] == null) continue;
            for (short packed : lists[i]) {
                BlockPos p = ProtoChunk.unpackOffsetCoordinates(packed, proto.getSectionYFromSectionIndex(i), pos);
                if (p.getY() < top) postProcess.add(p);
            }
        }
        return new Result(top, proto.getSections(), postProcess, proto.getOrCreateCarvingMask(GenerationStep.Carving.AIR));
    }

    /**
     * {@code EpicChunkGenerator#fillLakes} floods open air under the sea and lakes down to the first solid block. In
     * the normal pass that stopped at the placeholder; continue it where water reaches the deferred top.
     */
    private static void floodFromAbove(ProtoChunk chunk, int top) {
        BlockState water = Blocks.WATER.defaultBlockState();
        var pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int bx = chunk.getPos().getMinBlockX() + x;
                int bz = chunk.getPos().getMinBlockZ() + z;
                if (!chunk.getBlockState(pos.set(bx, top, bz)).is(Blocks.WATER)) continue;
                for (int y = top - 1; y > chunk.getMinBuildHeight(); y--) {
                    BlockState state = chunk.getBlockState(pos.set(bx, y, bz));
                    if (state.is(Blocks.WATER)) continue;
                    if (!state.isAir()) break;
                    chunk.setBlockState(pos, water, false);
                }
            }
        }
    }

    /** {@code NoiseBasedChunkGenerator#applyCarvers} without a world generation region. */
    private static void carve(EpicChunkGenerator generator, ProtoChunk chunk, net.minecraft.world.level.levelgen.RandomState random,
                              net.minecraft.core.RegistryAccess registries, BiomeManager biomeManager, long seed) {
        var settings = generator.generatorSettings().value();
        var step = GenerationStep.Carving.AIR;
        var source = generator.getBiomeSource();
        var rnd = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        NoiseChunk noise = chunk.getOrCreateNoiseChunk(c -> {
            throw new IllegalStateException("noise chunk must exist");
        });
        var context = new CarvingContext(generator, registries, chunk.getHeightAccessorForGeneration(), noise, random, settings.surfaceRule());
        var mask = chunk.getOrCreateCarvingMask(step);
        ChunkPos center = chunk.getPos();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ChunkPos from = new ChunkPos(center.x + dx, center.z + dz);
                Holder<Biome> biome = source.getNoiseBiome(QuartPos.fromBlock(from.getMinBlockX()), 0,
                        QuartPos.fromBlock(from.getMinBlockZ()), random.sampler());
                int index = 0;
                for (var carver : generator.getBiomeGenerationSettings(biome).getCarvers(step)) {
                    rnd.setLargeFeatureSeed(seed + index, from.x, from.z);
                    if (carver.value().isStartChunk(rnd)) {
                        carver.value().carve(context, chunk, biomeManager::getBiome, rnd, noise.aquifer(), from, mask);
                    }
                    index++;
                }
            }
        }
    }

    /** Server thread: installs finished noise stages. */
    private void collect() {
        if (jobs.isEmpty()) return;
        var it = jobs.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var future = entry.getValue();
            if (!future.isDone()) continue;
            ChunkPos pos = new ChunkPos(entry.getLongKey());
            it.remove();
            Result result;
            try {
                result = future.join();
            } catch (Exception e) {
                EpicWorld.LOGGER.error("Epic World: deferred depth of chunk {} failed", pos, e);
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) continue;
            DeferState state = DeferredDepth.state(chunk);
            if (state.top() != result.top() || state.stage() != DeferState.Stage.PLACEHOLDER) continue;
            install(chunk, state, result);
            noiseCount++;
        }
    }

    private void install(LevelChunk chunk, DeferState state, Result result) {
        LevelChunkSection[] sections = chunk.getSections();
        int topIndex = chunk.getSectionIndex(state.top());
        var light = level.getChunkSource().getLightEngine();
        for (int i = 0; i < topIndex; i++) {
            LevelChunkSection old = sections[i];
            LevelChunkSection fresh = result.sections()[i];
            // Anything that is not the placeholder was put there by a player (or a piston, an explosion...): keep it.
            if (old.maybeHas(s -> s != DeferredDepth.PLACEHOLDER)) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState s = old.getBlockState(x, y, z);
                            if (s != DeferredDepth.PLACEHOLDER) fresh.setBlockState(x, y, z, s, false);
                        }
                    }
                }
            }
            sections[i] = fresh;
            light.updateSectionStatus(SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(i)), fresh.hasOnlyAir());
        }
        postProcess.put(chunk.getPos().toLong(), result.postProcess());
        DeferredDepth.DEEP_MASKS.put(chunk.getPos().toLong(), result.mask());
        // Light: sources in the new depth, and sky or block light falling in through openings at the old plug.
        light.lightChunk(chunk, false);
        var pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                pos.set(chunk.getPos().getMinBlockX() + x, state.top() - 1, chunk.getPos().getMinBlockZ() + z);
                if (!chunk.getBlockState(pos).canOcclude()) light.checkBlock(pos);
            }
        }
        DeferredDepth.setState(chunk, state.with(DeferState.Stage.NOISE));
        chunk.setUnsaved(true);
        markResend(chunk.getPos());
    }

    // ------------------------------------------------------------------------------------------------ feature stage

    /** What {@code LevelChunk#postProcessGeneration} does for marked positions: start fluids, fix block shapes. */
    private void postProcess(List<BlockPos> marks) {
        for (BlockPos p : marks) {
            if (!level.isLoaded(p)) continue;
            BlockState state = level.getBlockState(p);
            var fluid = state.getFluidState();
            if (!fluid.isEmpty()) level.scheduleTick(p, fluid.getType(), 1);
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
                BlockState updated = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state, level, p);
                if (updated != state) level.setBlock(p, updated, 20);
            }
        }
    }

    private void decorate(LevelChunk chunk, DeferState state) {
        long start = System.nanoTime();
        var map = level.getChunkSource().chunkMap;
        var step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
        ChunkPos pos = chunk.getPos();
        StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(pos.x, pos.z, 1,
                (x, z) -> map.getVisibleChunkIfPresent(ChunkPos.asLong(x, z)));
        var region = new WorldGenRegion(level, cache, step, chunk);
        DeferredDepth.DEEP_POST_PROCESS.clear();
        DeferredDepth.DEEP_PASS.set(true);
        try {
            level.getChunkSource().getGenerator().applyBiomeDecoration(region, chunk, level.structureManager().forWorldGenRegion(region));
        } catch (Exception e) {
            EpicWorld.LOGGER.error("Epic World: features of the deferred depth of chunk {} failed", pos, e);
        } finally {
            DeferredDepth.DEEP_PASS.set(false);
        }
        var marks = new ArrayList<BlockPos>(DeferredDepth.DEEP_POST_PROCESS);
        DeferredDepth.DEEP_POST_PROCESS.clear();
        List<BlockPos> fromNoise = postProcess.remove(pos.toLong());
        DeferredDepth.DEEP_MASKS.remove(pos.toLong());
        if (fromNoise != null) marks.addAll(fromNoise);
        postProcess(marks);
        DeferredDepth.setState(chunk, state.with(DeferState.Stage.DONE));
        chunk.setUnsaved(true);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) markResend(new ChunkPos(pos.x + dx, pos.z + dz));
        }
        decorateNanos += System.nanoTime() - start;
        decorateCount++;
    }
}
