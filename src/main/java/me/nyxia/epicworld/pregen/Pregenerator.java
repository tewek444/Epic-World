package me.nyxia.epicworld.pregen;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.UUID;
import javax.annotation.Nullable;
import me.nyxia.epicworld.EpicWorld;
import me.nyxia.epicworld.config.EpicPerformance;
import me.nyxia.epicworld.voxy.VoxyBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * {@code /epicworld gen}: generates a rectangle (or the circle inside it) of chunks in full and saves them, like Chunky,
 * with as many chunks in flight as the (parallel) generation takes. Generated chunks also go to Voxy, so the area gets
 * LODs. One task at a time; it is saved in the world and continues after a restart.
 * <p>
 * Chunks are visited in 32x32 tiles (about one region file each), row by row inside a tile.
 */
public final class Pregenerator {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("epicworld_pregen", Comparator.comparingLong(ChunkPos::toLong));
    private static final int IN_FLIGHT = 256;
    private static final int TILE = 32;

    @Nullable private static Pregenerator task;

    private final MinecraftServer server;
    private final ServerLevel level;
    /** Chunk coordinates, inclusive. */
    private final int x1, z1, x2, z2;
    private final boolean circle;
    private final long total;
    private long cursor;
    private long done;
    private boolean paused;
    @Nullable private UUID starter;
    private final LongArrayList inFlight = new LongArrayList();
    /** The cursor index of each chunk in flight, to go back to the first unfinished one. */
    private final LongArrayList inFlightIndex = new LongArrayList();
    private final long[] history = new long[6];
    private int ticks;
    private long startNanos = System.nanoTime();

