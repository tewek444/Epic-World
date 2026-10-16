package me.nyxia.epicworld.block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import me.nyxia.epicworld.EpicWorld;
import me.nyxia.epicworld.registry.ModRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Blocks of the willow swamps, marble caves and glowing caves. */
public final class ModBlocks {
    private ModBlocks() {}

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EpicWorld.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EpicWorld.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EpicWorld.MODID);

    /** Every block with an item, in creative-tab order. */
    public static final List<DeferredBlock<? extends Block>> ALL = new ArrayList<>();

    public static final ResourceKey<ConfiguredFeature<?, ?>> WILLOW_TREE =
            ResourceKey.create(Registries.CONFIGURED_FEATURE, ModRegistries.id("willow"));
    public static final ResourceKey<ConfiguredFeature<?, ?>> HUGE_WILLOW_TREE =
            ResourceKey.create(Registries.CONFIGURED_FEATURE, ModRegistries.id("huge_willow"));
    private static final TreeGrower WILLOW_GROWER = new TreeGrower("epicworld_willow", 0.1f,
            Optional.empty(), Optional.empty(), Optional.of(WILLOW_TREE), Optional.of(HUGE_WILLOW_TREE), Optional.empty(), Optional.empty());

    // ---- willow swamp
    public static final DeferredBlock<RotatedPillarBlock> WILLOW_LOG = block("willow_log",
            p -> new RotatedPillarBlock(p), BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG).mapColor(MapColor.TERRACOTTA_GREEN));
    public static final DeferredBlock<Block> WILLOW_PLANKS = block("willow_planks",
            Block::new, BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS).mapColor(MapColor.TERRACOTTA_LIGHT_GREEN));
    public static final DeferredBlock<LeavesBlock> WILLOW_LEAVES = block("willow_leaves",
            LeavesBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LEAVES));
    public static final DeferredBlock<SaplingBlock> WILLOW_SAPLING = block("willow_sapling",
            p -> new SaplingBlock(WILLOW_GROWER, p), BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_SAPLING));
    public static final DeferredBlock<HangingStrandBlock> SPANISH_MOSS = block("spanish_moss",
            HangingStrandBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY).replaceable().noCollission()
                    .instabreak().sound(SoundType.MOSS_CARPET).pushReaction(PushReaction.DESTROY).ignitedByLava());
    public static final DeferredBlock<CattailBlock> CATTAIL = block("cattail",
            CattailBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.TALL_GRASS).mapColor(MapColor.COLOR_BROWN));
    public static final DeferredBlock<WaterGrassBlock> WATER_GRASS = block("water_grass",
            WaterGrassBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.SEAGRASS).mapColor(MapColor.WATER));
    public static final DeferredBlock<Block> SILT = block("silt",
            Block::new, BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BROWN).strength(0.5f).sound(SoundType.MUD));

    // ---- marble caves
    public static final DeferredBlock<Block> MARBLE = stone("marble", MapColor.QUARTZ);
    public static final DeferredBlock<Block> GRAY_MARBLE = stone("gray_marble", MapColor.COLOR_LIGHT_GRAY);
    public static final DeferredBlock<Block> BLUE_MARBLE = stone("blue_marble", MapColor.COLOR_LIGHT_BLUE);
    public static final DeferredBlock<Block> VEINED_MARBLE = stone("veined_marble", MapColor.QUARTZ);
    public static final DeferredBlock<Block> POLISHED_MARBLE = stone("polished_marble", MapColor.QUARTZ);
    public static final DeferredBlock<StairBlock> MARBLE_STAIRS = stairs("marble_stairs", MARBLE);
    public static final DeferredBlock<SlabBlock> MARBLE_SLAB = slab("marble_slab", MARBLE);
    public static final DeferredBlock<WallBlock> MARBLE_WALL = wall("marble_wall", MARBLE);
    public static final DeferredBlock<StairBlock> GRAY_MARBLE_STAIRS = stairs("gray_marble_stairs", GRAY_MARBLE);
    public static final DeferredBlock<SlabBlock> GRAY_MARBLE_SLAB = slab("gray_marble_slab", GRAY_MARBLE);
    public static final DeferredBlock<WallBlock> GRAY_MARBLE_WALL = wall("gray_marble_wall", GRAY_MARBLE);
    public static final DeferredBlock<StairBlock> BLUE_MARBLE_STAIRS = stairs("blue_marble_stairs", BLUE_MARBLE);
    public static final DeferredBlock<SlabBlock> BLUE_MARBLE_SLAB = slab("blue_marble_slab", BLUE_MARBLE);
    public static final DeferredBlock<WallBlock> BLUE_MARBLE_WALL = wall("blue_marble_wall", BLUE_MARBLE);
    public static final DeferredBlock<StairBlock> POLISHED_MARBLE_STAIRS = stairs("polished_marble_stairs", POLISHED_MARBLE);
    public static final DeferredBlock<SlabBlock> POLISHED_MARBLE_SLAB = slab("polished_marble_slab", POLISHED_MARBLE);
    public static final DeferredBlock<WallBlock> POLISHED_MARBLE_WALL = wall("polished_marble_wall", POLISHED_MARBLE);

    // ---- glowing caves
    public static final DeferredBlock<Block> LIMESTONE = stone("limestone", MapColor.SAND);
    public static final DeferredBlock<Block> WET_LIMESTONE = stone("wet_limestone", MapColor.TERRACOTTA_WHITE);
    public static final DeferredBlock<Block> MOSSY_LIMESTONE = stone("mossy_limestone", MapColor.TERRACOTTA_GREEN);
    public static final DeferredBlock<LimestoneSpikeBlock> LIMESTONE_SPIKE = block("limestone_spike",
            LimestoneSpikeBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.POINTED_DRIPSTONE).mapColor(MapColor.SAND)
                    .dynamicShape().offsetType(BlockBehaviour.OffsetType.XZ));
    public static final DeferredBlock<GlowwormBlock> GLOWWORMS = block("glowworms",
            GlowwormBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).noCollission().instabreak()
                    .sound(SoundType.GLOW_LICHEN).lightLevel(s -> 5).pushReaction(PushReaction.DESTROY).replaceable());
    public static final DeferredBlock<HangingStrandBlock> GLOWING_THREADS = block("glowing_threads",
            HangingStrandBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).noCollission().instabreak()
                    .sound(SoundType.CAVE_VINES).lightLevel(s -> 3).pushReaction(PushReaction.DESTROY).replaceable());

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("blocks", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.epicworld"))
            .icon(() -> new ItemStack(WILLOW_SAPLING.get()))
            .displayItems((parameters, output) -> CreativeSections.items().forEach(output::accept))
            .build());

    private static <B extends Block> DeferredBlock<B> block(String name, Function<BlockBehaviour.Properties, B> factory,
                                                           BlockBehaviour.Properties properties) {
        DeferredBlock<B> block = BLOCKS.register(name, () -> factory.apply(properties));
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
        ALL.add(block);
        return block;
    }

    private static DeferredBlock<Block> stone(String name, MapColor color) {
        return block(name, Block::new, BlockBehaviour.Properties.ofFullCopy(Blocks.CALCITE).mapColor(color).strength(1.5f, 6f));
    }

    private static DeferredBlock<StairBlock> stairs(String name, DeferredBlock<Block> base) {
        return block(name, p -> new StairBlock(base.get().defaultBlockState(), p), BlockBehaviour.Properties.ofFullCopy(Blocks.CALCITE).strength(1.5f, 6f));
    }

    private static DeferredBlock<SlabBlock> slab(String name, DeferredBlock<Block> base) {
        return block(name, SlabBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.CALCITE).strength(1.5f, 6f));
    }

    private static DeferredBlock<WallBlock> wall(String name, DeferredBlock<Block> base) {
        return block(name, WallBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.CALCITE).strength(1.5f, 6f).forceSolidOn());
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
    }
}
