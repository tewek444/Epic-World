package me.nyxia.epicworld.mixin;

import me.nyxia.epicworld.worldgen.deferred.DeferredDepth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Deferred depth: a feature whose origin lies well inside the part a pass may not write is not run at all (its blocks
 * would all be dropped), which saves the time of deep ores and caves features when a chunk is created and of surface
 * features when its depth is completed.
 */
@Mixin(ConfiguredFeature.class)
public abstract class ConfiguredFeatureMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void epicworld$skipOtherPass(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!(level instanceof WorldGenRegion region)) return;
        int x = SectionPos.blockToSectionCoord(origin.getX());
        int z = SectionPos.blockToSectionCoord(origin.getZ());
        if (!region.hasChunk(x, z)) return;
        var chunk = region.getChunk(x, z);
        if (DeferredDepth.skipFeature(chunk, origin.getY()) || me.nyxia.epicworld.voxy.LodContext.skipFeature(chunk, origin.getY())) {
            cir.setReturnValue(false);
        }
    }
}
