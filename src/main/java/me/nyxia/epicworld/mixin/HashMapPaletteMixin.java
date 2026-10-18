package me.nyxia.epicworld.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.chunk.HashMapPalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Listing a palette walked its whole id table, sized for every id the bit width allows (tens of thousands of slots for
 * a section packed from the global palette), on the server thread for every saved section. Palettes hand out ids
 * 0, 1, 2... in order, so listing ids below the size gives the same list; fall back if an id is missing.
 */
@Mixin(HashMapPalette.class)
public abstract class HashMapPaletteMixin<T> {
    @Shadow
    public abstract int getSize();

    @Shadow
    public abstract T valueFor(int id);

    private static final boolean DEBUG = Boolean.getBoolean("epicworld.debugPalettes");
    private static final java.util.concurrent.atomic.AtomicLong CALLS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MAX = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TOTAL = new java.util.concurrent.atomic.AtomicLong();

    @Inject(method = "getEntries", at = @At("HEAD"), cancellable = true)
    private void epicworld$denseEntries(CallbackInfoReturnable<List<T>> cir) {
        int size = getSize();
        if (DEBUG) {
            long n = CALLS.incrementAndGet();
            MAX.accumulateAndGet(size, Math::max);
            TOTAL.addAndGet(size);
            if (n % 20000 == 0) me.nyxia.epicworld.EpicWorld.LOGGER.info("palette entries: calls {}, max {}, avg {}", n, MAX.get(), TOTAL.get() / n);
        }
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            T value;
            try {
                value = valueFor(i);
            } catch (RuntimeException e) {
                return;
            }
            if (value == null) return;
            list.add(value);
        }
        cir.setReturnValue(list);
    }
}
