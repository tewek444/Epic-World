package me.nyxia.epicworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Eroded badlands hoodoos and frozen-ocean icebergs are hard-coded to vanilla biome keys; include the Epic copies. */
@Mixin(SurfaceSystem.class)
public abstract class SurfaceSystemMixin {
    @WrapOperation(method = "buildSurface", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/resources/ResourceKey;)Z"))
    private boolean epicworld$counterpart(Holder<?> holder, ResourceKey<?> key, Operation<Boolean> original) {
        if (original.call(holder, key)) return true;
        if (!key.registry().equals(Registries.BIOME.location()) || !key.location().getNamespace().equals("minecraft")) return false;
        @SuppressWarnings("unchecked") var biome = (Holder<net.minecraft.world.level.biome.Biome>) holder;
        return biome.is(ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("epicworld", key.location().getPath())));
    }
}
