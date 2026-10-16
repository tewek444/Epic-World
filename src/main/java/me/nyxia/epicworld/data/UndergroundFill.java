package me.nyxia.epicworld.data;

import com.mojang.serialization.Codec;
import me.nyxia.epicworld.worldgen.TerrainEngine;
import me.nyxia.epicworld.worldgen.noise.Hash;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.Aquifer;

/**
 * Generates the ground under an old chunk that was lifted by a world conversion: the bottom {@code lift} blocks of the
 * world, which the old chunk no longer covers. Uses Epic World's own density functions (caves, aquifers, ore veins),
 * then keeps everything solid that would be above the Epic surface (no open sea or sky under the old chunk), and adds
 * deepslate, a bedrock floor and ore blobs. Runs without a level, before the world is loaded.
 */
public final class UndergroundFill {
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    /** Solid blocks kept between the generated ground and the old chunk's former floor. */
    private static final int SEAL = 6;

    private final RandomState random;
    private final NoiseGeneratorSettings settings;
    private final TerrainEngine engine;
    private final long seed;
    private final int minY;
    private final Aquifer.FluidPicker fluids;

    public UndergroundFill(RandomState random, NoiseGeneratorSettings settings, TerrainEngine engine, long seed) {
        this.random = random;
        this.settings = settings;
        this.engine = engine;
        this.seed = seed;
        this.minY = settings.noiseSettings().minY();
        var lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        var water = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        int sea = settings.seaLevel();
        this.fluids = (x, y, z) -> y < Math.min(-54, sea) ? lava : water;
    }

    /** Block states of the bottom {@code lift} blocks, index {@code x + 16*(z + 16*(y - minY))}. */
    public BlockState[] generate(int chunkX, int chunkZ, int lift) {
        var base = settings.noiseSettings();
        var noise = NoiseSettings.create(minY, lift, base.noiseSizeHorizontal(), base.noiseSizeVertical());
        int cellWidth = noise.getCellWidth();
        int cellHeight = noise.getCellHeight();
        int cellsXZ = 16 / cellWidth;
        int cellsY = lift / cellHeight;
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        var chunk = new NoiseChunk(cellsXZ, random, baseX, baseZ, noise, DensityFunctions.BeardifierMarker.INSTANCE,
                settings, fluids, Blender.empty());
        BlockState[] states = new BlockState[16 * 16 * lift];
        BlockState stone = settings.defaultBlock();
        chunk.initializeForFirstCellX();
        for (int cx = 0; cx < cellsXZ; cx++) {
            chunk.advanceCellX(cx);
            for (int cz = 0; cz < cellsXZ; cz++) {
                for (int cy = cellsY - 1; cy >= 0; cy--) {
                    chunk.selectCellYZ(cy, cz);
                    for (int dy = cellHeight - 1; dy >= 0; dy--) {
                        int y = minY + cy * cellHeight + dy;
                        chunk.updateForY(y, (double) dy / cellHeight);
                        for (int dx = 0; dx < cellWidth; dx++) {
                            int x = baseX + cx * cellWidth + dx;
                            chunk.updateForX(x, (double) dx / cellWidth);
                            for (int dz = 0; dz < cellWidth; dz++) {
                                int z = baseZ + cz * cellWidth + dz;
                                chunk.updateForZ(z, (double) dz / cellWidth);
                                BlockState state = chunk.getInterpolatedState();
                                states[index(x & 15, y, z & 15)] = state == null ? stone : state;
                            }
                        }
                    }
                }
            }
            chunk.swapSlices();
        }
        finish(states, chunkX, chunkZ, lift);
        return states;
    }

    private int index(int x, int y, int z) {
        return x + 16 * (z + 16 * (y - minY));
    }

