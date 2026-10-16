package me.nyxia.epicworld.mixin;

import me.nyxia.epicworld.worldgen.EpicDensity;
import me.nyxia.epicworld.worldgen.TerrainEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Epic World's climate slots carry engine data, not vanilla climate; spawn is searched by the engine itself. */
@Mixin(Climate.Sampler.class)
public abstract class ClimateSamplerMixin {
    @Inject(method = "findSpawnPosition", at = @At("HEAD"), cancellable = true)
    private void epicworld$spawn(CallbackInfoReturnable<BlockPos> cir) {
        TerrainEngine engine = EpicDensity.find(((Climate.Sampler) (Object) this).continentalness());
        if (engine != null) cir.setReturnValue(engine.findSpawn());
    }
}
