package me.nyxia.epicworld.block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * Groups of the Epic World creative tab. Every group starts on a new row, preceded by an empty row that the client
 * draws over with a coloured header (see {@code CreativeTabSectionsMixin}).
 */
public enum CreativeSections {
    WILLOW_SWAMP(0xFF3F6B2A, () -> List.<net.minecraft.world.level.ItemLike>of(ModBlocks.WILLOW_LOG, ModBlocks.WILLOW_WOOD, ModBlocks.STRIPPED_WILLOW_LOG,
            ModBlocks.STRIPPED_WILLOW_WOOD, ModBlocks.WILLOW_PLANKS, ModBlocks.WILLOW_STAIRS, ModBlocks.WILLOW_SLAB, ModBlocks.WILLOW_FENCE,
            ModBlocks.WILLOW_FENCE_GATE, ModBlocks.WILLOW_DOOR, ModBlocks.WILLOW_TRAPDOOR, ModBlocks.WILLOW_PRESSURE_PLATE,
            ModBlocks.WILLOW_BUTTON, ModBlocks.WILLOW_SIGN_ITEM, ModBlocks.WILLOW_HANGING_SIGN_ITEM, ModBlocks.WILLOW_BOAT,
            ModBlocks.WILLOW_CHEST_BOAT, ModBlocks.WILLOW_LEAVES,
            ModBlocks.WILLOW_SAPLING, ModBlocks.SPANISH_MOSS, ModBlocks.CATTAIL, ModBlocks.REED, ModBlocks.CATTAIL_TORCH, ModBlocks.ROASTED_CATTAIL_ROOT,
            ModBlocks.WATER_GRASS, ModBlocks.SILT)),
    MARBLE_CAVES(0xFF5E7E92, () -> List.<net.minecraft.world.level.ItemLike>of(ModBlocks.MARBLE, ModBlocks.GRAY_MARBLE, ModBlocks.BLUE_MARBLE,
            ModBlocks.VEINED_MARBLE, ModBlocks.POLISHED_MARBLE, ModBlocks.MARBLE_PILLAR, ModBlocks.CHISELED_MARBLE, ModBlocks.MARBLE_BRICKS,
            ModBlocks.MARBLE_STAIRS, ModBlocks.MARBLE_SLAB, ModBlocks.MARBLE_WALL,
            ModBlocks.GRAY_MARBLE_STAIRS, ModBlocks.GRAY_MARBLE_SLAB, ModBlocks.GRAY_MARBLE_WALL,
            ModBlocks.BLUE_MARBLE_STAIRS, ModBlocks.BLUE_MARBLE_SLAB, ModBlocks.BLUE_MARBLE_WALL,
            ModBlocks.POLISHED_MARBLE_STAIRS, ModBlocks.POLISHED_MARBLE_SLAB, ModBlocks.POLISHED_MARBLE_WALL)),
    GLOWING_CAVES(0xFF1D3F63, () -> List.<net.minecraft.world.level.ItemLike>of(ModBlocks.LIMESTONE, ModBlocks.WET_LIMESTONE, ModBlocks.MOSSY_LIMESTONE,
            ModBlocks.LIMESTONE_SPIKE, ModBlocks.GLOWWORMS, ModBlocks.GLOWING_THREADS, ModBlocks.GLOWWORM_JAR, ModBlocks.SLAKED_LIME)),
    DRY_RIVERS(0xFF8A7458, () -> List.<net.minecraft.world.level.ItemLike>of(ModBlocks.CRACKED_MUD, ModBlocks.DRY_REEDS, ModBlocks.DRY_CATTAIL, ModBlocks.SILTSTONE,
            ModBlocks.POLISHED_SILTSTONE, ModBlocks.SILTSTONE_BRICKS,
            ModBlocks.SILTSTONE_STAIRS, ModBlocks.SILTSTONE_SLAB, ModBlocks.SILTSTONE_WALL,
            ModBlocks.POLISHED_SILTSTONE_STAIRS, ModBlocks.POLISHED_SILTSTONE_SLAB, ModBlocks.POLISHED_SILTSTONE_WALL,
            ModBlocks.SILTSTONE_BRICK_STAIRS, ModBlocks.SILTSTONE_BRICK_SLAB, ModBlocks.SILTSTONE_BRICK_WALL));

    public static final int PER_ROW = 9;

    /** Opaque ARGB colour of the header bar. */
    public final int color;
    private final Supplier<List<net.minecraft.world.level.ItemLike>> blocks;

    CreativeSections(int color, Supplier<List<net.minecraft.world.level.ItemLike>> blocks) {
        this.color = color;
        this.blocks = blocks;
    }

    public List<net.minecraft.world.level.ItemLike> blocks() {
        return blocks.get();
    }

    public Component title() {
        return Component.translatable("itemGroup.epicworld.section." + name().toLowerCase(java.util.Locale.ROOT));
    }

    public ItemStack icon() {
        return new ItemStack(blocks().get(0));
    }

    /** Tab contents in section order, without spacing. */
    public static List<ItemStack> items() {
        var list = new ArrayList<ItemStack>();
        for (var section : values()) section.blocks().forEach(b -> list.add(new ItemStack(b)));
        return list;
    }

    /** Total item count; the creative menu holds exactly this many entries before padding. */
    public static int itemCount() {
        int n = 0;
        for (var section : values()) n += section.blocks().size();
        return n;
    }

    /**
     * Inserts an empty header row before each section and fills each section's last row, so every section starts on
     * its own row. Returns the header row index of each section.
     */
    public static int[] pad(List<ItemStack> items) {
        int[] headerRows = new int[values().length];
        var padded = new ArrayList<ItemStack>();
        int index = 0;
        for (var section : values()) {
            headerRows[section.ordinal()] = padded.size() / PER_ROW;
            for (int i = 0; i < PER_ROW; i++) padded.add(ItemStack.EMPTY);
            int count = section.blocks().size();
            padded.addAll(items.subList(index, index + count));
            index += count;
            while (padded.size() % PER_ROW != 0) padded.add(ItemStack.EMPTY);
        }
        items.clear();
        items.addAll(padded);
        return headerRows;
    }
}
