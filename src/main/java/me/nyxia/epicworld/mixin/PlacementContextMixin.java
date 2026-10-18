package me.nyxia.epicworld.mixin;

import me.nyxia.epicworld.worldgen.deferred.DeferredDepth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Deferred depth: during the deep pass, carving-mask placements use the mask of the chunk's deep carvers. */
@Mixin(PlacementContext.class)
public abstract class PlacementContextMixin {
    @Inject(method = "getCarvingMask", at = @At("HEAD"), cancellable = true)
    private void epicworld$deepMask(ChunkPos pos, GenerationStep.Carving step, CallbackInfoReturnable<CarvingMask> cir) {
        if (DeferredDepth.DEEP_PASS.get()) cir.setReturnValue(DeferredDepth.deepMask(pos));
    }
}
