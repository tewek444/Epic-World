package me.nyxia.epicworld.dev;

import me.nyxia.epicworld.worldgen.EpicChunkGenerator;
import me.nyxia.epicworld.worldgen.TerrainEngine;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * {@code /epicworld check <x> <z> <radius>}: generates the chunks around a point and counts, in the real blocks, what
 * the height maps cannot show: river water blocked or missing, one-block needles on the ground, and thin floating
 * slabs (overhangs one block thick) near the surface.
 */
public final class TerrainCheck {
    private TerrainCheck() {}

    public static int run(CommandSourceStack source, int centerX, int centerZ, int radius) {
        ServerLevel level = source.getServer().overworld();
        TerrainEngine engine = EpicChunkGenerator.engine(level.getChunkSource().randomState());
        if (engine == null) {
            source.sendFailure(Component.literal("Not an Epic World overworld"));
            return 0;
        }
        int ccx = centerX >> 4;
        int ccz = centerZ >> 4;
        int side = (2 * radius + 1) * 16;
        int ox = (ccx - radius) * 16;
        int oz = (ccz - radius) * 16;
        int[] top = new int[side * side];
        int riverColumns = 0, riverMissing = 0, riverBlocked = 0, floating = 0, overhangColumns = 0;
        var pos = new BlockPos.MutableBlockPos();
        var examples = new java.util.ArrayList<String>();
        var kinds = new java.util.TreeMap<String, Integer>();
        for (int cx = ccx - radius; cx <= ccx + radius; cx++) {
            for (int cz = ccz - radius; cz <= ccz + radius; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, true);
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        int x = cx * 16 + dx;
                        int z = cz * 16 + dz;
                        var column = engine.column(x, z);
                        int expected = (int) Math.floor(column.height);
                        // Highest ground block (no plants, leaves or logs), searched from a little above the expected surface.
                        int y = expected + 40;
                        int ground = Integer.MIN_VALUE;
                        boolean slab = false;
                        for (; y > expected - 40; y--) {
                            BlockState s = chunk.getBlockState(pos.set(x, y, z));
                            if (isGround(s)) {
                                if (ground == Integer.MIN_VALUE) ground = y;
                                BlockState below = chunk.getBlockState(pos.set(x, y - 1, z));
                                if (below.isAir() && y > expected - 30) {
                                    floating++;
                                    BlockState above = chunk.getBlockState(pos.set(x, y + 1, z));
                                    if (!isGround(above)) slab = true;
                                }
                            }
                        }
                        if (slab) overhangColumns++;
                        top[(x - ox) * side + (z - oz)] = ground == Integer.MIN_VALUE ? expected : ground;
                        if (column.river && column.height < engine.sea - 1.5) {
                            riverColumns++;
                            BlockState w = chunk.getBlockState(pos.set(x, engine.sea - 1, z));
                            if (!w.getFluidState().isSource()) {
                                riverMissing++;
                                String k = "missing:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(w.getBlock()).getPath();
                                kinds.merge(k, 1, Integer::sum);
                                if (examples.size() < 12 && (x + z) % 7 == 0) examples.add(k + " at " + x + " " + (engine.sea - 1) + " " + z
                                        + " (h=" + String.format(java.util.Locale.ROOT, "%.1f", column.height) + ", tunnel " + column.tunnelKind + ")");
                            }
                            for (int yy = engine.sea; yy <= engine.sea + 4; yy++) {
                                BlockState b = chunk.getBlockState(pos.set(x, yy, z));
                                if (isGround(b)) {
                                    riverBlocked++;
                                    String k = "blocked:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath();
                                    kinds.merge(k, 1, Integer::sum);
                                    if (examples.size() < 24 && (x + z) % 5 == 0) examples.add(k + " at " + x + " " + yy + " " + z
                                            + " (h=" + String.format(java.util.Locale.ROOT, "%.1f", column.height) + ", tunnel " + column.tunnelKind + ")");
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        int needles = 0;
        int knives = 0;
        int land = 0;
        var thin = new java.util.ArrayList<String>();
        for (int i = 1; i < side - 1; i++) {
            for (int j = 1; j < side - 1; j++) {
                int h = top[i * side + j];
                if (h < engine.sea) continue;
                land++;
                int max = Integer.MIN_VALUE;
                for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) if (di != 0 || dj != 0) max = Math.max(max, top[(i + di) * side + j + dj]);
                if (h - max >= 3) {
                    needles++;
                    if (thin.size() < 10) thin.add("needle at " + (ox + i) + " " + h + " " + (oz + j) + " (" + (h - max) + " over neighbours)");
                }
                // A wall one block thick: higher than both neighbours along x or along z.
                int wx = Math.min(h - top[(i - 1) * side + j], h - top[(i + 1) * side + j]);
                int wz = Math.min(h - top[i * side + j - 1], h - top[i * side + j + 1]);
                if (Math.max(wx, wz) >= 4) {
                    knives++;
                    if (thin.size() < 20 && (i + j) % 11 == 0) thin.add("thin wall at " + (ox + i) + " " + h + " " + (oz + j) + " (" + Math.max(wx, wz)
                            + " over both sides, engine height " + String.format(java.util.Locale.ROOT, "%.1f", engine.column(ox + i, oz + j).height) + ")");
                }
            }
        }
        String report = String.format(java.util.Locale.ROOT,
                "check %d %d r%d: river columns %d, water missing %d, blocked above water %d; needles (>=3 over all neighbours) %d, thin walls %d of %d land;"
                        + " floating ground blocks %d, columns with a one-block-thick overhang %d",
                centerX, centerZ, radius, riverColumns, riverMissing, riverBlocked, needles, knives, land, floating, overhangColumns);
        me.nyxia.epicworld.EpicWorld.LOGGER.info("Epic World: {}", report);
        me.nyxia.epicworld.EpicWorld.LOGGER.info("Epic World: check kinds {}", kinds);
        for (String e : examples) me.nyxia.epicworld.EpicWorld.LOGGER.info("Epic World: check example {}", e);
        for (String e : thin) me.nyxia.epicworld.EpicWorld.LOGGER.info("Epic World: check relief {}", e);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static boolean isGround(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.FLOWERS)) return false;
        return s.blocksMotion();
    }
}
