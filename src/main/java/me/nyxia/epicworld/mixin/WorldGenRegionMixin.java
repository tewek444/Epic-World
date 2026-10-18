package me.nyxia.epicworld.mixin;

import me.nyxia.epicworld.worldgen.LegacyIndex;
import me.nyxia.epicworld.worldgen.deferred.DeferredDepth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Features of new chunks never write into chunks that existed before the current Epic World settings.
 * Deferred depth: writes are split between the normal and the deep pass ({@link DeferredDepth#canWrite}), and during
 * the deep pass the region works directly on the live level chunks.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Shadow
    @Final
    private ChunkAccess center;

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    public abstract boolean hasChunk(int chunkX, int chunkZ);

    @Inject(method = "ensureCanWrite", at = @At("HEAD"), cancellable = true)
    private void epicworld$protectLegacy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        int x = SectionPos.blockToSectionCoord(pos.getX());
        int z = SectionPos.blockToSectionCoord(pos.getZ());
        if (x == center.getPos().x && z == center.getPos().z) return;
        LegacyIndex index = LegacyIndex.current(level);
        if (index != null && index.isLegacy(x, z)) cir.setReturnValue(false);
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void epicworld$liveChunks(int x, int z, ChunkStatus status, boolean require, CallbackInfoReturnable<ChunkAccess> cir) {
        if (me.nyxia.epicworld.voxy.LodContext.active()) {
            cir.setReturnValue(me.nyxia.epicworld.voxy.LodContext.chunk(x, z));
            return;
        }
        if (!DeferredDepth.DEEP_PASS.get()) return;
        ChunkAccess chunk = level.getChunkSource().getChunkNow(x, z);
        // Structure starts are looked up up to 8 chunks away: those need not be loaded, read them from the level.
        if (chunk == null) chunk = level.getChunk(x, z, status, require);
        if (chunk != null) cir.setReturnValue(chunk);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void epicworld$deferredDepth(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                         CallbackInfoReturnable<Boolean> cir) {
        int x = SectionPos.blockToSectionCoord(pos.getX());
        int z = SectionPos.blockToSectionCoord(pos.getZ());
        if (!hasChunk(x, z)) return;
        ChunkAccess target = ((WorldGenRegion) (Object) this).getChunk(x, z);
        if (!DeferredDepth.canWrite(target, pos.getY())) cir.setReturnValue(false);
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void epicworld$deferredEntities(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (me.nyxia.epicworld.voxy.LodContext.active()) {
            cir.setReturnValue(false);
            return;
        }
        int x = SectionPos.blockToSectionCoord(entity.getBlockX());
        int z = SectionPos.blockToSectionCoord(entity.getBlockZ());
        if (!hasChunk(x, z)) return;
        ChunkAccess target = ((WorldGenRegion) (Object) this).getChunk(x, z);
        if (!DeferredDepth.canWrite(target, entity.getBlockY())) {
            cir.setReturnValue(false);
        } else if (DeferredDepth.DEEP_PASS.get()) {
            // Level chunks keep no entity list of their own: add it to the level like a spawn.
            cir.setReturnValue(level.addFreshEntity(entity));
        }
    }

    /** Level chunks keep only the final heightmaps; features of the deep pass ask for the world generation ones. */
    @ModifyVariable(method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", at = @At("HEAD"), argsOnly = true)
    private Heightmap.Types epicworld$finalHeightmaps(Heightmap.Types type) {
        if (!DeferredDepth.DEEP_PASS.get()) return type;
        return switch (type) {
            case WORLD_SURFACE_WG -> Heightmap.Types.WORLD_SURFACE;
            case OCEAN_FLOOR_WG -> Heightmap.Types.OCEAN_FLOOR;
            default -> type;
        };
    }

    /** Level chunks do not take post-processing marks; the deep pass runs them itself afterwards. */
    @Inject(method = "markPosForPostprocessing", at = @At("HEAD"), cancellable = true)
    private void epicworld$deepPostProcessing(BlockPos pos, CallbackInfo ci) {
        if (!DeferredDepth.DEEP_PASS.get()) return;
        DeferredDepth.DEEP_POST_PROCESS.add(pos.immutable());
        ci.cancel();
    }
}
