package me.nyxia.epicworld.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Sun-baked mud of dry river beds. Rain soaks it back into ordinary mud. */
public class CrackedMudBlock extends Block {
    public static final MapCodec<CrackedMudBlock> CODEC = simpleCodec(CrackedMudBlock::new);

    public CrackedMudBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.isRainingAt(pos.above())) level.setBlockAndUpdate(pos, Blocks.MUD.defaultBlockState());
    }
}
