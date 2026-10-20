package me.nyxia.epicworld.voxy;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Epic World's LOD service for Voxy, in place of Voxy World Gen, on dedicated servers and in single player alike.
 * <p>
 * A client with Voxy says hello ({@link LodPayloads.Hello}); the server then walks a spiral of 4x4-chunk tiles around
 * that player and sends the sections of each chunk the player has not got yet ({@link LodPayloads.Chunk}), which the
 * client hands to its Voxy:
 * <ul>
 *   <li>chunks saved in the world are read from disk, with everything players built;</li>
 *   <li>other chunks are generated in memory, outside Minecraft's chunk system (no tickets, light, server thread or
 *   saving), only as far as LODs need: biomes, the top of the terrain, surface and features. They are never saved:
 *   when a player gets there, the real chunk is generated with the same terrain. A generated chunk is sent once all 8
 *   neighbours have their features too, so trees are not cut at chunk borders;</li>
 *   <li>chunks within a player's view distance are skipped: the client gets the real ones.</li>
 * </ul>
 * What each player got is remembered in {@code <world>/epicworld/voxy/<uuid>.dat}, so nothing is sent twice. A chunk
 * several players need is generated once.
 */
public final class LodGenerator {
    private static final int TILE = 4;
    /** Generated chunks kept in memory while they wait for neighbours; above this, waiting ones are sent as they are. */
    private static final int MAX_CACHED = 6000;
    private static final int FEATURE_REACH = 32;
    /** Chunks sent to one player per tick at most (up to 1280 per second), while the connection takes them. */
    private static final int SEND_PER_TICK = 64;
    /** Chunks waiting for one player above which pregeneration waits (the queue holds copies of their sections). */
    private static final int MAX_OUTBOX = 2048;
    /**
     * LOD packets handed to one player's connection but not written to the socket yet. Packets sent from the server
     * thread wait as tasks of the network thread, which compresses them; those tasks are not counted by the channel's
     * writability, so without this limit they piled up in memory when the connection was slower than generation.
     */
    private static final int MAX_PENDING = 64;
    private static final boolean LOG = Boolean.getBoolean("epicworld.logChunkRate");

    @Nullable private static LodGenerator active;

    private final MinecraftServer server;
    private final ServerLevel level;
    private final EpicChunkGenerator generator;
    private final TerrainEngine engine;
    private final SavedChunks saved;
    private final Path dir;
    private final ConcurrentHashMap<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<LodChunk>> chunks = new ConcurrentHashMap<>();
    /** Chunks that exist in the world (sent from disk, never get features here). */
    private final ConcurrentHashMap.KeySetView<Long, Boolean> existing = ConcurrentHashMap.newKeySet();
    /** Chunks that were never saved (generated here). */
    private final ConcurrentHashMap.KeySetView<Long, Boolean> unsaved = ConcurrentHashMap.newKeySet();
    private final long[] history = new long[6];
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong generated = new AtomicLong();
    private final AtomicLong fromDisk = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    @Nullable private ExecutorService pool;
    private int poolThreads;
    private volatile boolean stopping;
    private int ticks;
    private int nextViewer;

