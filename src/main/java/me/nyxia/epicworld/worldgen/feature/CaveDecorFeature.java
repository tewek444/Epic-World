package me.nyxia.epicworld.worldgen.feature;

import com.mojang.serialization.Codec;
import me.nyxia.epicworld.block.HangingStrandBlock;
import me.nyxia.epicworld.block.LimestoneSpikeBlock;
import me.nyxia.epicworld.block.ModBlocks;
import me.nyxia.epicworld.worldgen.noise.Hash;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.material.Fluids;

/**
 * Lines every exposed cave surface of a cave biome in one chunk: marble bands in the marble caves, wet and mossy
 * limestone with glowworms, glowing threads and limestone spikes in the glowing caves.
 */
public class CaveDecorFeature extends Feature<CaveDecorFeature.Config> {
    public enum Kind implements StringRepresentable {
        MARBLE, GLOWING;

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Config(Kind kind) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = Kind.CODEC.fieldOf("kind").xmap(Config::new, Config::kind).codec();
    }

    public CaveDecorFeature() {
        super(Config.CODEC);
    }

    private static final Direction[] DIRECTIONS = Direction.values();

    @Override
    public boolean place(FeaturePlaceContext<Config> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        Holder<Biome> target = level.getBiome(origin);
        int baseX = origin.getX() & ~15;
        int baseZ = origin.getZ() & ~15;
        long seed = level.getSeed();
        var pos = new BlockPos.MutableBlockPos();
        var side = new BlockPos.MutableBlockPos();
        boolean marble = context.config().kind() == Kind.MARBLE;
        boolean placed = false;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx;
                int z = baseZ + dz;
                int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 8;
                boolean inBiome = false;
                for (int y = level.getMinBuildHeight() + 2; y < top; y++) {
                    if ((y & 3) == 0 || y == level.getMinBuildHeight() + 2) inBiome = level.getBiome(pos.set(x, y, z)).equals(target);
                    if (!inBiome) continue;
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(BlockTags.BASE_STONE_OVERWORLD)) continue;
                    Direction open = null;
                    boolean wet = false;
                    for (Direction d : DIRECTIONS) {
                        BlockState n = level.getBlockState(side.setWithOffset(pos, d));
                        if (n.isAir() || n.getFluidState().is(Fluids.WATER)) {
                            open = d;
                            wet |= n.getFluidState().is(Fluids.WATER);
                            if (d == Direction.DOWN && n.isAir()) break;
                        }
                    }
                    if (open == null) continue;
                    long h = Hash.cell3(seed, x, y, z, 404);
                    if (marble) {
                        level.setBlock(pos, marbleAt(x, y, z, h), 2);
                    } else {
                        BlockState lining = wet ? ModBlocks.WET_LIMESTONE.get().defaultBlockState()
                                : open == Direction.UP && Hash.unit(h, 1) < 0.35 ? ModBlocks.MOSSY_LIMESTONE.get().defaultBlockState()
                                : ModBlocks.LIMESTONE.get().defaultBlockState();
                        level.setBlock(pos, lining, 2);
                        decorateGlowing(level, pos, open, h);
                    }
                    placed = true;
                }
            }
        }
        return placed;
    }

    /** Wavy horizontal bands of white, grey and bluish marble with occasional veins. */
    private static BlockState marbleAt(int x, int y, int z, long h) {
        double wave = Math.sin(y * 0.45 + Math.sin(x * 0.07) * 2.2 + Math.cos(z * 0.06) * 2.2);
        if (Hash.unit(h, 2) < 0.04) return ModBlocks.VEINED_MARBLE.get().defaultBlockState();
        if (wave > 0.55) return ModBlocks.BLUE_MARBLE.get().defaultBlockState();
        if (wave < -0.45) return ModBlocks.GRAY_MARBLE.get().defaultBlockState();
        return Hash.unit(h, 3) < 0.06 ? ModBlocks.VEINED_MARBLE.get().defaultBlockState() : ModBlocks.MARBLE.get().defaultBlockState();
    }

    private static void decorateGlowing(WorldGenLevel level, BlockPos pos, Direction open, long h) {
        double roll = Hash.unit(h, 4);
        if (open == Direction.DOWN) {
            BlockPos below = pos.below();
            if (!level.getBlockState(below).isAir()) return;
            if (roll < 0.16) {
                level.setBlock(below, ModBlocks.GLOWWORMS.get().defaultBlockState(), 2);
            } else if (roll < 0.19) {
                int length = 2 + (int) (Hash.unit(h, 5) * 6);
                for (int i = 0; i < length; i++) {
                    BlockPos p = pos.below(i + 1);
                    if (!level.getBlockState(p).isAir()) break;
                    boolean tip = i == length - 1 || !level.getBlockState(p.below()).isAir();
                    level.setBlock(p, ModBlocks.GLOWING_THREADS.get().defaultBlockState().setValue(HangingStrandBlock.TIP, tip), 2);
                }
            } else if (roll < 0.20) {
                spike(level, below, Direction.DOWN, 1 + (int) (Hash.unit(h, 6) * 4));
            }
        } else if (open == Direction.UP && roll < 0.008) {
            spike(level, pos.above(), Direction.UP, 1 + (int) (Hash.unit(h, 6) * 4));
        }
    }

    private static void spike(WorldGenLevel level, BlockPos start, Direction tip, int length) {
        int n = 0;
        while (n < length && level.getBlockState(start.relative(tip, n)).isAir()) n++;
        if (n == 0) return;
        for (int i = 0; i < n; i++) {
            int fromTip = n - 1 - i;
            DripstoneThickness thickness = fromTip == 0 ? DripstoneThickness.TIP : fromTip == 1 ? DripstoneThickness.FRUSTUM
                    : i == 0 ? DripstoneThickness.BASE : DripstoneThickness.MIDDLE;
            level.setBlock(start.relative(tip, i), ModBlocks.LIMESTONE_SPIKE.get().defaultBlockState()
                    .setValue(LimestoneSpikeBlock.TIP_DIRECTION, tip).setValue(LimestoneSpikeBlock.THICKNESS, thickness), 2);
        }
    }
}
