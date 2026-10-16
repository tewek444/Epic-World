package me.nyxia.epicworld.worldgen.feature;

import java.util.function.Supplier;
import me.nyxia.epicworld.EpicWorld;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecoratorType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class EpicFeatureTypes {
    private EpicFeatureTypes() {}

    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, EpicWorld.MODID);
    private static final DeferredRegister<TreeDecoratorType<?>> DECORATORS = DeferredRegister.create(Registries.TREE_DECORATOR_TYPE, EpicWorld.MODID);

    public static final Supplier<CaveDecorFeature> CAVE_DECOR = FEATURES.register("cave_decor", CaveDecorFeature::new);
    public static final Supplier<TreeDecoratorType<HangingDecorator>> HANGING =
            DECORATORS.register("hanging", () -> new TreeDecoratorType<>(HangingDecorator.CODEC));

    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
        DECORATORS.register(modBus);
    }
}
