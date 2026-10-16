package me.nyxia.epicworld.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A strand hanging from leaves, logs or a ceiling: Spanish moss in the willow swamps, glowing threads in caves.
 * {@code tip} marks the lowest block of a strand.
 */
public class HangingStrandBlock extends Block {
    public static final MapCodec<HangingStrandBlock> CODEC = simpleCodec(HangingStrandBlock::new);
    public static final BooleanProperty TIP = BooleanProperty.create("tip");
    private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 16, 13);

    public HangingStrandBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TIP, true));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TIP);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState above = level.getBlockState(pos.above());
        return above.is(this) || above.is(BlockTags.LEAVES) || above.is(BlockTags.LOGS)
                || above.isFaceSturdy(level, pos.above(), Direction.DOWN);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.UP && !canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
        if (direction == Direction.DOWN) return state.setValue(TIP, !neighbor.is(this));
        return state;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getLightEmission(level, pos) > 0 && random.nextInt(12) == 0) {
            level.addParticle(ParticleTypes.GLOW, pos.getX() + random.nextDouble(), pos.getY() + random.nextDouble(),
                    pos.getZ() + random.nextDouble(), 0, -0.01, 0);
        }
    }
}
