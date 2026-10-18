package me.nyxia.epicworld.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Two-block dead reed of dry river banks: grows on dirt, sand, terracotta and dried or wet mud. */
public class DryReedsBlock extends DoublePlantBlock {
    public static final MapCodec<DryReedsBlock> CODEC = simpleCodec(DryReedsBlock::new);

    public DryReedsBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends DoublePlantBlock> codec() {
        return CODEC;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.TERRACOTTA)
                || state.is(Blocks.MUD) || state.is(ModBlocks.CRACKED_MUD.get());
    }
}