    private Pregenerator(MinecraftServer server, ServerLevel level, int x1, int z1, int x2, int z2, boolean circle) {
        this.server = server;
        this.level = level;
        this.x1 = Math.min(x1, x2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.z2 = Math.max(z1, z2);
        this.circle = circle;
        this.total = count();
    }

    // ------------------------------------------------------------------------------------------------ control

    @Nullable
    public static Pregenerator task() {
        return task;
    }

    /** Starts a task over blocks (x1, z1)–(x2, z2); a message for the player, or null if one is running. */
    @Nullable
    public static Component start(ServerLevel level, int blockX1, int blockZ1, int blockX2, int blockZ2, boolean circle,
                                  @Nullable ServerPlayer starter) {
        if (task != null) return null;
        var p = new Pregenerator(level.getServer(), level, blockX1 >> 4, blockZ1 >> 4, blockX2 >> 4, blockZ2 >> 4, circle);
        p.starter = starter == null ? null : starter.getUUID();
        task = p;
        p.save();
        return Component.translatable("epicworld.pregen.started", p.total, blockX1 + ", " + blockZ1, blockX2 + ", " + blockZ2,
                Component.translatable(circle ? "epicworld.shape.circle" : "epicworld.shape.square"));
    }

    public void pause(boolean pause) {
        paused = pause;
        if (pause) releaseAll();
        history[0] = done;
        save();
    }

    public boolean paused() {
        return paused;
    }

    public void cancel() {
        releaseAll();
        task = null;
        deleteSaved();
    }

    public long done() {
        return done;
    }

    public long total() {
        return total;
    }

    public double perSecond() {
        return (history[0] - history[history.length - 1]) / (double) (history.length - 1);
    }

    public String remaining() {
        double rate = perSecond();
        if (rate <= 0.5) return "?";
        return duration((long) ((total - done) / rate));
    }

    public Component progress() {
        return Component.translatable("epicworld.pregen.progress", String.format(java.util.Locale.ROOT, "%.1f", 100.0 * done / Math.max(1, total)),
                done, total, String.format(java.util.Locale.ROOT, "%.0f", perSecond()), remaining());
    }

    static String duration(long seconds) {
        if (seconds < 60) return seconds + " s";
        if (seconds < 3600) return (seconds / 60) + " min " + (seconds % 60) + " s";
        return (seconds / 3600) + " h " + (seconds % 3600 / 60) + " min";
    }

    // ------------------------------------------------------------------------------------------------ work

    public static void onTick(ServerTickEvent.Post event) {
        Pregenerator p = task;
        if (p != null) p.tick();
    }

    private void tick() {
        ticks++;
        if (ticks % 20 == 0) {
            System.arraycopy(history, 0, history, 1, history.length - 1);
            history[0] = done;
        }
        if (paused) return;
        var source = level.getChunkSource();
        // Collect finished chunks.
        for (int i = inFlight.size() - 1; i >= 0; i--) {
            long key = inFlight.getLong(i);
            LevelChunk chunk = source.getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            ChunkPos pos = chunk.getPos();
            source.removeRegionTicket(TICKET, pos, 0, pos);
            if (EpicPerformance.get(EpicPerformance.VOXY_ENABLED)) VoxyBridge.ingestChunk(level, chunk);
            inFlight.removeLong(i);
            inFlightIndex.removeLong(i);
            done++;
        }
        // Ask for more.
        long size = (long) tilesX() * tilesZ() * TILE * TILE;
        while (inFlight.size() < IN_FLIGHT && cursor < size) {
            long index = cursor++;
            ChunkPos pos = posAt(index);
            if (pos == null) continue;
            source.addRegionTicket(TICKET, pos, 0, pos);
            inFlight.add(pos.toLong());
            inFlightIndex.add(index);
        }
        if (ticks % 100 == 0) notifyStarter(progress());
        if (ticks % 600 == 0) save();
        if (cursor >= size && inFlight.isEmpty()) {
            task = null;
            deleteSaved();
            Component msg = Component.translatable("epicworld.pregen.finished", done, duration((System.nanoTime() - startNanos) / 1_000_000_000L));
            EpicWorld.LOGGER.info("Epic World: pregeneration finished, {} chunks", done);
            notifyStarter(msg);
        }
    }

    private void releaseAll() {
        var source = level.getChunkSource();
        for (long key : inFlight) {
            ChunkPos pos = new ChunkPos(key);
            source.removeRegionTicket(TICKET, pos, 0, pos);
        }
        // Unfinished chunks are visited again when the task continues (finished ones after them just load).
        cursor = resumeIndex();
        inFlight.clear();
        inFlightIndex.clear();
        rewindTo(cursor);
    }

    private long resumeIndex() {
        long first = cursor;
        for (long index : inFlightIndex) first = Math.min(first, index);
        return first;
    }

    /** After a pause the cursor went back; recount done chunks up to it so the progress stays right. */
    private void rewindTo(long index) {
        long counted = 0;
        for (long i = 0; i < index; i++) if (posAt(i) != null) counted++;
        done = counted;
    }

    private void notifyStarter(Component message) {
        ServerPlayer player = starter == null ? null : server.getPlayerList().getPlayer(starter);
        if (player != null) player.displayClientMessage(message, true);
    }

    private int tilesX() {
        return (x2 - x1) / TILE + 1;
    }

    private int tilesZ() {
        return (z2 - z1) / TILE + 1;
    }

    @Nullable
    private ChunkPos posAt(long index) {
        long tile = index / (TILE * TILE);
        int local = (int) (index % (TILE * TILE));
        int tx = (int) (tile % tilesX());
        int tz = (int) (tile / tilesX());
        int x = x1 + tx * TILE + local % TILE;
        int z = z1 + tz * TILE + local / TILE;
        return inArea(x, z) ? new ChunkPos(x, z) : null;
    }

    private boolean inArea(int x, int z) {
        if (x > x2 || z > z2) return false;
        if (!circle) return true;
        double cx = (x1 + x2) / 2.0;
        double cz = (z1 + z2) / 2.0;
        double rx = (x2 - x1) / 2.0 + 0.5;
        double rz = (z2 - z1) / 2.0 + 0.5;
        double dx = (x - cx) / rx;
        double dz = (z - cz) / rz;
        return dx * dx + dz * dz <= 1;
    }

    private long count() {
        if (!circle) return (long) (x2 - x1 + 1) * (z2 - z1 + 1);
        long n = 0;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) if (inArea(x, z)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------ persistence

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("epicworld").resolve("pregen.properties");
    }

    private void save() {
        var props = new Properties();
        props.setProperty("dimension", level.dimension().location().toString());
        props.setProperty("x1", "" + x1);
        props.setProperty("z1", "" + z1);
        props.setProperty("x2", "" + x2);
        props.setProperty("z2", "" + z2);
        props.setProperty("circle", "" + circle);
        props.setProperty("cursor", "" + resumeIndex());
        props.setProperty("paused", "" + paused);
        if (starter != null) props.setProperty("starter", starter.toString());
        try {
            Path f = file(server);
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f)) {
                props.store(w, "Epic World pregeneration task (chunk coordinates)");
            }
        } catch (IOException e) {
            EpicWorld.LOGGER.warn("Epic World: could not save the pregeneration task", e);
        }
    }

    private void deleteSaved() {
        try {
            Files.deleteIfExists(file(server));
        } catch (IOException ignored) {
            // A stale file only restarts the task.
        }
    }

    public static void onServerStarted(ServerStartedEvent event) {
        task = null;
        Path f = file(event.getServer());
        if (!Files.exists(f)) return;
        var props = new Properties();
        try (Reader r = Files.newBufferedReader(f)) {
            props.load(r);
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(props.getProperty("dimension")));
            ServerLevel level = event.getServer().getLevel(dim);
            if (level == null) return;
            var p = new Pregenerator(event.getServer(), level, Integer.parseInt(props.getProperty("x1")), Integer.parseInt(props.getProperty("z1")),
                    Integer.parseInt(props.getProperty("x2")), Integer.parseInt(props.getProperty("z2")), Boolean.parseBoolean(props.getProperty("circle")));
            p.cursor = Long.parseLong(props.getProperty("cursor"));
            p.paused = Boolean.parseBoolean(props.getProperty("paused"));
            String starter = props.getProperty("starter");
            if (starter != null) p.starter = UUID.fromString(starter);
            p.rewindTo(p.cursor);
            p.history[0] = p.done;
            task = p;
            EpicWorld.LOGGER.info("Epic World: pregeneration task continues ({} of {} chunks{})", p.done, p.total, p.paused ? ", paused" : "");
        } catch (Exception e) {
            EpicWorld.LOGGER.warn("Epic World: could not read the pregeneration task {}", f, e);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        Pregenerator p = task;
        if (p != null) {
            p.save();
            p.releaseAll();
        }
        task = null;
    }
}
