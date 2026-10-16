package me.nyxia.epicworld.block;

import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Short underwater grass of the willow swamps. Always submerged, like seagrass. */
public class WaterGrassBlock extends BushBlock implements LiquidBlockContainer {
    public static final MapCodec<WaterGrassBlock> CODEC = simpleCodec(WaterGrassBlock::new);
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 10, 14);

    public WaterGrassBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isFaceSturdy(level, pos, Direction.UP) && !state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        return fluid.is(FluidTags.WATER) && fluid.getAmount() == 8 ? super.getStateForPlacement(context) : null;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        BlockState updated = super.updateShape(state, direction, neighbor, level, pos, neighborPos);
        if (!updated.isAir()) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return updated;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }

    @Override
    public boolean canPlaceLiquid(@Nullable net.minecraft.world.entity.player.Player player, BlockGetter level, BlockPos pos,
                                  BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluid) {
        return false;
    }
}
