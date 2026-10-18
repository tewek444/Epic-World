package me.nyxia.epicworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shutdown livelock (also without Epic World's speed-ups, e.g. with Voxy World Gen and many active tasks): while the
 * server stops, chunks are unloaded with unlimited time, and an unload task whose chunk still has a generation in
 * flight puts itself straight back into the queue it is being drained from. The loop never ends, and the generation
 * never finishes because it needs the server thread. Run at most the tasks that were queued when the drain began.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow @Final private Queue<Runnable> unloadQueue;

    @Unique private int epicworld$unloadBudget;

    @Inject(method = "processUnloads", at = @At("HEAD"))
    private void epicworld$budget(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        epicworld$unloadBudget = unloadQueue.size();
    }

    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE", target = "Ljava/util/Queue;poll()Ljava/lang/Object;"))
    private Object epicworld$boundedPoll(Queue<Runnable> queue, Operation<Object> original) {
        if (epicworld$unloadBudget-- <= 0) return null;
        return original.call(queue);
    }
}
