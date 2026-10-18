package me.nyxia.epicworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.nyxia.epicworld.worldgen.deferred.DeferredDepth;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Deferred depth: surface rules skip the placeholder (it is not the default block), but the column loop still walked
 * through it down to the bottom of the world. Stop at the deferred top. The stone run below the last real block still
 * counts as reaching far down (the scan ends without finding air), as through the placeholder.
 */
@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystemDepthMixin {
    @ModifyExpressionValue(method = "buildSurface", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getMinBuildHeight()I"))
    private int epicworld$stopAtPlaceholder(int minY, @Local(argsOnly = true) ChunkAccess chunk) {
        int top = DeferredDepth.placeholderTop(chunk);
        return top == Integer.MIN_VALUE ? minY : Math.max(minY, top);
    }
}
