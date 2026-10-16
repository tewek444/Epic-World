package me.nyxia.epicworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.serialization.Dynamic;
import me.nyxia.epicworld.data.WorldConversion;
import me.nyxia.epicworld.data.WorldLift;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * World conversion hooks: {@link WorldLift} moves the old chunks before {@code level.dat} is decoded, then
 * {@link WorldConversion} replaces the Overworld generator of a save that asked for it.
 */
@Mixin(LevelStorageSource.class)
public abstract class LevelStorageSourceMixin {
    @ModifyVariable(method = "getLevelDataAndDimensions", at = @At("HEAD"), argsOnly = true)
    private static Dynamic<?> epicworld$lift(Dynamic<?> levelData, Dynamic<?> original, WorldDataConfiguration dataConfiguration,
                                             Registry<LevelStem> stems, RegistryAccess.Frozen registries) {
        return WorldLift.apply(levelData, registries);
    }

    @ModifyExpressionValue(method = "getLevelDataAndDimensions", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/OptionalDynamic;orElseEmptyMap()Lcom/mojang/serialization/Dynamic;"))
    private static Dynamic<?> epicworld$convert(Dynamic<?> worldGen, Dynamic<?> levelData,
                                                WorldDataConfiguration dataConfiguration, Registry<LevelStem> stems,
                                                RegistryAccess.Frozen registries) {
        return WorldConversion.apply(worldGen, registries);
    }
}
