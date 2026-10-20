package me.nyxia.epicworld.worldgen;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.WorldGenContext;

/** Runs generation steps of Epic World levels on the worker pool instead of the serial worldgen mailbox. */
public final class ParallelGeneration {
    private ParallelGeneration() {}

    /** {@code performance.fast_generation}, or {@code -Depicworld.parallelSteps}. */
    private static boolean enabled() {
        String override = System.getProperty("epicworld.parallelSteps");
        return override != null ? Boolean.parseBoolean(override) : me.nyxia.epicworld.config.EpicPerformance.fastGeneration();
    }

    private static final int STRIPES = 1024;
    private static final java.util.concurrent.locks.ReentrantLock[] LOCKS = new java.util.concurrent.locks.ReentrantLock[STRIPES];
    static {
        for (int i = 0; i < STRIPES; i++) LOCKS[i] = new java.util.concurrent.locks.ReentrantLock();
    }

    /**
     * Features write up to one chunk around their own: runs them on the worker pool while holding the locks of the 3x3
     * chunks they may touch (taken in a fixed order, so two neighbours never deadlock), which is all the exclusion the
     * serial mailbox gave them.
     */
    public static CompletableFuture<ChunkAccess> asyncLocked(WorldGenContext context, net.minecraft.world.level.ChunkPos pos,
                                                             Supplier<CompletableFuture<ChunkAccess>> step) {
        if (!enabled() || !(context.generator() instanceof EpicChunkGenerator)) return step.get();
        return submit(() -> withLocks(pos, step));
    }

    /** Runs {@code body} holding the locks of the 3x3 chunks around {@code pos}. */
    public static void withLocks(net.minecraft.world.level.ChunkPos pos, Runnable body) {
        withLocks(pos, () -> {
            body.run();
            return null;
        });
    }

    private static <T> T withLocks(net.minecraft.world.level.ChunkPos pos, Supplier<T> body) {
        int[] stripes = new int[9];
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int stripe = (int) (net.minecraft.world.level.ChunkPos.asLong(pos.x + dx, pos.z + dz) * 0x9E3779B97F4A7C15L >>> 54);
                boolean seen = false;
                for (int i = 0; i < n; i++) seen |= stripes[i] == stripe;
                if (!seen) stripes[n++] = stripe;
            }
        }
        java.util.Arrays.sort(stripes, 0, n);
        for (int i = 0; i < n; i++) LOCKS[stripes[i]].lock();
        try {
            return body.get();
        } finally {
            for (int i = n - 1; i >= 0; i--) LOCKS[stripes[i]].unlock();
        }
    }

    public static CompletableFuture<ChunkAccess> async(WorldGenContext context, Supplier<CompletableFuture<ChunkAccess>> step) {
        if (!enabled() || !(context.generator() instanceof EpicChunkGenerator)) return step.get();
        return submit(step);
    }

    /**
     * Runs a step on the worker pool as a plain task. Not {@code CompletableFuture.supplyAsync}: its tasks are ones a
     * worker blocked in {@code join()} runs itself while waiting, so a step waiting for the stronghold rings of a new
     * world ran the next step on the same stack, which waited too, and so on until the stack overflowed (and a feature
     * step holding its locks could run another one inside them).
     */
    private static CompletableFuture<ChunkAccess> submit(Supplier<CompletableFuture<ChunkAccess>> step) {
        CompletableFuture<CompletableFuture<ChunkAccess>> started = new CompletableFuture<>();
        Util.backgroundExecutor().execute(() -> {
            try {
                started.complete(step.get());
            } catch (Throwable e) {
                started.completeExceptionally(e);
            }
        });
        return started.thenCompose(f -> f);
    }
}
