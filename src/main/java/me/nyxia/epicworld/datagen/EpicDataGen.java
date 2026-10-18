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
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
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
        generator.addProvider(event.includeServer(), new DataMaps(output, lookup));
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
            axisBlock(ModBlocks.WILLOW_WOOD.get(), tex("willow_log"), tex("willow_log"));
            simpleBlockItem(ModBlocks.WILLOW_WOOD.get(), models().getExistingFile(modLoc("block/willow_wood")));
            logBlock(ModBlocks.STRIPPED_WILLOW_LOG.get());
            simpleBlockItem(ModBlocks.STRIPPED_WILLOW_LOG.get(), models().getExistingFile(modLoc("block/stripped_willow_log")));
            axisBlock(ModBlocks.STRIPPED_WILLOW_WOOD.get(), tex("stripped_willow_log"), tex("stripped_willow_log"));
            simpleBlockItem(ModBlocks.STRIPPED_WILLOW_WOOD.get(), models().getExistingFile(modLoc("block/stripped_willow_wood")));
            var planks = tex("willow_planks");
            stairsBlock(ModBlocks.WILLOW_STAIRS.get(), planks);
            simpleBlockItem(ModBlocks.WILLOW_STAIRS.get(), models().getExistingFile(modLoc("block/willow_stairs")));
            slabBlock(ModBlocks.WILLOW_SLAB.get(), planks, planks);
            simpleBlockItem(ModBlocks.WILLOW_SLAB.get(), models().getExistingFile(modLoc("block/willow_slab")));
            fenceBlock(ModBlocks.WILLOW_FENCE.get(), planks);
            itemModels().fenceInventory("willow_fence", planks);
            fenceGateBlock(ModBlocks.WILLOW_FENCE_GATE.get(), planks);
            simpleBlockItem(ModBlocks.WILLOW_FENCE_GATE.get(), models().getExistingFile(modLoc("block/willow_fence_gate")));
            doorBlockWithRenderType(ModBlocks.WILLOW_DOOR.get(), tex("willow_door_bottom"), tex("willow_door_top"), "cutout");
            itemModels().basicItem(ModBlocks.WILLOW_DOOR.get().asItem());
            trapdoorBlockWithRenderType(ModBlocks.WILLOW_TRAPDOOR.get(), tex("willow_trapdoor"), true, "cutout");
            simpleBlockItem(ModBlocks.WILLOW_TRAPDOOR.get(), models().getExistingFile(modLoc("block/willow_trapdoor_bottom")));
            pressurePlateBlock(ModBlocks.WILLOW_PRESSURE_PLATE.get(), planks);
            simpleBlockItem(ModBlocks.WILLOW_PRESSURE_PLATE.get(), models().getExistingFile(modLoc("block/willow_pressure_plate")));
            buttonBlock(ModBlocks.WILLOW_BUTTON.get(), planks);
            itemModels().buttonInventory("willow_button", planks);
            signBlock(ModBlocks.WILLOW_SIGN.get(), ModBlocks.WILLOW_WALL_SIGN.get(), planks);
            hangingSignBlock(ModBlocks.WILLOW_HANGING_SIGN.get(), ModBlocks.WILLOW_WALL_HANGING_SIGN.get(), tex("stripped_willow_log"));
            for (var item : List.of(ModBlocks.WILLOW_SIGN_ITEM, ModBlocks.WILLOW_HANGING_SIGN_ITEM, ModBlocks.WILLOW_BOAT, ModBlocks.WILLOW_CHEST_BOAT)) {
                itemModels().basicItem(item.get());
            }
            simpleBlockWithItem(ModBlocks.WILLOW_LEAVES.get(), models().leaves("willow_leaves", tex("willow_leaves")).renderType("cutout_mipped"));
            simpleBlock(ModBlocks.WILLOW_SAPLING.get(), cross("willow_sapling"));
            flatItem(ModBlocks.WILLOW_SAPLING.get(), "willow_sapling");
            strands(ModBlocks.SPANISH_MOSS.get(), "spanish_moss");
            strands(ModBlocks.GLOWING_THREADS.get(), "glowing_threads");
            for (var plant : List.of(ModBlocks.CATTAIL, ModBlocks.REED)) {
                String name = name(plant.get());
                ModelFile top = cross(name + "_top");
                ModelFile bottom = cross(name + "_bottom");
                getVariantBuilder(plant.get()).forAllStatesExcept(state -> ConfiguredModel.builder()
                        .modelFile(state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? top : bottom).build(),
                        CattailBlock.WATERLOGGED);
                flatItem(plant.get(), name + "_top");
            }
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
            simpleBlockWithItem(ModBlocks.GLOWWORM_JAR.get(), models().getExistingFile(modLoc("block/glowworm_jar")));
            simpleBlock(ModBlocks.CATTAIL_TORCH.get(), models().getExistingFile(modLoc("block/cattail_torch")));
            flatItem(ModBlocks.CATTAIL_TORCH.get(), "cattail_torch");
            axisBlock(ModBlocks.MARBLE_PILLAR.get(), tex("marble_pillar"), tex("marble_pillar_top"));
            simpleBlockItem(ModBlocks.MARBLE_PILLAR.get(), models().getExistingFile(modLoc("block/marble_pillar")));
            cube(ModBlocks.CHISELED_MARBLE.get());
            cube(ModBlocks.MARBLE_BRICKS.get());
            itemModels().basicItem(ModBlocks.ROASTED_CATTAIL_ROOT.get());
            itemModels().basicItem(ModBlocks.SLAKED_LIME.get());
            for (var block : List.of(ModBlocks.CRACKED_MUD, ModBlocks.SILTSTONE, ModBlocks.POLISHED_SILTSTONE, ModBlocks.SILTSTONE_BRICKS)) {
                cube(block.get());
            }
            family(ModBlocks.SILTSTONE_STAIRS.get(), ModBlocks.SILTSTONE_SLAB.get(), ModBlocks.SILTSTONE_WALL.get(), "siltstone");
            family(ModBlocks.POLISHED_SILTSTONE_STAIRS.get(), ModBlocks.POLISHED_SILTSTONE_SLAB.get(), ModBlocks.POLISHED_SILTSTONE_WALL.get(), "polished_siltstone");
            family(ModBlocks.SILTSTONE_BRICK_STAIRS.get(), ModBlocks.SILTSTONE_BRICK_SLAB.get(), ModBlocks.SILTSTONE_BRICK_WALL.get(), "siltstone_bricks");
            for (var plant : List.of(ModBlocks.DRY_REEDS, ModBlocks.DRY_CATTAIL)) {
                String name = name(plant.get());
                ModelFile top = cross(name + "_top");
                ModelFile bottom = cross(name + "_bottom");
                getVariantBuilder(plant.get()).forAllStates(state -> ConfiguredModel.builder()
                        .modelFile(state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? top : bottom).build());
                flatItem(plant.get(), name + "_top");
            }
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
            for (String[] text : me.nyxia.epicworld.UiStrings.ALL) add(text[0], ru ? text[2] : text[1]);
            add("itemGroup.epicworld", "Epic World");
            add("itemGroup.epicworld.section.willow_swamp", ru ? "Ивовые топи" : "Willow Swamp");
            add("itemGroup.epicworld.section.marble_caves", ru ? "Мраморные пещеры" : "Marble Caves");
            add("itemGroup.epicworld.section.glowing_caves", ru ? "Светящиеся пещеры" : "Glowing Caves");
            add("itemGroup.epicworld.section.dry_rivers", ru ? "Пересохшие реки" : "Dry Rivers");
            block(ModBlocks.WILLOW_LOG, "Willow Log", "Ивовое бревно");
            block(ModBlocks.WILLOW_PLANKS, "Willow Planks", "Ивовые доски");
            block(ModBlocks.WILLOW_WOOD, "Willow Wood", "Ивовая древесина");
            block(ModBlocks.STRIPPED_WILLOW_LOG, "Stripped Willow Log", "Обтёсанное ивовое бревно");
            block(ModBlocks.STRIPPED_WILLOW_WOOD, "Stripped Willow Wood", "Обтёсанная ивовая древесина");
            block(ModBlocks.WILLOW_STAIRS, "Willow Stairs", "Ивовые ступеньки");
            block(ModBlocks.WILLOW_SLAB, "Willow Slab", "Ивовая плита");
            block(ModBlocks.WILLOW_FENCE, "Willow Fence", "Ивовый забор");
            block(ModBlocks.WILLOW_FENCE_GATE, "Willow Fence Gate", "Ивовая калитка");
            block(ModBlocks.WILLOW_DOOR, "Willow Door", "Ивовая дверь");
            block(ModBlocks.WILLOW_TRAPDOOR, "Willow Trapdoor", "Ивовый люк");
            block(ModBlocks.WILLOW_PRESSURE_PLATE, "Willow Pressure Plate", "Ивовая нажимная плита");
            block(ModBlocks.WILLOW_BUTTON, "Willow Button", "Ивовая кнопка");
            block(ModBlocks.WILLOW_SIGN, "Willow Sign", "Ивовая табличка");
            block(ModBlocks.WILLOW_HANGING_SIGN, "Willow Hanging Sign", "Ивовая подвесная табличка");
            add(ModBlocks.WILLOW_BOAT.get(), ru ? "Ивовая лодка" : "Willow Boat");
            add(ModBlocks.WILLOW_CHEST_BOAT.get(), ru ? "Ивовая лодка с сундуком" : "Willow Boat with Chest");
            block(ModBlocks.WILLOW_LEAVES, "Willow Leaves", "Ивовая листва");
            block(ModBlocks.WILLOW_SAPLING, "Willow Sapling", "Саженец ивы");
            block(ModBlocks.SPANISH_MOSS, "Spanish Moss", "Испанский мох");
            block(ModBlocks.CATTAIL, "Cattail", "Рогоз");
            block(ModBlocks.REED, "Reed", "Камыш");
            block(ModBlocks.DRY_CATTAIL, "Dry Cattail", "Сухой рогоз");
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
            block(ModBlocks.CRACKED_MUD, "Cracked Mud", "Потрескавшийся ил");
            block(ModBlocks.GLOWWORM_JAR, "Jar of Glowworms", "Банка со светлячками");
            block(ModBlocks.CATTAIL_TORCH, "Cattail Torch", "Камышовый факел");
            block(ModBlocks.MARBLE_PILLAR, "Marble Pillar", "Мраморная колонна");
            block(ModBlocks.CHISELED_MARBLE, "Chiseled Marble", "Резной мрамор");
            block(ModBlocks.MARBLE_BRICKS, "Marble Bricks", "Мраморные кирпичи");
            add(ModBlocks.ROASTED_CATTAIL_ROOT.get(), ru ? "Печёный корень рогоза" : "Roasted Cattail Root");
            add(ModBlocks.SLAKED_LIME.get(), ru ? "Гашёная известь" : "Slaked Lime");
            block(ModBlocks.DRY_REEDS, "Dry Reeds", "Сухой камыш");
            block(ModBlocks.SILTSTONE, "Siltstone", "Алевролит");
            block(ModBlocks.POLISHED_SILTSTONE, "Polished Siltstone", "Полированный алевролит");
            block(ModBlocks.SILTSTONE_BRICKS, "Siltstone Bricks", "Алевролитовые кирпичи");
            block(ModBlocks.SILTSTONE_STAIRS, "Siltstone Stairs", "Ступени из алевролита");
            block(ModBlocks.SILTSTONE_SLAB, "Siltstone Slab", "Плита из алевролита");
            block(ModBlocks.SILTSTONE_WALL, "Siltstone Wall", "Стена из алевролита");
            block(ModBlocks.POLISHED_SILTSTONE_STAIRS, "Polished Siltstone Stairs", "Ступени из полированного алевролита");
            block(ModBlocks.POLISHED_SILTSTONE_SLAB, "Polished Siltstone Slab", "Плита из полированного алевролита");
            block(ModBlocks.POLISHED_SILTSTONE_WALL, "Polished Siltstone Wall", "Стена из полированного алевролита");
            block(ModBlocks.SILTSTONE_BRICK_STAIRS, "Siltstone Brick Stairs", "Ступени из алевролитовых кирпичей");
            block(ModBlocks.SILTSTONE_BRICK_SLAB, "Siltstone Brick Slab", "Плита из алевролитовых кирпичей");
            block(ModBlocks.SILTSTONE_BRICK_WALL, "Siltstone Brick Wall", "Стена из алевролитовых кирпичей");
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
                    ModBlocks.WET_LIMESTONE.get(), ModBlocks.MOSSY_LIMESTONE.get(), ModBlocks.SILTSTONE.get(),
                    ModBlocks.MARBLE_PILLAR.get(), ModBlocks.CHISELED_MARBLE.get(), ModBlocks.MARBLE_BRICKS.get(),
                    ModBlocks.POLISHED_SILTSTONE.get(), ModBlocks.SILTSTONE_BRICKS.get());
            var stairs = List.of(ModBlocks.MARBLE_STAIRS.get(), ModBlocks.GRAY_MARBLE_STAIRS.get(), ModBlocks.BLUE_MARBLE_STAIRS.get(), ModBlocks.POLISHED_MARBLE_STAIRS.get(),
                    ModBlocks.SILTSTONE_STAIRS.get(), ModBlocks.POLISHED_SILTSTONE_STAIRS.get(), ModBlocks.SILTSTONE_BRICK_STAIRS.get());
            var slabs = List.of(ModBlocks.MARBLE_SLAB.get(), ModBlocks.GRAY_MARBLE_SLAB.get(), ModBlocks.BLUE_MARBLE_SLAB.get(), ModBlocks.POLISHED_MARBLE_SLAB.get(),
                    ModBlocks.SILTSTONE_SLAB.get(), ModBlocks.POLISHED_SILTSTONE_SLAB.get(), ModBlocks.SILTSTONE_BRICK_SLAB.get());
            var walls = List.of(ModBlocks.MARBLE_WALL.get(), ModBlocks.GRAY_MARBLE_WALL.get(), ModBlocks.BLUE_MARBLE_WALL.get(), ModBlocks.POLISHED_MARBLE_WALL.get(),
                    ModBlocks.SILTSTONE_WALL.get(), ModBlocks.POLISHED_SILTSTONE_WALL.get(), ModBlocks.SILTSTONE_BRICK_WALL.get());
            var pickaxe = tag(BlockTags.MINEABLE_WITH_PICKAXE);
            stones.forEach(pickaxe::add);
            stairs.forEach(pickaxe::add);
            slabs.forEach(pickaxe::add);
            walls.forEach(pickaxe::add);
            pickaxe.add(ModBlocks.LIMESTONE_SPIKE.get());
            stairs.forEach(tag(BlockTags.STAIRS)::add);
            slabs.forEach(tag(BlockTags.SLABS)::add);
            walls.forEach(tag(BlockTags.WALLS)::add);
            var willowLogs = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(EpicWorld.MODID, "willow_logs"));
            tag(willowLogs).add(ModBlocks.WILLOW_LOG.get(), ModBlocks.WILLOW_WOOD.get(), ModBlocks.STRIPPED_WILLOW_LOG.get(),
                    ModBlocks.STRIPPED_WILLOW_WOOD.get());
            tag(BlockTags.LOGS_THAT_BURN).addTag(willowLogs);
            tag(BlockTags.MINEABLE_WITH_AXE).add(ModBlocks.WILLOW_LOG.get(), ModBlocks.WILLOW_PLANKS.get(), ModBlocks.WILLOW_WOOD.get(),
                    ModBlocks.STRIPPED_WILLOW_LOG.get(), ModBlocks.STRIPPED_WILLOW_WOOD.get(), ModBlocks.WILLOW_STAIRS.get(),
                    ModBlocks.WILLOW_SLAB.get(), ModBlocks.WILLOW_FENCE.get(), ModBlocks.WILLOW_FENCE_GATE.get(), ModBlocks.WILLOW_DOOR.get(),
                    ModBlocks.WILLOW_TRAPDOOR.get(), ModBlocks.WILLOW_PRESSURE_PLATE.get(), ModBlocks.WILLOW_BUTTON.get(),
                    ModBlocks.WILLOW_SIGN.get(), ModBlocks.WILLOW_WALL_SIGN.get(), ModBlocks.WILLOW_HANGING_SIGN.get(),
                    ModBlocks.WILLOW_WALL_HANGING_SIGN.get());
            tag(BlockTags.WOODEN_STAIRS).add(ModBlocks.WILLOW_STAIRS.get());
            tag(BlockTags.WOODEN_SLABS).add(ModBlocks.WILLOW_SLAB.get());
            tag(BlockTags.WOODEN_FENCES).add(ModBlocks.WILLOW_FENCE.get());
            tag(BlockTags.FENCE_GATES).add(ModBlocks.WILLOW_FENCE_GATE.get());
            tag(BlockTags.WOODEN_DOORS).add(ModBlocks.WILLOW_DOOR.get());
            tag(BlockTags.WOODEN_TRAPDOORS).add(ModBlocks.WILLOW_TRAPDOOR.get());
            tag(BlockTags.WOODEN_PRESSURE_PLATES).add(ModBlocks.WILLOW_PRESSURE_PLATE.get());
            tag(BlockTags.WOODEN_BUTTONS).add(ModBlocks.WILLOW_BUTTON.get());
            tag(BlockTags.STANDING_SIGNS).add(ModBlocks.WILLOW_SIGN.get());
            tag(BlockTags.WALL_SIGNS).add(ModBlocks.WILLOW_WALL_SIGN.get());
            tag(BlockTags.CEILING_HANGING_SIGNS).add(ModBlocks.WILLOW_HANGING_SIGN.get());
            tag(BlockTags.WALL_HANGING_SIGNS).add(ModBlocks.WILLOW_WALL_HANGING_SIGN.get());
            tag(BlockTags.MINEABLE_WITH_HOE).add(ModBlocks.WILLOW_LEAVES.get(), ModBlocks.SPANISH_MOSS.get(), ModBlocks.GLOWING_THREADS.get());
            tag(BlockTags.MINEABLE_WITH_SHOVEL).add(ModBlocks.SILT.get(), ModBlocks.CRACKED_MUD.get());

            tag(BlockTags.OVERWORLD_NATURAL_LOGS).add(ModBlocks.WILLOW_LOG.get());
            tag(BlockTags.PLANKS).add(ModBlocks.WILLOW_PLANKS.get());
            tag(BlockTags.LEAVES).add(ModBlocks.WILLOW_LEAVES.get());
            tag(BlockTags.SAPLINGS).add(ModBlocks.WILLOW_SAPLING.get());
            tag(BlockTags.DIRT).add(ModBlocks.SILT.get(), ModBlocks.CRACKED_MUD.get());
            // A water bottle soaks cracked mud back into mud, as it does with dirt.
            tag(BlockTags.CONVERTABLE_TO_MUD).add(ModBlocks.CRACKED_MUD.get());
            tag(BlockTags.REPLACEABLE_BY_TREES).add(ModBlocks.SPANISH_MOSS.get(), ModBlocks.WATER_GRASS.get(), ModBlocks.CATTAIL.get(),
                    ModBlocks.GLOWING_THREADS.get(), ModBlocks.DRY_REEDS.get(), ModBlocks.REED.get(), ModBlocks.DRY_CATTAIL.get());
            tag(BlockTags.OVERWORLD_CARVER_REPLACEABLES).add(ModBlocks.SILT.get(), ModBlocks.CRACKED_MUD.get()).add(stones.toArray(Block[]::new));
            tag(BlockTags.SWORD_EFFICIENT).add(ModBlocks.CATTAIL.get(), ModBlocks.SPANISH_MOSS.get(), ModBlocks.DRY_REEDS.get(),
                    ModBlocks.REED.get(), ModBlocks.DRY_CATTAIL.get());
        }
    }

    static final class ItemTags_ extends ItemTagsProvider {
        ItemTags_(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup,
                  CompletableFuture<TagLookup<Block>> blockTags, ExistingFileHelper files) {
            super(output, lookup, blockTags, EpicWorld.MODID, files);
        }

        @Override
        protected void addTags(HolderLookup.Provider provider) {
            copy(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                            ResourceLocation.fromNamespaceAndPath(EpicWorld.MODID, "willow_logs")),
                    net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                            ResourceLocation.fromNamespaceAndPath(EpicWorld.MODID, "willow_logs")));
            copy(BlockTags.LOGS_THAT_BURN, ItemTags.LOGS_THAT_BURN);
            copy(BlockTags.WOODEN_STAIRS, ItemTags.WOODEN_STAIRS);
            copy(BlockTags.WOODEN_SLABS, ItemTags.WOODEN_SLABS);
            copy(BlockTags.WOODEN_FENCES, ItemTags.WOODEN_FENCES);
            copy(BlockTags.FENCE_GATES, ItemTags.FENCE_GATES);
            copy(BlockTags.WOODEN_DOORS, ItemTags.WOODEN_DOORS);
            copy(BlockTags.WOODEN_TRAPDOORS, ItemTags.WOODEN_TRAPDOORS);
            copy(BlockTags.WOODEN_PRESSURE_PLATES, ItemTags.WOODEN_PRESSURE_PLATES);
            copy(BlockTags.WOODEN_BUTTONS, ItemTags.WOODEN_BUTTONS);
            tag(ItemTags.SIGNS).add(ModBlocks.WILLOW_SIGN_ITEM.get());
            tag(ItemTags.HANGING_SIGNS).add(ModBlocks.WILLOW_HANGING_SIGN_ITEM.get());
            tag(ItemTags.BOATS).add(ModBlocks.WILLOW_BOAT.get());
            tag(ItemTags.CHEST_BOATS).add(ModBlocks.WILLOW_CHEST_BOAT.get());
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
            dropSelf(ModBlocks.WILLOW_SIGN.get());
            dropSelf(ModBlocks.WILLOW_HANGING_SIGN.get());
            for (var holder : ModBlocks.ALL) {
                Block block = holder.get();
                if (block == ModBlocks.WILLOW_LEAVES.get()) {
                    add(block, createLeavesDrops(block, ModBlocks.WILLOW_SAPLING.get(), NORMAL_LEAVES_SAPLING_CHANCES));
                } else if (block instanceof net.minecraft.world.level.block.DoorBlock) {
                    add(block, createDoorTable(block));
                } else if (block instanceof SlabBlock) {
                    add(block, createSlabItemTable(block));
                } else if (block instanceof DoublePlantBlock) {
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
            var known = new java.util.ArrayList<Block>(ModBlocks.ALL.stream().map(h -> (Block) h.get()).toList());
            known.add(ModBlocks.WILLOW_SIGN.get());
            known.add(ModBlocks.WILLOW_HANGING_SIGN.get());
            return known;
        }
    }

    /** Furnace fuels and compostables. */
    static final class DataMaps extends net.neoforged.neoforge.common.data.DataMapProvider {
        DataMaps(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
            super(output, lookup);
        }

        @Override
        protected void gather() {
            // Dry reeds and cattails burn, but quickly (half an item each).
            var fuels = builder(net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps.FURNACE_FUELS);
            for (var plant : List.of(ModBlocks.DRY_REEDS, ModBlocks.DRY_CATTAIL)) {
                fuels.add(plant.get().asItem().builtInRegistryHolder(), new net.neoforged.neoforge.registries.datamaps.builtin.FurnaceFuel(100), false);
            }
            var compost = builder(net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps.COMPOSTABLES);
            for (var plant : List.of(ModBlocks.SPANISH_MOSS, ModBlocks.WATER_GRASS, ModBlocks.CATTAIL, ModBlocks.REED,
                    ModBlocks.DRY_REEDS, ModBlocks.DRY_CATTAIL, ModBlocks.WILLOW_LEAVES, ModBlocks.WILLOW_SAPLING)) {
                compost.add(plant.get().asItem().builtInRegistryHolder(), new net.neoforged.neoforge.registries.datamaps.builtin.Compostable(0.3f), false);
            }
        }
    }

    static final class Recipes extends RecipeProvider {
        Recipes(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup) {
            super(output, lookup);
        }

        @Override
        protected void buildRecipes(RecipeOutput out) {
            var willowLogs = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath(EpicWorld.MODID, "willow_logs"));
            planksFromLogs(out, ModBlocks.WILLOW_PLANKS.get(), willowLogs, 4);
            woodFromLogs(out, ModBlocks.WILLOW_WOOD.get(), ModBlocks.WILLOW_LOG.get());
            woodFromLogs(out, ModBlocks.STRIPPED_WILLOW_WOOD.get(), ModBlocks.STRIPPED_WILLOW_LOG.get());
            var planks = ModBlocks.WILLOW_PLANKS.get();
            var hasPlanks = has(planks);
            stairBuilder(ModBlocks.WILLOW_STAIRS.get(), Ingredient.of(planks)).group("wooden_stairs").unlockedBy("has_planks", hasPlanks).save(out);
            slab(out, RecipeCategory.BUILDING_BLOCKS, ModBlocks.WILLOW_SLAB.get(), planks);
            fenceBuilder(ModBlocks.WILLOW_FENCE.get(), Ingredient.of(planks)).group("wooden_fence").unlockedBy("has_planks", hasPlanks).save(out);
            fenceGateBuilder(ModBlocks.WILLOW_FENCE_GATE.get(), Ingredient.of(planks)).group("wooden_fence_gate").unlockedBy("has_planks", hasPlanks).save(out);
            doorBuilder(ModBlocks.WILLOW_DOOR.get(), Ingredient.of(planks)).group("wooden_door").unlockedBy("has_planks", hasPlanks).save(out);
            trapdoorBuilder(ModBlocks.WILLOW_TRAPDOOR.get(), Ingredient.of(planks)).group("wooden_trapdoor").unlockedBy("has_planks", hasPlanks).save(out);
            pressurePlate(out, ModBlocks.WILLOW_PRESSURE_PLATE.get(), planks);
            buttonBuilder(ModBlocks.WILLOW_BUTTON.get(), Ingredient.of(planks)).group("wooden_button").unlockedBy("has_planks", hasPlanks).save(out);
            signBuilder(ModBlocks.WILLOW_SIGN_ITEM.get(), Ingredient.of(planks)).group("wooden_sign").unlockedBy("has_planks", hasPlanks).save(out);
            hangingSign(out, ModBlocks.WILLOW_HANGING_SIGN_ITEM.get(), ModBlocks.STRIPPED_WILLOW_LOG.get());
            woodenBoat(out, ModBlocks.WILLOW_BOAT.get(), planks);
            chestBoat(out, ModBlocks.WILLOW_CHEST_BOAT.get(), ModBlocks.WILLOW_BOAT.get());
            moreRecipes(out);
            ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.POLISHED_MARBLE.get(), 4)
                    .define('#', ModBlocks.MARBLE.get()).pattern("##").pattern("##")
                    .unlockedBy("has_marble", has(ModBlocks.MARBLE.get())).save(out);
            cutting(out, RecipeCategory.BUILDING_BLOCKS, ModBlocks.POLISHED_MARBLE.get(), ModBlocks.MARBLE.get(), 1);
            family(out, ModBlocks.MARBLE.get(), ModBlocks.MARBLE_STAIRS.get(), ModBlocks.MARBLE_SLAB.get(), ModBlocks.MARBLE_WALL.get());
            family(out, ModBlocks.GRAY_MARBLE.get(), ModBlocks.GRAY_MARBLE_STAIRS.get(), ModBlocks.GRAY_MARBLE_SLAB.get(), ModBlocks.GRAY_MARBLE_WALL.get());
            family(out, ModBlocks.BLUE_MARBLE.get(), ModBlocks.BLUE_MARBLE_STAIRS.get(), ModBlocks.BLUE_MARBLE_SLAB.get(), ModBlocks.BLUE_MARBLE_WALL.get());
            family(out, ModBlocks.POLISHED_MARBLE.get(), ModBlocks.POLISHED_MARBLE_STAIRS.get(), ModBlocks.POLISHED_MARBLE_SLAB.get(), ModBlocks.POLISHED_MARBLE_WALL.get());
            ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.POLISHED_SILTSTONE.get(), 4)
                    .define('#', ModBlocks.SILTSTONE.get()).pattern("##").pattern("##")
                    .unlockedBy("has_siltstone", has(ModBlocks.SILTSTONE.get())).save(out);
            ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.SILTSTONE_BRICKS.get(), 4)
                    .define('#', ModBlocks.POLISHED_SILTSTONE.get()).pattern("##").pattern("##")
                    .unlockedBy("has_polished_siltstone", has(ModBlocks.POLISHED_SILTSTONE.get())).save(out);
            cutting(out, RecipeCategory.BUILDING_BLOCKS, ModBlocks.POLISHED_SILTSTONE.get(), ModBlocks.SILTSTONE.get(), 1);
            cutting(out, RecipeCategory.BUILDING_BLOCKS, ModBlocks.SILTSTONE_BRICKS.get(), ModBlocks.SILTSTONE.get(), 1);
            cutting(out, RecipeCategory.BUILDING_BLOCKS, ModBlocks.SILTSTONE_BRICKS.get(), ModBlocks.POLISHED_SILTSTONE.get(), 1);
            family(out, ModBlocks.SILTSTONE.get(), ModBlocks.SILTSTONE_STAIRS.get(), ModBlocks.SILTSTONE_SLAB.get(), ModBlocks.SILTSTONE_WALL.get());
            family(out, ModBlocks.POLISHED_SILTSTONE.get(), ModBlocks.POLISHED_SILTSTONE_STAIRS.get(), ModBlocks.POLISHED_SILTSTONE_SLAB.get(), ModBlocks.POLISHED_SILTSTONE_WALL.get());
            family(out, ModBlocks.SILTSTONE_BRICKS.get(), ModBlocks.SILTSTONE_BRICK_STAIRS.get(), ModBlocks.SILTSTONE_BRICK_SLAB.get(), ModBlocks.SILTSTONE_BRICK_WALL.get());
        }

        private void moreRecipes(RecipeOutput out) {
            var silt = ModBlocks.SILT.get();
            ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.SILTSTONE.get())
                    .define('#', silt).pattern("##").pattern("##").unlockedBy("has_silt", has(silt)).save(out);
            SimpleCookingRecipeBuilder.smelting(Ingredient.of(silt), RecipeCategory.MISC, net.minecraft.world.item.Items.BRICK, 0.3f, 200)
                    .unlockedBy("has_silt", has(silt)).save(out, id("brick_from_silt"));
            ShapelessRecipeBuilder.shapeless(RecipeCategory.BUILDING_BLOCKS, net.minecraft.world.level.block.Blocks.PACKED_MUD)
                    .requires(ModBlocks.CRACKED_MUD.get()).requires(net.minecraft.world.item.Items.WHEAT)
                    .unlockedBy("has_cracked_mud", has(ModBlocks.CRACKED_MUD.get())).save(out, id("packed_mud_from_cracked_mud"));
            var cattail = ModBlocks.CATTAIL.get();
            ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, net.minecraft.world.item.Items.BROWN_DYE).requires(cattail)
                    .group("brown_dye").unlockedBy("has_cattail", has(cattail)).save(out, id("brown_dye_from_cattail"));
            for (var cooking : List.<String>of("smelting", "smoking", "campfire_cooking")) {
                var builder = switch (cooking) {
                    case "smelting" -> SimpleCookingRecipeBuilder.smelting(Ingredient.of(cattail), RecipeCategory.FOOD,
                            ModBlocks.ROASTED_CATTAIL_ROOT.get(), 0.35f, 200);
                    case "smoking" -> SimpleCookingRecipeBuilder.smoking(Ingredient.of(cattail), RecipeCategory.FOOD,
                            ModBlocks.ROASTED_CATTAIL_ROOT.get(), 0.35f, 100);
                    default -> SimpleCookingRecipeBuilder.campfireCooking(Ingredient.of(cattail), RecipeCategory.FOOD,
                            ModBlocks.ROASTED_CATTAIL_ROOT.get(), 0.35f, 600);
                };
                builder.unlockedBy("has_cattail", has(cattail)).save(out, id("roasted_cattail_root_from_" + cooking));
            }
            ShapedRecipeBuilder.shaped(RecipeCategory.DECORATIONS, ModBlocks.CATTAIL_TORCH.get(), 2)
                    .define('C', net.minecraft.tags.ItemTags.COALS).define('#', cattail).pattern("C").pattern("#")
                    .unlockedBy("has_cattail", has(cattail)).save(out);
            var moss = ModBlocks.SPANISH_MOSS.get();
            ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, net.minecraft.world.item.Items.STRING).requires(moss, 4)
                    .unlockedBy("has_spanish_moss", has(moss)).save(out, id("string_from_spanish_moss"));
            ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, net.minecraft.world.item.Items.LIGHT_GRAY_DYE).requires(moss)
                    .group("light_gray_dye").unlockedBy("has_spanish_moss", has(moss)).save(out, id("light_gray_dye_from_spanish_moss"));
            var limestone = ModBlocks.LIMESTONE.get();
            SimpleCookingRecipeBuilder.smelting(Ingredient.of(limestone), RecipeCategory.MISC, ModBlocks.SLAKED_LIME.get(), 0.1f, 200)
                    .unlockedBy("has_limestone", has(limestone)).save(out, id("slaked_lime_from_limestone"));
            ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, net.minecraft.world.item.Items.WHITE_DYE).requires(ModBlocks.SLAKED_LIME.get())
                    .group("white_dye").unlockedBy("has_slaked_lime", has(ModBlocks.SLAKED_LIME.get())).save(out, id("white_dye_from_slaked_lime"));
            SimpleCookingRecipeBuilder.smelting(Ingredient.of(ModBlocks.WET_LIMESTONE.get()), RecipeCategory.BUILDING_BLOCKS, limestone, 0.1f, 200)
                    .unlockedBy("has_wet_limestone", has(ModBlocks.WET_LIMESTONE.get())).save(out, id("limestone_from_wet_limestone"));
            ShapelessRecipeBuilder.shapeless(RecipeCategory.BUILDING_BLOCKS, ModBlocks.MOSSY_LIMESTONE.get()).requires(limestone)
                    .requires(net.minecraft.world.item.Items.VINE).group("mossy_limestone").unlockedBy("has_limestone", has(limestone))
                    .save(out, id("mossy_limestone_from_vine"));
            ShapelessRecipeBuilder.shapeless(RecipeCategory.BUILDING_BLOCKS, ModBlocks.MOSSY_LIMESTONE.get()).requires(limestone)
                    .requires(net.minecraft.world.item.Items.MOSS_BLOCK).group("mossy_limestone").unlockedBy("has_limestone", has(limestone))
                    .save(out, id("mossy_limestone_from_moss_block"));
            ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, limestone).define('#', ModBlocks.LIMESTONE_SPIKE.get())
                    .pattern("##").pattern("##").unlockedBy("has_spike", has(ModBlocks.LIMESTONE_SPIKE.get())).save(out, id("limestone_from_spikes"));
            var marble = ModBlocks.MARBLE.get();
            ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.MARBLE_PILLAR.get(), 2).define('#', marble)
                    .pattern("#").pattern("#").unlockedBy("has_marble", has(marble)).save(out);
            ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.CHISELED_MARBLE.get()).define('#', ModBlocks.MARBLE_SLAB.get())
                    .pattern("#").pattern("#").unlockedBy("has_marble", has(marble)).save(out);
            ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, ModBlocks.MARBLE_BRICKS.get(), 4).define('#', ModBlocks.POLISHED_MARBLE.get())
                    .pattern("##").pattern("##").unlockedBy("has_marble", has(marble)).save(out);
            cutting(out, RecipeCategory.BUILDING_BLOCKS, ModBlocks.MARBLE_PILLAR.get(), marble, 1);
            cutting(out, RecipeCategory.BUILDING_BLOCKS, ModBlocks.CHISELED_MARBLE.get(), marble, 1);
            cutting(out, RecipeCategory.BUILDING_BLOCKS, ModBlocks.MARBLE_BRICKS.get(), marble, 1);
            ShapelessRecipeBuilder.shapeless(RecipeCategory.DECORATIONS, ModBlocks.GLOWWORM_JAR.get()).requires(ModBlocks.GLOWWORMS.get())
                    .requires(net.minecraft.world.item.Items.GLASS_BOTTLE).unlockedBy("has_glowworms", has(ModBlocks.GLOWWORMS.get())).save(out);
            ShapelessRecipeBuilder.shapeless(RecipeCategory.DECORATIONS, net.minecraft.world.item.Items.GLOW_ITEM_FRAME)
                    .requires(net.minecraft.world.item.Items.ITEM_FRAME).requires(ModBlocks.GLOWING_THREADS.get())
                    .unlockedBy("has_glowing_threads", has(ModBlocks.GLOWING_THREADS.get())).save(out, id("glow_item_frame_from_glowing_threads"));
        }

        private static ResourceLocation id(String path) {
            return ResourceLocation.fromNamespaceAndPath(EpicWorld.MODID, path);
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
