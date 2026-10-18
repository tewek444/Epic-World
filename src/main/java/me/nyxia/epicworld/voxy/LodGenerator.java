package me.nyxia.epicworld.voxy;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nullable;
import me.nyxia.epicworld.EpicWorld;
import me.nyxia.epicworld.config.EpicPerformance;
import me.nyxia.epicworld.worldgen.EpicChunkGenerator;
import me.nyxia.epicworld.worldgen.ParallelGeneration;
import me.nyxia.epicworld.worldgen.TerrainEngine;
import me.nyxia.epicworld.worldgen.deferred.DeepNoise;
import me.nyxia.epicworld.worldgen.deferred.DeferState;
import me.nyxia.epicworld.worldgen.deferred.DeferredDepth;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Epic World's own LOD generation for Voxy, in place of Voxy World Gen: chunks around the player are generated in
 * memory, outside Minecraft's chunk system (no tickets, light, server thread or saving), only as far as LODs need
 * (biomes, the top of the terrain down to {@link DeferredDepth#deferTop}, surface, features), and their sections are
 * handed to Voxy. They are never saved: when a player gets there, the real chunk is generated with the same terrain.
 * <p>
 * Work goes out in tiles of 4x4 chunks in a spiral around the player. A chunk is handed to Voxy once all 8 neighbours
 * have their features too (features write up to one chunk around their own), so trees are not cut at chunk borders.
 * Chunks that exist in the world (saved or near the player) are skipped: Voxy takes those from the real chunks.
 */
public final class LodGenerator {
    private static final int TILE = 4;
    /** Generated chunks kept in memory while they wait for neighbours; above this, waiting ones are handed over as they are. */
    private static final int MAX_CACHED = 6000;
    private static final int FEATURE_REACH = 32;
    private static final boolean LOG = Boolean.getBoolean("epicworld.logChunkRate");

    @Nullable private static LodGenerator active;

    private final ServerLevel level;
    private final EpicChunkGenerator generator;
    private final TerrainEngine engine;
    private final Path doneFile;
    private final LongOpenHashSet done = new LongOpenHashSet();
    private final ConcurrentHashMap<Long, CompletableFuture<LodChunk>> chunks = new ConcurrentHashMap<>();
    /** Chunks that will never get features here (they exist in the world, or are too close to the player). */
    private final ConcurrentHashMap.KeySetView<Long, Boolean> settled = ConcurrentHashMap.newKeySet();
    /** Chunks skipped because they were within the player's view distance (real chunks there); forgotten when the spiral restarts. */
    private final ConcurrentHashMap.KeySetView<Long, Boolean> near = ConcurrentHashMap.newKeySet();
    /** Handed-over counts of the last few seconds, for the rate shown in F3. */
    private final long[] history = new long[6];
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong handedOver = new AtomicLong();
    private final AtomicLong sessionStart = new AtomicLong(System.nanoTime());
    @Nullable private ExecutorService pool;
    private int poolThreads;
    private volatile boolean stopping;
    private int ticks;

    // Spiral over tiles around the centre: ring r has 8r tiles (1 for r = 0).
    private int centerTx;
    private int centerTz;
    private int ring = -1;
    private int ringIndex;
    private boolean finished;

    private LodGenerator(ServerLevel level, EpicChunkGenerator generator, TerrainEngine engine) {
        this.level = level;
        this.generator = generator;
        this.engine = engine;
        this.doneFile = level.getServer().getWorldPath(LevelResource.ROOT).resolve("epicworld").resolve("voxy-lods.dat");
        load();
    }

    final class LodChunk {
        final ProtoChunk proto;
        final int top;
        volatile boolean decorated;
        final AtomicBoolean handed = new AtomicBoolean();

        LodChunk(ProtoChunk proto, int top) {
            this.proto = proto;
            this.top = top;
        }
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (server.isDedicatedServer()) return;
        ServerLevel overworld = server.overworld();
        if (!(overworld.getChunkSource().getGenerator() instanceof EpicChunkGenerator generator)) return;
        TerrainEngine engine = EpicChunkGenerator.engine(overworld.getChunkSource().randomState());
        if (engine == null) return;
        active = new LodGenerator(overworld, generator, engine);
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        LodGenerator g = active;
        active = null;
        if (g == null) return;
        g.stopping = true;
        if (g.pool != null) g.pool.shutdownNow();
        g.save();
    }

    @Nullable
    public static LodGenerator active() {
        return active;
    }

    /** Why the generation does not run (a translation key), or null when it does. */
    @Nullable
    public static String blocker() {
        if (!EpicPerformance.get(EpicPerformance.VOXY_ENABLED)) return "epicworld.lods.blocked.disabled";
        if (net.neoforged.fml.ModList.get().isLoaded("voxyworldgenv2")) return "epicworld.lods.blocked.voxy_world_gen";
        if (!VoxyBridge.available()) return "epicworld.lods.blocked.no_voxy";
        return null;
    }

    public static void onTick(ServerTickEvent.Post event) {
        LodGenerator g = active;
        if (g != null && !g.stopping) g.tick();
    }

    private void tick() {
        ticks++;
        if (ticks % 6000 == 0) save();
        if (LOG && ticks % 200 == 0) EpicWorld.LOGGER.info("Epic World LODs: {}", status());
        if (ticks % 20 == 0) {
            System.arraycopy(history, 0, history, 1, history.length - 1);
            history[0] = handedOver.get();
        }
        if (blocker() != null) return;
        ServerPlayer player = level.players().isEmpty() ? null : level.players().get(0);
        if (player == null) return;
        ensurePool();
        int ptx = Math.floorDiv(player.chunkPosition().x, TILE);
        int ptz = Math.floorDiv(player.chunkPosition().z, TILE);
        // Restart the spiral around the player when they moved away (finished work is remembered in `done`).
        if (ring < 0 || Math.max(Math.abs(ptx - centerTx), Math.abs(ptz - centerTz)) > 4) {
            centerTx = ptx;
            centerTz = ptz;
            ring = 0;
            ringIndex = 0;
            finished = false;
            near.clear();
        }
        int radiusTiles = (EpicPerformance.get(EpicPerformance.VOXY_RADIUS) + TILE - 1) / TILE;
        int maxInFlight = poolThreads * 3;
        while (!finished && inFlight.get() < maxInFlight) {
            long tile = nextTile(radiusTiles);
            if (tile == Long.MIN_VALUE) {
                finished = true;
                flush();
                break;
            }
            int tx = ChunkPos.getX(tile);
            int tz = ChunkPos.getZ(tile);
            inFlight.incrementAndGet();
            pool.execute(() -> {
                try {
                    if (!stopping) processTile(tx, tz, player.chunkPosition());
                } catch (Throwable e) {
                    EpicWorld.LOGGER.error("Epic World: LOD tile {}, {} failed", tx, tz, e);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
        if (chunks.size() > MAX_CACHED) evict();
    }

    private long nextTile(int radiusTiles) {
        while (ring <= radiusTiles) {
            int count = ring == 0 ? 1 : 8 * ring;
            if (ringIndex < count) {
                int i = ringIndex++;
                int dx;
                int dz;
                if (ring == 0) {
                    dx = 0;
                    dz = 0;
                } else {
                    int side = i / (2 * ring);
                    int k = i % (2 * ring);
                    switch (side) {
                        case 0 -> { dx = -ring + k; dz = -ring; }
                        case 1 -> { dx = ring; dz = -ring + k; }
                        case 2 -> { dx = ring - k; dz = ring; }
                        default -> { dx = -ring; dz = ring - k; }
                    }
                }
                return ChunkPos.asLong(centerTx + dx, centerTz + dz);
            }
            ring++;
            ringIndex = 0;
        }
        return Long.MIN_VALUE;
    }

    private void ensurePool() {
        int configured = EpicPerformance.get(EpicPerformance.VOXY_THREADS);
        int threads = configured > 0 ? configured : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        if (pool != null && threads == poolThreads) return;
        if (pool != null) pool.shutdown();
        poolThreads = threads;
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Epic World LOD " + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    // ------------------------------------------------------------------------------------------------ work

    private void processTile(int tx, int tz, ChunkPos player) {
        boolean features = EpicPerformance.get(EpicPerformance.VOXY_FEATURES);
        // Minecraft keeps the chunks of a circle around the player loaded; Voxy builds the LODs of those itself.
        int keepOut = level.getServer().getPlayerList().getViewDistance();
        int radius = EpicPerformance.get(EpicPerformance.VOXY_RADIUS);
        boolean circle = EpicPerformance.get(EpicPerformance.VOXY_SHAPE) == EpicPerformance.Shape.CIRCLE;
        int cx = centerTx * TILE + TILE / 2;
        int cz = centerTz * TILE + TILE / 2;
        var targets = new java.util.ArrayList<ChunkPos>();
        for (int dx = 0; dx < TILE; dx++) {
            for (int dz = 0; dz < TILE; dz++) {
                var pos = new ChunkPos(tx * TILE + dx, tz * TILE + dz);
                long key = pos.toLong();
                synchronized (done) {
                    if (done.contains(key)) continue;
                }
                if (settled.contains(key)) continue;
                if (circle && (long) (pos.x - cx) * (pos.x - cx) + (long) (pos.z - cz) * (pos.z - cz) > (long) radius * radius) continue;
                long px = pos.x - player.x;
                long pz = pos.z - player.z;
                if (px * px + pz * pz <= (long) keepOut * keepOut) {
                    near.add(key);
                    continue;
                }
                if (exists(pos)) {
                    settled.add(key);
                    continue;
                }
                targets.add(pos);
            }
        }
        if (targets.isEmpty()) return;
        if (!features) {
            for (ChunkPos pos : targets) {
                LodChunk c = base(pos);
                c.decorated = true;
                handOver(pos, c);
            }
            return;
        }
        for (ChunkPos pos : targets) {
            if (stopping) return;
            decorate(pos);
        }
        for (ChunkPos pos : targets) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) tryHandOver(new ChunkPos(pos.x + dx, pos.z + dz));
            }
        }
    }

    /** Whether the chunk exists in the world: Voxy builds its LODs from the real chunk then. */
    private boolean exists(ChunkPos pos) {
        if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) return true;
        try {
            return level.getChunkSource().chunkMap.read(pos).join().isPresent();
        } catch (Exception e) {
            return true;
        }
    }

    /** Biomes, the top of the noise, lakes and surface of one chunk, in memory. */
    LodChunk base(ChunkPos pos) {
        long key = pos.toLong();
        CompletableFuture<LodChunk> mine = new CompletableFuture<>();
        CompletableFuture<LodChunk> existing = chunks.putIfAbsent(key, mine);
        if (existing != null) return existing.join();
        try {
            LodChunk chunk = generate(pos);
            mine.complete(chunk);
            return chunk;
        } catch (Throwable e) {
            chunks.remove(key);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    private LodChunk generate(ChunkPos pos) {
        var random = level.getChunkSource().randomState();
        var settings = generator.generatorSettings().value();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var proto = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomes, null);
        var source = generator.getBiomeSource();
        DeepNoise.fillBiomes(proto, source, random.sampler(), level.getMinBuildHeight(), level.getMaxBuildHeight());
        int noiseMin = settings.noiseSettings().minY();
        int deferred = DeferredDepth.deferTop(engine, pos);
        int from = deferred == Integer.MIN_VALUE ? noiseMin : deferred;
        int to = EpicChunkGenerator.solidTop(engine, pos, null, random, settings);
        var fluids = DeepNoise.fluids(settings);
        if (to > from) {
            var noise = DeepNoise.noiseChunk(proto, random, settings, DensityFunctions.BeardifierMarker.INSTANCE, fluids,
                    Blender.empty(), from, to);
            DeepNoise.fill(proto, noise, settings, from, to);
        }
        if (deferred != Integer.MIN_VALUE) {
            DeferredDepth.placeholder(proto, deferred);
            DeferredDepth.setState(proto, new DeferState(deferred, DeferState.Stage.PLACEHOLDER));
        }
        EpicChunkGenerator.fillLakes(engine, proto, null);
        proto.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, random, DensityFunctions.BeardifierMarker.INSTANCE, settings,
                fluids, Blender.empty()));
        var biomeManager = new BiomeManager((x, y, z) -> source.getNoiseBiome(x, y, z, random.sampler()),
                BiomeManager.obfuscateSeed(level.getSeed()));
        generator.buildSurface(proto, new WorldGenerationContext(generator, level), random, level.structureManager(),
                biomeManager, biomes, Blender.empty());
        proto.setPersistedStatus(ChunkStatus.CARVERS);
        return new LodChunk(proto, from);
    }

    private void decorate(ChunkPos pos) {
        // The features of this chunk read and write the chunks around it.
        LodChunk center = base(pos);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) base(new ChunkPos(pos.x + dx, pos.z + dz));
        }
        ParallelGeneration.withLocks(pos, () -> {
            LodContext.enter(this);
            try {
                Heightmap.primeHeightmaps(center.proto, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
                StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(pos.x, pos.z, 1, (x, z) -> null);
                var region = new WorldGenRegion(level, cache, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), center.proto);
                generator.applyBiomeDecoration(region, center.proto, level.structureManager().forWorldGenRegion(region));
            } catch (Throwable e) {
                EpicWorld.LOGGER.debug("Epic World: LOD features of {} failed", pos, e);
            } finally {
                LodContext.exit();
            }
        });
        center.decorated = true;
    }

    /** The chunk for a world generation region of the LOD pass (made on demand). */
    ChunkAccess chunk(int x, int z) {
        return base(new ChunkPos(x, z)).proto;
    }

    /** Whether a feature at this height can only matter underground (LODs show the surface). */
    static boolean deepFeature(ChunkAccess chunk, int y) {
        if (y == chunk.getMinBuildHeight()) return false;
        DeferState state = DeferredDepth.state(chunk);
        return state.deferred() && y < state.top() - FEATURE_REACH;
    }

    private void tryHandOver(ChunkPos pos) {
        CompletableFuture<LodChunk> f = chunks.get(pos.toLong());
        if (f == null || !f.isDone() || f.isCompletedExceptionally()) return;
        LodChunk c = f.join();
        if (!c.decorated) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long key = ChunkPos.asLong(pos.x + dx, pos.z + dz);
                if (settled.contains(key) || near.contains(key)) continue;
                synchronized (done) {
                    if (done.contains(key)) continue;
                }
                CompletableFuture<LodChunk> n = chunks.get(key);
                if (n == null || !n.isDone() || n.isCompletedExceptionally() || !n.join().decorated) return;
            }
        }
        handOver(pos, c);
    }

    private void handOver(ChunkPos pos, LodChunk c) {
        if (!c.handed.compareAndSet(false, true)) return;
        Object world = VoxyBridge.world(level);
        if (world == null) {
            c.handed.set(false);
            return;
        }
        var proto = c.proto;
        for (int i = 0; i < proto.getSectionsCount(); i++) {
            var section = proto.getSection(i);
            if (section.hasOnlyAir()) continue;
            int sectionY = proto.getSectionYFromSectionIndex(i);
            // The placeholder depth: one section under the deferred top is enough to close cave mouths in the LODs.
            if ((sectionY + 1) * 16 <= c.top - 16) continue;
            VoxyBridge.ingest(world, section, pos.x, sectionY, pos.z);
        }
        synchronized (done) {
            done.add(pos.toLong());
        }
        handedOver.incrementAndGet();
        chunks.remove(pos.toLong());
    }

    /** At the end of the radius (or when memory fills up), hand over chunks still waiting for neighbours as they are. */
    private void flush() {
        for (var e : chunks.entrySet()) {
            var f = e.getValue();
            if (f.isDone() && !f.isCompletedExceptionally() && f.join().decorated) handOver(new ChunkPos(e.getKey()), f.join());
        }
    }

    private void evict() {
        flush();
        chunks.entrySet().removeIf(e -> e.getValue().isDone());
    }

    // ------------------------------------------------------------------------------------------------ persistence

    private void load() {
        if (!Files.exists(doneFile)) return;
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(doneFile)))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) done.add(in.readLong());
        } catch (IOException e) {
            EpicWorld.LOGGER.warn("Epic World: could not read {}", doneFile, e);
        }
    }

    private void save() {
        long[] keys;
        synchronized (done) {
            keys = done.toLongArray();
        }
        try {
            Files.createDirectories(doneFile.getParent());
            Path tmp = doneFile.resolveSibling("voxy-lods.dat.tmp");
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(keys.length);
                for (long k : keys) out.writeLong(k);
            }
            Files.move(tmp, doneFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            EpicWorld.LOGGER.warn("Epic World: could not write {}", doneFile, e);
        }
    }

    /** Forgets which LODs were made, so they are made again (after the Voxy data was deleted, for example). */
    public void reset() {
        synchronized (done) {
            done.clear();
        }
        settled.clear();
        near.clear();
        ring = -1;
        save();
    }

    public String status() {
        String blocker = blocker();
        double seconds = (System.nanoTime() - sessionStart.get()) / 1e9;
        int count;
        synchronized (done) {
            count = done.size();
        }
        return String.format(java.util.Locale.ROOT,
                "%s; готово лодов: %d (за этот сеанс %d, %.1f в секунду), в работе %d плиток, в памяти %d чанков, поток(ов) %d, радиус %d",
                blocker == null ? (finished ? "радиус заполнен" : "идёт генерация")
                        : "не работает: " + net.minecraft.network.chat.Component.translatable(blocker).getString(),
                count, handedOver.get(), handedOver.get() / Math.max(1, seconds), inFlight.get(), chunks.size(), poolThreads,
                EpicPerformance.get(EpicPerformance.VOXY_RADIUS));
    }

    /** For the F3 overlay. */
    public record Stats(String state, int done, double perSecond, int inFlight, int cached, int radius, boolean circle, int threads) {}

    public Stats stats() {
        String blocker = blocker();
        int count;
        synchronized (done) {
            count = done.size();
        }
        double rate = (history[0] - history[history.length - 1]) / (double) (history.length - 1);
        String state = blocker != null ? blocker : finished ? "done" : "running";
        return new Stats(state, count, rate, inFlight.get(), chunks.size(), EpicPerformance.get(EpicPerformance.VOXY_RADIUS),
                EpicPerformance.get(EpicPerformance.VOXY_SHAPE) == EpicPerformance.Shape.CIRCLE, poolThreads);
    }

    public Level level() {
        return level;
    }
}
