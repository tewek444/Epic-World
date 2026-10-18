package me.nyxia.epicworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * When a generated chunk becomes a level chunk, every fluid the aquifers marked is ticked once on the server thread.
 * A source whose sides and bottom are all sources of the same fluid or full solid blocks cannot spread, so its tick
 * does nothing: skip it. Only neighbours inside the chunk are looked at; at the edge the tick runs as usual.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    private static final Direction[] SPREAD = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    @WrapOperation(method = "postProcessGeneration", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/material/FluidState;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void epicworld$skipSettledSources(FluidState fluid, Level level, BlockPos pos, Operation<Void> original) {
        if (fluid.isSource() && settled((LevelChunk) (Object) this, fluid, pos)) return;
        original.call(fluid, level, pos);
    }

    private static boolean settled(LevelChunk chunk, FluidState fluid, BlockPos pos) {
        int x = pos.getX() & 15;
        int z = pos.getZ() & 15;
        if (x == 0 || x == 15 || z == 0 || z == 15 || pos.getY() <= chunk.getMinBuildHeight()) return false;
        var n = new BlockPos.MutableBlockPos();
        for (Direction d : SPREAD) {
            n.setWithOffset(pos, d);
            BlockState state = chunk.getBlockState(n);
            FluidState other = state.getFluidState();
            if (other.isSource() && other.getType().isSame(fluid.getType())) continue;
            if (other.isEmpty() && state.isSolidRender(chunk, n)) continue;
            return false;
        }
        return true;
    }
}
