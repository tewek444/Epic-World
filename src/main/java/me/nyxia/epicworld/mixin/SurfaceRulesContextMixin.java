package me.nyxia.epicworld.mixin;

import me.nyxia.epicworld.worldgen.LegacyIndex;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Surface rules only place soil above the "preliminary surface" estimated from the noise. Near old chunks the terrain
 * is reshaped after the noise step (see {@code LegacyBlending}), often below that estimate, which left bare stone.
 * There the estimate is capped at the real surface of the column.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public abstract class SurfaceRulesContextMixin {
    @Shadow
    @Final
    ChunkAccess chunk;

    @Shadow
    int blockX;

    @Shadow
    int blockZ;

    @Shadow
    int surfaceDepth;

    @Inject(method = "getMinSurfaceLevel", at = @At("RETURN"), cancellable = true)
    private void epicworld$realSurface(CallbackInfoReturnable<Integer> cir) {
        LegacyIndex index = LegacyIndex.currentAny();
        if (index == null || index.isEmpty()) return;
        var pos = chunk.getPos();
        if (!index.anyNear(pos.x, pos.z, 17)) return;
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, blockX & 15, blockZ & 15);
        cir.setReturnValue(Math.min(cir.getReturnValueI(), top + surfaceDepth - 8));
    }
}
