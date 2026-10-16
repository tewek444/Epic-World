package me.nyxia.epicworld.mixin;

import me.nyxia.epicworld.worldgen.LegacyIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Features of new chunks never write into chunks that existed before the current Epic World settings. */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Shadow
    @Final
    private ChunkAccess center;

    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "ensureCanWrite", at = @At("HEAD"), cancellable = true)
    private void epicworld$protectLegacy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        int x = SectionPos.blockToSectionCoord(pos.getX());
        int z = SectionPos.blockToSectionCoord(pos.getZ());
        if (x == center.getPos().x && z == center.getPos().z) return;
        LegacyIndex index = LegacyIndex.current(level);
        if (index != null && index.isLegacy(x, z)) cir.setReturnValue(false);
    }
}
