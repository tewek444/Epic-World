package me.nyxia.epicworld.datagen;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import me.nyxia.epicworld.EpicWorld;
import me.nyxia.epicworld.block.CattailBlock;
import me.nyxia.epicworld.block.HangingStrandBlock;
import me.nyxia.epicworld.block.LimestoneSpikeBlock;
import me.nyxia.epicworld.block.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.common.data.LanguageProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** Assets and data that do not depend on world settings. Worldgen JSON is produced at runtime instead. */
public final class EpicDataGen {
    private EpicDataGen() {}

    public static void gather(GatherDataEvent event) {
        var generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        var lookup = event.getLookupProvider();
        var files = event.getExistingFileHelper();
        generator.addProvider(event.includeClient(), new Models(output, files));
        generator.addProvider(event.includeClient(), new Lang(output, "en_us"));
        generator.addProvider(event.includeClient(), new Lang(output, "ru_ru"));
        var blockTags = generator.addProvider(event.includeServer(), new Tags(output, lookup, files));
        generator.addProvider(event.includeServer(), new ItemTags_(output, lookup, blockTags.contentsGetter(), files));
        generator.addProvider(event.includeServer(), new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(Loot::new, LootContextParamSets.BLOCK)), lookup));
        generator.addProvider(event.includeServer(), new Recipes(output, lookup));
        generator.addProvider(event.includeServer(), new BiomeCompatibilityData(output));
        generator.addProvider(event.includeServer(), new VanillaCompatData(output));
        generator.addProvider(event.includeServer(), new TerrainReport());
    }

    private static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(EpicWorld.MODID, "block/" + name);
    }

    private static String name(Block block) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    static final class Models extends BlockStateProvider {
        Models(PackOutput output, ExistingFileHelper files) {
            super(output, EpicWorld.MODID, files);
        }

        private void cube(Block block) {
            simpleBlockWithItem(block, cubeAll(block));
        }

        private void flatItem(Block block, String texture) {
            itemModels().getBuilder(name(block)).parent(new ModelFile.UncheckedModelFile("item/generated")).texture("layer0", tex(texture));
        }

        private ModelFile cross(String name) {
            return models().cross(name, tex(name)).renderType("cutout");
        }

        @Override
        protected void registerStatesAndModels() {
            logBlock(ModBlocks.WILLOW_LOG.get());
            simpleBlockItem(ModBlocks.WILLOW_LOG.get(), models().getExistingFile(modLoc("block/willow_log")));
            cube(ModBlocks.WILLOW_PLANKS.get());
            simpleBlockWithItem(ModBlocks.WILLOW_LEAVES.get(), models().leaves("willow_leaves", tex("willow_leaves")).renderType("cutout_mipped"));
            simpleBlock(ModBlocks.WILLOW_SAPLING.get(), cross("willow_sapling"));
            flatItem(ModBlocks.WILLOW_SAPLING.get(), "willow_sapling");
            strands(ModBlocks.SPANISH_MOSS.get(), "spanish_moss");
            strands(ModBlocks.GLOWING_THREADS.get(), "glowing_threads");
            ModelFile cattailTop = cross("cattail_top");
            ModelFile cattailBottom = cross("cattail_bottom");
            getVariantBuilder(ModBlocks.CATTAIL.get()).forAllStatesExcept(state -> ConfiguredModel.builder()
                    .modelFile(state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? cattailTop : cattailBottom).build(),
                    CattailBlock.WATERLOGGED);
            flatItem(ModBlocks.CATTAIL.get(), "cattail_top");
            simpleBlock(ModBlocks.WATER_GRASS.get(), cross("water_grass"));
            flatItem(ModBlocks.WATER_GRASS.get(), "water_grass");
            cube(ModBlocks.SILT.get());
            for (var block : List.of(ModBlocks.MARBLE, ModBlocks.GRAY_MARBLE, ModBlocks.BLUE_MARBLE, ModBlocks.VEINED_MARBLE,
                    ModBlocks.POLISHED_MARBLE, ModBlocks.LIMESTONE, ModBlocks.WET_LIMESTONE, ModBlocks.MOSSY_LIMESTONE)) {
                cube(block.get());
            }
            family(ModBlocks.MARBLE_STAIRS.get(), ModBlocks.MARBLE_SLAB.get(), ModBlocks.MARBLE_WALL.get(), "marble");
            family(ModBlocks.GRAY_MARBLE_STAIRS.get(), ModBlocks.GRAY_MARBLE_SLAB.get(), ModBlocks.GRAY_MARBLE_WALL.get(), "gray_marble");
            family(ModBlocks.BLUE_MARBLE_STAIRS.get(), ModBlocks.BLUE_MARBLE_SLAB.get(), ModBlocks.BLUE_MARBLE_WALL.get(), "blue_marble");
            family(ModBlocks.POLISHED_MARBLE_STAIRS.get(), ModBlocks.POLISHED_MARBLE_SLAB.get(), ModBlocks.POLISHED_MARBLE_WALL.get(), "polished_marble");
            getVariantBuilder(ModBlocks.LIMESTONE_SPIKE.get()).forAllStatesExcept(state -> {
                String dir = state.getValue(LimestoneSpikeBlock.TIP_DIRECTION) == Direction.UP ? "up" : "down";
                String thickness = state.getValue(LimestoneSpikeBlock.THICKNESS).getSerializedName();
                String model = "limestone_spike_" + dir + "_" + thickness;
                return ConfiguredModel.builder().modelFile(models().withExistingParent(model, mcLoc("block/pointed_dripstone"))
                        .texture("cross", tex(model)).renderType("cutout")).build();
            }, LimestoneSpikeBlock.WATERLOGGED);
            flatItem(ModBlocks.LIMESTONE_SPIKE.get(), "limestone_spike_up_" + DripstoneThickness.TIP.getSerializedName());
            simpleBlock(ModBlocks.GLOWWORMS.get(), models().getExistingFile(modLoc("block/glowworms")));
            flatItem(ModBlocks.GLOWWORMS.get(), "glowworms");
        }

        private void strands(Block block, String name) {
            ModelFile middle = cross(name);
            ModelFile tip = cross(name + "_tip");
            getVariantBuilder(block).forAllStates(state -> ConfiguredModel.builder()
                    .modelFile(state.getValue(HangingStrandBlock.TIP) ? tip : middle).build());
            flatItem(block, name + "_tip");
        }

        private void family(StairBlock stairs, SlabBlock slab, WallBlock wall, String texture) {
            stairsBlock(stairs, tex(texture));
            simpleBlockItem(stairs, models().getExistingFile(modLoc("block/" + name(stairs))));
            slabBlock(slab, tex(texture), tex(texture));
            simpleBlockItem(slab, models().getExistingFile(modLoc("block/" + name(slab))));
            wallBlock(wall, tex(texture));
            itemModels().wallInventory(name(wall), tex(texture));
        }
    }

    static final class Lang extends LanguageProvider {
        private final boolean ru;

        Lang(PackOutput output, String locale) {
            super(output, EpicWorld.MODID, locale);
            this.ru = locale.equals("ru_ru");
        }

        private void block(net.neoforged.neoforge.registries.DeferredBlock<?> block, String en, String ruName) {
            add(block.get(), ru ? ruName : en);
        }

        @Override
        protected void addTranslations() {
            add("generator.epicworld.epic_world", "Epic World");
            add("itemGroup.epicworld", "Epic World");
            add("itemGroup.epicworld.section.willow_swamp", ru ? "Ивовые топи" : "Willow Swamp");
            add("itemGroup.epicworld.section.marble_caves", ru ? "Мраморные пещеры" : "Marble Caves");
            add("itemGroup.epicworld.section.glowing_caves", ru ? "Светящиеся пещеры" : "Glowing Caves");
            block(ModBlocks.WILLOW_LOG, "Willow Log", "Ивовое бревно");
            block(ModBlocks.WILLOW_PLANKS, "Willow Planks", "Ивовые доски");
            block(ModBlocks.WILLOW_LEAVES, "Willow Leaves", "Ивовая листва");
            block(ModBlocks.WILLOW_SAPLING, "Willow Sapling", "Саженец ивы");
            block(ModBlocks.SPANISH_MOSS, "Spanish Moss", "Испанский мох");
            block(ModBlocks.CATTAIL, "Cattail", "Рогоз");
            block(ModBlocks.WATER_GRASS, "Water Grass", "Водная трава");
            block(ModBlocks.SILT, "Silt", "Илистая почва");
            block(ModBlocks.MARBLE, "White Marble", "Белый мрамор");
            block(ModBlocks.GRAY_MARBLE, "Gray Marble", "Серый мрамор");
            block(ModBlocks.BLUE_MARBLE, "Bluish Marble", "Голубоватый мрамор");
            block(ModBlocks.VEINED_MARBLE, "Veined Marble", "Полосатый мрамор");
            block(ModBlocks.POLISHED_MARBLE, "Polished Marble", "Полированный мрамор");
            block(ModBlocks.MARBLE_STAIRS, "White Marble Stairs", "Ступени из белого мрамора");
            block(ModBlocks.MARBLE_SLAB, "White Marble Slab", "Плита из белого мрамора");
            block(ModBlocks.MARBLE_WALL, "White Marble Wall", "Стена из белого мрамора");
            block(ModBlocks.GRAY_MARBLE_STAIRS, "Gray Marble Stairs", "Ступени из серого мрамора");
            block(ModBlocks.GRAY_MARBLE_SLAB, "Gray Marble Slab", "Плита из серого мрамора");
            block(ModBlocks.GRAY_MARBLE_WALL, "Gray Marble Wall", "Стена из серого мрамора");
            block(ModBlocks.BLUE_MARBLE_STAIRS, "Bluish Marble Stairs", "Ступени из голубоватого мрамора");
            block(ModBlocks.BLUE_MARBLE_SLAB, "Bluish Marble Slab", "Плита из голубоватого мрамора");
            block(ModBlocks.BLUE_MARBLE_WALL, "Bluish Marble Wall", "Стена из голубоватого мрамора");
            block(ModBlocks.POLISHED_MARBLE_STAIRS, "Polished Marble Stairs", "Ступени из полированного мрамора");
            block(ModBlocks.POLISHED_MARBLE_SLAB, "Polished Marble Slab", "Плита из полированного мрамора");
            block(ModBlocks.POLISHED_MARBLE_WALL, "Polished Marble Wall", "Стена из полированного мрамора");
            block(ModBlocks.LIMESTONE, "Limestone", "Известняк");
            block(ModBlocks.WET_LIMESTONE, "Wet Limestone", "Влажный известняк");
            block(ModBlocks.MOSSY_LIMESTONE, "Mossy Limestone", "Мшистый известняк");
            block(ModBlocks.LIMESTONE_SPIKE, "Limestone Spike", "Известняковый сталактит");
            block(ModBlocks.GLOWWORMS, "Glowworms", "Светящиеся личинки");
            block(ModBlocks.GLOWING_THREADS, "Glowing Threads", "Светящиеся нити");
            for (var entry : BiomeNames.NAMES.entrySet()) {
                add("biome.epicworld." + entry.getKey(), ru ? entry.getValue()[1] : entry.getValue()[0]);
            }
        }
    }

    static final class Tags extends BlockTagsProvider {
        Tags(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup, ExistingFileHelper files) {
            super(output, lookup, EpicWorld.MODID, files);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            var stones = List.of(ModBlocks.MARBLE.get(), ModBlocks.GRAY_MARBLE.get(), ModBlocks.BLUE_MARBLE.get(),
                    ModBlocks.VEINED_MARBLE.get(), ModBlocks.POLISHED_MARBLE.get(), ModBlocks.LIMESTONE.get(),
                    ModBlocks.WET_LIMESTONE.get(), ModBlocks.MOSSY_LIMESTONE.get());
            var stairs = List.of(ModBlocks.MARBLE_STAIRS.get(), ModBlocks.GRAY_MARBLE_STAIRS.get(), ModBlocks.BLUE_MARBLE_STAIRS.get(), ModBlocks.POLISHED_MARBLE_STAIRS.get());
            var slabs = List.of(ModBlocks.MARBLE_SLAB.get(), ModBlocks.GRAY_MARBLE_SLAB.get(), ModBlocks.BLUE_MARBLE_SLAB.get(), ModBlocks.POLISHED_MARBLE_SLAB.get());
            var walls = List.of(ModBlocks.MARBLE_WALL.get(), ModBlocks.GRAY_MARBLE_WALL.get(), ModBlocks.BLUE_MARBLE_WALL.get(), ModBlocks.POLISHED_MARBLE_WALL.get());
            var pickaxe = tag(BlockTags.MINEABLE_WITH_PICKAXE);
            stones.forEach(pickaxe::add);
            stairs.forEach(pickaxe::add);
            slabs.forEach(pickaxe::add);
            walls.forEach(pickaxe::add);
            pickaxe.add(ModBlocks.LIMESTONE_SPIKE.get());
            stairs.forEach(tag(BlockTags.STAIRS)::add);
            slabs.forEach(tag(BlockTags.SLABS)::add);
            walls.forEach(tag(BlockTags.WALLS)::add);
            tag(BlockTags.MINEABLE_WITH_AXE).add(ModBlocks.WILLOW_LOG.get(), ModBlocks.WILLOW_PLANKS.get());
            tag(BlockTags.MINEABLE_WITH_HOE).add(ModBlocks.WILLOW_LEAVES.get(), ModBlocks.SPANISH_MOSS.get(), ModBlocks.GLOWING_THREADS.get());
            tag(BlockTags.MINEABLE_WITH_SHOVEL).add(ModBlocks.SILT.get());
            tag(BlockTags.LOGS_THAT_BURN).add(ModBlocks.WILLOW_LOG.get());
            tag(BlockTags.OVERWORLD_NATURAL_LOGS).add(ModBlocks.WILLOW_LOG.get());
            tag(BlockTags.PLANKS).add(ModBlocks.WILLOW_PLANKS.get());
            tag(BlockTags.LEAVES).add(ModBlocks.WILLOW_LEAVES.get());
            tag(BlockTags.SAPLINGS).add(ModBlocks.WILLOW_SAPLING.get());
            tag(BlockTags.DIRT).add(ModBlocks.SILT.get());
            tag(BlockTags.REPLACEABLE_BY_TREES).add(ModBlocks.SPANISH_MOSS.get(), ModBlocks.WATER_GRASS.get(), ModBlocks.CATTAIL.get(),
                    ModBlocks.GLOWING_THREADS.get());
            tag(BlockTags.OVERWORLD_CARVER_REPLACEABLES).add(ModBlocks.SILT.get()).add(stones.toArray(Block[]::new));
            tag(BlockTags.SWORD_EFFICIENT).add(ModBlocks.CATTAIL.get(), ModBlocks.SPANISH_MOSS.get());
        }
    }

    static final class ItemTags_ extends ItemTagsProvider {
        ItemTags_(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup,
                  CompletableFuture<TagLookup<Block>> blockTags, ExistingFileHelper files) {
            super(output, lookup, blockTags, EpicWorld.MODID, files);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            copy(BlockTags.LOGS_THAT_BURN, ItemTags.LOGS_THAT_BURN);
            copy(BlockTags.PLANKS, ItemTags.PLANKS);
            copy(BlockTags.LEAVES, ItemTags.LEAVES);
            copy(BlockTags.SAPLINGS, ItemTags.SAPLINGS);
            copy(BlockTags.STAIRS, ItemTags.STAIRS);
            copy(BlockTags.SLABS, ItemTags.SLABS);
            copy(BlockTags.WALLS, ItemTags.WALLS);
            copy(BlockTags.DIRT, ItemTags.DIRT);
        }
    }

    static final class Loot extends BlockLootSubProvider {
        Loot(HolderLookup.Provider registries) {
            super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
        }

        @Override
        protected void generate() {
            for (var holder : ModBlocks.ALL) {
                Block block = holder.get();
                if (block == ModBlocks.WILLOW_LEAVES.get()) {
                    add(block, createLeavesDrops(block, ModBlocks.WILLOW_SAPLING.get(), NORMAL_LEAVES_SAPLING_CHANCES));
                } else if (block instanceof SlabBlock) {
                    add(block, createSlabItemTable(block));
                } else if (block == ModBlocks.CATTAIL.get()) {
                    add(block, createSinglePropConditionTable(block, DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
                } else if (block == ModBlocks.SPANISH_MOSS.get() || block == ModBlocks.WATER_GRASS.get() || block == ModBlocks.GLOWING_THREADS.get()) {
                    add(block, createShearsOnlyDrop(block));
                } else if (block == ModBlocks.GLOWWORMS.get()) {
                    add(block, createSilkTouchOnlyTable(block));
                } else {
                    dropSelf(block);
                }
            }
        }

        @Override
        protected Iterable<Block> getKnownBlocks() {
            return ModBlocks.ALL.stream().map(h -> (Block) h.get()).toList();
        }
    }

    static final class Recipes extends RecipeProvider {
        Recipes(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
            super(output, lookup);
        }

        @Override
        protected void buildRecipes(RecipeOutput out) {
            ShapelessRecipeBuilder.shapeless(RecipeCategory.BUILDING_BLOCKS, ModBlocks.WILLOW_PLANKS.get(), 4)
                    .requires(ModBlocks.WILLOW_LOG.get()).group("planks").unlockedBy("has_log", has(ModBlocks.WILLOW_LOG.get())).save(out);
            ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.POLISHED_MARBLE.get(), 4)
                    .define('#', ModBlocks.MARBLE.get()).pattern("##").pattern("##")
                    .unlockedBy("has_marble", has(ModBlocks.MARBLE.get())).save(out);
            cutting(out, RecipeCategory.BUILDING_BLOCKS, ModBlocks.POLISHED_MARBLE.get(), ModBlocks.MARBLE.get(), 1);
            family(out, ModBlocks.MARBLE.get(), ModBlocks.MARBLE_STAIRS.get(), ModBlocks.MARBLE_SLAB.get(), ModBlocks.MARBLE_WALL.get());
            family(out, ModBlocks.GRAY_MARBLE.get(), ModBlocks.GRAY_MARBLE_STAIRS.get(), ModBlocks.GRAY_MARBLE_SLAB.get(), ModBlocks.GRAY_MARBLE_WALL.get());
            family(out, ModBlocks.BLUE_MARBLE.get(), ModBlocks.BLUE_MARBLE_STAIRS.get(), ModBlocks.BLUE_MARBLE_SLAB.get(), ModBlocks.BLUE_MARBLE_WALL.get());
            family(out, ModBlocks.POLISHED_MARBLE.get(), ModBlocks.POLISHED_MARBLE_STAIRS.get(), ModBlocks.POLISHED_MARBLE_SLAB.get(), ModBlocks.POLISHED_MARBLE_WALL.get());
        }

        private void family(RecipeOutput out, Block base, Block stairs, Block slab, Block wall) {
            stairBuilder(stairs, Ingredient.of(base)).unlockedBy("has_base", has(base)).save(out);
            slab(out, RecipeCategory.BUILDING_BLOCKS, slab, base);
            wall(out, RecipeCategory.DECORATIONS, wall, base);
            cutting(out, RecipeCategory.BUILDING_BLOCKS, stairs, base, 1);
            cutting(out, RecipeCategory.BUILDING_BLOCKS, slab, base, 2);
            cutting(out, RecipeCategory.DECORATIONS, wall, base, 1);
        }

        private void cutting(RecipeOutput out, RecipeCategory category, Block result, Block base, int count) {
            net.minecraft.data.recipes.SingleItemRecipeBuilder.stonecutting(Ingredient.of(base), category, result, count)
                    .unlockedBy("has_base", has(base))
                    .save(out, ResourceLocation.fromNamespaceAndPath(EpicWorld.MODID, name(result) + "_from_" + name(base) + "_stonecutting"));
        }
    }
}
