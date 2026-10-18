package me.nyxia.epicworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.nyxia.epicworld.worldgen.deferred.DeferredDepth;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Deferred depth: carvers of a chunk with a placeholder depth only cut blocks above the deferred top (below it the
 * placeholder is restored anyway). Only the lower bound of the block loop changes, so every block above is decided
 * exactly as before.
 */
@Mixin(WorldCarver.class)
public abstract class WorldCarverDepthMixin {
    @ModifyExpressionValue(method = "carveEllipsoid", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/carver/CarvingContext;getMinGenY()I", ordinal = 0))
    private int epicworld$skipPlaceholder(int minGenY, @Local(argsOnly = true) ChunkAccess chunk) {
        int top = DeferredDepth.placeholderTop(chunk);
        return top == Integer.MIN_VALUE ? minGenY : Math.max(minGenY, top - 2);
    }
}
