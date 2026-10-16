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

    private record Run(ServerLevel level, CommandSourceStack source, List<ChunkPos> chunks, long start) {}

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
        active = new Run(level, source, chunks, System.nanoTime());
        source.sendSuccess(() -> Component.literal("Benchmark: generating " + chunks.size() + " chunks in 9 areas from chunk "
                + baseX + ", " + origin.z + " with " + describe(level) + "..."), false);
        return 1;
    }

    private static String describe(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        String name = generator.getClass().getSimpleName();
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            name += " " + noise.generatorSettings().unwrapKey().map(k -> k.location().toString()).orElse("inline");
        }
        return name;
    }

    public static void tick(ServerTickEvent.Post event) {
        Run run = active;
        if (run == null) return;
        int done = 0;
        for (ChunkPos pos : run.chunks) {
            if (run.level.getChunkSource().getChunkNow(pos.x, pos.z) != null) done++;
        }
        long elapsed = System.nanoTime() - run.start;
        boolean timedOut = elapsed > TIMEOUT_NANOS;
        if (done < run.chunks.size() && !timedOut) return;
        for (ChunkPos pos : run.chunks) run.level.getChunkSource().removeRegionTicket(TICKET, pos, 0, pos);
        active = null;
        double seconds = elapsed / 1e9;
        String result = String.format(Locale.ROOT, "%s: %d/%d chunks in %.1f s = %.1f chunks/s (%.1f ms per chunk, wall clock, %d threads)%s",
                describe(run.level), done, run.chunks.size(), seconds, done / seconds, seconds * 1000 / Math.max(1, done),
                Runtime.getRuntime().availableProcessors(), timedOut ? " TIMED OUT" : "");
        EpicWorld.LOGGER.info("Generation benchmark: {}", result);
        run.source.sendSuccess(() -> Component.literal("Benchmark finished. " + result), false);
    }
}
