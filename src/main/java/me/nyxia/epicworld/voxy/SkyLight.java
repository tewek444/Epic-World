package me.nyxia.epicworld.voxy;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * Sky light for LOD sections that arrive without light (sent by the server): straight down each column from the top,
 * dimmed by water, leaves and other translucent blocks and stopped by opaque ones, as Minecraft's own sky light does
 * vertically. Without it every block got full light 15, and water, foliage and grass looked brighter in the LODs than
 * after Voxy rebuilt them from the real chunk.
 */
public final class SkyLight {
    private SkyLight() {}

    private static final DataLayer DARK = new DataLayer(0);

    /** Light per section Y; sections below the depth where every column is dark share one dark layer. */
    public static Int2ObjectOpenHashMap<DataLayer> compute(List<LodPayloads.Section> sections) {
        var sorted = new ArrayList<>(sections);
        sorted.sort(Comparator.comparingInt(LodPayloads.Section::y).reversed());
        var result = new Int2ObjectOpenHashMap<DataLayer>(sorted.size());
        int[] light = new int[256];
        java.util.Arrays.fill(light, 15);
        boolean dark = false;
        for (var s : sorted) {
            if (dark) {
                result.put(s.y(), DARK);
                continue;
            }
            var layer = new DataLayer();
            var section = s.section();
            boolean any = false;
            for (int y = 15; y >= 0; y--) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int i = z << 4 | x;
                        int l = light[i];
                        if (l > 0) {
                            var state = section.getBlockState(x, y, z);
                            if (!state.isAir()) {
                                int opacity = state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                                if (opacity > 0) l = Math.max(0, l - opacity);
                                light[i] = l;
                            }
                        }
                        if (l > 0) {
                            layer.set(x, y, z, l);
                            any = true;
                        }
                    }
                }
            }
            result.put(s.y(), layer);
            dark = !any;
        }
        return result;
    }
}