    private void finish(BlockState[] states, int chunkX, int chunkZ, int lift) {
        BlockState stone = settings.defaultBlock();
        BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        int top = minY + lift;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int wx = chunkX * 16 + x;
                int wz = chunkZ * 16 + z;
                // Epic terrain here may be sea floor or sky below the old chunk's bottom: keep that part solid.
                int solidFrom = (int) Math.floor(engine.column(wx, wz).height) - 6;
                for (int y = minY; y < top; y++) {
                    int i = index(x, y, z);
                    BlockState s = states[i];
                    // Above the Epic surface, and in a seal under the old chunk's former floor so new caves and
                    // aquifers never open into the old caves above.
                    if ((y >= solidFrom || y >= top - SEAL) && (s.isAir() || !s.getFluidState().isEmpty())) s = stone;
                    long h = Hash.mix(seed, Hash.mix(wx * 31L + wz, y));
                    if (y == minY || (y < minY + 5 && Hash.unit(h, 0) < (minY + 5 - y) / 5.0)) {
                        s = bedrock;
                    } else if (s.is(Blocks.STONE) && (y < 0 || (y < 8 && Hash.unit(h, 1) < (8 - y) / 8.0))) {
                        s = deepslate;
                    }
                    states[i] = s;
                }
            }
        }
        ores(states, chunkX, chunkZ, lift);
    }

    /** Ore blobs roughly matching vanilla counts and heights (the noise only adds the large ore veins). */
    private void ores(BlockState[] states, int chunkX, int chunkZ, int lift) {
        int top = minY + lift - 1;
        long h = Hash.cell(seed, chunkX, chunkZ, 0x0E5);
        int salt = 0;
        salt = ore(states, h, salt, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 14, 0, top, 12);
        salt = ore(states, h, salt, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 10, -24, 56, 8);
        salt = ore(states, h, salt, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 10, minY, 72, 4);
        salt = ore(states, h, salt, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 12, -16, 96, 9);
        salt = ore(states, h, salt, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 4, minY, 32, 8);
        salt = ore(states, h, salt, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 6, minY, 15, 7);
        salt = ore(states, h, salt, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 6, minY, -32, 7);
        salt = ore(states, h, salt, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 3, -32, 32, 6);
        salt = ore(states, h, salt, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 7, minY, 16, 4);
        ore(states, h, salt, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 1, minY, 16, 10);
    }

    private int ore(BlockState[] states, long h, int salt, Block ore, Block deepOre, int count, int fromY, int toY, int size) {
        int top = minY + states.length / 256 - 1;
        fromY = Math.max(fromY, minY + 1);
        toY = Math.min(toY, top);
        for (int n = 0; n < count; n++, salt++) {
            if (toY <= fromY) continue;
            long k = Hash.mix(h, salt);
            int cx = (int) (Hash.unit(k, 0) * 16);
            int cz = (int) (Hash.unit(k, 1) * 16);
            // Triangular height distribution, denser in the middle of the range, as vanilla ores mostly are.
            double t = (Hash.unit(k, 2) + Hash.unit(k, 3)) / 2;
            int cy = fromY + (int) (t * (toY - fromY));
            int x = cx, y = cy, z = cz;
            for (int i = 0; i < size; i++) {
                if (x >= 0 && x < 16 && z >= 0 && z < 16 && y > minY && y <= top) {
                    int idx = index(x, y, z);
                    BlockState s = states[idx];
                    if (s.is(Blocks.STONE)) states[idx] = ore.defaultBlockState();
                    else if (s.is(Blocks.DEEPSLATE)) states[idx] = deepOre.defaultBlockState();
                }
                double r = Hash.unit(k, 10 + i);
                if (r < 1 / 3.0) x += Hash.unit(k, 40 + i) < 0.5 ? -1 : 1;
                else if (r < 2 / 3.0) z += Hash.unit(k, 40 + i) < 0.5 ? -1 : 1;
                else y += Hash.unit(k, 40 + i) < 0.5 ? -1 : 1;
            }
        }
        return salt;
    }

    /**
     * Section tags (block states only; biomes are copied by the caller) for the filled range, ordered from the bottom.
     */
    public CompoundTag[] sections(BlockState[] states, int lift) {
        int count = lift / 16;
        var out = new CompoundTag[count];
        for (int s = 0; s < count; s++) {
            var container = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                    PalettedContainer.Strategy.SECTION_STATES);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        container.getAndSetUnchecked(x, y, z, states[x + 16 * (z + 16 * (s * 16 + y))]);
                    }
                }
            }
            var tag = new CompoundTag();
            tag.putByte("Y", (byte) (minY / 16 + s));
            tag.put("block_states", STATES.encodeStart(NbtOps.INSTANCE, container).getOrThrow());
            out[s] = tag;
        }
        return out;
    }

    static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
}
