package me.nyxia.epicworld.block;

import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Limestone stalactite (pointing down) or stalagmite (pointing up). Decorative: it neither falls nor grows. */
public class LimestoneSpikeBlock extends Block implements SimpleWaterloggedBlock {
    public static final MapCodec<LimestoneSpikeBlock> CODEC = simpleCodec(LimestoneSpikeBlock::new);
    public static final DirectionProperty TIP_DIRECTION = BlockStateProperties.VERTICAL_DIRECTION;
    public static final EnumProperty<DripstoneThickness> THICKNESS = BlockStateProperties.DRIPSTONE_THICKNESS;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    private static final VoxelShape TIP_UP = Block.box(5, 0, 5, 11, 11, 11);
    private static final VoxelShape TIP_DOWN = Block.box(5, 5, 5, 11, 16, 11);
    private static final VoxelShape FRUSTUM = Block.box(4, 0, 4, 12, 16, 12);
    private static final VoxelShape MIDDLE = Block.box(3, 0, 3, 13, 16, 13);
    private static final VoxelShape BASE = Block.box(2, 0, 2, 14, 16, 14);

    public LimestoneSpikeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TIP_DIRECTION, Direction.UP)
                .setValue(THICKNESS, DripstoneThickness.TIP).setValue(WATERLOGGED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TIP_DIRECTION, THICKNESS, WATERLOGGED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(THICKNESS)) {
            case TIP_MERGE, TIP -> state.getValue(TIP_DIRECTION) == Direction.UP ? TIP_UP : TIP_DOWN;
            case FRUSTUM -> FRUSTUM;
            case MIDDLE -> MIDDLE;
            case BASE -> BASE;
        };
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction tip = state.getValue(TIP_DIRECTION);
        BlockPos supportPos = pos.relative(tip.getOpposite());
        BlockState support = level.getBlockState(supportPos);
        return support.is(this) && support.getValue(TIP_DIRECTION) == tip
                || support.isFaceSturdy(level, supportPos, tip);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction tip = context.getClickedFace() == Direction.DOWN ? Direction.DOWN : Direction.UP;
        BlockState state = defaultBlockState().setValue(TIP_DIRECTION, tip)
                .setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);
        return canSurvive(state, context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        if (!canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        return state;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }
}