    private LodGenerator(MinecraftServer server, ServerLevel level, EpicChunkGenerator generator, TerrainEngine engine) {
        this.server = server;
        this.level = level;
        this.generator = generator;
        this.engine = engine;
        this.saved = new SavedChunks(level);
        this.dir = server.getWorldPath(LevelResource.ROOT).resolve("epicworld").resolve("voxy");
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

    /** A player who gets LODs: what they got, their spiral and the chunks waiting to be sent to them. */
    final class Viewer {
        final UUID id;
        final Path file;
        final LongOpenHashSet got = new LongOpenHashSet();
        final ConcurrentLinkedQueue<LodPayloads.Chunk> outbox = new ConcurrentLinkedQueue<>();
        final AtomicInteger pending = new AtomicInteger();
        volatile int playerX;
        volatile int playerZ;
        volatile boolean here;
        // Spiral over tiles around the centre: ring r has 8r tiles (1 for r = 0).
        volatile int centerTx;
        volatile int centerTz;
        int ring = -1;
        int ringIndex;
        volatile boolean finished;

        Viewer(UUID id) {
            this.id = id;
            this.file = dir.resolve(id + ".dat");
            load();
        }

        boolean has(long key) {
            synchronized (got) {
                return got.contains(key);
            }
        }

        /** Whether this player still needs the LOD of a chunk now. */
        boolean wants(int x, int z) {
            if (!here) return false;
            long px = x - playerX;
            long pz = z - playerZ;
            int keepOut = server.getPlayerList().getViewDistance();
            if (px * px + pz * pz <= (long) keepOut * keepOut) return false;
            int radius = EpicPerformance.get(EpicPerformance.VOXY_RADIUS);
            long cx = x - (centerTx * TILE + TILE / 2);
            long cz = z - (centerTz * TILE + TILE / 2);
            boolean inside = EpicPerformance.get(EpicPerformance.VOXY_SHAPE) == EpicPerformance.Shape.CIRCLE
                    ? cx * cx + cz * cz <= (long) radius * radius
                    : Math.max(Math.abs(cx), Math.abs(cz)) <= radius;
            return inside && !has(ChunkPos.asLong(x, z));
        }

        long nextTile() {
            int radiusTiles = (EpicPerformance.get(EpicPerformance.VOXY_RADIUS) + TILE - 1) / TILE;
            while (ring <= radiusTiles) {
                int count = ring == 0 ? 1 : 8 * ring;
                if (ringIndex < count) {
                    int i = ringIndex++;
                    int dx = 0;
                    int dz = 0;
                    if (ring > 0) {
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

        private void load() {
            if (!Files.exists(file)) return;
            try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
                int n = in.readInt();
                for (int i = 0; i < n; i++) got.add(in.readLong());
            } catch (IOException e) {
                EpicWorld.LOGGER.warn("Epic World: could not read {}", file, e);
            }
        }

        void save() {
            long[] keys;
            synchronized (got) {
                // Chunks still in the outbox were never delivered.
                for (var p : outbox) got.remove(ChunkPos.asLong(p.x(), p.z()));
                keys = got.toLongArray();
            }
            try {
                Files.createDirectories(dir);
                Path tmp = file.resolveSibling(id + ".dat.tmp");
                try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
                    out.writeInt(keys.length);
                    for (long k : keys) out.writeLong(k);
                }
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                EpicWorld.LOGGER.warn("Epic World: could not write {}", file, e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        if (!(overworld.getChunkSource().getGenerator() instanceof EpicChunkGenerator generator)) return;
        TerrainEngine engine = EpicChunkGenerator.engine(overworld.getChunkSource().randomState());
        if (engine == null) return;
        active = new LodGenerator(server, overworld, generator, engine);
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        LodGenerator g = active;
        active = null;
        if (g == null) return;
        g.stopping = true;
        if (g.pool != null) g.pool.shutdownNow();
        g.viewers.values().forEach(Viewer::save);
    }

    /** A client with Voxy said hello. */
    public static void hello(ServerPlayer player) {
        LodGenerator g = active;
        if (g == null || g.stopping) return;
        g.viewers.computeIfAbsent(player.getUUID(), g::newViewer);
    }

    private Viewer newViewer(UUID id) {
        return new Viewer(id);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LodGenerator g = active;
        if (g == null) return;
        Viewer v = g.viewers.remove(event.getEntity().getUUID());
        if (v != null) v.save();
    }

    @Nullable
    public static LodGenerator active() {
        return active;
    }

    /** Why the service does not run (a translation key), or null when it does. */
    @Nullable
    public static String blocker() {
        if (!EpicPerformance.get(EpicPerformance.VOXY_ENABLED)) return "epicworld.lods.blocked.disabled";
        if (net.neoforged.fml.ModList.get().isLoaded("voxyworldgenv2")) return "epicworld.lods.blocked.voxy_world_gen";
        return null;
    }

    public static void onTick(ServerTickEvent.Post event) {
        LodGenerator g = active;
        if (g != null && !g.stopping) g.tick();
    }

    private void tick() {
        ticks++;
        if (ticks % 6000 == 0) viewers.values().forEach(Viewer::save);
        if (LOG && ticks % 200 == 0) EpicWorld.LOGGER.info("Epic World LODs: {}", status());
        if (ticks % 20 == 0) {
            System.arraycopy(history, 0, history, 1, history.length - 1);
            history[0] = sent.get();
        }
        if (blocker() != null || viewers.isEmpty()) return;
        // Positions (read on the server thread) and spiral restarts.
        for (Viewer v : viewers.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(v.id);
            v.here = player != null && player.level() == level;
            if (!v.here) continue;
            v.playerX = player.chunkPosition().x;
            v.playerZ = player.chunkPosition().z;
            int ptx = Math.floorDiv(v.playerX, TILE);
            int ptz = Math.floorDiv(v.playerZ, TILE);
            if (v.ring < 0 || Math.max(Math.abs(ptx - v.centerTx), Math.abs(ptz - v.centerTz)) > 4) {
                v.centerTx = ptx;
                v.centerTz = ptz;
                v.ring = 0;
                v.ringIndex = 0;
                v.finished = false;
            }
            // Deliver, no faster than the connection writes: unsent packets wait in memory.
            var channel = player.connection.getConnection().channel;
            for (int i = 0; i < SEND_PER_TICK && v.pending.get() < MAX_PENDING && (channel == null || channel.isWritable()); i++) {
                LodPayloads.Chunk payload = v.outbox.poll();
                if (payload == null) break;
                v.pending.incrementAndGet();
                player.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload),
                        new net.minecraft.network.PacketSendListener() {
                            @Override
                            public void onSuccess() {
                                v.pending.decrementAndGet();
                            }

                            @Override
                            public net.minecraft.network.protocol.Packet<?> onFailure() {
                                v.pending.decrementAndGet();
                                return null;
                            }
                        });
                sent.incrementAndGet();
            }
        }
        // Without generation, players only get the chunks the world makes (see offer).
        if (!EpicPerformance.get(EpicPerformance.VOXY_GENERATE)) return;
        // Hand out tiles, taking turns between players.
        ensurePool();
        List<Viewer> list = new ArrayList<>(viewers.values());
        int maxInFlight = poolThreads * 3;
        int idle = 0;
        while (inFlight.get() < maxInFlight && idle < list.size()) {
            Viewer v = list.get(nextViewer++ % list.size());
            if (!v.here || v.finished || v.outbox.size() > MAX_OUTBOX / 2) {
                idle++;
                continue;
            }
            long tile = v.nextTile();
            if (tile == Long.MIN_VALUE) {
                v.finished = true;
                flush();
                idle++;
                continue;
            }
            idle = 0;
            int tx = ChunkPos.getX(tile);
            int tz = ChunkPos.getZ(tile);
            inFlight.incrementAndGet();
            pool.execute(() -> {
                try {
                    if (!stopping) processTile(tx, tz);
                } catch (Throwable e) {
                    EpicWorld.LOGGER.error("Epic World: LOD tile {}, {} failed", tx, tz, e);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
        if (chunks.size() > MAX_CACHED) evict();
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

    private boolean anyWants(int x, int z) {
        for (Viewer v : viewers.values()) if (v.wants(x, z)) return true;
        return false;
    }

    private void processTile(int tx, int tz) {
        boolean features = EpicPerformance.get(EpicPerformance.VOXY_FEATURES);
        var targets = new ArrayList<ChunkPos>();
        for (int dx = 0; dx < TILE; dx++) {
            for (int dz = 0; dz < TILE; dz++) {
                var pos = new ChunkPos(tx * TILE + dx, tz * TILE + dz);
                if (!anyWants(pos.x, pos.z)) continue;
                long key = pos.toLong();
                if (!unsaved.contains(key)) {
                    var tag = saved.read(pos);
                    if (tag.isPresent()) {
                        var sections = saved.sections(tag.get());
                        if (sections != null) {
                            existing.add(key);
                            deliver(pos, sections);
                            fromDisk.incrementAndGet();
                            continue;
                        }
                    }
                    unsaved.add(key);
                }
                CompletableFuture<LodChunk> known = chunks.get(key);
                if (known != null && known.isDone() && !known.isCompletedExceptionally() && known.join().handed.get()) continue;
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
            LodChunk c = base(pos);
            if (!c.decorated) decorate(pos);
        }
        for (ChunkPos pos : targets) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) tryHandOver(new ChunkPos(pos.x + dx, pos.z + dz));
            }
        }
    }

    /** Biomes, the top of the noise, lakes and surface of one chunk, in memory. */
    LodChunk base(ChunkPos pos) {
        long key = pos.toLong();
        CompletableFuture<LodChunk> mine = new CompletableFuture<>();
        CompletableFuture<LodChunk> existingFuture = chunks.putIfAbsent(key, mine);
        if (existingFuture != null) return existingFuture.join();
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
            if (center.decorated) return;
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
            center.decorated = true;
        });
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
                int nx = pos.x + dx;
                int nz = pos.z + dz;
                long key = ChunkPos.asLong(nx, nz);
                if (existing.contains(key)) continue;
                CompletableFuture<LodChunk> n = chunks.get(key);
                boolean decorated = n != null && n.isDone() && !n.isCompletedExceptionally() && n.join().decorated;
                // A neighbour nobody needs now will not get its features here: do not wait for it.
                if (!decorated && anyWants(nx, nz)) return;
            }
        }
        handOver(pos, c);
    }

    private void handOver(ChunkPos pos, LodChunk c) {
        if (!c.handed.compareAndSet(false, true)) return;
        var proto = c.proto;
        var sections = new ArrayList<LodPayloads.Section>();
        for (int i = 0; i < proto.getSectionsCount(); i++) {
            var section = proto.getSection(i);
            if (section.hasOnlyAir()) continue;
            int sectionY = proto.getSectionYFromSectionIndex(i);
            // The placeholder depth: one section under the deferred top is enough to close cave mouths in the LODs.
            if ((sectionY + 1) * 16 <= c.top - 16) continue;
            sections.add(new LodPayloads.Section(sectionY, copy(section)));
        }
        deliver(pos, sections);
        generated.incrementAndGet();
        chunks.remove(pos.toLong());
    }

    /** Queues the sections of a chunk for every player who needs them. */
    private void deliver(ChunkPos pos, List<LodPayloads.Section> sections) {
        LodPayloads.Chunk payload = null;
        long key = pos.toLong();
        for (Viewer v : viewers.values()) {
            if (!v.wants(pos.x, pos.z)) continue;
            synchronized (v.got) {
                if (!v.got.add(key)) continue;
            }
            if (payload == null) payload = new LodPayloads.Chunk(level.dimension().location(), pos.x, pos.z, sections);
            v.outbox.add(payload);
        }
    }

    /** Whether some player's queue of LODs is full: pregeneration then waits for it to be sent. */
    public static boolean backlogged() {
        LodGenerator g = active;
        if (g == null) return false;
        for (Viewer v : g.viewers.values()) if (v.here && v.outbox.size() > MAX_OUTBOX) return true;
        return false;
    }

    /** A copy of a section to send: payloads are encoded on the network thread, while the chunk may still change. */
    private static net.minecraft.world.level.chunk.LevelChunkSection copy(net.minecraft.world.level.chunk.LevelChunkSection section) {
        return new net.minecraft.world.level.chunk.LevelChunkSection(section.getStates().copy(), section.getBiomes().recreate());
    }

    /** A chunk the world has just generated (pregeneration): its sections go to every player who needs them. */
    public static void offer(ServerLevel level, ChunkAccess chunk) {
        LodGenerator g = active;
        if (g == null || g.stopping || level != g.level || g.viewers.isEmpty() || blocker() != null) return;
        int floor = DeferredDepth.placeholderTop(chunk);
        var sections = new ArrayList<LodPayloads.Section>();
        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            var section = chunk.getSection(i);
            if (section.hasOnlyAir()) continue;
            int sectionY = chunk.getSectionYFromSectionIndex(i);
            if (floor != Integer.MIN_VALUE && (sectionY + 1) * 16 <= floor - 16) continue;
            sections.add(new LodPayloads.Section(sectionY, copy(section)));
        }
        ChunkPos pos = chunk.getPos();
        g.existing.add(pos.toLong());
        g.deliver(pos, sections);
    }

    /** At the end of a spiral (or when memory fills up), send chunks still waiting for neighbours as they are. */
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

    // ------------------------------------------------------------------------------------------------ commands, F3

    /** Forgets what was sent (to one player, or to everyone when null), so their LODs are sent again. */
    public int reset(@Nullable UUID player) {
        int n = 0;
        for (Viewer v : viewers.values()) {
            if (player != null && !player.equals(v.id)) continue;
            synchronized (v.got) {
                v.got.clear();
            }
            v.ring = -1;
            v.save();
            n++;
        }
        if (player == null) {
            try (var files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".dat")).forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                        // Left over: it only means those LODs are not sent again.
                    }
                });
            } catch (IOException ignored) {
                // No folder yet.
            }
        }
        return n;
    }

    public String status() {
        String blocker = blocker();
        return String.format(java.util.Locale.ROOT,
                "%s; игроков с Voxy: %d; отправлено %d (%.0f в секунду), из них сгенерировано %d и прочитано с диска %d;"
                        + " в работе %d плиток, в памяти %d чанков, потоков %d, радиус %d",
                blocker == null ? "работает" : "не работает: " + net.minecraft.network.chat.Component.translatable(blocker).getString(),
                viewers.size(), sent.get(), perSecond(), generated.get(), fromDisk.get(), inFlight.get(), chunks.size(), poolThreads,
                EpicPerformance.get(EpicPerformance.VOXY_RADIUS));
    }

    private double perSecond() {
        return (history[0] - history[history.length - 1]) / (double) (history.length - 1);
    }

    /** For the F3 overlay of the host. */
    public record Stats(String state, long sent, double perSecond, int viewers, int inFlight, int cached, int radius, boolean circle, int threads) {}

    public Stats stats() {
        String blocker = blocker();
        boolean allDone = !viewers.isEmpty() && viewers.values().stream().allMatch(v -> v.finished && v.outbox.isEmpty());
        String state = blocker != null ? blocker : viewers.isEmpty() ? "idle" : allDone ? "done" : "running";
        return new Stats(state, sent.get(), perSecond(), viewers.size(), inFlight.get(), chunks.size(),
                EpicPerformance.get(EpicPerformance.VOXY_RADIUS),
                EpicPerformance.get(EpicPerformance.VOXY_SHAPE) == EpicPerformance.Shape.CIRCLE, poolThreads);
    }
}
