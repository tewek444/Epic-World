package me.nyxia.epicworld.dev;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import me.nyxia.epicworld.EpicWorld;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * {@code /epicworld benchmark <radius>}: fully generates (structures, noise, surface, carvers, features, light) nine fresh
 * squares of chunks (a 3x3 grid, 4096 blocks apart) far from the player and reports throughput. Works for any generator, so world types can be compared
 * under identical conditions. Each run moves further east so it never reuses generated chunks. For fair numbers the
 * player should stand still in an already generated area (otherwise its own chunk loading competes).
 */
public final class GenerationBenchmark {
    private GenerationBenchmark() {}

    private static final TicketType<ChunkPos> TICKET = TicketType.create("epicworld_benchmark", Comparator.comparingLong(ChunkPos::toLong));
    private static final long TIMEOUT_NANOS = 15L * 60 * 1_000_000_000L;

    private record Run(ServerLevel level, CommandSourceStack source, List<ChunkPos> chunks, List<ChunkPos> pending, long start, long cpuStart) {}

    @Nullable
    private static Run active;
    /** Chunks already used by earlier runs east of the player; runs never overlap. */
    private static int cursor;

    public static int start(CommandSourceStack source, int radius) {
        if (active != null) {
            source.sendFailure(Component.literal("A benchmark is already running."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        ChunkPos origin = new ChunkPos(net.minecraft.core.BlockPos.containing(source.getPosition()));
        // A 3x3 grid of squares 256 chunks (4096 blocks) apart samples mixed terrain (ocean, plains, mountains),
        // starting 1600 chunks east of the player. Every run uses a fresh grid further east (never overlapping).
        int spacing = 256;
        int baseX = origin.x + 1600 + cursor;
        cursor += 3 * spacing;
        var chunks = new ArrayList<ChunkPos>();
        for (int gx = 0; gx < 3; gx++) {
            for (int gz = -1; gz <= 1; gz++) {
                int cx = baseX + gx * spacing;
                int cz = origin.z + gz * spacing;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) chunks.add(new ChunkPos(cx + dx, cz + dz));
                }
            }
        }
        for (ChunkPos pos : chunks) level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
        active = new Run(level, source, chunks, new ArrayList<>(chunks), System.nanoTime(), cpuNanos());
        source.sendSuccess(() -> Component.literal("Benchmark: generating " + chunks.size() + " chunks in 9 areas from chunk "
                + baseX + ", " + origin.z + " with " + describe(level) + "..."), false);
        return 1;
    }

    /** CPU time of the whole process (all threads), in nanoseconds. */
    private static long cpuNanos() {
        var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.OperatingSystemMXBean sun ? sun.getProcessCpuTime() : 0;
    }

    private static String describe(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        String name = generator.getClass().getSimpleName();
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            name += " " + noise.generatorSettings().unwrapKey().map(k -> k.location().toString()).orElse("inline");
        }
        return name;
    }

    /** {@code -Depicworld.autoBench=N}: run the benchmark N times as soon as a player is in the world, then quit. */
    private static int autoRuns = Integer.getInteger("epicworld.autoBench", 0);
    static {
        if (autoRuns > 0) System.setProperty("epicworld.autoBenchActive", "true");
    }
    private static int autoWait;

    /** {@code -Depicworld.autoQuit=S}: quit the game S seconds after a player joined (unattended test runs). */
    private static final int AUTO_QUIT = Integer.getInteger("epicworld.autoQuit", 0);
    private static int quitTicks;

    private static void autoQuit(ServerTickEvent.Post event) {
        if (AUTO_QUIT <= 0 || event.getServer().getPlayerList().getPlayers().isEmpty()) return;
        if (++quitTicks == AUTO_QUIT * 20) {
            EpicWorld.LOGGER.info("Automatic quit after {} s", AUTO_QUIT);
            if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) me.nyxia.epicworld.client.EpicClient.quit();
        }
    }

    /** {@code -Depicworld.logChunkRate=true}: log how many new chunks became full in each 10 s (e.g. Voxy World Gen). */
    private static final boolean LOG_RATE = Boolean.getBoolean("epicworld.logChunkRate");
    private static final java.util.concurrent.atomic.AtomicInteger NEW_CHUNKS = new java.util.concurrent.atomic.AtomicInteger();
    private static int rateTicks;

    public static void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (LOG_RATE && event.isNewChunk()) NEW_CHUNKS.incrementAndGet();
    }

    private static void rate() {
        if (!LOG_RATE || ++rateTicks % 200 != 0) return;
        int n = NEW_CHUNKS.getAndSet(0);
        EpicWorld.LOGGER.info("New chunks in the last 10 s: {} ({} per second)", n, n / 10.0);
    }

    private static void auto(ServerTickEvent.Post event) {
        rate();
        autoQuit(event);
        if (autoRuns <= 0 || active != null) return;
        var server = event.getServer();
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty() || ++autoWait < 200) return;
        autoWait = 0;
        if (autoRuns-- == 0) return;
        var player = players.get(0);
        var source = server.createCommandSourceStack().withLevel(player.serverLevel()).withPosition(player.position());
        start(source, Integer.getInteger("epicworld.benchRadius", 6));
    }

    private static void autoFinished() {
        if (autoRuns > 0 || !Boolean.getBoolean("epicworld.autoBenchActive")) return;
        EpicWorld.LOGGER.info("Generation benchmark: automatic runs done");
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) me.nyxia.epicworld.client.EpicClient.quit();
    }

    public static void tick(ServerTickEvent.Post event) {
        auto(event);
        Run run = active;
        if (run == null) return;
        // Only chunks not seen finished yet are looked up (a full scan every tick cost the server thread a lot).
        run.pending.removeIf(pos -> run.level.getChunkSource().getChunkNow(pos.x, pos.z) != null);
        int done = run.chunks.size() - run.pending.size();
        long elapsed = System.nanoTime() - run.start;
        boolean timedOut = elapsed > TIMEOUT_NANOS;
        if (done < run.chunks.size() && !timedOut) return;
        for (ChunkPos pos : run.chunks) run.level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
        active = null;
        double seconds = elapsed / 1e9;
        double cpuMs = (cpuNanos() - run.cpuStart) / 1e6;
        String result = String.format(Locale.ROOT, "%s: %d/%d chunks in %.1f s = %.1f chunks/s (%.1f ms per chunk, wall clock, %d threads; %.1f ms CPU per chunk, %.1f cores busy)%s",
                describe(run.level), done, run.chunks.size(), seconds, done / seconds, seconds * 1000 / Math.max(1, done),
                Runtime.getRuntime().availableProcessors(), cpuMs / Math.max(1, done), cpuMs / 1000 / seconds, timedOut ? " TIMED OUT" : "");
        EpicWorld.LOGGER.info("Generation benchmark: {}", result);
        run.source.sendSuccess(() -> Component.literal("Benchmark finished. " + result), false);
        autoFinished();
    }
}
