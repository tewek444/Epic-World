package me.nyxia.epicworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Saving a section remaps every entry from the live palette to a compact one, and vanilla looks the mapping up again
 * whenever the value changes from one entry to the next, which in generated terrain is nearly every block (and it ran
 * on the server thread). Remember the mapping per palette id instead: same result, a fraction of the lookups.
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin<T> {
    /**
     * Most saved sections hold one value (air, one biome, the deferred placeholder), yet vanilla unpacks and remaps all
     * 4096 entries to pack them. The packed form of a single value is that value and no data.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "pack", at = @At("HEAD"), cancellable = true)
    private void epicworld$packSingleValue(net.minecraft.core.IdMap<T> registry, PalettedContainer.Strategy strategy,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.level.chunk.PalettedContainerRO.PackedData<T>> cir) {
        @SuppressWarnings("unchecked") var self = (PalettedContainer<T>) (Object) this;
        var data = self.data;
        if (!(data.storage() instanceof net.minecraft.util.ZeroBitStorage)) return;
        cir.setReturnValue(new net.minecraft.world.level.chunk.PalettedContainerRO.PackedData<>(
                java.util.List.of(data.palette().valueFor(0)), java.util.Optional.empty()));
    }

    @WrapOperation(method = "pack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/PalettedContainer;swapPalette([ILjava/util/function/IntUnaryOperator;)V"))
    private void epicworld$memoizedSwap(int[] bits, IntUnaryOperator operator, Operation<Void> original) {
        int max = 0;
        for (int v : bits) if (v > max) max = v;
        if (max >= 4096) {
            original.call(bits, operator);
            return;
        }
        int[] memo = new int[max + 1];
        Arrays.fill(memo, -1);
        for (int k = 0; k < bits.length; k++) {
            int v = bits[k];
            int mapped = memo[v];
            if (mapped < 0) mapped = memo[v] = operator.applyAsInt(v);
            bits[k] = mapped;
        }
    }
}
