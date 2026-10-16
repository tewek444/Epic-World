package me.nyxia.epicworld.mixin;

import me.nyxia.epicworld.worldgen.EpicChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft flags every non-vanilla Overworld as "experimental" and asks for confirmation on each world creation and
 * load. Epic World is a supported world type, so its Overworld counts as stable.
 */
@Mixin(WorldDimensions.class)
public abstract class WorldDimensionsMixin {
    @Inject(method = "isStableOverworld", at = @At("HEAD"), cancellable = true)
    private static void epicworld$stable(LevelStem stem, CallbackInfoReturnable<Boolean> cir) {
        if (stem.generator() instanceof EpicChunkGenerator) cir.setReturnValue(true);
    }
}
