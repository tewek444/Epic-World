package me.nyxia.epicworld.mixin;

import me.nyxia.epicworld.worldgen.deferred.DeferredDepth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Deferred depth: features of the deep pass mark level chunks for post-processing; collect the marks instead. */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {
    @Inject(method = "markPosForPostprocessing", at = @At("HEAD"), cancellable = true)
    private void epicworld$deepPostProcessing(BlockPos pos, CallbackInfo ci) {
        if (!DeferredDepth.DEEP_PASS.get()) return;
        DeferredDepth.DEEP_POST_PROCESS.add(pos.immutable());
        ci.cancel();
    }
}
