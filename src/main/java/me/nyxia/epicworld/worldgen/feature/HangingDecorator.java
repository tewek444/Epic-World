package me.nyxia.epicworld.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import me.nyxia.epicworld.block.HangingStrandBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecoratorType;

/** Strands hanging from the underside of a tree crown: willow branches and Spanish moss. */
public class HangingDecorator extends TreeDecorator {
    public static final MapCodec<HangingDecorator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BlockStateProvider.CODEC.fieldOf("strand").forGetter(d -> d.strand),
            Codec.floatRange(0, 1).fieldOf("probability").forGetter(d -> d.probability),
            Codec.intRange(1, 16).fieldOf("max_length").forGetter(d -> d.maxLength)).apply(i, HangingDecorator::new));

    private final BlockStateProvider strand;
    private final float probability;
    private final int maxLength;

    public HangingDecorator(BlockStateProvider strand, float probability, int maxLength) {
        this.strand = strand;
        this.probability = probability;
        this.maxLength = maxLength;
    }

    @Override
    protected TreeDecoratorType<?> type() {
        return EpicFeatureTypes.HANGING.get();
    }

    @Override
    public void place(Context context) {
        var random = context.random();
        for (BlockPos leaf : context.leaves()) {
            if (!context.isAir(leaf.below()) || random.nextFloat() >= probability) continue;
            int length = 1 + random.nextInt(maxLength);
            for (int i = 1; i <= length; i++) {
                BlockPos pos = leaf.below(i);
                if (!context.isAir(pos)) break;
                BlockState state = strand.getState(random, pos);
                if (state.hasProperty(HangingStrandBlock.TIP)) {
                    boolean last = i == length || !context.isAir(pos.below());
                    state = state.setValue(HangingStrandBlock.TIP, last);
                }
                context.setBlock(pos, state);
            }
        }
    }
}
