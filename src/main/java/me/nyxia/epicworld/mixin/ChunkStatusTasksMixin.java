package me.nyxia.epicworld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.CompletableFuture;
import me.nyxia.epicworld.worldgen.ParallelGeneration;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Vanilla runs every generation step except biomes and noise on one serial "worldgen" mailbox, so surfaces and carvers
 * of all chunks queue behind each other. Both write only into their own chunk, and no other step writes into a chunk
 * before it has its carvers (features need neighbours at CARVERS), so they can run on the worker pool.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
    @WrapMethod(method = "generateSurface")
    private static CompletableFuture<ChunkAccess> epicworld$asyncSurface(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
        return ParallelGeneration.async(context, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "generateCarvers")
    private static CompletableFuture<ChunkAccess> epicworld$asyncCarvers(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
        return ParallelGeneration.async(context, () -> original.call(context, step, cache, chunk));
    }

    @WrapMethod(method = "generateFeatures")
    private static CompletableFuture<ChunkAccess> epicworld$asyncFeatures(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
        return ParallelGeneration.asyncLocked(context, chunk.getPos(), () -> original.call(context, step, cache, chunk));
    }

    /** Structure starts are decided per chunk from the seed and the biome source, and stored in the chunk itself. */
    @WrapMethod(method = "generateStructureStarts")
    private static CompletableFuture<ChunkAccess> epicworld$asyncStarts(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original) {
        return ParallelGeneration.async(context, () -> original.call(context, step, cache, chunk));
    }
}
