package me.nyxia.epicworld.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Air above the terrain dominates noise fills, and every air block ran the full aquifer search: the three nearest of
 * twelve aquifer cells, their fluid statuses and barrier pressures. When all twelve cells around a block have the
 * global fluid status (one shared object, so equal fluid levels and zero pressure between them) and the block is at
 * or above that level, vanilla's answer is always air. Remember per cell neighbourhood whether that holds.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
public abstract class NoiseBasedAquiferMixin {
    @Shadow @Final private PositionalRandomFactory positionalRandomFactory;
    @Shadow @Final protected long[] aquiferLocationCache;
    @Shadow @Final private Aquifer.FluidPicker globalFluidPicker;
    @Shadow protected boolean shouldScheduleFluidUpdate;
    @Shadow @Final protected int minGridX;
    @Shadow @Final protected int minGridY;
    @Shadow @Final protected int minGridZ;
    @Shadow @Final protected int gridSizeX;
    @Shadow @Final protected int gridSizeZ;

    @Shadow protected abstract int getIndex(int gridX, int gridY, int gridZ);
    @Shadow protected abstract Aquifer.FluidStatus getAquiferStatus(long packedPos);

    /** Per (gridX, gridY, gridZ) of a block: 0 unknown, 1 all twelve cells global with this level, 2 not. */
    @Unique private byte[] epicworld$uniform;
    @Unique private Aquifer.FluidStatus[] epicworld$uniformStatus;

    @Inject(method = "computeSubstance", at = @At("HEAD"), cancellable = true)
    private void epicworld$uniformAir(DensityFunction.FunctionContext context, double substance, CallbackInfoReturnable<BlockState> cir) {
        if (substance > 0.0) return;
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();
        int gx = Math.floorDiv(x - 5, 16);
        int gy = Math.floorDiv(y + 1, 12);
        int gz = Math.floorDiv(z - 5, 16);
        int ix = gx - minGridX;
        int iy = gy - minGridY;
        int iz = gz - minGridZ;
        // The twelve cells are gx..gx+1, gy-1..gy+1, gz..gz+1; stay where vanilla's own cache index is valid.
        if (ix < 0 || ix + 1 >= gridSizeX || iz < 0 || iz + 1 >= gridSizeZ || iy < 1) return;
        int size = aquiferLocationCache.length;
        int key = getIndex(gx, gy, gz);
        if (key < 0 || key >= size || getIndex(gx + 1, gy + 1, gz + 1) >= size) return;
        if (epicworld$uniform == null) {
            epicworld$uniform = new byte[size];
            epicworld$uniformStatus = new Aquifer.FluidStatus[size];
        }
        byte known = epicworld$uniform[key];
        if (known == 0) {
            Aquifer.FluidStatus shared = null;
            boolean uniform = true;
            for (int dx = 0; dx <= 1 && uniform; dx++) {
                for (int dy = -1; dy <= 1 && uniform; dy++) {
                    for (int dz = 0; dz <= 1 && uniform; dz++) {
                        int cx = gx + dx;
                        int cy = gy + dy;
                        int cz = gz + dz;
                        int index = getIndex(cx, cy, cz);
                        long location = aquiferLocationCache[index];
                        if (location == Long.MAX_VALUE) {
                            RandomSource random = positionalRandomFactory.at(cx, cy, cz);
                            location = BlockPos.asLong(cx * 16 + random.nextInt(10), cy * 12 + random.nextInt(9), cz * 16 + random.nextInt(10));
                            aquiferLocationCache[index] = location;
                        }
                        Aquifer.FluidStatus status = getAquiferStatus(location);
                        Aquifer.FluidStatus global = globalFluidPicker.computeFluid(BlockPos.getX(location), BlockPos.getY(location),
                                BlockPos.getZ(location));
                        if (status != global || (shared != null && status != shared)) uniform = false;
                        shared = status;
                    }
                }
            }
            known = uniform ? (byte) 1 : (byte) 2;
            epicworld$uniform[key] = known;
            epicworld$uniformStatus[key] = shared;
        }
        if (known != 1) return;
        Aquifer.FluidStatus status = epicworld$uniformStatus[key];
        // Same object for every block of this neighbourhood: only blocks at or above its level are sure to be air.
        if (globalFluidPicker.computeFluid(x, y, z) != status || !status.at(y).isAir()) return;
        shouldScheduleFluidUpdate = false;
        cir.setReturnValue(Blocks.AIR.defaultBlockState());
    }
}
