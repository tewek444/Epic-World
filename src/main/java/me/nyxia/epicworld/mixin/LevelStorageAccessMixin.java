package me.nyxia.epicworld.mixin;

import me.nyxia.epicworld.data.WorldSettingsStore;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells Epic World which save is being opened, so its generated data pack can use that save's settings. */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessMixin {
    @Shadow
    @Final
    LevelStorageSource.LevelDirectory levelDirectory;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void epicworld$open(CallbackInfo ci) {
        WorldSettingsStore.openWorld(levelDirectory.path());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void epicworld$close(CallbackInfo ci) {
        WorldSettingsStore.closeWorld(levelDirectory.path());
    }
}
